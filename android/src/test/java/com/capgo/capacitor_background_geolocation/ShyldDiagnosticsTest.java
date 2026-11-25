/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Shyld (BL-092): a failure upstream swallowed is kept for diagnostics(), not lost. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class ShyldDiagnosticsTest {

    @After
    public void tearDown() {
        ShyldDiagnostics.reset();
    }

    @Test
    public void nothingHappenedYet() throws Exception {
        ShyldDiagnostics.reset();
        JSObject s = ShyldDiagnostics.snapshot();
        assertFalse(s.getBoolean("running"));
        assertFalse(s.getBoolean("foreground"));
        assertEquals(JSONObject.NULL, s.get("lastFixAt"));
        assertEquals(JSONObject.NULL, s.get("lastError"));
    }

    @Test
    public void aFailedForegroundStartIsKeptWithWhereItHappened() throws Exception {
        ShyldDiagnostics.reset();
        ShyldDiagnostics.running = true;
        ShyldDiagnostics.error("foreground", new SecurityException("Starting FGS with type location requires permissions"));
        JSObject s = ShyldDiagnostics.snapshot();
        assertTrue(s.getBoolean("running"));
        assertFalse(s.getBoolean("foreground"));
        assertEquals("foreground: SecurityException Starting FGS with type location requires permissions", s.getString("lastError"));
        assertTrue(s.getLong("lastErrorAt") > 0);
    }

    @Test
    public void theNotificationAlwaysHasASmallIcon() {
        assertEquals(42, BackgroundGeolocationService.smallIcon(42));
        assertEquals(android.R.drawable.ic_menu_mylocation, BackgroundGeolocationService.smallIcon(0));
    }

    @Test
    public void aRefusedStartIsKept() throws Exception {
        ShyldDiagnostics.reset();
        ShyldDiagnostics.error("start", "NOT_AUTHORIZED location services off");
        assertEquals("start: NOT_AUTHORIZED location services off", ShyldDiagnostics.snapshot().getString("lastError"));
    }

    @Test
    public void aProviderThatComesBackClearsItsOwnErrorOnly() throws Exception {
        ShyldDiagnostics.error("fused", "provider off");
        ShyldDiagnostics.resolved("network");
        assertEquals("fused: provider off", ShyldDiagnostics.snapshot().getString("lastError"));
        ShyldDiagnostics.resolved("fused");
        assertEquals(JSONObject.NULL, ShyldDiagnostics.snapshot().get("lastError"));
    }

    @Test
    public void countsWhatCameAndWhatWasTurnedAway() throws Exception {
        ShyldDiagnostics.reset();
        ShyldDiagnostics.fixFrom("fused");
        ShyldDiagnostics.fixFrom("fused");
        ShyldDiagnostics.fixFrom("gps");
        ShyldDiagnostics.rejected("cap");
        ShyldDiagnostics.asked("fresh");
        ShyldDiagnostics.asked("fresh");
        ShyldDiagnostics.answered("fresh");
        ShyldDiagnostics.alarmFired();
        JSObject s = ShyldDiagnostics.snapshot();
        assertEquals(2, s.getJSONObject("fixes").getInt("fused"));
        assertEquals(1, s.getJSONObject("fixes").getInt("gps"));
        assertEquals(1, s.getJSONObject("rejected").getInt("cap"));
        assertEquals("2 asked, 1 answered", s.getJSONObject("asks").getString("fresh"));
        assertEquals(1, s.getInt("alarmFires"));
    }

    @Test
    public void followsIdleModeInAndOut() throws Exception {
        ShyldDiagnostics.reset();
        ShyldDiagnostics.idle(true, 1_000L);
        ShyldDiagnostics.idle(true, 2_000L);
        JSObject during = ShyldDiagnostics.snapshot();
        assertEquals(1, during.getInt("idleEntries"));
        assertEquals(1_000L, during.getLong("idleSince"));
        ShyldDiagnostics.idle(false, 5_000L);
        JSObject after = ShyldDiagnostics.snapshot();
        assertEquals(JSONObject.NULL, after.get("idleSince"));
        assertEquals(5_000L, after.getLong("idleEndedAt"));
    }

    @Test
    public void withAContextSaysWhetherIdleNowAndWhetherExempt() throws Exception {
        ShyldDiagnostics.reset();
        JSObject s = ShyldDiagnostics.snapshot(androidx.test.core.app.ApplicationProvider.getApplicationContext());
        assertFalse(s.getBoolean("idleNow"));
        assertFalse(s.getBoolean("batteryExempt"));
        // Without one (tests, an older caller) they're unknown, not false.
        assertEquals(JSONObject.NULL, ShyldDiagnostics.snapshot().get("idleNow"));
    }
}
