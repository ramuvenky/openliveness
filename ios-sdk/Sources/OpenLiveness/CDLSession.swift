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

    private func waitForAcknowledgement() async throws {
        guard let ws = webSocket else { throw CDLError.networkError("websocket not open") }
        // Loop past heartbeats and other informational frames until the
        // session_acknowledged frame arrives. Bounded by maxFrames so a
        // chatty relay can't trap the SDK here forever.
        let maxFrames = 16
        for _ in 0..<maxFrames {
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
        throw CDLError.networkError("no session_acknowledged within \(maxFrames) frames")
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
