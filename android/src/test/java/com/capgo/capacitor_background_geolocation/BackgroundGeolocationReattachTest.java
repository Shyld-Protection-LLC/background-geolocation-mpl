/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginCall;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Shyld (BL-088): start() while tracking runs re-attaches the new page's callback instead of
 * rejecting ALREADY_STARTED, so a live update's reload gets fixes again without a cold start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class BackgroundGeolocationReattachTest {

    @Test
    public void startWhileRunningReattachesTheNewCallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Plugin plugin = new Plugin(context);
        AtomicReference<String> oldRejected = new AtomicReference<>();
        AtomicReference<String> newRejected = new AtomicReference<>();
        PluginCall oldCall = startCall("old-page", oldRejected);
        PluginCall newCall = startCall("new-page", newRejected);
        oldCall.setKeepAlive(true);
        set(plugin, "watchCall", oldCall);
        set(plugin, "serviceConnectionFuture", new CompletableFuture<>());

        plugin.start(newCall);

        assertNull("start() while running must not be refused", newRejected.get());
        assertSame("the new page's callback gets the fixes", newCall, get(plugin, "watchCall"));
        assertTrue(newCall.isKeptAlive());
        assertFalse("the old page's callback is let go", oldCall.isKeptAlive());
        assertNull(oldRejected.get());
    }

    @Test
    public void reattachingTheSameCallbackKeepsIt() throws Exception {
        Plugin plugin = new Plugin(ApplicationProvider.getApplicationContext());
        AtomicReference<String> rejected = new AtomicReference<>();
        PluginCall call = startCall("page", rejected);
        set(plugin, "watchCall", call);
        set(plugin, "serviceConnectionFuture", new CompletableFuture<>());

        plugin.start(call);

        assertSame(call, get(plugin, "watchCall"));
        assertTrue(call.isKeptAlive());
        assertNull(rejected.get());
    }

    /*
     * BL-092, VALP-1233 (build 19, the owner's Pixel): the service stopped itself on a 409 while the
     * plugin stayed bound, and every later start() only re-attached: no notification, no tracking.
     */
    @Test
    public void aConnectionWhoseServiceStoppedItselfIsStale() {
        CompletableFuture<BackgroundGeolocationService.LocalBinder> connection = CompletableFuture.completedFuture(binderOf(service()));
        assertFalse("stopped, only bound: start afresh", BackgroundGeolocation.reattaches(connection, CompletableFuture.completedFuture(null)));
        assertFalse(BackgroundGeolocation.reattaches(connection, null));
    }

    @Test
    public void aRunningOrInFlightStartReattaches() throws Exception {
        BackgroundGeolocationService service = service();
        BackgroundGeolocationService.LocalBinder binder = binderOf(service);
        CompletableFuture<BackgroundGeolocationService.LocalBinder> connection = CompletableFuture.completedFuture(binder);
        assertTrue("connecting", BackgroundGeolocation.reattaches(new CompletableFuture<>(), null));
        assertTrue("connected, the service's start not run yet", BackgroundGeolocation.reattaches(connection, new CompletableFuture<>()));
        Field active = BackgroundGeolocationService.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(service, true);
        assertTrue("running", BackgroundGeolocation.reattaches(connection, CompletableFuture.completedFuture(null)));
        binder.stop();
        assertFalse("stopped by itself", BackgroundGeolocation.reattaches(connection, CompletableFuture.completedFuture(null)));
        assertFalse("no connection", BackgroundGeolocation.reattaches(null, null));
    }

    @Test
    public void startAfterTheServiceStoppedItselfIsAFreshStart() throws Exception {
        Plugin plugin = new Plugin(ApplicationProvider.getApplicationContext());
        AtomicReference<String> oldRejected = new AtomicReference<>();
        AtomicReference<String> rejected = new AtomicReference<>();
        PluginCall oldCall = startCall("old-page", oldRejected);
        PluginCall call = startCall("page", rejected);
        CompletableFuture<BackgroundGeolocationService.LocalBinder> stale = CompletableFuture.completedFuture(binderOf(service()));
        set(plugin, "watchCall", oldCall);
        set(plugin, "serviceConnectionFuture", stale);
        set(plugin, "serviceStartedFuture", CompletableFuture.completedFuture(null));

        plugin.start(call);

        assertNotSame("the stale connection is dropped", stale, get(plugin, "serviceConnectionFuture"));
        assertSame(call, get(plugin, "watchCall"));
    }

    private static BackgroundGeolocationService service() {
        return Robolectric.buildService(BackgroundGeolocationService.class).create().get();
    }

    private static BackgroundGeolocationService.LocalBinder binderOf(BackgroundGeolocationService service) {
        return (BackgroundGeolocationService.LocalBinder) service.onBind(null);
    }

    private static PluginCall startCall(String id, AtomicReference<String> rejected) {
        return new PluginCall(null, "BackgroundGeolocation", id, "start", new JSObject()) {
            @Override
            public void reject(String msg, String code) {
                rejected.set(code);
            }

            @Override
            public void reject(String msg) {
                rejected.set(msg);
            }

            @Override
            public void resolve(JSObject data) {}
        };
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = BackgroundGeolocation.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field f = BackgroundGeolocation.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** No bridge or screen in a JVM test: the app context, location granted, no activity. */
    private static final class Plugin extends BackgroundGeolocation {

        private final Context appContext;

        Plugin(Context context) {
            this.appContext = context;
        }

        @Override
        public Context getContext() {
            return appContext;
        }

        @Override
        public AppCompatActivity getActivity() {
            return null;
        }

        @Override
        public PermissionState getPermissionState(String alias) {
            return PermissionState.GRANTED;
        }
    }
}
