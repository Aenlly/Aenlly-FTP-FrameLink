// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FrameLinkCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "FrameLinkCore", targets: ["FrameLinkCore"]), .executable(name: "FrameLinkCoreCheck", targets: ["FrameLinkCoreCheck"])],
    targets: [
        .target(name: "FrameLinkCore"),
        .executableTarget(name: "FrameLinkCoreCheck", dependencies: ["FrameLinkCore"], path: "Checks"),
        .testTarget(name: "FrameLinkCoreTests", dependencies: ["FrameLinkCore"])
    ]
)
