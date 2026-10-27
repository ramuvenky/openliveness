import Foundation
import os

/// Entry point for the mobile side of a CDL session. Construct from the
/// scanned QR payload, call `start()`, and the SDK walks the full flow:
/// open WebSocket → wait for session_acknowledged → run liveness →
/// POST signed attestation → return the relay's response.
public final class CDLSession {
    public let sessionId: String
    public let challengeId: String
    public let challengeSequence: [String]
    public let relayWS: String
    public let relayHTTP: String

    private let relay: RelayClient
    private var webSocket: URLSessionWebSocketTask?
    /// Optional live-camera landmark source. When nil, start() takes the
    /// Phase-1 stub path only if [allowStubMode] is true; otherwise it
    /// throws CDLError.stubModeDisallowed at run time. When non-nil,
    /// start() drives a ChallengeSequencer against the stream and the
    /// attestation reports the real Layer-2 result.
    private let landmarkStream: LandmarkStream?
    /// S-NEW-1(b) — Escape hatch for the Phase-1 stub liveness path.
    /// Default is `false` on release builds so a production integrator
    /// who forgets to inject a `landmarkStream` gets a loud throw at
    /// `start()` rather than silently shipping an SDK that always
    /// passes liveness. Unit tests and relay CI e2e that still need the
    /// stub path must set this to `true` explicitly — the explicit
    /// opt-in is the tripwire. The server-side `stub_mode` wire marker
    /// (see AttestationBuilder) is the defense-in-depth half of this
    /// gate: even if a hostile fork sets this flag to `true`, the relay
    /// can refuse `stub_mode: true` payloads from production session
    /// IDs.
    ///
    /// The `OPENLIVENESS_STUB_ALLOWED` SwiftPM compile flag flips the
    /// *default* to `true` so test harnesses (and only test harnesses)
    /// can call `CDLSession(from: payload)` without threading the flag
    /// through every construction site. Downstream integrators must
    /// NOT compile with this flag in production — the server-side
    /// `stub_mode` enforcement is what protects against the case where
    /// they do.
    private let allowStubMode: Bool

    // `@usableFromInline internal` (not `private`) because this is referenced
    // from the default-argument expression of the `public init` below. Swift
    // inlines default-argument expressions at the caller's module, so the
    // referenced symbol must be visible there — `private` would make the init
    // uncompilable (xcode error "cannot be referenced from a default argument
    // value"). Keeping the storage `internal` instead of `public` means the
    // compile-flag mechanism itself isn't part of the SDK's stable API; only
    // the resolved `allowStubMode` arg is.
    #if OPENLIVENESS_STUB_ALLOWED
    @usableFromInline internal static let defaultAllowStubMode = true
    #else
    @usableFromInline internal static let defaultAllowStubMode = false
    #endif

    public init(
        from payload: QRPayload,
        landmarkStream: LandmarkStream? = nil,
        allowStubMode: Bool = CDLSession.defaultAllowStubMode
    ) {
        self.sessionId = payload.session_id
        self.challengeId = payload.challenge_id
        self.challengeSequence = payload.challenge_sequence
        self.relayWS = payload.relay_ws
        self.relayHTTP = payload.relay_http
        self.relay = RelayClient(baseURL: payload.relay_http)
        self.landmarkStream = landmarkStream
        self.allowStubMode = allowStubMode
    }

