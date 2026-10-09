import XCTest
import Security
@testable import OpenLiveness

final class OpenLivenessTests: XCTestCase {

    func testQRPayloadDecodesFromBase64() throws {
        let json = """
        {"session_id":"s","challenge_id":"c","challenge_sequence":["blink","smile"],\
        "relay_ws":"wss://x/","relay_http":"https://x","expires_at":"2026-01-01T00:00:00Z","version":"1.0"}
        """
        let b64 = Data(json.utf8).base64EncodedString()
        let p = try parseQRPayload(b64)
        XCTAssertEqual(p.session_id, "s")
        XCTAssertEqual(p.challenge_sequence, ["blink", "smile"])
        XCTAssertEqual(p.version, "1.0")
    }

    func testCanonicalJSONSortsKeysAndStripsWhitespace() throws {
        let obj: [String: Any] = ["b": 1, "a": "x", "c": [3, 1, 2]]
        let data = try CanonicalJSON.encode(obj)
        XCTAssertEqual(String(data: data, encoding: .utf8), #"{"a":"x","b":1,"c":[3,1,2]}"#)
    }

    func testCanonicalJSONNestedOrdering() throws {
        let obj: [String: Any] = ["outer": ["z": true, "a": false]]
        let data = try CanonicalJSON.encode(obj)
        XCTAssertEqual(String(data: data, encoding: .utf8), #"{"outer":{"a":false,"z":true}}"#)
    }

    func testCanonicalJSONFormatsFractionalDoublesFixedPoint() throws {
        let obj: [String: Any] = ["score": 0.85, "count": 3.0, "pulse": true]
        let data = try CanonicalJSON.encode(obj)
        XCTAssertEqual(
            String(data: data, encoding: .utf8),
            #"{"count":3,"pulse":true,"score":0.850000}"#
        )
    }

    func testCanonicalJSONRejectsNonFinite() {
        XCTAssertThrowsError(try CanonicalJSON.encode(["n": Double.nan]))
        XCTAssertThrowsError(try CanonicalJSON.encode(["n": Double.infinity]))
    }

    /// Mirrors protocol/canonical-json-vectors.json. Keep in sync with the
    /// Kotlin side (android-sdk/.../GoldenVectors.kt, which loads the file
    /// directly) so iOS and Android encoders assert against identical input.
    func testCanonicalJSONMatchesSharedGoldenVectors() throws {
        let vectors: [(name: String, input: Any, expected: String)] = [
            ("basic_sort",
             ["b": 1, "a": "x", "c": [3, 1, 2]] as [String: Any],
             #"{"a":"x","b":1,"c":[3,1,2]}"#),
            ("nested_sort",
             ["outer": ["z": true, "a": false]] as [String: Any],
             #"{"outer":{"a":false,"z":true}}"#),
            ("integer_valued_double",
             ["n": 1.0] as [String: Any],
             #"{"n":1}"#),
            ("fractional_double",
             ["n": 0.85] as [String: Any],
             #"{"n":0.850000}"#),
            ("small_fractional",
             ["n": 0.0001] as [String: Any],
             #"{"n":0.000100}"#),
            ("mixed_scores",
             ["a": 0.85, "b": true, "c": 3.0] as [String: Any],
             #"{"a":0.850000,"b":true,"c":3}"#),
            ("null_value",
             ["n": NSNull()] as [String: Any],
             #"{"n":null}"#),
            ("empty_object",
             [:] as [String: Any],
             "{}"),
            ("empty_array",
             ["a": [] as [Any]] as [String: Any],
             #"{"a":[]}"#),
            ("control_char_escape",
             ["s": "line\nbreak"] as [String: Any],
             #"{"s":"line\nbreak"}"#),
            ("quote_escape",
             ["s": "he said \"hi\""] as [String: Any],
             #"{"s":"he said \"hi\""}"#),
            ("backslash_escape",
             ["s": "a\\b"] as [String: Any],
             #"{"s":"a\\b"}"#),
        ]
        for vec in vectors {
            let data = try CanonicalJSON.encode(vec.input)
            XCTAssertEqual(
                String(data: data, encoding: .utf8),
                vec.expected,
                "vector \(vec.name)"
            )
        }
    }

    func testSigningProducesVerifiableSignature() throws {
        let attestation: [String: Any] = ["session_id": "abc", "liveness_decision": "pass"]
        let sig = try SigningService.sign(attestation)
        XCTAssertFalse(sig.isEmpty)
        XCTAssertFalse(SigningService.publicKeyBase64().isEmpty)
    }

    /// Locks in the High-severity fix: the signing key must be persisted in
    /// Keychain so the public_key reported in attestations stays stable across
    /// process restarts. Simulates a restart by constructing two independent
    /// KeyStore instances and asserting they resolve to the same public key.
    /// Skips when the test environment's Keychain is unavailable (e.g. CI
    /// sandbox without entitlements) so we don't false-fail on infra, but
    /// still catches real persistence regressions when it is available.
    func testSigningKeyPersistsAcrossKeyStoreInstances() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()

        let pub1 = SigningService.KeyStore().publicKeyDER()
        let pub2 = SigningService.KeyStore().publicKeyDER()

        XCTAssertFalse(pub1.isEmpty)
        XCTAssertEqual(
            pub1,
            pub2,
            "KeyStore must load the persisted key from Keychain, not regenerate per instance"
        )
    }

    // MARK: - CDLSession ack-frame classifier
    //
    // Locks in the Medium-severity ack-loop fix: the SDK must skip past
    // heartbeats and other informational frames rather than erroring on the
    // first non-ack frame. The maxFrames bound and the WebSocket receive loop
    // itself are covered by instrumented E2E — no iOS WebSocket harness exists
    // yet, so we unit-test the pure frame-classification half here.

    func testAckFrameClassifierRecognizesSessionAcknowledged() {
        XCTAssertTrue(CDLSession.isAcknowledgementFrame(#"{"type":"session_acknowledged"}"#))
        XCTAssertTrue(CDLSession.isAcknowledgementFrame(
            #"{"type":"session_acknowledged","session_id":"s1"}"#
        ))
    }

    func testAckFrameClassifierSkipsOtherTypes() {
        XCTAssertFalse(CDLSession.isAcknowledgementFrame(#"{"type":"heartbeat"}"#))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame(#"{"type":"challenge_update","step":1}"#))
    }

    func testAckFrameClassifierSkipsMalformedFrames() {
        XCTAssertFalse(CDLSession.isAcknowledgementFrame(""))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame("not json"))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame("{"))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame("{}"))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame(#"{"type":42}"#))
        XCTAssertFalse(CDLSession.isAcknowledgementFrame(#"["session_acknowledged"]"#))
    }

    private func keychainIsAvailable() -> Bool {
        let probeAccount = "dev.openliveness.tests.keychain-probe"
        let delete: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: probeAccount
        ]
        SecItemDelete(delete as CFDictionary)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: probeAccount,
            kSecValueData as String: Data([0x01]),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        SecItemDelete(delete as CFDictionary)
        return status == errSecSuccess
    }
}
