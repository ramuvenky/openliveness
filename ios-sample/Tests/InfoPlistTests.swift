import XCTest

/// Pins the Info.plist contents the review surfaced: H-1 (ATS exception
/// for localhost) and H-4 (export-compliance declaration). These are
/// supplied via `project.yml` `info.properties` so a reviewer deleting
/// either key wouldn't fail compilation — this test is the backstop.
///
/// Hosted on the OpenLivenessSample app target so `Bundle.main` points at
/// the real app bundle, not the test bundle. (XcodeGen wires TEST_HOST
/// automatically for `type: bundle.unit-test` with an app dependency.)
final class InfoPlistTests: XCTestCase {

    private func value(_ key: String) -> Any? {
        Bundle.main.object(forInfoDictionaryKey: key)
    }

    // MARK: - H-1 regression pin

    func test_ats_allowsLocalNetworking() {
        // Without NSAllowsLocalNetworking the sample cannot reach the dev
        // stub-relay on http://127.0.0.1:8787 — iOS silently refuses the
        // connection. Checking the nested dict rather than
        // NSAllowsArbitraryLoads keeps the fix tight: only local traffic
        // bypasses ATS, public-internet HTTP stays blocked.
        let ats = value("NSAppTransportSecurity") as? [String: Any]
        XCTAssertNotNil(ats, "NSAppTransportSecurity dict missing from Info.plist (H-1 regression)")
        XCTAssertEqual(
            ats?["NSAllowsLocalNetworking"] as? Bool, true,
            "NSAllowsLocalNetworking must be true so http://127.0.0.1 relay traffic is allowed (H-1)"
        )
        // Belt-and-braces: make sure we didn't accidentally slip the
        // nuclear option in. NSAllowsArbitraryLoads=true would silently
        // disable ATS globally and defeat the point of being specific.
        XCTAssertNotEqual(
            ats?["NSAllowsArbitraryLoads"] as? Bool, true,
            "NSAllowsArbitraryLoads must NOT be true — scoped to local networking only"
        )
    }

    // MARK: - H-4 regression pin

    func test_exportComplianceAnswer_isPresent() {
        // Without this key, every TestFlight upload parks in App Store
        // Connect waiting for a manual answer to the export-compliance
        // questionnaire — breaks the whole "push a tag, get a build on
        // my phone 15 min later" loop the workflow is designed around.
        XCTAssertEqual(
            value("ITSAppUsesNonExemptEncryption") as? Bool, false,
            "ITSAppUsesNonExemptEncryption must be set to false so TestFlight uploads skip the manual export-compliance review (H-4)"
        )
    }

    // MARK: - Existing-contract pins (not from the review, but low-cost
    // guards against a well-meaning cleanup that strips a required key).

    func test_cameraUsageDescription_isNonEmpty() {
        let desc = value("NSCameraUsageDescription") as? String ?? ""
        XCTAssertFalse(
            desc.isEmpty,
            "NSCameraUsageDescription must be non-empty or iOS silently refuses the capture session"
        )
    }
}
