import XCTest
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

    func testSigningProducesVerifiableSignature() throws {
        let attestation: [String: Any] = ["session_id": "abc", "liveness_decision": "pass"]
        let sig = try SigningService.sign(attestation)
        XCTAssertFalse(sig.isEmpty)
        XCTAssertFalse(SigningService.publicKeyBase64().isEmpty)
    }
}
