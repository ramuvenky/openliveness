import Foundation
import CryptoKit

/// ES256 (ECDSA P-256 + SHA-256) signing of the canonical JSON attestation.
/// Uses the Secure Enclave when available, with a software P-256 fallback
/// so the SDK still produces a verifiable signature on simulators / devices
/// without SEP support. The public key is DER-encoded (SPKI) and base64'd
/// into the `public_key` field so the relay can verify without state.
struct SigningService {

    /// Shared singleton key for the lifetime of the process. The App Attest
    /// flow owns the long-lived device key; this is the ephemeral per-session
    /// key used to sign the attestation JSON itself.
    private static let keyStore = KeyStore()

    static func publicKeyBase64() -> String {
        keyStore.publicKeyDER().base64EncodedString()
    }

    static func sign(_ attestation: [String: Any]) throws -> String {
        let canonical = try CanonicalJSON.encode(attestation)
        let sig = try keyStore.sign(canonical)
        return sig.base64EncodedString()
    }

    private final class KeyStore {
        private let secureEnclaveKey: SecureEnclave.P256.Signing.PrivateKey?
        private let fallbackKey: P256.Signing.PrivateKey?

        init() {
            if SecureEnclave.isAvailable,
               let k = try? SecureEnclave.P256.Signing.PrivateKey() {
                self.secureEnclaveKey = k
                self.fallbackKey = nil
            } else {
                self.secureEnclaveKey = nil
                self.fallbackKey = P256.Signing.PrivateKey()
            }
        }

        func publicKeyDER() -> Data {
            if let k = secureEnclaveKey { return k.publicKey.derRepresentation }
            return fallbackKey!.publicKey.derRepresentation
        }

        func sign(_ data: Data) throws -> Data {
            let hash = SHA256.hash(data: data)
            if let k = secureEnclaveKey {
                return try k.signature(for: hash).rawRepresentation
            }
            return try fallbackKey!.signature(for: hash).rawRepresentation
        }
    }
}
