// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KeepAppleKit",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "KeepCore", targets: ["KeepCore"]),
        .library(name: "KeepApp", targets: ["KeepApp"]),
        .library(name: "KeepPlatform", targets: ["KeepPlatform"]),
        .library(name: "KeepUI", targets: ["KeepUI"]),
        .library(name: "KeepDesignSystem", targets: ["KeepDesignSystem"]),
    ],
    targets: [
        .binaryTarget(name: "KeepCoreFFI", path: "Generated/KeepCoreFFI.xcframework"),
        .target(
            name: "KeepCoreBindings",
            dependencies: ["KeepCoreFFI"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(name: "KeepCore", dependencies: ["KeepCoreBindings"]),
        .target(name: "KeepApp", dependencies: ["KeepCore"]),
        .target(name: "KeepPlatform"),
        .target(name: "KeepUI", dependencies: ["KeepCore"]),
        .target(name: "KeepDesignSystem", resources: [.copy("Resources/Fonts")]),
        .testTarget(name: "KeepCoreTests", dependencies: ["KeepCore"]),
        .testTarget(name: "KeepAppTests", dependencies: ["KeepApp", "KeepCore"]),
    ]
)
