// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "OpenLiveness",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "OpenLiveness", targets: ["OpenLiveness"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "OpenLiveness",
            path: "Sources/OpenLiveness",
            // Review fix C-2: without this, `#if OPENLIVENESS_STUB_ALLOWED`
            // in CDLSession.swift was dead code — the SDK module compiled
            // the symbol out regardless of build config, so
            // `defaultAllowStubMode` was always false and any integrator
            // relying on the default got a `stubModeDisallowed` throw on
            // Debug too. Defining the flag only on `.debug` restores the
            // intended "safe-by-default in Release, convenient in Debug"
            // semantics documented on CDLSession.allowStubMode. Mirrors
            // the Android `buildTypes.debug { buildConfigField
            // STUB_PATH_ENABLED=true }` split in android-sdk/build.gradle.kts.
            swiftSettings: [
                .define("OPENLIVENESS_STUB_ALLOWED", .when(configuration: .debug))
            ]
        ),
        .testTarget(
            name: "OpenLivenessTests",
            dependencies: ["OpenLiveness"],
            path: "Tests"
        ),
        // Mirror of android-sdk/src/androidTest/ — the Android split is
        // src/test/ (JVM, no device) vs src/androidTest/ (emulator + stub
        // relay). iOS has no equivalent native split, so we fake it with a
        // dedicated test target that CI runs behind a Python stub-relay.
        // Keeping it as a separate target (not a test class inside
        // OpenLivenessTests) lets the ios.yml `unit-tests` job
        // `-skip-testing:` this bundle wholesale, matching the gradle
        // `./gradlew test` vs `connectedDebugAndroidTest` boundary.
        .testTarget(
            name: "OpenLivenessIntegrationTests",
            dependencies: ["OpenLiveness"],
            path: "IntegrationTests"
        )
    ]
)
