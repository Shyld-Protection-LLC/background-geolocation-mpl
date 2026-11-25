/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;

/**
 * Shyld (BL-092): what the tracker is doing, for the app's More › About and support. Upstream
 * swallows a failed foreground start and a refused location request; they're kept here instead,
 * and logged. Process memory only: a new process starts empty.
 */
final class ShyldDiagnostics {

    static volatile boolean running;
    static volatile boolean foreground;
    static volatile long lastFixAtMs;
    static volatile String lastError;
    static volatile long lastErrorAtMs;

    /*
     * BL-092 (build 18's gaps on a still, locked Pixel; C 11-24): what one read of About needs to
     * tell "the phone offered no fix" from "a fix came and was turned away", and whether Android's
     * idle mode was on. Counts since the process started.
     */
    private static final Map<String, Integer> fixes = new TreeMap<>();
    private static final Map<String, Integer> rejections = new TreeMap<>();
    private static final Map<String, int[]> asks = new TreeMap<>();
    private static int alarmFires;
    private static int idleEntries;
    private static long idleSinceMs;
    private static long idleEndedAtMs;
    private static int lightIdleEntries;
    private static BroadcastReceiver idleReceiver;

    private ShyldDiagnostics() {}

    /** A fix taken, by its provider ("gps", "network", "fused"). */
    static synchronized void fixFrom(String provider) {
        fixes.merge(provider == null ? "unknown" : provider, 1, Integer::sum);
    }

    /** A fix turned away ("grace", "cap"; ShyldFixFilter.rejection). */
    static synchronized void rejected(String why) {
        rejections.merge(why, 1, Integer::sum);
    }

    /** A current-location ask ("fresh", "first") went out. */
    static synchronized void asked(String kind) {
        asks.computeIfAbsent(kind, (k) -> new int[2])[0]++;
    }

    /** A current-location ask came back with a fix. */
    static synchronized void answered(String kind) {
        asks.computeIfAbsent(kind, (k) -> new int[2])[1]++;
    }

    /** The fresh-fix alarm (the one timer that still runs in Doze) fired. */
    static synchronized void alarmFired() {
        alarmFires++;
    }

    /** Android's idle mode (Doze) went on or off. */
    static synchronized void idle(boolean on, long nowMs) {
        if (on && idleSinceMs == 0) {
            idleEntries++;
            idleSinceMs = nowMs;
        } else if (!on && idleSinceMs != 0) {
            idleSinceMs = 0;
            idleEndedAtMs = nowMs;
        }
    }

    static synchronized void lightIdle(boolean on) {
        if (on) lightIdleEntries++;
    }

    /** Follows idle mode while the tracker runs (onCreate to onDestroy). */
    static void watchIdle(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        idle(power.isDeviceIdleMode(), System.currentTimeMillis());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                    idle(power.isDeviceIdleMode(), System.currentTimeMillis());
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lightIdle(power.isDeviceLightIdleMode());
                }
            }
        };
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filter.addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED);
        }
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            idleReceiver = receiver;
        } catch (RuntimeException exception) {
            error("idle", exception);
        }
    }

    static void unwatchIdle(Context context) {
        BroadcastReceiver receiver = idleReceiver;
        idleReceiver = null;
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    /** Notes a failure where it happened ("start", "foreground", "gps", "network"), and logs it. */
    static void error(String where, String message) {
        lastError = where + ": " + message;
        lastErrorAtMs = System.currentTimeMillis();
        Logger.warn("BackgroundGeolocation " + lastError);
    }

    static void error(String where, Throwable throwable) {
        error(where, throwable.getClass().getSimpleName() + (throwable.getMessage() == null ? "" : " " + throwable.getMessage()));
    }

    /**
     * A provider that failed now works (BL-092: the owner's Pixel showed "fused: provider off" 30 min
     * after Location was turned on). Its error goes; another provider's stays.
     */
    static void resolved(String where) {
        String error = lastError;
        if (error != null && error.startsWith(where + ":")) {
            lastError = null;
            lastErrorAtMs = 0;
        }
    }

    static JSObject snapshot() {
        return snapshot(null);
    }

    /** With a context, also whether idle mode is on now and whether the app is exempt from it. */
    static synchronized JSObject snapshot(Context context) {
        JSObject out = new JSObject();
        out.put("fixes", counts(fixes));
        out.put("rejected", counts(rejections));
        JSObject asked = new JSObject();
        for (Map.Entry<String, int[]> e : asks.entrySet()) {
            asked.put(e.getKey(), e.getValue()[0] + " asked, " + e.getValue()[1] + " answered");
        }
        out.put("asks", asked);
        out.put("alarmFires", alarmFires);
        out.put("idleEntries", idleEntries);
        out.put("idleSince", idleSinceMs == 0 ? JSONObject.NULL : idleSinceMs);
        out.put("idleEndedAt", idleEndedAtMs == 0 ? JSONObject.NULL : idleEndedAtMs);
        out.put("lightIdleEntries", lightIdleEntries);
        PowerManager power = context == null ? null : (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        out.put("idleNow", power == null ? JSONObject.NULL : power.isDeviceIdleMode());
        out.put("batteryExempt", power == null ? JSONObject.NULL : power.isIgnoringBatteryOptimizations(context.getPackageName()));
        out.put("running", running);
        out.put("foreground", foreground);
        out.put("lastFixAt", lastFixAtMs == 0 ? JSONObject.NULL : lastFixAtMs);
        out.put("lastError", lastError == null ? JSONObject.NULL : lastError);
        out.put("lastErrorAt", lastErrorAtMs == 0 ? JSONObject.NULL : lastErrorAtMs);
        return out;
    }

    private static JSObject counts(Map<String, Integer> map) {
        JSObject out = new JSObject();
        for (Map.Entry<String, Integer> e : map.entrySet()) out.put(e.getKey(), (int) e.getValue());
        return out;
    }

    /** For tests. */
    static synchronized void reset() {
        fixes.clear();
        rejections.clear();
        asks.clear();
        alarmFires = 0;
        idleEntries = 0;
        idleSinceMs = 0;
        idleEndedAtMs = 0;
        lightIdleEntries = 0;
        running = false;
        foreground = false;
        lastFixAtMs = 0;
        lastError = null;
        lastErrorAtMs = 0;
    }
}
