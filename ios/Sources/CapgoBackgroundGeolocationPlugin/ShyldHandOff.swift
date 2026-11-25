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
    /// Posted by the outbox on a 409 stop_tracking (BL-092, C 11-24; `OutboxRules.trackerStopName`),
    /// userInfo "tracking": whether it still tracks a shift.
    static let stopName = Notification.Name("ShyldTrackerStop")

    /// BL-092 backstop: a tracker whose fixes no shift wants stops itself after this long.
    static let untrackedStopSeconds: TimeInterval = 10 * 60

    /// Hands the fix over and returns whether the outbox tracks a shift: nil without an outbox, or
    /// one that can't say (it answers synchronously in `reply`, under the key "tracking").
    @discardableResult
    static func deliver(_ location: CLLocation) -> Bool? {
        let reply = NSMutableDictionary()
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
            "reply": reply,
        ])
        return reply["tracking"] as? Bool
    }

    /// Whether the tracker should stop: the outbox has tracked no shift for `untrackedStopSeconds`.
    static func stopsUntracked(tracking: Bool?, untrackedSince: Date?, now: Date) -> Bool {
        guard tracking == false, let untrackedSince else { return false }
        return now.timeIntervalSince(untrackedSince) >= untrackedStopSeconds
    }

    /// On the outbox's stop: the tracker stops at once only when no shift is tracked any more (a
    /// stop for one shift never ends another's, and an outbox that can't say never stops it).
    static func stopsNow(tracking: Bool?) -> Bool {
        tracking == false
    }

    /// VALP-1233 (build 19, 11-25): within this long of the tracker's own start, the earlier
    /// shift's late 409 doesn't stop it, since the outbox may not track the new shift yet. The
    /// 10-minute backstop still stops a tracker nothing ever tracks.
    static let startSettleSeconds: TimeInterval = 2 * 60

    static func stopsNow(tracking: Bool?, startedAt: Date?, now: Date) -> Bool {
        guard stopsNow(tracking: tracking) else { return false }
        guard let startedAt = startedAt else { return true }
        return now.timeIntervalSince(startedAt) >= startSettleSeconds
    }
}
