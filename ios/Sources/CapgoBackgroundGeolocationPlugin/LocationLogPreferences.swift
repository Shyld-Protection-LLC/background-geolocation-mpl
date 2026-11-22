/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import Foundation

enum LocationLogPreferences {
    private static let suiteName = "CapgoBackgroundGeolocationWatcher"
    private static let locationLogSqliteKey = "locationLogSqlite"

    static func setLocationLogSqlite(_ enabled: Bool) {
        UserDefaults(suiteName: suiteName)?.set(enabled, forKey: locationLogSqliteKey)
    }

    static var locationLogSqlite: Bool {
        UserDefaults(suiteName: suiteName)?.bool(forKey: locationLogSqliteKey) ?? false
    }
}
