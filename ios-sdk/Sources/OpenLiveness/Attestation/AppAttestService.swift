import Foundation
import CryptoKit
#if canImport(DeviceCheck)
import DeviceCheck
#endif

/// Apple App Attest wrapper. Week 1 implements Phase 1 (one-time key
/// registration against the relay). Phase 2 (per-session assertion) will
/// land alongside the hardware-attested session flow.
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

    /// Phase 2 — per-session assertion. TODO: wire into CDLSession once
    /// Phase 2 lands. Keeps the API visible so the orchestrator can plan
    /// around it.
    func generateAssertion(
        sessionId: String,
        challengeId: String,
        livenessDecision: String
    ) async throws -> String {
        #if canImport(DeviceCheck)
        let keyId = try KeychainService.retrieveKeyId()
        let clientData = (sessionId + challengeId + livenessDecision).data(using: .utf8)!
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
