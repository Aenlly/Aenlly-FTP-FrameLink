// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FrameLinkCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "FrameLinkCore", targets: ["FrameLinkCore"]), .executable(name: "FrameLinkCoreCheck", targets: ["FrameLinkCoreCheck"]), .executable(name: "FrameLinkConnectionFixture", targets: ["FrameLinkConnectionFixture"])],
    targets: [
        .target(name: "FrameLinkCore"),
        .executableTarget(name: "FrameLinkCoreCheck", dependencies: ["FrameLinkCore"], path: "Checks"),
        .executableTarget(name: "FrameLinkConnectionFixture", dependencies: ["FrameLinkCore"], path: "ConnectionFixture"),
        .testTarget(name: "FrameLinkCoreTests", dependencies: ["FrameLinkCore"])
    ]
)
