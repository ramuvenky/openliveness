import Foundation
import Security

/// Stores the App Attest keyId (and the relay-issued device token) in the
/// Keychain so the device identity survives app restarts without exposing
/// the raw secret to disk.
struct KeychainService {
    private static let keyIdAccount = "dev.openliveness.appAttest.keyId"
    private static let deviceTokenAccount = "dev.openliveness.appAttest.deviceToken"

    static func store(keyId: String, deviceToken: String) throws {
        try write(keyIdAccount, value: keyId)
        try write(deviceTokenAccount, value: deviceToken)
    }

    static func retrieveKeyId() throws -> String {
        try read(keyIdAccount)
    }

    static func retrieveDeviceToken() throws -> String {
        try read(deviceTokenAccount)
    }

    private static func write(_ account: String, value: String) throws {
        guard let data = value.data(using: .utf8) else { throw CDLError.keychainError }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw CDLError.keychainError }
    }

    private static func read(_ account: String) throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8)
        else { throw CDLError.keychainError }
        return value
    }
}