    /// Run the full handshake. Returns the relay's complete response
    /// (`accepted` + attestation token) once the session is accepted.
    @discardableResult
    public func start() async throws -> CompleteResponse {
        try await openWebSocket()
        defer { closeWebSocket() }
        try await waitForAcknowledgement()

        // M-4 / Phase 5 TODO: these are the default-constructed ambient
        // flags (all false). The orchestrator's low-light / motion
        // reweighting branches are therefore inert in Phase 3. Phase 5
        // wires in the real ALS + accelerometer readings via
        // AmbientConditionsSampler so the ambient-aware weights actually
        // kick in — until then this is a known inert path, not a bug.
        let ambient = AmbientConditions()
        let (scores, challengeResult) = try await runLiveness(ambient: ambient)
        // Compute the decision once so the App Attest assertion and the
        // posted attestation sign/stamp the identical string, even if the
        // decision function later becomes non-deterministic.
        let decision = challengeResult != nil
            ? LivenessOrchestrator().orchestrate(
                layerScores: scores,
                ambientConditions: ambient
            ).decision
            : AttestationBuilder.stubLivenessDecision(layerScores: scores)
        let hw = await requestDeviceAttestation(livenessDecision: decision)
        // `challengeResult == nil` is the signal that runLiveness fell
        // back to the stub path (allowStubMode=true + no landmarkStream).
        // Stamp the wire marker so the relay can enforce production
        // policy — stub_mode=true outside allow-listed CI session IDs
        // is a server-side rejection.
        let attestation = try AttestationBuilder.build(
            session: self,
            layerScores: scores,
            ambient: ambient,
            deviceAttestation: hw.token,
            livenessDecision: decision,
            hwAttestationStatus: hw.status,
            challengeResult: challengeResult,
            stubMode: (challengeResult == nil)
        )
        return try await relay.submitAttestation(
            sessionId: sessionId,
            attestation: attestation
        )
    }

    /// Return real Layer-2 scores + the sequencer's ChallengeResult when
    /// a landmark stream was supplied; fall back to Phase-1 stub scores
    /// (and nil result) only when `allowStubMode == true`. Throws
    /// `CDLError.stubModeDisallowed` otherwise — a release-build integrator
    /// who forgets to inject a landmarkStream gets a loud failure here
    /// instead of a silently-passing liveness check. The nil-result signal
    /// is still what tells `.build(...)` to echo the legacy
    /// challenge_response and stamp `stub_mode=true`.
    ///
    /// M-1: `private` in release builds — the SDK's public surface must
    /// not expose the liveness pump directly. A `#if DEBUG` shim below
    /// re-exports it to the test target so the stub-gate drift test can
    /// hit this path without a WebSocket harness. The equivalent Android
    /// test lives in `CDLSessionE2ETest` and uses MockWebServer; iOS
    /// lacks that infra, so a debug-only shim is the pragmatic middle.
    private func runLiveness(ambient: AmbientConditions) async throws
        -> (LayerScores, ChallengeResult?)
    {
        guard let stream = landmarkStream else {
            guard allowStubMode else {
                throw CDLError.stubModeDisallowed
            }
            return (AttestationBuilder.stubLayerScores(), nil)
        }
        let sequencer = try ChallengeSequencer(wire: challengeSequence)
        let runner = Layer2Runner(sequencer: sequencer, stream: stream) { [weak self] progress, layer in
            self?.sendProcessingUpdate(progress: progress, currentLayer: layer)
        }
        let result = try await runner.run()

        // Only layer2 is real in Phase 3; layers 3/4/5 stay at stub
        // confidence so the weighted sum doesn't collapse to a fail
        // just because other detectors haven't shipped yet. The hard
        // rule still triggers via layer2ChallengeCompleted == false
        // when the user doesn't complete the challenge.
        var scores = AttestationBuilder.stubLayerScores()
        scores.layer2ChallengeCompleted = result.completed
        scores.layer2ChallengeScore = result.completed ? result.score : 0.0
        return (scores, result)
    }

    #if DEBUG
    /// M-1 test-only shim. Not compiled into release builds — the leading
    /// underscore and `_testOnly_` prefix mark it as off-limits for app
    /// code even inside DEBUG. iOS lacks a MockWebServer-equivalent so
    /// the stub-gate drift test invokes `runLiveness` directly; a public
    /// `internal` would leak the liveness pump to any SPM dependant with
    /// `@testable import OpenLiveness` and widen the attack surface. Keep
    /// this shim narrow and @_disfavoredOverload-free; the tests should
    /// call this exact method name and the audit team greps for it.
    internal func _testOnly_runLiveness(ambient: AmbientConditions) async throws
        -> (LayerScores, ChallengeResult?)
    {
        try await runLiveness(ambient: ambient)
    }
    #endif

