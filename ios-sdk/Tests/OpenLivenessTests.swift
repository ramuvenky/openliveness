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
        // Keychain writes now throw instead of silently falling back to an
        // ephemeral key (HIGH #1 fix). Simulators under xcodebuild often lack
        // Keychain for test bundles (no entitlements), so skip rather than
        // false-fail on infra.
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let attestation: [String: Any] = ["session_id": "abc", "liveness_decision": "pass"]
        let sig = try SigningService.sign(attestation)
        XCTAssertFalse(sig.isEmpty)
        XCTAssertFalse(try SigningService.publicKeyBase64().isEmpty)
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

        let pub1 = try SigningService.KeyStore().publicKeyDER()
        let pub2 = try SigningService.KeyStore().publicKeyDER()

        XCTAssertFalse(pub1.isEmpty)
        XCTAssertEqual(
            pub1,
            pub2,
            "KeyStore must load the persisted key from Keychain, not regenerate per instance"
        )
    }

    /// Locks in the High-severity fix: SigningService must persist its key
    /// under `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so the
    /// device-bound signing identity never migrates to another device via
    /// iCloud Keychain backup. We re-create the key by clearing the cache
    /// and then inspect the stored attribute directly.
    func testSigningKeyAccessibilityIsThisDeviceOnly() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        _ = try SigningService.KeyStore()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: "dev.openliveness.signing.privateKey",
            kSecReturnAttributes as String: true,
        ]
        var result: AnyObject?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let item = result as? [String: Any]
        XCTAssertEqual(
            item?[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
        )
    }

    /// Companion to the above for KeychainService — the App Attest keyId
    /// is also hardware-bound and must not migrate via iCloud Keychain.
    func testKeychainServiceAccessibilityIsThisDeviceOnly() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        try KeychainService.store(keyId: "test-key-id", deviceToken: "test-device-token")
        defer {
            for account in [
                "dev.openliveness.appAttest.keyId",
                "dev.openliveness.appAttest.deviceToken",
            ] {
                let delete: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrAccount as String: account,
                ]
                SecItemDelete(delete as CFDictionary)
            }
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: "dev.openliveness.appAttest.keyId",
            kSecReturnAttributes as String: true,
        ]
        var result: AnyObject?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let item = result as? [String: Any]
        XCTAssertEqual(
            item?[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
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

    // MARK: - CDLSession.race (ack timeout helper)
    //
    // Locks in the High-severity fix: a silent peer must not hang the SDK.
    // URLSessionWebSocketTask.receive() has no per-call timeout, so the
    // ack path uses a race between the receive loop and a timeout task.
    // Testing the race helper directly avoids needing a WebSocket harness.

    func testRaceReturnsWorkerResultWhenItFinishesFirst() async throws {
        let result: String = try await CDLSession.race(
            timeoutSeconds: 5,
            timeoutError: CDLError.networkError("should-not-fire")
        ) {
            "done"
        }
        XCTAssertEqual(result, "done")
    }

    func testRaceSurfacesWorkerError() async {
        enum E: Error { case boom }
        do {
            let _: String = try await CDLSession.race(
                timeoutSeconds: 5,
                timeoutError: CDLError.networkError("should-not-fire")
            ) {
                throw E.boom
            }
            XCTFail("expected worker error to propagate")
        } catch E.boom {
            // ok
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testRaceThrowsTimeoutErrorWhenWorkerIsSlow() async {
        do {
            let _: String = try await CDLSession.race(
                timeoutSeconds: 1,
                timeoutError: CDLError.networkError("ack-timeout-marker")
            ) {
                try await Task.sleep(nanoseconds: 10_000_000_000) // 10s
                return "won't reach here"
            }
            XCTFail("expected timeout error")
        } catch CDLError.networkError(let msg) {
            XCTAssertEqual(msg, "ack-timeout-marker")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    // MARK: - RelayClient.parseCompleteResponse
    //
    // Locks in the Low-severity fix: malformed /complete responses throw a
    // CDLError with an explicit per-field message instead of surfacing an
    // opaque DecodingError. Mirrors Android's RelayClientTest coverage.

    func testParseCompleteResponseValid() throws {
        let data = Data(#"{"accepted":true,"attestation_token":"tok-xyz"}"#.utf8)
        let resp = try RelayClient.parseCompleteResponse(data)
        XCTAssertTrue(resp.accepted)
        XCTAssertEqual(resp.attestationToken, "tok-xyz")
    }

    func testParseCompleteResponseAcceptsNullToken() throws {
        let data = Data(#"{"accepted":false,"attestation_token":null}"#.utf8)
        let resp = try RelayClient.parseCompleteResponse(data)
        XCTAssertFalse(resp.accepted)
        XCTAssertNil(resp.attestationToken)
    }

    func testParseCompleteResponseAcceptsMissingToken() throws {
        let data = Data(#"{"accepted":false}"#.utf8)
        let resp = try RelayClient.parseCompleteResponse(data)
        XCTAssertFalse(resp.accepted)
        XCTAssertNil(resp.attestationToken)
    }

    func testParseCompleteResponseThrowsWhenAcceptedMissing() {
        let data = Data(#"{"attestation_token":"tok"}"#.utf8)
        XCTAssertThrowsError(try RelayClient.parseCompleteResponse(data)) { error in
            guard case CDLError.networkError(let msg) = error else {
                XCTFail("expected CDLError.networkError, got \(error)"); return
            }
            XCTAssertTrue(msg.contains("accepted"))
        }
    }

    func testParseCompleteResponseThrowsWhenAcceptedWrongType() {
        let data = Data(#"{"accepted":"yes"}"#.utf8)
        XCTAssertThrowsError(try RelayClient.parseCompleteResponse(data)) { error in
            guard case CDLError.networkError(let msg) = error else {
                XCTFail("expected CDLError.networkError, got \(error)"); return
            }
            XCTAssertTrue(msg.contains("accepted"))
        }
    }

    func testParseCompleteResponseThrowsWhenTokenWrongType() {
        let data = Data(#"{"accepted":true,"attestation_token":123}"#.utf8)
        XCTAssertThrowsError(try RelayClient.parseCompleteResponse(data)) { error in
            guard case CDLError.networkError(let msg) = error else {
                XCTFail("expected CDLError.networkError, got \(error)"); return
            }
            XCTAssertTrue(msg.contains("attestation_token"))
        }
    }

    func testParseCompleteResponseThrowsOnNonJsonBody() {
        let data = Data("not json".utf8)
        XCTAssertThrowsError(try RelayClient.parseCompleteResponse(data)) { error in
            guard case CDLError.networkError = error else {
                XCTFail("expected CDLError.networkError, got \(error)"); return
            }
        }
    }

    func testParseCompleteResponseThrowsOnEmptyBody() {
        // Mirrors Android's `parseCompleteResponse rejects empty body` so the
        // "no body received" case produces an identifiable error on both
        // platforms. iOS routes empty data through JSONSerialization which
        // throws, so this currently flows through the same "empty or non-JSON"
        // branch as `testParseCompleteResponseThrowsOnNonJsonBody` — but the
        // explicit coverage protects against future refactors that special-case
        // one path but not the other.
        let data = Data()
        XCTAssertThrowsError(try RelayClient.parseCompleteResponse(data)) { error in
            guard case CDLError.networkError = error else {
                XCTFail("expected CDLError.networkError, got \(error)"); return
            }
        }
    }

    // MARK: - SigningService wire format
    //
    // Locks in that iOS emits DER-encoded ECDSA signatures (ASN.1 SEQUENCE of
    // two INTEGERs) rather than CryptoKit's raw r||s `rawRepresentation`.
    // This matches Android's SHA256withECDSA output so both platforms produce
    // the same wire format and server verifiers only need one code path. A
    // regression back to `rawRepresentation` would produce exactly 64 bytes
    // with no 0x30 prefix and would fail this test immediately.

    func testSigningServiceEmitsDerEncodedSignature() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()

        let attestation: [String: Any] = ["session_id": "abc", "liveness_decision": "pass"]
        let base64Sig = try SigningService.sign(attestation)
        guard let sig = Data(base64Encoded: base64Sig) else {
            XCTFail("signature not base64"); return
        }

        // DER ECDSA(P-256): 0x30 <len> 0x02 <r_len> <r> 0x02 <s_len> <s>.
        // Minimum realistic length is well above 64; raw r||s is always
        // exactly 64 with no 0x30 prefix. Checking the tag + length-byte
        // self-consistency catches a regression to rawRepresentation without
        // being flaky across r/s leading-zero stripping edge cases.
        XCTAssertEqual(sig.first, 0x30, "expected ASN.1 SEQUENCE tag; got \(sig.prefix(4) as NSData)")
        XCTAssertNotEqual(sig.count, 64, "signature is raw r||s — regressed from DER")
        XCTAssertGreaterThanOrEqual(sig.count, 8, "signature too short to be DER")
        // Second byte is the content length — must equal remaining bytes.
        XCTAssertEqual(Int(sig[1]) + 2, sig.count, "DER length byte doesn't match total size")
    }

    // MARK: - AttestationBuilder wire format
    //
    // Locks in the Medium-severity fix: AttestationBuilder.build must return
    // canonical JSON bytes so the server verifies the signature over exactly
    // what was sent on the wire. Previously the dict was serialized via
    // JSONSerialization at POST time (non-canonical, non-deterministic order).

    func testAttestationBuilderReturnsCanonicalBytes() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: QRPayload(
            session_id: "s1",
            challenge_id: "c1",
            challenge_sequence: ["blink"],
            relay_ws: "wss://x",
            relay_http: "https://x",
            expires_at: "2099-01-01T00:00:00Z",
            version: "1.0"
        ))

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions()
        )

        guard let str = String(data: bytes, encoding: .utf8) else {
            XCTFail("bytes not valid utf-8"); return
        }
        // Canonical form: keys sorted alphabetically, no whitespace. The
        // first attestation key alphabetically is "ambient_conditions",
        // so canonical output must open with it.
        XCTAssertTrue(str.hasPrefix(#"{"ambient_conditions":"#),
                      "wire bytes not canonical; got prefix: \(str.prefix(60))")
        XCTAssertFalse(str.contains(" "), "canonical bytes must not contain whitespace")
        XCTAssertTrue(str.contains(#""signature":"#), "signature field missing")
        // Re-canonicalizing the parsed-back dict must produce byte-identical
        // output: a sanity check that we really did emit canonical form.
        let roundTripped = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
        XCTAssertNotNil(roundTripped)
        let recanonicalized = try CanonicalJSON.encode(roundTripped!)
        XCTAssertEqual(recanonicalized, bytes)
    }

    // MARK: - helpers

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
