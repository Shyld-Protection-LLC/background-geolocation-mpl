/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Shyld (BL-092): Shyld's outbox calls these by name (its {@code TrackerHandOff}), from any
 * thread; the work runs on the main thread, and with no tracker running nothing happens.
 * <ul>
 *   <li>{@link #outboxStarted}: the outbox began tracking a shift, so the tracker asks for a fix
 *       now and runs fast for a minute (build 18: the first row came 3 minutes after clock-in).</li>
 *   <li>{@link #outboxStopped}: the server answered 409 stop_tracking, so the tracker stops at once
 *       rather than within the 10-minute backstop (C, 11-24).</li>
 * </ul>
 */
public final class ShyldTracker {
    private static BackgroundGeolocationService service;

    private ShyldTracker() {}

    static synchronized void attach(BackgroundGeolocationService running) {
        service = running;
    }

    static synchronized void detach(BackgroundGeolocationService gone) {
        if (service == gone) service = null;
    }

    /** The outbox began tracking a shift: a first fix now. */
    public static void outboxStarted(Context context) {
        onMain(BackgroundGeolocationService::startFast);
    }

    /** The outbox stopped a shift's tracking on the server's word; the tracker stops if none is left. */
    public static void outboxStopped(Context context) {
        onMain(BackgroundGeolocationService::stopForOutbox);
    }

    private interface Work {
        void run(BackgroundGeolocationService service);
    }

    private static void onMain(Work work) {
        final BackgroundGeolocationService running;
        synchronized (ShyldTracker.class) {
            running = service;
        }
        if (running == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (ShyldTracker.class) {
                if (service != running) return;
            }
            work.run(running);
        });
    }
}
