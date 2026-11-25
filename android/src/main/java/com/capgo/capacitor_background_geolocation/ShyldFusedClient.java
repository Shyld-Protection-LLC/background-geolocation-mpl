/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import androidx.core.content.ContextCompat;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import java.util.concurrent.Executor;

/**
 * Shyld (BL-092, VALP-1225): Play services' fused location for the tracker, where the phone has
 * Play services. Its stream runs at high accuracy, and its current-location ask has a max age of
 * 0, so Android computes a new fix rather than handing back a held one (ShyldFixFilter). Phones
 * without Play services keep LocationManager's providers.
 */
final class ShyldFusedClient {

    interface OnFix {
        void fix(Location location);
    }

    private final FusedLocationProviderClient client;
    private final Executor executor;
    private LocationListener listener;
    private CancellationTokenSource ask;
    // The first-fix asks (BL-092, build 18), apart from `ask` so a fresh-fix ask doesn't cancel them.
    private final java.util.List<CancellationTokenSource> firstAsks = new java.util.ArrayList<>();

    private ShyldFusedClient(Context context) {
        client = LocationServices.getFusedLocationProviderClient(context);
        executor = ContextCompat.getMainExecutor(context);
    }

    /** The client, or null when Play services isn't there or usable (the platform path then). */
    static ShyldFusedClient createIfAvailable(Context context) {
        try {
            int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
            if (status != ConnectionResult.SUCCESS) {
                // No Play services (or not usable): a normal phone state, not a failure.
                return null;
            }
            return new ShyldFusedClient(context);
        } catch (RuntimeException | LinkageError exception) {
            ShyldDiagnostics.error("play-services", exception);
            return null;
        }
    }

    /**
     * Starts the high-accuracy stream (50 s, no closer than 30 s; fast: 5 s, no closer than 2 s, and
     * no wait for an accurate first fix), replacing any earlier one.
     */
    @SuppressLint("MissingPermission")
    void startUpdates(float minDistanceM, OnFix onFix, boolean fast) {
        stopUpdates();
        LocationRequest request = new LocationRequest.Builder(ShyldFixFilter.PRIORITY_HIGH_ACCURACY, ShyldFixFilter.streamIntervalMs(fast))
            .setMinUpdateIntervalMillis(ShyldFixFilter.streamMinIntervalMs(fast))
            .setMinUpdateDistanceMeters(Math.max(0f, minDistanceM))
            .setWaitForAccurateLocation(!fast)
            .build();
        LocationListener next = onFix::fix;
        client.requestLocationUpdates(request, executor, next);
        listener = next;
    }

    void stopUpdates() {
        if (listener != null) {
            client.removeLocationUpdates(listener);
            listener = null;
        }
    }

    /**
     * Asks for a newly computed fix (max age 0) at this priority, for at most `durationMs`. No fix
     * in time answers null, and nothing is handed on: the stream's repeat stays the heartbeat.
     */
    @SuppressLint("MissingPermission")
    void askCurrent(int priority, long durationMs, OnFix onFix) {
        cancelAsk();
        CancellationTokenSource source = new CancellationTokenSource();
        ask = source;
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(durationMs)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .build();
        client
            .getCurrentLocation(request, source.getToken())
            .addOnSuccessListener(executor, (fix) -> {
                if (fix != null && ask == source) {
                    onFix.fix(fix);
                }
            })
            .addOnFailureListener(executor, (exception) -> ShyldDiagnostics.error("fresh", exception));
    }

    /**
     * BL-092 (build 18): a newly computed fix at each of FIRST_FIX_PRIORITIES at once, each for at
     * most FIRST_FIX_ASK_MS. Every one that answers is handed on; the outbox keeps the first and
     * each much sharper one.
     */
    @SuppressLint("MissingPermission")
    void askFirst(OnFix onFix) {
        cancelFirst();
        for (int priority : ShyldFixFilter.FIRST_FIX_PRIORITIES) {
            CancellationTokenSource source = new CancellationTokenSource();
            firstAsks.add(source);
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(0)
                .setDurationMillis(ShyldFixFilter.FIRST_FIX_ASK_MS)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .build();
            client
                .getCurrentLocation(request, source.getToken())
                .addOnSuccessListener(executor, (fix) -> {
                    if (fix != null && firstAsks.contains(source)) {
                        onFix.fix(fix);
                    }
                })
                .addOnFailureListener(executor, (exception) -> ShyldDiagnostics.error("first", exception));
        }
    }

    void cancelFirst() {
        for (CancellationTokenSource source : firstAsks) source.cancel();
        firstAsks.clear();
    }

    void cancelAsk() {
        if (ask != null) {
            ask.cancel();
            ask = null;
        }
    }

    void stop() {
        cancelFirst();
        cancelAsk();
        stopUpdates();
    }
}
