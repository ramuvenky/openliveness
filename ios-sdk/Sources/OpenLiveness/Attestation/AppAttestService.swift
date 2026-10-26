import Foundation
import CryptoKit
#if canImport(DeviceCheck)
import DeviceCheck
#endif

/// Apple App Attest wrapper. Phase 1 is one-time key registration against
/// the relay; Phase 2 is per-session assertion.
///
/// App Attest requires a real device — isSupported returns false in the
/// simulator and this SDK must then fall back to `software_only`.
class AppAttestService {

    #if canImport(DeviceCheck)
    private let service = DCAppAttestService.shared
    #endif
    private let relay: RelayClient

    init(relay: RelayClient) {
        self.relay = relay
    }

    var isSupported: Bool {
        #if canImport(DeviceCheck)
        return service.isSupported
        #else
        return false
        #endif
    }

    /// Generate the device key, attest it with Apple, and register the
    /// resulting attestation blob with the relay. Returns the keyId so the
    /// caller can persist it. Idempotent per install — if we already have a
    /// keyId in the keychain we return it without re-attesting.
    func registerKey() async throws -> String {
        if let cached = try? KeychainService.retrieveKeyId() { return cached }

        #if canImport(DeviceCheck)
        guard service.isSupported else {
            throw CDLError.hardwareAttestationUnsupported
        }

        let keyId = try await generateKeyWrapped()
        let challenge = try await relay.requestKeyChallenge(keyId: keyId)
        guard let challengeData = Data(base64Encoded: challenge) else {
            throw CDLError.networkError("relay returned non-base64 challenge")
        }
        let clientDataHash = Data(SHA256.hash(data: challengeData))
        let attestation = try await attestKeyWrapped(keyId: keyId, clientDataHash: clientDataHash)
        let deviceToken = try await relay.registerDevice(
            keyId: keyId,
            attestation: attestation.base64EncodedString()
        )
        try KeychainService.store(keyId: keyId, deviceToken: deviceToken)
        return keyId
        #else
        throw CDLError.hardwareAttestationUnsupported
        #endif
    }

    /// Builds the canonical client-data bytes App Attest will sign over.
    /// Exposed (internal) so the relay-side verifier and the drift tests
    /// can reproduce the exact preimage. Format: canonical JSON of a flat
    /// four-field object (challenge_id, liveness_decision, nonce, session_id).
    ///
    /// Canonical JSON (not string concat) closes the "session_id=abc +
    /// challenge_id=def" vs "session_id=abcde + challenge_id=f" ambiguity.
    /// The nonce is a server-issued single-use string fetched from
    /// /integrity-nonce; binding it into the signed preimage prevents an
    /// assertion captured for one session from being replayed to another.
    static func assertionClientData(
        sessionId: String,
        challengeId: String,
        livenessDecision: String,
        nonce: String
    ) throws -> Data {
        return try CanonicalJSON.encode([
            "challenge_id": challengeId,
            "liveness_decision": livenessDecision,
            "nonce": nonce,
            "session_id": sessionId,
        ])
    }

    /// Per-session assertion. The signed client data is a canonical-JSON
    /// object (see assertionClientData) so the server can replicate the
    /// exact byte sequence without worrying about field-boundary parsing.
    /// The nonce argument MUST come from a prior /integrity-nonce call for
    /// this same session — the server fetch-and-deletes it on verification.
    func generateAssertion(
        sessionId: String,
        challengeId: String,
        livenessDecision: String,
        nonce: String
    ) async throws -> String {
        #if canImport(DeviceCheck)
        let keyId = try KeychainService.retrieveKeyId()
        let clientData = try AppAttestService.assertionClientData(
            sessionId: sessionId,
            challengeId: challengeId,
            livenessDecision: livenessDecision,
            nonce: nonce
        )
        let clientDataHash = Data(SHA256.hash(data: clientData))
        let assertion = try await generateAssertionWrapped(keyId: keyId, clientDataHash: clientDataHash)
        return assertion.base64EncodedString()
        #else
        throw CDLError.hardwareAttestationUnsupported
        #endif
    }

    // MARK: - DCAppAttestService async wrappers
    // DCAppAttestService exposes completion-handler APIs. These shims adapt
    // them to async/await so the registration flow reads top-to-bottom.

    #if canImport(DeviceCheck)
    private func generateKeyWrapped() async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            service.generateKey { keyId, err in
                if let err = err { cont.resume(throwing: err); return }
                guard let keyId = keyId else {
                    cont.resume(throwing: CDLError.hardwareAttestationUnsupported); return
                }
                cont.resume(returning: keyId)
            }
        }
    }

    private func attestKeyWrapped(keyId: String, clientDataHash: Data) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            service.attestKey(keyId, clientDataHash: clientDataHash) { data, err in
                if let err = err { cont.resume(throwing: err); return }
                guard let data = data else {
                    cont.resume(throwing: CDLError.hardwareAttestationUnsupported); return
                }
                cont.resume(returning: data)
            }
        }
    }

    private func generateAssertionWrapped(keyId: String, clientDataHash: Data) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            service.generateAssertion(keyId, clientDataHash: clientDataHash) { data, err in
                if let err = err { cont.resume(throwing: err); return }
                guard let data = data else {
                    cont.resume(throwing: CDLError.hardwareAttestationUnsupported); return
                }
                cont.resume(returning: data)
            }
        }
    }
    #endif
}
