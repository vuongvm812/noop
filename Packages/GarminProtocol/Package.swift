// swift-tools-version: 5.9
import PackageDescription

// Clean-room Garmin GFDI / multi-link BLE protocol package. Mirrors the layout of Packages/PolarProtocol:
// a pure value-type library target (zero CoreBluetooth, Linux-buildable) plus a test target. The live
// CoreBluetooth source that drives it (`GarminSource`) is an app-target follow-up; this package owns every
// byte the source sends or receives, including the send allowlist (`GarminOutbound`).
let package = Package(
    name: "GarminProtocol",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "GarminProtocol", targets: ["GarminProtocol"]),
    ],
    targets: [
        .target(
            name: "GarminProtocol"
        ),
        .testTarget(
            name: "GarminProtocolTests",
            dependencies: ["GarminProtocol"]
        ),
    ]
)
