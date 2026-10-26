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

    public init(from payload: QRPayload) {
        self.sessionId = payload.session_id
        self.challengeId = payload.challenge_id
        self.challengeSequence = payload.challenge_sequence
        self.relayWS = payload.relay_ws
        self.relayHTTP = payload.relay_http
        self.relay = RelayClient(baseURL: payload.relay_http)
    }

    /// Run the full handshake. Returns the relay's complete response
    /// (`accepted` + attestation token) once the session is accepted.
    @discardableResult
    public func start() async throws -> CompleteResponse {
        try await openWebSocket()
        defer { closeWebSocket() }
        try await waitForAcknowledgement()

        let scores = AttestationBuilder.stubLayerScores()
        let ambient = AmbientConditions()
        // Compute the decision once so the App Attest assertion and the
        // posted attestation sign/stamp the identical string, even if the
        // decision function later becomes non-deterministic.
        let decision = AttestationBuilder.livenessDecision(layerScores: scores)
        let hw = await requestDeviceAttestation(livenessDecision: decision)
        let attestation = try AttestationBuilder.build(
            session: self,
            layerScores: scores,
            ambient: ambient,
            deviceAttestation: hw.token,
            livenessDecision: decision,
            hwAttestationStatus: hw.status
        )
        return try await relay.submitAttestation(
            sessionId: sessionId,
            attestation: attestation
        )
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
