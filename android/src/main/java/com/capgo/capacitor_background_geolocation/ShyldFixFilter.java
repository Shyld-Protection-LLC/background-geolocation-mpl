/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

/**
 * Shyld (BL-092, the Pixel on VALP-1224, build 12): which fixes the service takes, and when its
 * watchdog may restart the providers. Pure, so it's tested on the JVM.
 *
 * Indoors GPS gives no fix, and on a Pixel the network provider is off unless Google Location
 * Accuracy is on, so the fork never had a fix there while the iPhone beside it had a 7 m Wi-Fi one.
 * On API 31+ the fused provider (Wi-Fi, cell and GPS, no Play services) is asked too, and its fixes
 * are taken on the same terms as the network's: GPS first, a fused or network fix only once GPS
 * has been silent for the grace, and only within the accuracy cap.
 */
final class ShyldFixFilter {
    static final String GPS = "gps";
    static final String NETWORK = "network";
    static final String FUSED = "fused";

    private ShyldFixFilter() {}

    /**
     * Whether a fix is taken. A GPS fix always is. Any other is taken once GPS has been silent for
     * `graceMs` (or never had a fix), and only with an accuracy within `maxAccuracyM`.
     *
     * @param lastGpsFixAtMs elapsedRealtime of the last GPS fix, or 0 for none
     * @param accuracyM the fix's accuracy, or negative when it has none
     */
    static boolean accepts(String provider, long nowMs, long lastGpsFixAtMs, long graceMs, float accuracyM,
            float maxAccuracyM) {
        return rejection(provider, nowMs, lastGpsFixAtMs, graceMs, accuracyM, maxAccuracyM) == null;
    }

    /**
     * Why a fix isn't taken, for diagnostics (BL-092, build 18's gaps): "grace" (GPS answered within
     * the grace), "cap" (no accuracy, or coarser than the cap), or null when it's taken.
     */
    static String rejection(String provider, long nowMs, long lastGpsFixAtMs, long graceMs, float accuracyM,
            float maxAccuracyM) {
        if (GPS.equals(provider)) return null;
        boolean gpsStillFresh = lastGpsFixAtMs != 0 && nowMs - lastGpsFixAtMs < graceMs;
        if (gpsStillFresh) return "grace";
        return accuracyM >= 0 && accuracyM <= maxAccuracyM ? null : "cap";
    }

    /*
     * BL-092 (build 18, ~4.6 %/h on a still Pixel indoors; C 11-24): LocationManager's providers were
     * asked every second (no minInterval given), so the GPS chip searched all shift. The outbox keeps
     * at most one fix every 5 s, so they're asked no oftener than that.
     */
    static final long PROVIDER_MIN_INTERVAL_MS = 5_000L;

    /*
     * BL-092 (build 19, VALP-1231: the Pixel's first row came +30 s after clock-in; owner via C, ~01:05Z).
     * Upstream set the last GPS fix to the start time, so for the first grace (20 s) of every start
     * each network and fused fix was turned away as "grace", the first-fix answers included, though
     * no GPS fix had come. The grace now runs only after a real GPS fix: at a start there is none.
     */
    static final long LAST_GPS_FIX_AT_START = 0L;

    static long providerIntervalMs(long minIntervalMs) {
        return Math.max(minIntervalMs, PROVIDER_MIN_INTERVAL_MS);
    }

    /**
     * Whether the watchdog removes and asks again for updates after a silent minute. Only once a
     * fix has come in this start: before the first one, a restart throws away a cold GPS's search
     * (which can take minutes indoors) and changes nothing for the other providers.
     */
    static boolean restartsAfterSilence(boolean hadFixSinceStart) {
        return hadFixSinceStart;
    }

    /**
     * How long without a taken fix before the service asks for a fresh one (BL-092): the Pixel still
     * and locked on build 12 got no fix for 7 minutes with its providers listening, so the outbox's
     * one-a-minute heartbeat had nothing to send. An old fix is never sent again as new.
     */
    static final long FRESH_FIX_AFTER_MS = 50_000L;

    /** The Doze-proof alarm's delay: just after the Handler's, which runs whenever the CPU is awake. */
    static final long FRESH_FIX_ALARM_MS = 60_000L;

