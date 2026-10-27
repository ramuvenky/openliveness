import XCTest
@testable import OpenLiveness

/// Pins the C-2 regression fix: Package.swift defines
/// `OPENLIVENESS_STUB_ALLOWED` on `.debug` configuration so the compile-flag
/// mechanism documented on `CDLSession.allowStubMode` actually works. Prior
/// to the fix the flag was referenced in CDLSession.swift but never defined
/// anywhere, making `defaultAllowStubMode` always false — a silent dead
/// code path that caused the ios-sample dev harness to throw
/// `.stubModeDisallowed` on every run.
///
/// If someone removes the `swiftSettings` block from Package.swift, this
/// test fails before CI ever builds the sample.
final class StubModeDefaultTests: XCTestCase {

    #if DEBUG
    func test_defaultAllowStubMode_isTrueWhenCompiledInDebug() {
        // swift test / xcodebuild Debug both set DEBUG and (via the Package.swift
        // swiftSettings) OPENLIVENESS_STUB_ALLOWED. The two should move together —
        // if Debug ever stops setting the stub flag, integrators who rely on the
        // default start hitting .stubModeDisallowed in dev builds, which is
        // exactly the breakage C-2 fixed.
        XCTAssertTrue(
            CDLSession.defaultAllowStubMode,
            "Debug builds must enable OPENLIVENESS_STUB_ALLOWED. " +
            "Check Package.swift's swiftSettings — the .define must stay gated on .debug."
        )
    }
    #else
    func test_defaultAllowStubMode_isFalseWhenCompiledInRelease() {
        // The second half of the invariant: Release must NOT set the flag, so
        // integrators who forget to inject a stream in production get
        // .stubModeDisallowed at run time (the whole point of the gate).
        XCTAssertFalse(
            CDLSession.defaultAllowStubMode,
            "Release builds must NOT enable OPENLIVENESS_STUB_ALLOWED. " +
            "The compile-flag gate exists so a Release integrator who forgets " +
            "to inject a landmark stream fails loudly instead of silently " +
            "passing liveness."
        )
    }
    #endif
}
