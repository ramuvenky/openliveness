import Foundation

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
        let attestation = try AttestationBuilder.build(
            session: self,
            layerScores: scores,
            ambient: ambient,
            deviceAttestation: nil
        )
        return try await relay.submitAttestation(
            sessionId: sessionId,
            attestation: attestation
        )
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
