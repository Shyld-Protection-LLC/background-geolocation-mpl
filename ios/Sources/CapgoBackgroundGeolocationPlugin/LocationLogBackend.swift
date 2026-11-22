/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import Foundation

enum LocationLogBackend {
    static func makeStorage() -> LocationLogStorage {
        #if BGGEO_LOCATION_LOG_SQLITE
        if LocationLogPreferences.locationLogSqlite {
            return LocationLogSqliteStorage.make()
        }
        #endif
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return LocationLogFileStorage(
            url: support.appendingPathComponent("CapgoBackgroundGeolocation/location_log.txt")
        )
    }
}
