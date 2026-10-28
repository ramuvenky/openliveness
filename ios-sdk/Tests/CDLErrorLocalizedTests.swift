import XCTest
@testable import OpenLiveness

/// Pins the M-2 fix: CDLError conforms to LocalizedError so UI code reading
/// `error.localizedDescription` gets a human-readable, actionable string
/// rather than Cocoa's default "The operation couldn't be completed."
/// boilerplate. The dev-harness status labels on both platforms depend on
/// this; a regression here puts opaque text in front of developers trying
/// to triage a failed session.
final class CDLErrorLocalizedTests: XCTestCase {

    private let cocoaBoilerplate = "couldn't be completed"

    func test_everyCase_hasNonBoilerplateDescription() {
        // One assertion per case — explicit list, not a loop over
        // CaseIterable, because CDLError.networkError takes an associated
        // value. Keeping the cases enumerated here also makes a new error
        // case fail compilation of this test (missing switch arm would be
        // ideal but we assert against instances), reminding whoever adds
        // one to supply a message.
        let cases: [CDLError] = [
            .hardwareAttestationUnsupported,
            .keychainError,
            .qrParseError,
            .networkError("relay unreachable"),
            .challengeTimeout,
            .sessionExpired,
            .cameraPermissionDenied,
            .stubModeDisallowed,
        ]
        for err in cases {
            let desc = (err as Error).localizedDescription
            XCTAssertFalse(
                desc.contains(cocoaBoilerplate),
                "\(err) still returns Cocoa boilerplate — LocalizedError conformance regressed. Got: \(desc)"
            )
            XCTAssertFalse(desc.isEmpty, "\(err) has empty description")
        }
    }

    func test_networkError_preservesInnerDetail() {
        // The networkError associated value carries the specific failure (e.g.
        // "timeout waiting for session_acknowledged") — losing it in the
        // human-readable form makes triage from a user screenshot impossible.
        let err: Error = CDLError.networkError("timeout waiting for session_acknowledged")
        XCTAssertTrue(
            err.localizedDescription.contains("timeout waiting for session_acknowledged"),
            "networkError description must preserve the inner detail string"
        )
    }

    func test_stubModeDisallowed_mentionsBothEscapes() {
        // The error's whole value is pointing a confused integrator at the fix.
        // If the message loses either "LandmarkStream" or "allowStubMode" the
        // triage breadcrumb is gone and we're back to grep-the-source.
        let desc = (CDLError.stubModeDisallowed as Error).localizedDescription
        XCTAssertTrue(desc.contains("LandmarkStream"), "stubModeDisallowed must name the LandmarkStream escape")
        XCTAssertTrue(desc.contains("allowStubMode"), "stubModeDisallowed must name the allowStubMode escape")
    }
}
