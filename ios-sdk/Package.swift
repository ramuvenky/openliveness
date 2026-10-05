// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "OpenLiveness",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "OpenLiveness", targets: ["OpenLiveness"])
    ],
    dependencies: [
        .package(
            url: "https://github.com/google/mediapipe",
            from: "0.10.0"
        )
    ],
    targets: [
        .target(
            name: "OpenLiveness",
            dependencies: [
                .product(name: "MediaPipeTasksVision", package: "mediapipe")
            ],
            path: "Sources/OpenLiveness"
        ),
        .testTarget(
            name: "OpenLivenessTests",
            dependencies: ["OpenLiveness"],
            path: "Tests"
        )
    ]
)
