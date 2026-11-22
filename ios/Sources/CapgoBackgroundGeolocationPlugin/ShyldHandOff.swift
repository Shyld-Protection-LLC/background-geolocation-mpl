/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import CoreLocation
import Foundation

/// Shyld: hands every fix to Shyld's outbox, which keeps and sends it with the shift credential
/// even while the web view sleeps. A notification, so this plugin needs no link to the outbox;
/// the outbox observes `ShyldOutboxLocation` (`Outbox.locationNotification`).
enum ShyldHandOff {
    static let name = Notification.Name("ShyldOutboxLocation")

    static func deliver(_ location: CLLocation) {
        var simulated = false
        if #available(iOS 15.0, *), let source = location.sourceInformation {
            simulated = source.isSimulatedBySoftware
        }
        NotificationCenter.default.post(name: name, object: nil, userInfo: [
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "timeMs": Int64((location.timestamp.timeIntervalSince1970 * 1000).rounded()),
            "speed": location.speed,
            "isMock": simulated,
        ])
    }
}