    /// Encode and send a processing_update frame. Silent-fails if the WS
    /// is already closed — a lost progress frame must not fail the
    /// session; the server's state machine advances on /complete, not on
    /// progress breadcrumbs. A send error is still logged at DEBUG so a
    /// browser-UI freeze at "1 of 3 done" is diagnosable in triage;
    /// successful sends stay silent to avoid log noise on healthy runs.
    private func sendProcessingUpdate(progress: Float, currentLayer: String) {
        // M-2: capture `webSocket` into a local before anything else so a
        // concurrent `closeWebSocket()` that nils the property can't make
        // our `ws.send(...)` crash on a torn read. The local keeps a
        // strong reference to the task — if close happens between the
        // read and the send, the task's `send` returns an error on a
        // cancelled task rather than racing against deallocation.
        guard let ws = webSocket else { return }
        let payload: [String: Any] = [
            "type": "processing_update",
            "progress": Double(progress),
            "current_layer": currentLayer
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return }
        ws.send(.string(text)) { error in
            if let error = error {
                CDLSession.log.debug(
                    "processing_update dropped: \(error.localizedDescription, privacy: .public)"
                )
            }
        }
    }

    /// Result of the hardware-attestation attempt. `token` is non-nil only
    /// when `status == .ok`. The status field lets the relay distinguish
    /// "device cannot attest" (simulator, legitimate software_only) from
    /// "device tried and failed" — the second clustering on previously-ok
    /// devices is a downgrade-attack signal.
    struct HardwareAttestationResult {
        let token: String?
        let status: AttestationBuilder.HwAttestationStatus
    }

    // Dedicated signpost for hardware-attestation failures. The server sees
    // one of three bucket labels (ok / unsupported / failed) — without a
    // local log the `failed` bucket is opaque in triage. Public privacy
    // level is deliberate: the strings here are OSStatus codes and system
    // error descriptions, not user data.
    static let log = Logger(subsystem: "dev.openliveness", category: "attestation")

    /// Attempts hardware attestation via App Attest. Non-adversarial failures
    /// (simulator, keychain locked pre-first-unlock) are tagged `.unsupported`
    /// so the server-side downgrade heuristic doesn't light up for benign
    /// environmental issues. Only unexpected failures land in `.failed`, which
    /// is the bucket the relay watches for clustering.
    func requestDeviceAttestation(livenessDecision: String) async -> HardwareAttestationResult {
        let service = AppAttestService(relay: relay)
        guard service.isSupported else {
            return HardwareAttestationResult(token: nil, status: .unsupported)
        }
        do {
            _ = try await service.registerKey()
            // Fetch a fresh, single-use integrity nonce. Must happen AFTER
            // registerKey (so a device that fails Phase 1 doesn't burn a
            // nonce) and BEFORE generateAssertion (the nonce is baked into
            // the signed client data, matching the server-side verifier).
            let nonce = try await relay.requestIntegrityNonce(sessionId: sessionId)
            let assertion = try await service.generateAssertion(
                sessionId: sessionId,
                challengeId: challengeId,
                livenessDecision: livenessDecision,
                nonce: nonce
            )
            return HardwareAttestationResult(token: assertion, status: .ok)
        } catch {
            let status = CDLSession.classifyAttestationError(error)
            // Log only the adversarial-adjacent bucket. Environmental misses
            // (locked keychain, simulator-style unsupported) are quiet; the
            // `failed` bucket is the one the server clusters on so we want
            // enough local breadcrumbs to triage it.
            if status == .failed {
                CDLSession.log.warning(
                    "hardware attestation failed: \(error.localizedDescription, privacy: .public)"
                )
            }
            return HardwareAttestationResult(token: nil, status: status)
        }
    }

