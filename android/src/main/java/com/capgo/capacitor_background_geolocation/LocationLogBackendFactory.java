/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.content.Context;

final class LocationLogBackendFactory {

    private LocationLogBackendFactory() {}

    static LocationLogBackend create(Context context) {
        if (LocationStore.getLocationLogSqlite(context) && !BuildConfig.LOCATION_LOG_SQLITE) {
            throw new IllegalStateException(LocationLog.SQLITE_DISABLED_MESSAGE);
        }
        return new LocationLogBackend(context);
    }
}
