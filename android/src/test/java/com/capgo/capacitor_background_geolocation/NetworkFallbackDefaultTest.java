/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Shyld (BL-092): the network fallback is on unless the app turns it off, at start() and after a
 * sticky restart, so a phone indoors or on approximate location still gets fixes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class NetworkFallbackDefaultTest {

    @Test
    public void startTurnsTheFallbackOnUnlessAsked() {
        assertTrue(BackgroundGeolocation.networkFallbackOption(call(new JSObject())));
        assertTrue(BackgroundGeolocation.networkFallbackOption(call(new JSObject().put("networkFallback", true))));
        assertFalse(BackgroundGeolocation.networkFallbackOption(call(new JSObject().put("networkFallback", false))));
    }

    @Test
    public void aStickyRestartWithNothingSavedKeepsTheFallbackOn() {
        Context context = ApplicationProvider.getApplicationContext();
        LocationStore.clear(context);
        assertTrue(LocationStore.getNetworkFallback(context));
    }

    @Test
    public void aSavedChoiceIsKept() {
        Context context = ApplicationProvider.getApplicationContext();
        LocationStore.clear(context);
        LocationStore.saveSetup(context, null, "t", "m", 0f, Collections.emptyMap(), 0L, false, false, 1, false);
        assertFalse(LocationStore.getNetworkFallback(context));
        LocationStore.clear(context);
    }

    private static PluginCall call(JSObject options) {
        return new PluginCall(null, "BackgroundGeolocation", "id", "start", options);
    }
}
