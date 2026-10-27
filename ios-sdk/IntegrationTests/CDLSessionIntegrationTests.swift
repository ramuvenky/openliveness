import XCTest
@testable import OpenLiveness

/// End-to-end integration against the Python stub-relay
/// (`tools/stub-relay/server.py`). Mirror of the Android instrumented test
/// `CDLSessionE2ETest.fullFlowAgainstStubRelay` — Android targets the
/// emulator's host loopback `10.0.2.2:8787`; the iOS Simulator shares the
/// host's loopback directly, so we target `127.0.0.1:8787` instead.
///
/// Local run:
///     cd tools/stub-relay && python -m venv .venv && \
///         .venv/bin/pip install -r requirements.txt && \
///         nohup .venv/bin/python server.py > /tmp/relay.log 2>&1 &
///     cd ios-sdk && swift test --filter OpenLivenessIntegrationTests
///
/// If the relay isn't up this suite skips (not fails) via a health probe
/// against `/cdl/session/t/complete` — the same readiness endpoint
/// `.github/workflows/android.yml` pings. CI runs this target explicitly
/// via `-only-testing:OpenLivenessIntegrationTests`, so a skip in CI means
/// the relay failed to start; the integration-tests job surfaces that as
/// a build failure before test execution.
final class CDLSessionIntegrationTests: XCTestCase {

    private static let relayHTTP = "http://127.0.0.1:8787"
    private static let relayWS   = "ws://127.0.0.1:8787"

    func test_fullFlowAgainstStubRelay() async throws {
        try await XCTSkipUnlessRelayReachable()

        let sessionId = "sess-\(Int(Date().timeIntervalSince1970 * 1000))"
        let payload = QRPayload(
            session_id: sessionId,
            challenge_id: "ch-1",
            challenge_sequence: ["blink", "turn_left"],
            relay_ws: "\(Self.relayWS)/cdl/session/\(sessionId)/ws",
            relay_http: Self.relayHTTP,
            expires_at: "2099-01-01T00:00:00Z",
            version: "1.0"
        )

        // allowStubMode=true: no landmarkStream wired, so start() takes the
        // Phase-1 stub path. Production configuration (allowStubMode=false,
        // no stream) is already covered by the Debug/Release default test
        // in OpenLivenessTests — repeating it here against the real relay
        // would just block on the stub-gate throw before any HTTP call.
        let response = try await CDLSession(from: payload, allowStubMode: true).start()

        XCTAssertTrue(response.accepted)
        XCTAssertNotNil(response.attestationToken)
        XCTAssertTrue(
            response.attestationToken?.hasPrefix("stub-attest-") ?? false,
            "stub relay must stamp a `stub-attest-` token; got: \(response.attestationToken ?? "nil")"
        )
    }

    /// Readiness probe matched to the android.yml step — POST an empty body
    /// at `/cdl/session/t/complete` and treat any 2xx as "relay is up". Kept
    /// at a 2-second timeout so a missing relay surfaces fast; the actual
    /// flow has its own 30s ack deadline.
    private func XCTSkipUnlessRelayReachable() async throws {
        guard let url = URL(string: "\(Self.relayHTTP)/cdl/session/t/complete") else {
            throw XCTSkip("relay URL malformed")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data("{}".utf8)
        req.timeoutInterval = 2

        let reachable: Bool
        do {
            let (_, response) = try await URLSession.shared.data(for: req)
            reachable = (response as? HTTPURLResponse)
                .map { (200..<300).contains($0.statusCode) } ?? false
        } catch {
            reachable = false
        }

        if !reachable {
            throw XCTSkip(
                "stub-relay not reachable at \(Self.relayHTTP) — start it before `swift test` or run via CI's integration-tests job"
            )
        }
    }
}
