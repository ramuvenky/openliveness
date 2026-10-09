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
            path: "Sources/OpenLiveness"
        ),
        .testTarget(
            name: "OpenLivenessTests",
            dependencies: ["OpenLiveness"],
            path: "Tests"
        )
    ]
)