    /**
     * The fallback providers' accuracy cap: upstream's 300 m. Kept there (C, build 13, 11-24): when a
     * ±300 m fix is all the phone has, it still goes as the heartbeat; a lower cap would leave it
     * with nothing. The movement rule (both fixes' accuracies) stops coarse fixes reading as moving.
     */
    static final float FALLBACK_MAX_ACCURACY_M = 300f;

    /**
     * The fallback cap to apply (BL-092, the owner via C 11-24: a coarse fresh fix is shown as
     * "Approximate position", never "not updating"). With precise location, 300 m, so a coarse fix
     * never replaces a good one. With approximate only, Android refuses GPS and every fused or network
     * fix is ±2-3 km, so the cap would drop them all: none applies.
     */
    static float fallbackCap(boolean preciseGranted) {
        return preciseGranted ? FALLBACK_MAX_ACCURACY_M : Float.MAX_VALUE;
    }

    /** Whether an alarm should ask: no new reading in this start, or none for 40 s or more. */
    static boolean freshFixDue(long nowMs, long lastTakenAtMs) {
        return lastTakenAtMs == 0 || nowMs - lastTakenAtMs >= FRESH_FIX_AFTER_MS - 10_000L;
    }

    /** Which provider a fresh-fix ask goes to: fused where it runs, else network, else GPS, else none. */
    static String freshFixProvider(int sdkInt, boolean fusedOn, boolean networkOn, boolean gpsOn) {
        if (sdkInt >= 31 && fusedOn) return FUSED;
        if (networkOn) return NETWORK;
        if (gpsOn) return GPS;
        return null;
    }

    /**
     * Whether LocationManager's fused provider is asked for: API 31+, where it has one, and only
     * without Play services, whose fused client takes its place (BL-092, VALP-1225).
     */
    static boolean asksFused(int sdkInt, boolean fallbackOn, boolean playServices) {
        return fallbackOn && sdkInt >= 31 && !playServices;
    }

    /*
     * BL-092, VALP-1225 (the Pixel locked and still indoors, 01:40 to 03:13Z on 11-24): one row a
     * minute, but the position changed only twice in 90 minutes. The platform's current-location
     * ask may answer from a held estimate, and the fused listener re-handed the same one for an
     * hour. With Play services the ask is a CurrentLocationRequest with a max age of 0, so Android
     * must compute a new fix: balanced (a Wi-Fi and cell scan) each minute, and high accuracy (GPS
     * too) once the same reading has stood for 5 minutes. The owner's word, via C, 11-24 ~03:18Z.
     */

    /** Play services' Priority values (com.google.android.gms.location.Priority), kept here to test. */
    static final int PRIORITY_HIGH_ACCURACY = 100;
    static final int PRIORITY_BALANCED_POWER_ACCURACY = 102;

    /** How long the same reading stands before a fresh-fix ask is at high accuracy. */
    static final long SAME_SPOT_HIGH_ACCURACY_MS = 5 * 60_000L;
    /** How long each ask may take before Play services answers with no fix. */
    static final long BALANCED_ASK_MS = 20_000L;
    static final long HIGH_ACCURACY_ASK_MS = 30_000L;
    /** The Play services fused stream: high accuracy, every 50 s and no closer than 30 s apart. */
    static final long STREAM_INTERVAL_MS = 50_000L;
    static final int PRIORITY_LOW_POWER = 104;

    /*
     * BL-092 (build 18, VALP-1227: indoors, the Pixel's first row was 3 min 3 s after clock-in).
     * At each start, and when the outbox begins tracking a shift, the tracker asks Play services for
     * a newly computed fix at high, balanced and low-power accuracy at once (max age 0, each for at
     * most FIRST_FIX_ASK_MS), and its fused stream runs every 5 s for FAST_START_MS without waiting
     * for an accurate fix. The outbox sends the first fix at once and each much sharper one after it.
     */
    static final long FIRST_FIX_ASK_MS = 10_000L;
    static final long FAST_START_MS = 60_000L;
    static final long FAST_INTERVAL_MS = 5_000L;
    static final long FAST_MIN_INTERVAL_MS = 2_000L;
    static final int[] FIRST_FIX_PRIORITIES = {PRIORITY_HIGH_ACCURACY, PRIORITY_BALANCED_POWER_ACCURACY, PRIORITY_LOW_POWER};

