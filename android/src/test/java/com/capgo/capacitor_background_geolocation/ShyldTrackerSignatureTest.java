/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shyld's outbox finds {@code ShyldTracker.outboxStarted} and {@code outboxStopped} by name (its {@code TrackerHandOff});
 * this is the exact class and signature it looks up, so a rename fails here, not on a phone.
 */
public class ShyldTrackerSignatureTest {
    @Test
    public void bothCallsKeepTheSignaturesTheOutboxLooksUp() throws Exception {
        Class<?> c = Class.forName("com.capgo.capacitor_background_geolocation.ShyldTracker");
        assertTrue(Modifier.isPublic(c.getModifiers()));
        for (String name : new String[] {"outboxStarted", "outboxStopped"}) {
            Method m = c.getMethod(name, Context.class);
            assertTrue(Modifier.isStatic(m.getModifiers()));
            assertTrue(Modifier.isPublic(m.getModifiers()));
        }
    }
}
