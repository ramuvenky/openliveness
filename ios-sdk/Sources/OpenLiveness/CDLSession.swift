import Foundation

/// Entry point for the mobile side of a CDL session. Construct from the
/// scanned QR payload, call `start()`, and the SDK walks the full flow:
/// open WebSocket → wait for session_acknowledged → run liveness (stub in
/// Week 1) → POST signed attestation → return the relay's response.
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

    /// Run the full Week 1 handshake. Returns the relay's complete response
    /// (`accepted` + attestation JWT) once the session is accepted.
    @discardableResult
    public func start() async throws -> RelayClient.CompleteResponse {
        try await openWebSocket()
        try await waitForAcknowledgement()

        let scores = AttestationBuilder.stubLayerScores()
        let ambient = AmbientConditions()
        let attestation = try AttestationBuilder.build(
            session: self,
            layerScores: scores,
            ambient: ambient,
            deviceAttestation: nil
        )
        let response = try await relay.submitAttestation(
            sessionId: sessionId,
            attestation: attestation
        )
        closeWebSocket()
        return response
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
        let message = try await ws.receive()
        let text: String
        switch message {
        case .string(let s): text = s
        case .data(let d): text = String(data: d, encoding: .utf8) ?? ""
        @unknown default:
            throw CDLError.networkError("unexpected websocket frame")
        }
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (obj["type"] as? String) == "session_acknowledged" else {
            throw CDLError.networkError("expected session_acknowledged, got: \(text)")
        }
    }

    private func closeWebSocket() {
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
    }
}
