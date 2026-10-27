import XCTest
import Security
import CryptoKit
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

    // Phase 1 error paths. parseQRPayload rolls base64 and JSON failures
    // into a single qrParseError so the camera UI can show one "scan again"
    // state regardless of which half failed. These lock that in so a future
    // refactor doesn't start surfacing DecodingError directly.

    func testParseQRPayloadThrowsOnInvalidBase64() {
        XCTAssertThrowsError(try parseQRPayload("not valid base64!!!!")) { error in
            guard case CDLError.qrParseError = error else {
                XCTFail("expected CDLError.qrParseError, got \(error)"); return
            }
        }
    }

    func testParseQRPayloadThrowsOnBase64ThatIsNotJSON() {
        let b64 = Data("not json at all".utf8).base64EncodedString()
        XCTAssertThrowsError(try parseQRPayload(b64)) { error in
            guard case CDLError.qrParseError = error else {
                XCTFail("expected CDLError.qrParseError, got \(error)"); return
            }
        }
    }

    func testParseQRPayloadThrowsWhenRequiredFieldMissing() {
        let json = #"{"session_id":"s","challenge_id":"c","challenge_sequence":["blink"]}"#
        let b64 = Data(json.utf8).base64EncodedString()
        XCTAssertThrowsError(try parseQRPayload(b64)) { error in
            guard case CDLError.qrParseError = error else {
                XCTFail("expected CDLError.qrParseError, got \(error)"); return
            }
        }
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

    // MARK: - Phase 2 — hardware attestation wiring
    //
    // Locks in the Phase 2 wiring: AttestationBuilder stamps IAL2 when
    // CDLSession hands it a device_attestation string and downgrades to
    // software_only otherwise. The CDLSession-level fallback (unsupported
    // simulator, App Attest error) relies on this behavior to keep the
    // session alive instead of aborting.

    func testAttestationBuilderStampsSoftwareOnlyWhenNoDeviceAttestation() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: nil
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#))
        XCTAssertFalse(str.contains(#""device_attestation""#),
                       "device_attestation must be absent when nil")
        // Default hw status when nothing was passed and no device attestation
        // is present must be "unsupported" (not "failed") so a legitimate
        // simulator / non-attesting device doesn't look like a downgrade.
        XCTAssertTrue(str.contains(#""hw_attestation_status":"unsupported""#))
    }

    func testAttestationBuilderStampsIAL2WhenDeviceAttestationPresent() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: "stub-app-attest-assertion-base64"
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"IAL2""#))
        XCTAssertTrue(str.contains(#""device_attestation":"stub-app-attest-assertion-base64""#))
        XCTAssertTrue(str.contains(#""hw_attestation_status":"ok""#))
    }

    // H1 drift test — forced "failed" status with no device attestation.
    // This is the downgrade-attack signal the relay watches for: a
    // regression that collapsed "failed" back into "unsupported" (or
    // dropped the field) would show up here before shipping.
    func testAttestationBuilderExplicitHwStatusFailedIsPreserved() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: nil,
            hwAttestationStatus: .failed
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#))
        XCTAssertTrue(str.contains(#""hw_attestation_status":"failed""#))
    }

    // M-1 pessimism — token present but caller explicitly says .failed.
    // The real CDLSession.requestDeviceAttestation flow cannot produce this
    // pair, but direct AttestationBuilder.build callers (tests, future
    // orchestrators) could; the builder downgrades to software_only here
    // rather than passing an inconsistent IAL2 claim the relay would reject
    // at checkAssurance. Preserves the raw status for triage visibility.
    func testAttestationBuilderIsPessimisticWhenTokenAndFailedPairMismatched() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: "stub-app-attest-assertion-base64",
            hwAttestationStatus: .failed
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#),
                      "mismatched token+status must downgrade to software_only")
        XCTAssertTrue(str.contains(#""hw_attestation_status":"failed""#),
                      "raw status must be preserved verbatim for triage")
    }

    // M-1 pessimism — status .ok but no deviceAttestation to back it up.
    // A token-less .ok claim is nonsense (server has nothing to verify),
    // so assurance_level drops to software_only rather than passing the
    // inconsistency through for server-side rejection.
    func testAttestationBuilderIsPessimisticWhenOkStatusWithoutToken() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: nil,
            hwAttestationStatus: .ok
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#),
                      "status=.ok without a token must downgrade to software_only")
        XCTAssertTrue(str.contains(#""hw_attestation_status":"ok""#),
                      "raw status must be preserved verbatim even when assurance_level is pessimistic")
    }

    // M-1 companion — explicit .unsupported with a non-null token (odd
    // pair a buggy caller might produce) also stays pessimistic.
    func testAttestationBuilderIsPessimisticWhenTokenAndUnsupportedPairMismatched() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: "stub-token",
            hwAttestationStatus: .unsupported
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#))
        XCTAssertTrue(str.contains(#""hw_attestation_status":"unsupported""#))
    }

    // H3 drift test — if a caller passes an explicit livenessDecision, the
    // builder MUST use it verbatim instead of recomputing from layerScores.
    // The App Attest assertion signs over the caller's string; a builder
    // that recomputed could emit "pass" while the assertion signed "fail".
    func testAttestationBuilderUsesExplicitLivenessDecision() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        // stubLayerScores would normally produce "pass". Force the caller
        // override to "fail" and assert the override wins.
        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: nil,
            livenessDecision: "fail"
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""liveness_decision":"fail""#),
                      "explicit livenessDecision must win over the computed stub")
    }

    // Shared-fixture drift test — the signed client data is a canonical-JSON
    // object keyed by (challenge_id, liveness_decision, nonce, session_id).
    // The relay-side verifier reproduces the exact string via canonicalize()
    // in relay/src/attestation/apple.ts and SHA-256s it to recover the
    // preimage; relay/tests/apple.test.ts pins the same byte string on the
    // server. Any drift between the two sides breaks every iOS IAL2 session,
    // so this fixture is deliberately verbatim.
    func testAppAttestClientDataIsCanonicalJSON() throws {
        let bytes = try AppAttestService.assertionClientData(
            sessionId: "s1",
            challengeId: "c1",
            livenessDecision: "pass",
            nonce: "n1"
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertEqual(
            str,
            #"{"challenge_id":"c1","liveness_decision":"pass","nonce":"n1","session_id":"s1"}"#
        )
    }

    // Drift test — the ambiguity we were fixing: ("abc","def","pass","n")
    // and ("ab","cdef","pass","n") must produce *different* client data. The
    // old concat produced identical bytes; the canonical-JSON form keys each
    // field explicitly so they can never collide.
    func testAppAttestClientDataDisambiguatesAdjacentFields() throws {
        let a = try AppAttestService.assertionClientData(
            sessionId: "abc", challengeId: "def", livenessDecision: "pass", nonce: "n"
        )
        let b = try AppAttestService.assertionClientData(
            sessionId: "ab", challengeId: "cdef", livenessDecision: "pass", nonce: "n"
        )
        XCTAssertNotEqual(a, b, "distinct (session_id, challenge_id) pairs must produce distinct preimages")
    }

    // M1 drift test — the server-issued nonce must be baked into the signed
    // preimage. A regression that stripped the nonce (or forgot to thread it
    // in from CDLSession) would make assertions replayable across sessions
    // if server-side nonce enforcement ever regressed. Two different nonces
    // must produce two different preimages.
    func testAppAttestClientDataBindsNonce() throws {
        let a = try AppAttestService.assertionClientData(
            sessionId: "s1", challengeId: "c1", livenessDecision: "pass", nonce: "nonce-A"
        )
        let b = try AppAttestService.assertionClientData(
            sessionId: "s1", challengeId: "c1", livenessDecision: "pass", nonce: "nonce-B"
        )
        XCTAssertNotEqual(a, b, "assertion client data must change when the nonce changes")
    }

    // M1 drift test — CDLSession.requestDeviceAttestation must swallow
    // unsupported-platform failures and surface them as `.unsupported`
    // (not `.failed`) so legitimate simulators / older devices don't
    // get flagged as downgrade attacks. The simulator CI runner has
    // DCAppAttestService.isSupported == false, which exercises the
    // first early-return branch.
    func testRequestDeviceAttestationReturnsUnsupportedOnSimulator() async throws {
        let session = CDLSession(from: makeStubPayload())
        let result = await session.requestDeviceAttestation(livenessDecision: "pass")
        // On the simulator isSupported is false → status should be .unsupported.
        // On a hypothetical real-device CI runner this would be .ok or .failed;
        // we don't assert equality to .unsupported there, only that the token
        // and status stay in sync.
        if result.status == .ok {
            XCTAssertNotNil(result.token)
        } else {
            XCTAssertNil(result.token, "non-ok status must have a nil token")
        }
    }

    // S-NEW-2(c) — the on-wire `stub_mode` marker. Defaults to false so a
    // caller that forgets to stamp it doesn't accidentally pass as "stub"
    // on the server. The CDLSession.start path flips this to true when
    // the Phase-1 stub liveness ran (challengeResult == nil); the server
    // enforces policy on this field, so a regression that stops emitting
    // it (or hardcodes false) would silently let the stub path reach
    // production.
    func testAttestationBuilderStampsStubModeFalseByDefault() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions()
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""stub_mode":false"#),
                      "default stub_mode must be false — opt-in only, never on by default")
    }

    func testAttestationBuilderStampsStubModeTrueWhenCallerSetsIt() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            stubMode: true
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""stub_mode":true"#),
                      "stub_mode must be true on the wire when the SDK ran the stub path")
    }

    // S-NEW-1(b) — production builds must reject the stub-liveness path.
    // A CDLSession constructed without a landmarkStream and with
    // allowStubMode=false must throw CDLError.stubModeDisallowed when
    // runLiveness runs. Invoked directly (not via start()) because iOS
    // doesn't have a WebSocket mock harness yet; the Android sibling
    // uses MockWebServer to exercise the same gate at start()-time.
    func testRunLivenessThrowsWhenStubModeDisallowedAndNoLandmarkStream() async {
        let session = CDLSession(
            from: makeStubPayload(),
            landmarkStream: nil,
            allowStubMode: false
        )
        do {
            _ = try await session._testOnly_runLiveness(ambient: AmbientConditions())
            XCTFail("expected CDLError.stubModeDisallowed")
        } catch CDLError.stubModeDisallowed {
            // ok
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    func testRunLivenessUsesStubWhenStubModeAllowedAndNoLandmarkStream() async throws {
        let session = CDLSession(
            from: makeStubPayload(),
            landmarkStream: nil,
            allowStubMode: true
        )
        let (scores, result) = try await session._testOnly_runLiveness(ambient: AmbientConditions())
        // Stub path signature: scores match stubLayerScores() and the
        // nil result is what tells build() to stamp stub_mode=true.
        XCTAssertNil(result, "stub path must produce nil ChallengeResult")
        XCTAssertTrue(scores.layer2ChallengeCompleted)
    }

    // Phase 3 Step 4 compile-regression guard. `CDLSession.defaultAllowStubMode`
    // backs the public init's default-arg expression:
    //   public init(from:, landmarkStream:, allowStubMode: Bool = CDLSession.defaultAllowStubMode)
    // Swift inlines default-arg expressions at the caller's module, so the
    // referenced symbol must be `@usableFromInline internal` or `public` —
    // `private` is uncompilable. The original Phase-3 commit shipped
    // `private static let` and failed CI with:
    //   CDLSession.swift:53:42: error: static property 'defaultAllowStubMode'
    //   is private and cannot be referenced from a default argument value
    // Two complementary guards below:
    //   1. Referencing the symbol by name from the test module (which is
    //      an external consumer of the public API — `@testable import`
    //      still can't see `private` or `fileprivate`) will fail to
    //      compile if a future refactor drops `@usableFromInline internal`.
    //   2. Constructing CDLSession without the arg exercises the inlined
    //      default-arg path from outside the SDK module — the exact
    //      shape that broke under xcodebuild on CI.
    func testDefaultAllowStubModeIsReferenceableFromExternalModule() {
        // Package.swift doesn't define OPENLIVENESS_STUB_ALLOWED, so the
        // `#else` branch wins and the default must resolve to `false`.
        // Flipping this on by default would silently route Phase-1 stub
        // scores into every session that forgets to opt in.
        XCTAssertFalse(
            CDLSession.defaultAllowStubMode,
            "defaultAllowStubMode must be false unless OPENLIVENESS_STUB_ALLOWED is set at SDK build time"
        )
    }

    func testPublicInitCompilesWithoutExplicitAllowStubMode() {
        // Pure compile-shape check: if the public init's default-arg
        // expression stops compiling (e.g. storage reverted to `private`),
        // this call site goes with it. No behavioral assertion — the stub
        // vs. real-landmark branches are already covered above.
        _ = CDLSession(from: makeStubPayload())
    }

    // C-1 — assurance_level must downgrade to software_only when the
    // orchestrator decided `fail` or `inconclusive`, even if hw
    // attestation succeeded. Before the fix the builder emitted "IAL2"
    // off (deviceAttestation != nil && hwOk) alone — i.e. an attestation
    // claiming the strongest assurance level while the SDK's own
    // liveness decision was `fail`. Belt-and-suspenders at the SDK layer
    // so direct callers can't emit the self-contradicting attestation.
    func testAssuranceLevelDowngradesToSoftwareOnlyWhenDecisionFailEvenIfHwOk() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: "stub-token",
            livenessDecision: "fail",
            hwAttestationStatus: .ok
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""liveness_decision":"fail""#))
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#))
        XCTAssertTrue(str.contains(#""hw_attestation_status":"ok""#))
    }

    func testAssuranceLevelDowngradesToSoftwareOnlyWhenDecisionInconclusiveEvenIfHwOk() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            deviceAttestation: "stub-token",
            livenessDecision: "inconclusive",
            hwAttestationStatus: .ok
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""assurance_level":"software_only""#))
    }

    // H-1 — a caller that has a real `challengeResult` MUST also pass an
    // explicit `livenessDecision`. The old default routed through
    // `stubLivenessDecision` which only inspects layer 2; a catastrophic
    // layer-3/4/5 score would silently become "pass" on the wire.
    func testBuilderThrowsWhenChallengeResultProvidedWithoutLivenessDecision() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())
        let result = ChallengeResult(
            completed: true,
            score: 1.0,
            elapsedSeconds: 0.1,
            stepsCompleted: 1,
            totalSteps: 1,
            firedSteps: [.blink]
        )
        XCTAssertThrowsError(
            try AttestationBuilder.build(
                session: session,
                layerScores: AttestationBuilder.stubLayerScores(),
                ambient: AmbientConditions(),
                challengeResult: result
                // livenessDecision deliberately omitted
            )
        ) { err in
            XCTAssertEqual(
                err as? AttestationBuilder.BuildError,
                .missingLivenessDecisionForRealChallengeResult
            )
        }
    }

    // M-3 — challenge_response reflects what the SDK actually detected
    // (`firedSteps`), not just what the server prompted
    // (`session.challengeSequence`). On completed==true these are equal
    // by construction. The on-wire test fixes `firedSteps` to a known
    // order and asserts the comma-join matches.
    func testChallengeResponseIsFiredStepsWireJoinedWhenChallengeResultIsComplete() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())
        let result = ChallengeResult(
            completed: true,
            score: 1.0,
            elapsedSeconds: 0.1,
            stepsCompleted: 3,
            totalSteps: 3,
            firedSteps: [.blink, .lookLeft, .smile]
        )
        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            livenessDecision: "pass",
            challengeResult: result
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""challenge_response":"blink,look_left,smile""#),
                      "challenge_response must be firedSteps wire-joined, got: \(str)")
    }

    // M-3 — timed-out run still sends empty string so relay's
    // checkChallenge rejects the half-done result (unchanged from
    // pre-fix). Keeping this test wires in the new field surface.
    func testChallengeResponseIsEmptyStringOnTimedOutRunEvenWithPartialFiredSteps() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())
        let result = ChallengeResult(
            completed: false,
            score: 0.0,
            elapsedSeconds: 8.1,
            stepsCompleted: 1,
            totalSteps: 3,
            firedSteps: [.blink]
        )
        let bytes = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions(),
            livenessDecision: "fail",
            challengeResult: result
        )
        let str = String(data: bytes, encoding: .utf8) ?? ""
        XCTAssertTrue(str.contains(#""challenge_response":"""#))
    }

    func testStubLivenessDecisionReflectsLayer2Completion() {
        var scores = AttestationBuilder.stubLayerScores()
        XCTAssertEqual(AttestationBuilder.stubLivenessDecision(layerScores: scores), "pass")
        scores.layer2ChallengeCompleted = false
        XCTAssertEqual(AttestationBuilder.stubLivenessDecision(layerScores: scores), "fail")
    }

    // S-1 drift guard — the stub layer scores MUST compose with the real
    // LivenessOrchestrator to produce `.pass` (not inconclusive/fail). If
    // a future tuning of weights or thresholds breaks that, the Phase-1
    // e2e relay tests that still take the stub path would start failing
    // with the diagnostic buried in the server response. Locking this
    // here catches the drift at iOS-SDK unit-test time, where the fix
    // (bump the stub scores OR adjust the orchestrator) is obvious.
    func testStubLayerScoresComposeWithOrchestratorToPass() {
        let stub = AttestationBuilder.stubLayerScores()
        let outcome = LivenessOrchestrator().orchestrate(
            layerScores: stub,
            ambientConditions: AmbientConditions()
        )
        XCTAssertEqual(outcome.decision, "pass",
            "stub scores drifted below pass threshold — stub e2e path would start failing")
    }

    func testAttestationBuilderDeviceIdHashIsStableAcrossBuilds() throws {
        guard keychainIsAvailable() else {
            throw XCTSkip("Keychain unavailable in this test environment")
        }
        SigningService.KeyStore._clearPersistedKeyForTesting()
        let session = CDLSession(from: makeStubPayload())

        // Two builds back-to-back must produce the same device_id_hash: the
        // relay joins sessions-per-device via this hash, so a non-deterministic
        // value would make the per-device rate limit toothless and skew the
        // "repeated device" audit log.
        let bytes1 = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions()
        )
        let bytes2 = try AttestationBuilder.build(
            session: session,
            layerScores: AttestationBuilder.stubLayerScores(),
            ambient: AmbientConditions()
        )
        let hash1 = extractDeviceIdHash(from: bytes1)
        let hash2 = extractDeviceIdHash(from: bytes2)
        XCTAssertNotNil(hash1)
        XCTAssertEqual(hash1, hash2)
        // SHA-256 → 64 hex chars.
        XCTAssertEqual(hash1?.count, 64)
        // Guard against a false pass: on hosts where
        // UIDevice.identifierForVendor returns nil (some CI configurations,
        // pre-first-unlock), the hash degrades to SHA-256("unknown"). The
        // attestation would then share a device_id_hash with every other
        // such host — the relay's per-device audit log and rate limit would
        // be useless. Skip rather than silently accept the degraded path.
        let fallbackHash = Data(SHA256.hash(data: Data("unknown".utf8)))
            .compactMap { String(format: "%02x", $0) }.joined()
        if hash1 == fallbackHash {
            throw XCTSkip("UIDevice.identifierForVendor returned nil; cannot test real device_id_hash")
        }
    }

    private func extractDeviceIdHash(from bytes: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            return nil
        }
        return obj["device_id_hash"] as? String
    }

    // M2 drift tests — classifyAttestationError is the only switch between
    // "downgrade attack" and "benign environment" on iOS. If a keychain-locked
    // device started landing in `.failed`, every lock-screen attestation
    // would look adversarial server-side. Pin the mapping so a regression
    // tightening these catch arms shows up here before shipping.

    func testClassifyAttestationErrorMapsHardwareUnsupportedToUnsupported() {
        XCTAssertEqual(
            CDLSession.classifyAttestationError(CDLError.hardwareAttestationUnsupported),
            .unsupported
        )
    }

    func testClassifyAttestationErrorMapsKeychainErrorToUnsupported() {
        // The load-bearing case: a locked Keychain pre-first-unlock is
        // environmental, not adversarial. Must NOT land in `.failed`.
        XCTAssertEqual(
            CDLSession.classifyAttestationError(CDLError.keychainError),
            .unsupported
        )
    }

    func testClassifyAttestationErrorMapsNetworkErrorToFailed() {
        // Relay-nonce HTTP failure during Phase 2 handshake is the
        // adversarial-adjacent bucket (operator misconfig OR forced
        // downgrade). Keep in `.failed`.
        XCTAssertEqual(
            CDLSession.classifyAttestationError(CDLError.networkError("boom")),
            .failed
        )
    }

    func testClassifyAttestationErrorMapsUnknownErrorToFailed() {
        // DCAppAttestService can throw NSErrors we don't own. The default
        // branch must land them in `.failed` so the server still sees the
        // signal rather than silently tagging them as `.unsupported`.
        let unknown = NSError(domain: "com.apple.devicecheck", code: 2, userInfo: nil)
        XCTAssertEqual(CDLSession.classifyAttestationError(unknown), .failed)
    }

    private func makeStubPayload() -> QRPayload {
        QRPayload(
            session_id: "s1",
            challenge_id: "c1",
            challenge_sequence: ["blink"],
            relay_ws: "wss://x",
            relay_http: "https://x",
            expires_at: "2099-01-01T00:00:00Z",
            version: "1.0"
        )
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