    /// Maps an error from the App Attest / Keychain / relay-nonce pipeline
    /// to the `hw_attestation_status` wire value. Internal so the drift test
    /// can pin the mapping without having to inject an AppAttestService stub.
    ///
    /// The distinction is server-visible: `.failed` is the downgrade signal
    /// the relay clusters on, so benign environmental errors (Keychain
    /// pre-first-unlock) must map to `.unsupported` or every lock-screen
    /// submission looks like an attack.
    static func classifyAttestationError(_ error: Error) -> AttestationBuilder.HwAttestationStatus {
        if case CDLError.hardwareAttestationUnsupported = error { return .unsupported }
        if case CDLError.keychainError = error { return .unsupported }
        return .failed
    }

    // MARK: - WebSocket

    private func openWebSocket() async throws {
        guard let url = URL(string: relayWS) else {
            throw CDLError.networkError("invalid relay_ws URL: \(relayWS)")
        }
        let ws = URLSession.shared.webSocketTask(with: url)
        webSocket = ws
        ws.resume()
    }

    /// Overall deadline for session_acknowledged. Protects against a
    /// WebSocket that opens cleanly but never emits a frame — URLSessionWS
    /// `receive()` has no per-call timeout, so without this the SDK would
    /// hang forever on a silent peer.
    static let ackTimeoutSeconds: UInt64 = 30
    static let ackMaxFrames = 16

    private func waitForAcknowledgement() async throws {
        guard let ws = webSocket else { throw CDLError.networkError("websocket not open") }
        try await CDLSession.race(
            timeoutSeconds: CDLSession.ackTimeoutSeconds,
            timeoutError: CDLError.networkError("timeout waiting for session_acknowledged")
        ) {
            // Loop past heartbeats and other informational frames until the
            // session_acknowledged frame arrives. Bounded by maxFrames so a
            // chatty relay can't trap the SDK here forever.
            for _ in 0..<CDLSession.ackMaxFrames {
                let message = try await ws.receive()
                let text: String
                switch message {
                case .string(let s): text = s
                case .data(let d): text = String(data: d, encoding: .utf8) ?? ""
                @unknown default:
                    throw CDLError.networkError("unexpected websocket frame")
                }
                if CDLSession.isAcknowledgementFrame(text) { return }
            }
            throw CDLError.networkError(
                "no session_acknowledged within \(CDLSession.ackMaxFrames) frames"
            )
        }
    }

    /// Runs `work` concurrently with a sleep task; whichever completes first
    /// wins and cancels the other. Extracted so the ack timeout can be
    /// exercised in unit tests without a WebSocket.
    ///
    /// `work` must honor cooperative cancellation — the task group waits for
    /// every subtask to terminate before returning, so a `work` body that
    /// ignores `Task.isCancelled` / cancellation-throwing APIs will keep the
    /// race alive past the deadline. `URLSessionWebSocketTask.receive()` and
    /// `Task.sleep` already behave correctly; a hand-rolled busy loop would
    /// not.
    static func race<T>(
        timeoutSeconds: UInt64,
        timeoutError: Error,
        work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutSeconds * 1_000_000_000)
                throw timeoutError
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else {
                throw CDLError.networkError("empty race")
            }
            return first
        }
    }

    /// Decides whether a received WS frame is the session_acknowledged signal.
    /// Non-ack frames (heartbeats, other type values, malformed JSON, missing
    /// `type`) return false so the receive loop keeps reading rather than
    /// erroring. Extracted so the Medium-severity ack-loop fix is unit-testable
    /// without a WebSocket harness.
    static func isAcknowledgementFrame(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else {
            return false
        }
        return type == "session_acknowledged"
    }

    private func closeWebSocket() {
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
    }
}
