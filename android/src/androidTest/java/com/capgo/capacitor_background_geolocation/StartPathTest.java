/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Shyld (BL-092), on Test Lab's devices: the tracker's start path. With location allowed while the
 * app is in use and the app in front, starting the service must bring it to the foreground with
 * its notification, the state the Pixel never reached. Fails with the error diagnostics() keeps.
 */
@RunWith(AndroidJUnit4.class)
public class StartPathTest {

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        ShyldDiagnostics.reset();
        grant("android.permission.ACCESS_FINE_LOCATION");
        grant("android.permission.ACCESS_COARSE_LOCATION");
        if (Build.VERSION.SDK_INT >= 33) grant("android.permission.POST_NOTIFICATIONS");
    }

    @After
    public void tearDown() {
        context.stopService(new Intent(context, BackgroundGeolocationService.class));
        LocationStore.clear(context);
    }

    @Test
    public void startedInFrontItReachesTheForegroundWithItsNotification() {
        try (ActivityScenario<StartPathHostActivity> scenario = ActivityScenario.launch(StartPathHostActivity.class)) {
            scenario.onActivity((activity) -> {
                Intent intent = new Intent(activity, BackgroundGeolocationService.class)
                    .putExtra(BackgroundGeolocationService.EXTRA_NOTIFICATION_TITLE, "On shift")
                    .putExtra(BackgroundGeolocationService.EXTRA_NOTIFICATION_MESSAGE, "Sharing your location with your firm");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent);
                } else {
                    activity.startService(intent);
                }
            });
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (SystemClock.elapsedRealtime() < deadline && !(ShyldDiagnostics.foreground && notificationShown())) {
                SystemClock.sleep(200);
            }
            assertNull("the start path failed", ShyldDiagnostics.lastError);
            assertTrue("the service reached the foreground", ShyldDiagnostics.foreground);
            assertTrue("its notification is shown", notificationShown());
        }
    }

    private boolean notificationShown() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification n : manager.getActiveNotifications()) {
            if (n.getId() == BackgroundGeolocationService.NOTIFICATION_ID) return true;
        }
        return false;
    }

    /** Grants a runtime permission and waits for the command to finish (read to its end). */
    private void grant(String permission) throws Exception {
        try (
            ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("pm grant " + context.getPackageName() + " " + permission);
            InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)
        ) {
            byte[] buffer = new byte[256];
            while (in.read(buffer) != -1) {
                // drain
            }
        }
    }
}
