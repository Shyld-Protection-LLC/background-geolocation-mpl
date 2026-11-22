// swift-tools-version: 5.9
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
import PackageDescription

// Shyld: the package and product take the npm name in PascalCase, as Capacitor's sync expects
// (FORK.md); the target and its Swift module keep upstream's name.
let package = Package(
    name: "ShyldPluginBackgroundGeolocation",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "ShyldPluginBackgroundGeolocation",
            targets: ["CapgoBackgroundGeolocationPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "CapgoBackgroundGeolocationPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios",
            sources: ["Sources/CapgoBackgroundGeolocationPlugin"],
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),
            ],
            // linkerSettings: [.linkedLibrary("sqlite3")],
        ),
        .testTarget(
            name: "CapgoBackgroundGeolocationPluginTests",
            dependencies: ["CapgoBackgroundGeolocationPlugin"],
            path: "ios/Tests/CapgoBackgroundGeolocationPluginTests",
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),
            ])
    ]
)
