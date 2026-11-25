/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Shyld (BL-092): indoors, a fused Wi-Fi fix is taken once GPS has been silent; GPS stays first. */
public class ShyldFixFilterTest {
    private static final long GRACE = 20_000;
    private static final float CAP = 300f;

    @Test
    public void aGpsFixIsAlwaysTaken() {
        assertTrue(ShyldFixFilter.accepts("gps", 1_000, 900, GRACE, -1, CAP));
    }

    @Test
    public void aFusedFixWaitsForGpsToGoQuietThenIsTaken() {
        // Started at 10 s (the service counts the start as a GPS moment): a fused fix at 15 s waits.
        assertFalse(ShyldFixFilter.accepts("fused", 15_000, 10_000, GRACE, 8f, CAP));
        // At 31 s with no GPS fix, the Pixel's indoor Wi-Fi fix is taken.
        assertTrue(ShyldFixFilter.accepts("fused", 31_000, 10_000, GRACE, 8f, CAP));
        assertTrue(ShyldFixFilter.accepts("network", 31_000, 0, GRACE, 40f, CAP));
    }

    @Test
    public void aCoarseOrUnknownFallbackFixIsNot() {
        assertFalse(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 1_200f, CAP));
        assertFalse(ShyldFixFilter.accepts("network", 60_000, 0, GRACE, -1, CAP));
    }

    @Test
    public void theWatchdogLeavesASearchingGpsAloneUntilTheFirstFix() {
        assertFalse(ShyldFixFilter.restartsAfterSilence(false));
        assertTrue(ShyldFixFilter.restartsAfterSilence(true));
    }

    @Test
    public void platformFusedIsAskedFromApi31WithTheFallbackOnAndNoPlayServices() {
        assertTrue(ShyldFixFilter.asksFused(31, true, false));
        assertTrue(ShyldFixFilter.asksFused(36, true, false));
        assertFalse(ShyldFixFilter.asksFused(30, true, false));
        assertFalse(ShyldFixFilter.asksFused(36, false, false));
        // Play services' fused client takes its place.
        assertFalse(ShyldFixFilter.asksFused(36, true, true));
    }

    @Test
    public void aFreshFixIsAskedOfFusedThenNetworkThenGps() {
        assertEquals("fused", ShyldFixFilter.freshFixProvider(34, true, true, true));
        assertEquals("network", ShyldFixFilter.freshFixProvider(30, true, true, true));
        assertEquals("network", ShyldFixFilter.freshFixProvider(34, false, true, true));
        // Location Accuracy off on a Pixel: GPS is all there is.
        assertEquals("gps", ShyldFixFilter.freshFixProvider(34, false, false, true));
        assertNull(ShyldFixFilter.freshFixProvider(34, false, false, false));
    }

    @Test
    public void theAlarmAsksOnlyWhenFixesHaveStopped() {
        // Build 13: 7 minutes with no fix after the Pixel locked.
        assertTrue(ShyldFixFilter.freshFixDue(420_000, 0));
        assertTrue(ShyldFixFilter.freshFixDue(100_000, 50_000));
        // The Handler just asked and a fix came: the alarm leaves it.
        assertFalse(ShyldFixFilter.freshFixDue(100_000, 80_000));
    }

    @Test
    public void aCoarseFallbackFixAtTheCapIsStillTaken() {
        // C, build 13: a ±300 m fix is all a phone may have; it goes as the heartbeat.
        float cap = ShyldFixFilter.FALLBACK_MAX_ACCURACY_M;
        assertTrue(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 300f, cap));
        assertFalse(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 301f, cap));
    }

    @Test
    public void withApproximateLocationOnlyACoarseFixIsTaken() {
        // Approximate only: Android refuses GPS and fused fixes are ±2-3 km. They must go.
        assertTrue(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 2_400f, ShyldFixFilter.fallbackCap(false)));
        // Precise allowed: the 300 m cap keeps a coarse fix from standing in for a good one.
        assertFalse(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 2_400f, ShyldFixFilter.fallbackCap(true)));
        assertTrue(ShyldFixFilter.accepts("fused", 60_000, 0, GRACE, 300f, ShyldFixFilter.fallbackCap(true)));
    }

    @Test
    public void onlyTheSameLatitudeLongitudeAndAccuracyIsARepeat() {
        // VALP-1225: the Pixel's ±142 m estimate handed back 61 times.
        assertTrue(ShyldFixFilter.isRepeat(true, 51.5, -0.12, 142f, 51.5, -0.12, 142f));
        assertFalse(ShyldFixFilter.isRepeat(true, 51.5, -0.12, 116f, 51.5, -0.12, 142f));
        assertFalse(ShyldFixFilter.isRepeat(true, 51.50001, -0.12, 142f, 51.5, -0.12, 142f));
        assertFalse(ShyldFixFilter.isRepeat(false, 51.5, -0.12, 142f, 51.5, -0.12, 142f));
    }

    @Test
    public void theAskIsBalancedUntilTheSameSpotHasStoodFiveMinutesThenHighAccuracy() {
        long since = 1_000_000L;
        assertEquals(ShyldFixFilter.PRIORITY_BALANCED_POWER_ACCURACY, ShyldFixFilter.freshFixPriority(since + 60_000, since));
        assertEquals(ShyldFixFilter.PRIORITY_BALANCED_POWER_ACCURACY, ShyldFixFilter.freshFixPriority(since + 299_999, since));
        assertEquals(ShyldFixFilter.PRIORITY_HIGH_ACCURACY, ShyldFixFilter.freshFixPriority(since + 300_000, since));
        assertEquals(ShyldFixFilter.PRIORITY_HIGH_ACCURACY, ShyldFixFilter.freshFixPriority(since + 3_600_000, since));
        // No reading yet in this start: GPS too.
        assertEquals(ShyldFixFilter.PRIORITY_HIGH_ACCURACY, ShyldFixFilter.freshFixPriority(5_000, 0));
    }

    @Test
    public void aHighAccuracyAskRunsThirtySecondsAndABalancedOneTwenty() {
        assertEquals(30_000L, ShyldFixFilter.freshFixDurationMs(ShyldFixFilter.PRIORITY_HIGH_ACCURACY));
        assertEquals(20_000L, ShyldFixFilter.freshFixDurationMs(ShyldFixFilter.PRIORITY_BALANCED_POWER_ACCURACY));
        // The Play services values these mirror.
        assertEquals(100, ShyldFixFilter.PRIORITY_HIGH_ACCURACY);
        assertEquals(102, ShyldFixFilter.PRIORITY_BALANCED_POWER_ACCURACY);
    }

    @Test
    public void aTrackerNoShiftWantsStopsAfterTenMinutes() {
        long since = 1_000_000L;
        assertFalse(ShyldFixFilter.stopsUntracked(false, since, since + 599_999));
        assertTrue(ShyldFixFilter.stopsUntracked(false, since, since + 600_000));
        // Tracking, no outbox, an outbox that can't say, or not seen untracked yet: never.
        assertFalse(ShyldFixFilter.stopsUntracked(true, since, since + 3_600_000));
        assertFalse(ShyldFixFilter.stopsUntracked(null, since, since + 3_600_000));
        assertFalse(ShyldFixFilter.stopsUntracked(false, 0, since + 3_600_000));
    }

    @Test
    public void aStopFromTheServerStopsTheTrackerOnlyWhenNoShiftIsLeft() {
        assertTrue(ShyldFixFilter.stopsNow(false));
        // Another shift still tracked, or no outbox / one that can't say: keep running.
        assertFalse(ShyldFixFilter.stopsNow(true));
        assertFalse(ShyldFixFilter.stopsNow(null));
    }

    @Test
    public void aStartRunsTheStreamFastForAMinute() {
        long start = 1_000_000L;
        long until = start + ShyldFixFilter.FAST_START_MS;
        assertTrue(ShyldFixFilter.fastStream(start, until));
        assertTrue(ShyldFixFilter.fastStream(until - 1, until));
        assertFalse(ShyldFixFilter.fastStream(until, until));
        assertFalse(ShyldFixFilter.fastStream(start, 0));
        assertEquals(5_000L, ShyldFixFilter.streamIntervalMs(true));
        assertEquals(2_000L, ShyldFixFilter.streamMinIntervalMs(true));
        assertEquals(50_000L, ShyldFixFilter.streamIntervalMs(false));
        assertEquals(30_000L, ShyldFixFilter.streamMinIntervalMs(false));
    }

    @Test
    public void theFirstFixAsksAtEveryAccuracyForTenSeconds() {
        assertEquals(10_000L, ShyldFixFilter.FIRST_FIX_ASK_MS);
        assertArrayEquals(new int[] {100, 102, 104}, ShyldFixFilter.FIRST_FIX_PRIORITIES);
    }

    @Test
    public void providersAreAskedNoOftenerThanEveryFiveSeconds() {
        assertEquals(5_000L, ShyldFixFilter.providerIntervalMs(0));
        assertEquals(5_000L, ShyldFixFilter.providerIntervalMs(1_000));
        assertEquals(10_000L, ShyldFixFilter.providerIntervalMs(10_000));
    }

    @Test
    public void saysWhyAFixIsTurnedAway() {
        assertNull(ShyldFixFilter.rejection("gps", 100_000, 99_000, 20_000, 500f, 300f));
        assertEquals("grace", ShyldFixFilter.rejection("fused", 100_000, 90_000, 20_000, 10f, 300f));
        assertEquals("cap", ShyldFixFilter.rejection("fused", 100_000, 0, 20_000, 301f, 300f));
        assertEquals("cap", ShyldFixFilter.rejection("network", 100_000, 0, 20_000, -1f, 300f));
        assertNull(ShyldFixFilter.rejection("network", 100_000, 0, 20_000, 280f, 300f));
    }

    @Test
    public void aNetworkFixJustAfterAStartIsTakenWithNoGpsFixYet() {
        // VALP-1231 (build 19): the start set the last GPS fix to now, so 20 s of first-fix answers
        // were turned away as "grace". With no GPS fix yet there is no grace.
        long start = 1_000_000L;
        assertNull(ShyldFixFilter.rejection("network", start + 1_000, ShyldFixFilter.LAST_GPS_FIX_AT_START, 20_000, 70f, 300f));
        assertNull(ShyldFixFilter.rejection("fused", start + 1_000, ShyldFixFilter.LAST_GPS_FIX_AT_START, 20_000, 152f, 300f));
        // After a real GPS fix the grace still prefers GPS for 20 s.
        assertEquals("grace", ShyldFixFilter.rejection("network", start + 5_000, start, 20_000, 70f, 300f));
    }

    @Test
    public void aStopForTheEarlierShiftWaitsOutTheNextStart() {
        // VALP-1233: clock-out of the first shift, then On my way for the next; the first shift's
        // late 409 comes 5 s after the tracker started, while the outbox tracks nothing yet.
        long started = 1_000_000L;
        assertFalse(ShyldFixFilter.stopsNow(false, started, started + 5_000L));
        assertFalse(ShyldFixFilter.stopsNow(false, started, started + ShyldFixFilter.START_SETTLE_MS - 1));
        assertTrue(ShyldFixFilter.stopsNow(false, started, started + ShyldFixFilter.START_SETTLE_MS));
        assertTrue("never started here", ShyldFixFilter.stopsNow(false, 0L, started));
        assertFalse("a shift is tracked", ShyldFixFilter.stopsNow(true, 0L, started));
        assertFalse("the outbox can't say", ShyldFixFilter.stopsNow(null, 0L, started));
    }
}
