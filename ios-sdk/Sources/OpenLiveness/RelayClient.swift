import Foundation

public struct CompleteResponse: Decodable {
    public let accepted: Bool
    public let attestation_token: String?
}

/// HTTP client for the relay endpoints the mobile SDK must call.
/// Base URL is the `relay_http` host supplied in the QR payload — the
/// relay runs under both http (local dev) and https (production).
struct RelayClient {
    let baseURL: String

    private var url: String {
        baseURL.contains("://") ? baseURL : "https://\(baseURL)"
    }

    // MARK: - App Attest Phase 1

    func requestKeyChallenge(keyId: String) async throws -> String {
        let body: [String: String] = ["platform": "ios", "key_id": keyId]
        let resp: [String: String] = try await post("/cdl/device/register", body: body)
        guard let challenge = resp["challenge"] else {
            throw CDLError.networkError("missing challenge in /cdl/device/register response")
        }
        return challenge
    }

    func registerDevice(keyId: String, attestation: String) async throws -> String {
        let body: [String: String] = [
            "platform": "ios", "key_id": keyId, "attestation": attestation
        ]
        let resp: [String: String] = try await post("/cdl/device/attest", body: body)
        guard let token = resp["device_token"] else {
            throw CDLError.networkError("missing device_token in /cdl/device/attest response")
        }
        return token
    }

    // MARK: - Session

    func requestIntegrityNonce(sessionId: String) async throws -> String {
        let path = "/cdl/session/\(sessionId)/integrity-nonce"
        let resp: [String: String] = try await post(path, body: [String: String]())
        guard let nonce = resp["nonce"] else {
            throw CDLError.networkError("missing nonce in integrity-nonce response")
        }
        return nonce
    }

    func submitAttestation(sessionId: String, attestation: [String: Any]) async throws -> CompleteResponse {
        let path = "/cdl/session/\(sessionId)/complete"
        let data = try JSONSerialization.data(withJSONObject: attestation, options: [])
        return try await postRaw(path, body: data)
    }

    // MARK: - internals

    private func post<B: Encodable, R: Decodable>(_ path: String, body: B) async throws -> R {
        let data = try JSONEncoder().encode(body)
        return try await postRaw(path, body: data)
    }

    private func postRaw<R: Decodable>(_ path: String, body: Data) async throws -> R {
        guard let endpoint = URL(string: url + path) else {
            throw CDLError.networkError("invalid url: \(url + path)")
        }
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        let (respData, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let detail = String(data: respData, encoding: .utf8) ?? ""
            throw CDLError.networkError("HTTP \(http.statusCode): \(detail)")
        }
        return try JSONDecoder().decode(R.self, from: respData)
    }
}
