/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.location.Location;
import androidx.core.location.LocationCompat;
import com.getcapacitor.Logger;
import java.lang.reflect.Method;

/**
 * Shyld: hands every fix to Shyld's outbox (`com.shyld.plugins.outbox.Outbox.onLocation`), which
 * keeps and sends it with the shift credential even while the web view sleeps. Found by name so
 * this plugin builds and runs without the outbox; with no outbox in the app it does nothing.
 */
final class ShyldHandOff {
    private static final String OUTBOX = "com.shyld.plugins.outbox.Outbox";
    private static final String ALERTS = "com.shyld.plugins.alerts.Alerts";
    private static Method onLocation;
    private static boolean looked;

    private ShyldHandOff() {}

    static void deliver(Context context, Location location) {
        Method m = method();
        if (m == null) return;
        try {
            m.invoke(null, context.getApplicationContext(), location.getLatitude(), location.getLongitude(),
                    location.hasAccuracy() ? (double) location.getAccuracy() : -1d, location.getTime(),
                    location.hasSpeed() ? (double) location.getSpeed() : -1d, LocationCompat.isMock(location));
        } catch (Exception e) {
            Logger.error("Shyld outbox hand-off failed", e);
        }
    }

    /**
     * Whether Shyld's outbox wants tracking kept after a swipe-away (its switch on and a shift tracked;
     * decision 0139). False without the outbox.
     */
    static boolean keepTracking(Context context) {
        try {
            Object keep = Class.forName(OUTBOX).getMethod("keepsTrackingAfterSwipe", Context.class)
                    .invoke(null, context.getApplicationContext());
            return Boolean.TRUE.equals(keep);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            Logger.error("Shyld outbox keep-alive check failed", e);
            return false;
        }
    }

    /** The app was swiped away: Shyld's alerts say "Tracking stopped" if a shift is on. */
    static void appClosed(Context context) {
        try {
            Class.forName(ALERTS).getMethod("onAppClosed", Context.class).invoke(null, context.getApplicationContext());
        } catch (ClassNotFoundException e) {
            // No alerts plugin in this app: nothing to say.
        } catch (Exception e) {
            Logger.error("Shyld alerts hand-off failed", e);
        }
    }

    private static synchronized Method method() {
        if (looked) return onLocation;
        looked = true;
        try {
            onLocation = Class.forName(OUTBOX).getMethod("onLocation", Context.class, double.class, double.class,
                    double.class, long.class, double.class, boolean.class);
        } catch (Exception e) {
            onLocation = null;
        }
        return onLocation;
    }
}
