import Foundation
import CryptoKit

/// ES256 (ECDSA P-256 + SHA-256) signing of the canonical JSON attestation.
/// Uses the Secure Enclave when available, with a software P-256 fallback
/// so the SDK still produces a verifiable signature on simulators / devices
/// without SEP support. The private key material (SE blob or raw P-256 key)
/// is persisted in the Keychain so the `public_key` field stays stable
/// across process restarts, matching Android's AndroidKeyStore behavior.
/// The public key is DER-encoded (SPKI) and base64'd for the attestation.
struct SigningService {

    private static let keyStore = KeyStore()

    static func publicKeyBase64() -> String {
        keyStore.publicKeyDER().base64EncodedString()
    }

    static func sign(_ attestation: [String: Any]) throws -> String {
        let canonical = try CanonicalJSON.encode(attestation)
        let sig = try keyStore.sign(canonical)
        return sig.base64EncodedString()
    }

    final class KeyStore {
        private static let keychainAccount = "dev.openliveness.signing.privateKey"
        private static let typeSecureEnclave = "se"
        private static let typeSoftware = "sw"

        private let lock = NSLock()
        private var secureEnclaveKey: SecureEnclave.P256.Signing.PrivateKey?
        private var fallbackKey: P256.Signing.PrivateKey?

        init() {
            if let (type, data) = KeyStore.loadFromKeychain() {
                switch type {
                case KeyStore.typeSecureEnclave:
                    if SecureEnclave.isAvailable,
                       let k = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data) {
                        self.secureEnclaveKey = k
                        return
                    }
                case KeyStore.typeSoftware:
                    if let k = try? P256.Signing.PrivateKey(rawRepresentation: data) {
                        self.fallbackKey = k
                        return
                    }
                default:
                    break
                }
            }
            generateAndPersist()
        }

        func publicKeyDER() -> Data {
            lock.lock(); defer { lock.unlock() }
            if let k = secureEnclaveKey { return k.publicKey.derRepresentation }
            return fallbackKey!.publicKey.derRepresentation
        }

        func sign(_ data: Data) throws -> Data {
            lock.lock(); defer { lock.unlock() }
            let hash = SHA256.hash(data: data)
            if let k = secureEnclaveKey {
                return try k.signature(for: hash).rawRepresentation
            }
            return try fallbackKey!.signature(for: hash).rawRepresentation
        }

        private func generateAndPersist() {
            if SecureEnclave.isAvailable,
               let k = try? SecureEnclave.P256.Signing.PrivateKey() {
                self.secureEnclaveKey = k
                KeyStore.storeInKeychain(type: KeyStore.typeSecureEnclave, data: k.dataRepresentation)
                return
            }
            let k = P256.Signing.PrivateKey()
            self.fallbackKey = k
            KeyStore.storeInKeychain(type: KeyStore.typeSoftware, data: k.rawRepresentation)
        }

        private static func loadFromKeychain() -> (type: String, data: Data)? {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: keychainAccount,
                kSecReturnData as String: true,
                kSecReturnAttributes as String: true
            ]
            var result: AnyObject?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
                  let item = result as? [String: Any],
                  let data = item[kSecValueData as String] as? Data,
                  let type = item[kSecAttrLabel as String] as? String
            else { return nil }
            return (type, data)
        }

        private static func storeInKeychain(type: String, data: Data) {
            let delete: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: keychainAccount
            ]
            SecItemDelete(delete as CFDictionary)
            let add: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: keychainAccount,
                kSecAttrLabel as String: type,
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            ]
            SecItemAdd(add as CFDictionary, nil)
        }

        /// Test-only: wipe the persisted key so the next KeyStore() call starts
        /// from a clean slate. Not for production use.
        static func _clearPersistedKeyForTesting() {
            let delete: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: keychainAccount
            ]
            SecItemDelete(delete as CFDictionary)
        }
    }
}
