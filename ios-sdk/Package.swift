// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "OpenLiveness",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "OpenLiveness", targets: ["OpenLiveness"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "OpenLiveness",
            path: "Sources/OpenLiveness"
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