    /** Whether the fused stream runs fast: within FAST_START_MS of the last start (elapsedRealtime). */
    static boolean fastStream(long nowMs, long fastUntilMs) {
        return fastUntilMs != 0 && nowMs < fastUntilMs;
    }

    static long streamIntervalMs(boolean fast) {
        return fast ? FAST_INTERVAL_MS : STREAM_INTERVAL_MS;
    }

    static long streamMinIntervalMs(boolean fast) {
        return fast ? FAST_MIN_INTERVAL_MS : STREAM_MIN_INTERVAL_MS;
    }

    static final long STREAM_MIN_INTERVAL_MS = 30_000L;

    /**
     * Whether a taken fix repeats the last taken one (the same latitude, longitude and accuracy), so
     * it is not a new reading: it doesn't put off the fresh-fix ask, and the same spot keeps its age.
     */
    static boolean isRepeat(boolean hasLast, double lat, double lng, float accuracyM, double lastLat, double lastLng,
            float lastAccuracyM) {
        return hasLast && lat == lastLat && lng == lastLng && accuracyM == lastAccuracyM;
    }

    /**
     * The fresh-fix ask's priority: high accuracy once the same reading (or no new one) has stood
     * for SAME_SPOT_HIGH_ACCURACY_MS, else balanced.
     *
     * @param sameSinceMs elapsedRealtime of the last new reading, or 0 for none in this start
     */
    static int freshFixPriority(long nowMs, long sameSinceMs) {
        if (sameSinceMs == 0) return PRIORITY_HIGH_ACCURACY;
        return nowMs - sameSinceMs >= SAME_SPOT_HIGH_ACCURACY_MS ? PRIORITY_HIGH_ACCURACY : PRIORITY_BALANCED_POWER_ACCURACY;
    }

    /** How long an ask at this priority may run. */
    static long freshFixDurationMs(int priority) {
        return priority == PRIORITY_HIGH_ACCURACY ? HIGH_ACCURACY_ASK_MS : BALANCED_ASK_MS;
    }

    /*
     * BL-092 backstop (the owner's Pixel, 11-24 06:17Z): About read "Tracker: running" after the app
     * had stopped the outbox for a missed shift. A tracker whose fixes no shift wants only drains
     * the battery, so after UNTRACKED_STOP_MS of that it stops itself. N1 fixed the cause in the app
     * (n1-tracker-stops); this is defence in depth (N1's word, 06:26Z).
     */
    static final long UNTRACKED_STOP_MS = 10 * 60_000L;

    /**
     * @param outboxTracking whether the outbox tracks a shift, or null when there's no outbox or it can't say
     * @param untrackedSinceMs when it was first seen not tracking (elapsedRealtime), or 0
     */
    static boolean stopsUntracked(Boolean outboxTracking, long untrackedSinceMs, long nowMs) {
        if (outboxTracking == null || outboxTracking) return false;
        return untrackedSinceMs != 0 && nowMs - untrackedSinceMs >= UNTRACKED_STOP_MS;
    }

    /**
     * BL-092, C 11-24: on a 409 stop_tracking the outbox tells the tracker at once
     * (ShyldTracker), and it stops only when the outbox then tracks no shift: a stop for one
     * shift never ends another's tracking, and an outbox that can't say (null) never stops it.
     */
    static boolean stopsNow(Boolean outboxTracking) {
        return Boolean.FALSE.equals(outboxTracking);
    }

    /*
     * BL-092, VALP-1233 (build 19, the owner's Pixel, 11-25): an On my way right after a clock-out
     * started the tracker, and the earlier shift's late 409 came while the app was still fetching
     * the token before the outbox tracked the new shift, so the tracker stopped at once. Within
     * START_SETTLE_MS of its own start a 409 doesn't stop it: the start says a shift wants it. The
     * 10-minute backstop still stops a tracker nothing ever tracks.
     */
    static final long START_SETTLE_MS = 2 * 60_000L;

    /**
     * @param startedAtMs when the tracker last started (elapsedRealtime), or 0
     */
    static boolean stopsNow(Boolean outboxTracking, long startedAtMs, long nowMs) {
        if (!stopsNow(outboxTracking)) return false;
        return startedAtMs == 0 || nowMs - startedAtMs >= START_SETTLE_MS;
    }
}
