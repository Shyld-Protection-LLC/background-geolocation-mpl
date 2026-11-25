/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.capgo.capacitor_background_geolocation;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationCompat;
import androidx.core.location.LocationListenerCompat;
import com.getcapacitor.Logger;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

// A bound and started service that is promoted to a foreground service
// (showing a persistent notification) when the first background watcher is
// added, and demoted when the last background watcher is removed.
public class BackgroundGeolocationService extends Service {

    static final String NOTIFICATION_CHANNEL_ID = BackgroundGeolocationService.class.getPackage().getName();
    private final IBinder binder = new LocalBinder();

    private static final double EARTH_RADIUS_M = 6371000;

    // Must be unique for this application.
    static final int NOTIFICATION_ID = 28351;
    static final String EXTRA_NOTIFICATION_TITLE = "notificationTitle";
    static final String EXTRA_NOTIFICATION_MESSAGE = "notificationMessage";
    // Shyld (BL-092): the Doze-proof alarm's start, asking for a fresh fix.
    static final String ACTION_FRESH_FIX = "com.shyld.tracker.FRESH_FIX";

    private String callbackId;

    private LocationManager client;
    private LocationListener locationCallback;
    private MediaPlayer mediaPlayer;
    private double[][] route;
    private double distanceThreshold;
    private boolean isOffRoute;

    private Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private Runnable restartRunnable;
    private float currentDistanceFilter;
    private long currentMinIntervalMs;
    private boolean locationLogEnabled;
    private int locationLogMaxEntries;
    private PowerManager.WakeLock wakeLock;

    // How long a GPS fix is considered "fresh" before we allow a NETWORK_PROVIDER fix through.
    private static final long NETWORK_FALLBACK_GRACE_MS = 20000L;

    // Max acceptable accuracy radius (meters) for a NETWORK_PROVIDER fix; missing accuracy counts
    // as too imprecise too.

    // elapsedRealtime() of the last GPS_PROVIDER fix, or 0 if none yet. Monotonic, so it can't be
    // confused by wall-clock adjustments the way System.currentTimeMillis() could.
    private volatile long lastGpsFixAtMs = 0L;

    // Flag for the NETWORK_PROVIDER fallback below; set via the "networkFallback" start option.
    // Shyld (BL-092): on unless the app turns it off (upstream's default is off).
    private volatile boolean networkFallbackEnabled = false;

    // Shyld (BL-092): whether a fix has been taken since this start; the watchdog leaves a cold GPS
    // searching until then (ShyldFixFilter.restartsAfterSilence).
    private volatile boolean hadFixSinceStart = false;

    // Shyld (BL-092): a fresh-fix ask after FRESH_FIX_AFTER_MS without a taken fix.
    private Runnable freshFixRunnable;
    private android.os.CancellationSignal freshFixCancel;
    // elapsedRealtime of the last new reading (a repeat doesn't count), so the alarm and the
    // Handler don't both ask, and a run of repeats doesn't put the ask off (VALP-1225).
    private volatile long lastTakenAtMs = 0L;
    // Shyld (BL-092, VALP-1225): the last taken fix, to tell a repeat from a new reading.
    private boolean hasLastTaken = false;
    // Shyld (BL-092, VALP-1233): whether a start is in effect. The service can stop itself while
    // the plugin stays bound (stopForOutbox, stopIfUntracked), so being bound isn't running.
    private volatile boolean active = false;
    // Shyld (BL-092, VALP-1233): when the last start took effect (elapsedRealtime), for stopForOutbox.
    private volatile long startedAtMs = 0L;
    private double lastTakenLat;
    private double lastTakenLng;
    private float lastTakenAccuracyM;
    // Play services' fused client where the phone has it, else null (LocationManager's providers).
    private ShyldFusedClient fused;
    private static final String PLAY_FUSED = "play-services-fused";
    // BL-092 backstop: when the outbox was first seen tracking no shift (elapsedRealtime), or 0.
    private long untrackedSinceMs = 0L;
    // BL-092 (build 18's first fix): until when (elapsedRealtime) the fused stream runs fast, or 0.
    private long fastUntilMs = 0L;
    private final Runnable endFastRunnable = this::endFast;
    // The providers this start listens to (BL-092), so one that comes back on is asked again.
    private final java.util.Set<String> listening = new java.util.HashSet<>();

    // When set (via the "url" start option), each location is also POSTed to
    // this URL directly from native code so delivery survives the WebView being
    // destroyed. Delivery runs on postExecutor to keep it off the main thread.
    private String nativePostUrl;
    private ExecutorService postExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        ShyldTracker.attach(this);
        ShyldDiagnostics.watchIdle(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Some devices allow a foreground service to outlive the application's main
    // activity, leading to nasty crashes as reported in issue #59. If we learn
    // that the application has been killed, all watchers are stopped and the
    // service is terminated immediately.
    //
    // The exception is native delivery mode (a "url" was given to start()): there
    // the service is meant to outlive the app UI, so it keeps running and relies
    // on onStartCommand to re-establish updates if the process is later restarted.
    @Override
    public boolean onUnbind(Intent intent) {
        if (LocationStore.keepsRunning(getApplicationContext())) {
            return false;
        }
        if (client != null && locationCallback != null) {
            removeAllUpdates();
        }
        releaseMediaPlayer();
        releaseWakeLock();
        stopWatchdog();
        stopSelf();
        return false;
    }

    // In native delivery mode, keep tracking after the user swipes the app away
    // from the recents list; otherwise fall back to the default teardown.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (LocationStore.keepsRunning(getApplicationContext())) {
            return;
        }
        // Shyld: the app was swiped away on shift and tracking ends; say so (ShyldHandOff, FORK.md).
        ShyldHandOff.appClosed(getApplicationContext());
        super.onTaskRemoved(rootIntent);
    }

    // The system may kill and later restart a START_STICKY foreground service
    // with a null intent and no binder call. When native delivery is configured,
    // re-establish location updates from the persisted config so tracking resumes
    // without the app UI.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context context = getApplicationContext();
        if (intent != null && ACTION_FRESH_FIX.equals(intent.getAction())) {
            // Shyld (BL-092): the alarm woke us in Doze, where the Handler's timer doesn't run.
            ShyldDiagnostics.alarmFired();
            if (client != null && locationCallback != null) {
                if (ShyldFixFilter.freshFixDue(SystemClock.elapsedRealtime(), lastTakenAtMs)) {
                    askFreshFix();
                } else {
                    scheduleFreshFix();
                }
                return START_STICKY;
            }
            // Not tracking (a stale alarm after a stop): nothing to ask for.
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!LocationStore.keepsRunning(context)) {
            // The plugin starts this service with startForegroundService(), and Android
            // kills the app unless startForeground() follows within a few seconds. Promote
            // here, so that it does not wait for the bind and the start() call behind it.
            if (intent != null && intent.hasExtra(EXTRA_NOTIFICATION_TITLE)) {
                promoteToForeground(intent.getStringExtra(EXTRA_NOTIFICATION_TITLE), intent.getStringExtra(EXTRA_NOTIFICATION_MESSAGE));
            }
            // Not in native delivery mode: preserve the original behavior where the
            // service does not outlive the app, so it is not sticky-restarted.
            return START_NOT_STICKY;
        }
        nativePostUrl = LocationStore.getUrl(context);
        promoteToForeground(LocationStore.getTitle(context), LocationStore.getMessage(context));
        active = true;
        startedAtMs = SystemClock.elapsedRealtime();
        if (client == null || locationCallback == null) {
            acquireWakeLock();
            client = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            currentDistanceFilter = LocationStore.getDistanceFilter(context);
            currentMinIntervalMs = LocationStore.getMinIntervalMs(context);
            networkFallbackEnabled = LocationStore.getNetworkFallback(context);
            locationLogEnabled = LocationStore.getLocationLog(context);
            locationLogMaxEntries = LocationStore.getLocationLogMaxEntries(context);
            locationCallback = createLocationListener(this);
            // Shyld (BL-092): no GPS fix yet, so no grace (ShyldFixFilter.LAST_GPS_FIX_AT_START).
            lastGpsFixAtMs = ShyldFixFilter.LAST_GPS_FIX_AT_START;
            hadFixSinceStart = false;
            hasLastTaken = false;
            lastTakenAtMs = 0L;
            requestLocationUpdates();
            startWatchdog();
            startFast();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        ShyldTracker.detach(this);
        ShyldDiagnostics.unwatchIdle(this);
        ShyldDiagnostics.running = false;
        ShyldDiagnostics.foreground = false;
        if (client != null && locationCallback != null) {
            removeAllUpdates();
        }
        super.onDestroy();
        releaseMediaPlayer();
        releaseWakeLock();
        stopWatchdog();
        if (postExecutor != null) {
            postExecutor.shutdown();
            postExecutor = null;
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
        } catch (Exception e) {
            Logger.error("Error releasing MediaPlayer", e);
        }
        mediaPlayer = null;
    }

    // No timeout: tracking runs for as long as the caller keeps a background watcher
    // registered, and the lock is always released in stop() and onDestroy().
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackgroundGeolocation::LocationWakeLock");
            wakeLock.acquire();
            Logger.info("Wake lock acquired");
        } catch (Exception e) {
            Logger.error("Error acquiring wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Logger.info("Wake lock released");
            }
        } catch (Exception e) {
            Logger.error("Error releasing wake lock", e);
        }
        wakeLock = null;
    }

    private void restartLocationUpdates() {
        if (client == null || locationCallback == null) {
            return;
        }
        if (!ShyldFixFilter.restartsAfterSilence(hadFixSinceStart)) {
            // Shyld (BL-092): no fix yet in this start. Restarting would throw away a cold GPS's
            // search, so the providers keep listening and the watchdog looks again in a minute.
            startWatchdog();
            return;
        }
        Logger.debug("Location watchdog timed out, restarting updates");
        removeAllUpdates();
        if (restartRunnable != null) {
            watchdogHandler.removeCallbacks(restartRunnable);
        }
        restartRunnable = () -> {
            if (client == null || locationCallback == null) {
                return;
            }
            requestLocationUpdates();
            startWatchdog();
        };
        watchdogHandler.postDelayed(restartRunnable, 10000);
    }

    private void startWatchdog() {
        startWatchdog(true);
    }

    /**
     * @param newReading false for a repeat of the last taken fix (VALP-1225): it still shows the
     *     providers deliver, so the restart watchdog moves on, but the fresh-fix ask stays due.
     */
    private void startWatchdog(boolean newReading) {
        // Shyld (BL-092): the timers only; a fresh-fix ask in flight is left to finish.
        stopWatchdogTimers();
        if (newReading) {
            scheduleFreshFix();
        }
        if (watchdogRunnable == null) {
            watchdogRunnable = this::restartLocationUpdates;
        }
        watchdogHandler.postDelayed(watchdogRunnable, 60000);
    }

    /** Shyld (BL-092): ask for a fresh fix once no fix has been taken for FRESH_FIX_AFTER_MS. */
    private void scheduleFreshFix() {
        if (freshFixRunnable == null) {
            freshFixRunnable = this::askFreshFix;
        }
        watchdogHandler.removeCallbacks(freshFixRunnable);
        watchdogHandler.postDelayed(freshFixRunnable, ShyldFixFilter.FRESH_FIX_AFTER_MS);
        armFreshFixAlarm();
    }

    /**
     * Shyld (BL-092, build 13: a 7-minute gap right after the Pixel locked). The Handler's timer
     * stops while the phone sleeps in Doze; an alarm set with setAndAllowWhileIdle still fires there
     * (inexact, and at most about once every 9 minutes while idle), so a quiet, still phone is asked
     * again. Each taken fix moves it on, so it fires only when fixes stop.
     */
    private void armFreshFixAlarm() {
        try {
            android.app.AlarmManager alarms = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) return;
            alarms.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ShyldFixFilter.FRESH_FIX_ALARM_MS,
                freshFixIntent()
            );
        } catch (RuntimeException exception) {
            ShyldDiagnostics.error("fresh", exception);
        }
    }

    private void cancelFreshFixAlarm() {
        try {
            android.app.AlarmManager alarms = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) alarms.cancel(freshFixIntent());
        } catch (RuntimeException ignored) {
            // Nothing armed.
        }
    }

    private PendingIntent freshFixIntent() {
        Intent intent = new Intent(this, BackgroundGeolocationService.class).setAction(ACTION_FRESH_FIX);
        return PendingIntent.getService(this, 0x5f1e, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Still and locked, the providers can go quiet for minutes (the Pixel on build 12). A current-
     * location ask computes a new fix (a fresh Wi-Fi or cell reading), which the filter then judges
     * like any other; the outbox's heartbeat needs one each minute. Asked again until fixes return.
     */
    private void askFreshFix() {
        if (client == null || locationCallback == null) {
            return;
        }
        listenToFallbacks();
        if (fused != null) {
            // Shyld (BL-092, VALP-1225): a newly computed fix, balanced each minute and at high
            // accuracy once the same reading has stood for 5 minutes (ShyldFixFilter).
            int priority = ShyldFixFilter.freshFixPriority(SystemClock.elapsedRealtime(), lastTakenAtMs);
            ShyldDiagnostics.asked("fresh");
            try {
                fused.askCurrent(priority, ShyldFixFilter.freshFixDurationMs(priority), (fix) -> {
                    ShyldDiagnostics.answered("fresh");
                    if (client != null && locationCallback != null) {
                        handleLocationChanged(fix);
                    }
                });
            } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
                ShyldDiagnostics.error("fresh", exception);
            }
            scheduleFreshFix();
            return;
        }
        String provider = ShyldFixFilter.freshFixProvider(
            Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providerOn(LocationManager.FUSED_PROVIDER),
            providerOn(LocationManager.NETWORK_PROVIDER),
            providerOn(LocationManager.GPS_PROVIDER)
        );
        if (provider != null) {
            ShyldDiagnostics.asked("fresh");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (freshFixCancel != null) {
                        freshFixCancel.cancel();
                    }
                    freshFixCancel = new android.os.CancellationSignal();
                    client.getCurrentLocation(provider, freshFixCancel, ContextCompat.getMainExecutor(this), (fix) -> {
                        if (fix != null) ShyldDiagnostics.answered("fresh");
                        if (fix != null && client != null && locationCallback != null) {
                            handleLocationChanged(fix);
                        }
                    });
                } else {
                    client.requestSingleUpdate(provider, locationCallback, Looper.getMainLooper());
                }
            } catch (SecurityException | IllegalArgumentException exception) {
                ShyldDiagnostics.error("fresh", exception);
            }
        }
        scheduleFreshFix();
    }

    /** Precise location allowed (fine); approximate only gets no fallback cap (ShyldFixFilter.fallbackCap). */
    private boolean preciseGranted() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean providerOn(String provider) {
        try {
            return client != null && client.isProviderEnabled(provider);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void stopWatchdog() {
        stopWatchdogTimers();
        cancelFreshFixAlarm();
        if (freshFixRunnable != null) {
            watchdogHandler.removeCallbacks(freshFixRunnable);
        }
        if (freshFixCancel != null) {
            freshFixCancel.cancel();
            freshFixCancel = null;
        }
        if (fused != null) {
            fused.cancelAsk();
        }
    }

    private void stopWatchdogTimers() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
        }
        if (restartRunnable != null) {
            watchdogHandler.removeCallbacks(restartRunnable);
        }
    }

    private void handleLocationChanged(android.location.Location location) {
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            lastGpsFixAtMs = SystemClock.elapsedRealtime();
        } else {
            // Shyld (BL-092): network and fused fixes on the same terms (ShyldFixFilter).
            String rejection = ShyldFixFilter.rejection(
                location.getProvider(),
                SystemClock.elapsedRealtime(),
                lastGpsFixAtMs,
                NETWORK_FALLBACK_GRACE_MS,
                location.hasAccuracy() ? location.getAccuracy() : -1f,
                ShyldFixFilter.fallbackCap(preciseGranted())
            );
            if (rejection != null) {
                ShyldDiagnostics.rejected(rejection);
                // Drop it - and skip startWatchdog() below so a run of rejected fixes can't mask a
                // genuinely stalled GPS_PROVIDER and suppress the restart that would recover it.
                return;
            }
        }
        ShyldDiagnostics.fixFrom(location.getProvider());
        hadFixSinceStart = true;
        float accuracyM = location.hasAccuracy() ? location.getAccuracy() : -1f;
        boolean repeat = ShyldFixFilter.isRepeat(
            hasLastTaken,
            location.getLatitude(),
            location.getLongitude(),
            accuracyM,
            lastTakenLat,
            lastTakenLng,
            lastTakenAccuracyM
        );
        hasLastTaken = true;
        lastTakenLat = location.getLatitude();
        lastTakenLng = location.getLongitude();
        lastTakenAccuracyM = accuracyM;
        if (!repeat) {
            lastTakenAtMs = SystemClock.elapsedRealtime();
        }
        startWatchdog(!repeat);
        ShyldDiagnostics.lastFixAtMs = System.currentTimeMillis();
        // Shyld: every accepted fix to Shyld's outbox (ShyldHandOff, FORK.md).
        ShyldHandOff.deliver(getApplicationContext(), location);
        if (stopIfUntracked()) return;
        if (locationLogEnabled) {
            boolean locationLogSqlite = LocationStore.getLocationLogSqlite(getApplicationContext());
            if (!locationLogSqlite || nativePostUrl == null) {
                logLocation(location);
            }
        }
        if (nativePostUrl != null) {
            postLocationNatively(location);
        }
        if (mediaPlayer != null) {
            double[] point = { location.getLongitude(), location.getLatitude() };
            var offRoute = distancePointToRoute(point) > distanceThreshold;
            if (offRoute == true && isOffRoute == false) {
                mediaPlayer.start();
            }
            isOffRoute = offRoute;
        }
        LocalEvents.emitLocation(callbackId, location);
    }

    /**
     * Shyld (BL-092 backstop): after 10 minutes of fixes the outbox wants for no shift, the tracker
     * stops itself (ShyldFixFilter.stopsUntracked). The app starts it again for the next shift.
     */
    private boolean stopIfUntracked() {
        Boolean tracking = ShyldHandOff.outboxTracking(getApplicationContext());
        long now = SystemClock.elapsedRealtime();
        if (!Boolean.FALSE.equals(tracking)) {
            untrackedSinceMs = 0L;
            return false;
        }
        if (untrackedSinceMs == 0L) untrackedSinceMs = now;
        if (!ShyldFixFilter.stopsUntracked(tracking, untrackedSinceMs, now)) return false;
        untrackedSinceMs = 0L;
        ShyldDiagnostics.error("untracked", "stopped: no shift wanted its fixes for 10 minutes");
        ((LocalBinder) binder).stop();
        return true;
    }

    /**
     * Shyld (BL-092, C 11-24): the outbox had a 409 stop_tracking (ShyldTracker, main thread).
     * The tracker stops at once when no shift is tracked any more; the 10-minute backstop above
     * stays for every other way a tracker ends up running with nothing tracked.
     */
    void stopForOutbox() {
        if (client == null || locationCallback == null) return;
        if (!ShyldFixFilter.stopsNow(ShyldHandOff.outboxTracking(getApplicationContext()), startedAtMs, SystemClock.elapsedRealtime())) return;
        untrackedSinceMs = 0L;
        ShyldDiagnostics.error("untracked", "stopped: the server ended the shift's tracking");
        ((LocalBinder) binder).stop();
    }

    /**
     * Shyld (BL-092, build 18: indoors, the Pixel's first row came 3 minutes after clock-in). At a
     * start, and when the outbox begins tracking a shift (ShyldTracker.outboxStarted), ask for a
     * newly computed fix at once and run the fused stream fast for a minute (ShyldFixFilter).
     * Without Play services, LocationManager's current-location ask (askFreshFix) goes at once.
     */
    void startFast() {
        if (client == null || locationCallback == null) return;
        fastUntilMs = SystemClock.elapsedRealtime() + ShyldFixFilter.FAST_START_MS;
        watchdogHandler.removeCallbacks(endFastRunnable);
        watchdogHandler.postDelayed(endFastRunnable, ShyldFixFilter.FAST_START_MS);
        if (fused == null) {
            askFreshFix();
            return;
        }
        try {
            ShyldDiagnostics.asked("first");
            fused.askFirst((fix) -> {
                ShyldDiagnostics.answered("first");
                if (client != null && locationCallback != null) {
                    handleLocationChanged(fix);
                }
            });
            if (listening.contains(PLAY_FUSED)) {
                fused.startUpdates(currentDistanceFilter, this::handleLocationChanged, true);
            }
        } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
            ShyldDiagnostics.error("first", exception);
        }
    }

    /** The minute is over: the fused stream goes back to every 50 s. */
    private void endFast() {
        fastUntilMs = 0L;
        if (fused == null || client == null || locationCallback == null || !listening.contains(PLAY_FUSED)) return;
        try {
            fused.startUpdates(currentDistanceFilter, this::handleLocationChanged, false);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
            ShyldDiagnostics.error("fused", exception);
        }
    }

    // Delivers a location to the configured URL from native code, so it works
    // even when the WebView/JavaScript layer no longer exists.
    private void postLocationNatively(android.location.Location location) {
        if (postExecutor == null) {
            postExecutor = Executors.newSingleThreadExecutor();
        }
        Context context = getApplicationContext();
        JSONObject payload = locationToJson(location);
        postExecutor.execute(() -> {
            long logId = LocationStore.logLocationForNativePost(context, payload);
            try {
                LocationStore.sendLocation(context, payload, logId);
            } catch (Exception e) {
                Logger.error("Native location POST failed", e);
            }
        });
    }

    // Adds a location to the location log on the log's own thread, so a slow disk never holds up the next one.
    private void logLocation(android.location.Location location) {
        Context context = getApplicationContext();
        JSONObject entry = locationToJson(location);
        entry.remove("source");
        int maxEntries = locationLogMaxEntries;
        LocationLog.EXECUTOR.execute(() -> {
            try {
                LocationLog.getInstance(context).append(entry, maxEntries);
            } catch (Exception e) {
                Logger.error("Could not add the location to the location log", e);
            }
        });
    }

    private static JSONObject locationToJson(android.location.Location location) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("latitude", location.getLatitude());
            obj.put("longitude", location.getLongitude());
            obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            obj.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                obj.put("altitudeAccuracy", location.getVerticalAccuracyMeters());
            } else {
                obj.put("altitudeAccuracy", JSONObject.NULL);
            }
            obj.put("simulated", LocationCompat.isMock(location));
            obj.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
            obj.put("bearing", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
            obj.put("time", location.getTime());
            // Lets the server distinguish native-delivered updates from those
            // forwarded by the JavaScript callback.
            obj.put("source", "native");
        } catch (org.json.JSONException e) {
            Logger.error("Could not serialize location", e);
        }
        return obj;
    }

    // Android API < 30
    static LocationListenerCompat createLocationListener(final BackgroundGeolocationService service) {
        return (location) -> service.handleLocationChanged(location);
    }

    private long locationIntervalMs() {
        // Shyld (BL-092): never oftener than every 5 s (ShyldFixFilter.providerIntervalMs).
        return ShyldFixFilter.providerIntervalMs(currentMinIntervalMs);
    }

    /** Shyld (BL-092): LocationManager's listener and Play services' stream, both off. */
    private void removeAllUpdates() {
        if (client != null && locationCallback != null) {
            client.removeUpdates(locationCallback);
        }
        if (fused != null) {
            fused.stop();
        }
        listening.clear();
    }

    private void requestLocationUpdates() {
        listening.clear();
        if (fused == null) {
            fused = ShyldFusedClient.createIfAvailable(getApplicationContext());
        }
        try {
            client.requestLocationUpdates(LocationManager.GPS_PROVIDER, locationIntervalMs(), currentDistanceFilter, locationCallback);
            listening.add(LocationManager.GPS_PROVIDER);
            ShyldDiagnostics.resolved("gps");
        } catch (SecurityException | IllegalArgumentException exception) {
            // Shyld (BL-092): refused with approximate location only, or no GPS provider. Said in the
            // log rather than ignored; the network fallback below still gives fixes.
            ShyldDiagnostics.error("gps", exception);
        }
        if (!networkFallbackEnabled) {
            return;
        }
        // GPS_PROVIDER can go quiet for extended periods in the background or with poor sky
        // visibility. Request NETWORK_PROVIDER on the same listener as a fallback; whichever
        // fires first reaches handleLocationChanged(). No manifest change needed -
        // ACCESS_COARSE_LOCATION is already declared. isProviderEnabled guards against devices
        // with network location turned off.
        listenToFallbacks();
    }

    /**
     * Shyld (BL-092): the network provider (on a Pixel, off while Google Location Accuracy is off)
     * and, on API 31+, the fused one, which places a phone indoors from Wi-Fi as iOS does. Asked
     * again at each fresh-fix ask for any that was off: the owner's Pixel started tracking as the
     * one-tap turned Location on, found fused off, and never listened to it after.
     */
    private void listenToFallbacks() {
        if (client == null || locationCallback == null || !networkFallbackEnabled) {
            return;
        }
        listenIfOn(LocationManager.NETWORK_PROVIDER, "network");
        if (ShyldFixFilter.asksFused(Build.VERSION.SDK_INT, networkFallbackEnabled, fused != null)) {
            listenIfOn(LocationManager.FUSED_PROVIDER, "fused");
        }
        listenToPlayServices();
    }

    /**
     * Shyld (BL-092, VALP-1225): with Play services, its fused stream at high accuracy in place of
     * LocationManager's fused provider, whose legacy request ran at the platform's default quality.
     */
    private void listenToPlayServices() {
        if (fused == null || listening.contains(PLAY_FUSED)) {
            return;
        }
        try {
            fused.startUpdates(currentDistanceFilter, this::handleLocationChanged,
                ShyldFixFilter.fastStream(SystemClock.elapsedRealtime(), fastUntilMs));
            listening.add(PLAY_FUSED);
            ShyldDiagnostics.resolved("fused");
        } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
            ShyldDiagnostics.error("fused", exception);
        }
    }

    private void listenIfOn(String provider, String name) {
        if (listening.contains(provider)) {
            return;
        }
        try {
            if (client.isProviderEnabled(provider)) {
                client.requestLocationUpdates(provider, locationIntervalMs(), currentDistanceFilter, locationCallback);
                listening.add(provider);
                ShyldDiagnostics.resolved(name);
            } else {
                ShyldDiagnostics.error(name, "provider off");
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            ShyldDiagnostics.error(name, exception);
        }
    }

    // Promote the service to the foreground if necessary.
    // Ideally we would only call 'startForeground' if the service is not already
    // foregrounded. Unfortunately, 'getForegroundServiceType' was only introduced
    // in API level 29 and seems to behave weirdly, as reported in #120. However,
    // it appears that 'startForeground' is idempotent, so we just call it repeatedly
    // each time a background watcher is added.
    private void promoteToForeground(String notificationTitle, String notificationMessage) {
        try {
            // This method has been known to fail due to weird
            // permission bugs, so we prevent any exceptions from
            // crashing the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createBackgroundNotification(notificationTitle, notificationMessage),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(NOTIFICATION_ID, createBackgroundNotification(notificationTitle, notificationMessage));
            }
            ShyldDiagnostics.running = true;
            ShyldDiagnostics.foreground = true;
        } catch (Exception exception) {
            Logger.error("Failed to foreground service", exception);
            // Shyld (BL-092): kept for diagnostics() rather than only logged.
            ShyldDiagnostics.running = true;
            ShyldDiagnostics.foreground = false;
            ShyldDiagnostics.error("foreground", exception);
        }
    }

    // Handles requests from the activity.
    public class LocalBinder extends Binder {

        void start(
            final String id,
            final String notificationTitle,
            final String notificationMessage,
            float distanceFilter,
            final String url,
            final Map<String, String> headers,
            final long minIntervalMs,
            final boolean networkFallback,
            final boolean locationLog,
            final int locationLogMaxEntries,
            final boolean locationLogSqlite
        ) {
            // The plugin starts this service with startForegroundService(). If the setup
            // below throws (for example 'provider "gps" does not exist' on a device without
            // a GPS chip), the plugin stops the service again, and Android kills the app
            // unless startForeground() already ran. So promote first.
            promoteToForeground(notificationTitle, notificationMessage);
            active = true;
            startedAtMs = SystemClock.elapsedRealtime();
            releaseMediaPlayer();
            acquireWakeLock();
            client = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            // Shyld (BL-092): no GPS fix yet, so no grace (ShyldFixFilter.LAST_GPS_FIX_AT_START).
            lastGpsFixAtMs = ShyldFixFilter.LAST_GPS_FIX_AT_START;
            hadFixSinceStart = false;
            hasLastTaken = false;
            lastTakenAtMs = 0L;
            callbackId = id;
            currentDistanceFilter = distanceFilter;
            currentMinIntervalMs = Math.max(0L, minIntervalMs);
            networkFallbackEnabled = networkFallback;
            locationLogEnabled = locationLog;
            BackgroundGeolocationService.this.locationLogMaxEntries = locationLogMaxEntries;

            nativePostUrl = url == null || url.isEmpty() ? null : url;
            LocationStore.saveSetup(
                getApplicationContext(),
                nativePostUrl,
                notificationTitle,
                notificationMessage,
                distanceFilter,
                headers,
                currentMinIntervalMs,
                networkFallback,
                locationLog,
                locationLogMaxEntries,
                locationLogSqlite
            );
            LocationLog.resetInstance();
            if (locationLog && locationLogSqlite) {
                LocationLog.EXECUTOR.execute(() -> LocationLog.getInstance(getApplicationContext()).open());
            }

            // The service may already be running (for example after a sticky
            // restart), so drop any previous listener before registering a new one.
            if (locationCallback != null) {
                removeAllUpdates();
            }
            locationCallback = createLocationListener(BackgroundGeolocationService.this);
            requestLocationUpdates();
            // Arm the watchdog here so rejected network fixes during the grace period cannot
            // leave tracking without a restart path if GPS_PROVIDER goes silent.
            startWatchdog();
            // Shyld (BL-092, build 18): a first fix now, not after the stream's first minute.
            startFast();
        }

        void updateHeaders(final Map<String, String> headers) {
            LocationStore.saveHeaders(getApplicationContext(), headers);
        }

        /** Shyld (BL-092, VALP-1233): whether a start is in effect, not only bound. */
        boolean isActive() {
            return active;
        }

        String stop() {
            active = false;
            ShyldDiagnostics.running = false;
            ShyldDiagnostics.foreground = false;
            fastUntilMs = 0L;
            watchdogHandler.removeCallbacks(endFastRunnable);
            LocationStore.clear(getApplicationContext());
            nativePostUrl = null;
            stopWatchdog();
            // stop() can reach a bound service whose start() has not run yet
            // (JS calls start() then stop() back-to-back), in which case neither
            // field is set. Same guard as onUnbind() and onDestroy().
            if (client != null && locationCallback != null) {
                removeAllUpdates();
            }
            ServiceCompat.stopForeground(BackgroundGeolocationService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
            releaseMediaPlayer();
            releaseWakeLock();
            return callbackId;
        }

        void setPlannedRoute(String filePath, double[][] routeCoordinates, float distance) {
            route = routeCoordinates;
            distanceThreshold = distance;
            isOffRoute = true;
            try {
                if (mediaPlayer != null) {
                    return;
                }
                mediaPlayer = new MediaPlayer();
                AssetManager am = getApplicationContext().getResources().getAssets();
                AssetFileDescriptor assetFileDescriptor = am.openFd("public/" + filePath);

                mediaPlayer.setDataSource(
                    assetFileDescriptor.getFileDescriptor(),
                    assetFileDescriptor.getStartOffset(),
                    assetFileDescriptor.getLength()
                );
                mediaPlayer.setLooping(false);

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Logger.error("MediaPlayer error: what=" + what + ", extra=" + extra);
                    releaseMediaPlayer();
                    return true; // Indicate we handled the error
                });

                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Logger.error("PlaySound: Unexpected error", e);
                releaseMediaPlayer();
            }
        }
    }

    /**
     * Shyld (BL-092): the service makes its own channel. Upstream made it only in the plugin's load(),
     * so a service started before that (a sticky restart, or a test) posted to a missing channel,
     * which Android 8+ refuses for a foreground service. Creating an existing channel changes nothing.
     */
    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return;
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getAppString("capacitor_background_geolocation_notification_channel_name", "Background Tracking", context),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification createBackgroundNotification(String backgroundTitle, String backgroundMessage) {
        ensureChannel(getApplicationContext());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NOTIFICATION_CHANNEL_ID)
            .setContentTitle(backgroundTitle)
            .setContentText(backgroundMessage)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis());

        try {
            String name = getAppString("capacitor_background_geolocation_notification_icon", "mipmap/ic_launcher", getApplicationContext());
            String[] parts = name.split("/");
            // It is actually necessary to set a valid icon for the notification to behave
            // correctly when tapped. If there is no icon specified, tapping it will open the
            // app's settings, rather than bringing the application to the foreground.
            builder.setSmallIcon(smallIcon(getAppResourceIdentifier(parts[1], parts[0], getApplicationContext())));
        } catch (Exception e) {
            Logger.error("Could not set notification icon", e);
            builder.setSmallIcon(smallIcon(0));
        }

        try {
            String color = getAppString("capacitor_background_geolocation_notification_color", null, getApplicationContext());
            if (color != null) {
                builder.setColor(Color.parseColor(color));
            }
        } catch (Exception e) {
            Logger.error("Could not set notification color", e);
        }

        Intent launchIntent = getApplicationContext()
            .getPackageManager()
            .getLaunchIntentForPackage(getApplicationContext().getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            builder.setContentIntent(
                PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                )
            );
        }

        return builder.build();
    }

    // Gets the identifier of the app's resource by name, returning 0 if not found.
    // The name comes from the host app's configuration, so it can only be resolved by
    // reflection; the compile-time R class of this library does not contain it.
    @SuppressLint("DiscouragedApi")
    /**
     * Shyld (BL-092): the app's icon, or Android's own location icon when the app has none. Without
     * a small icon Android refuses startForeground, the service never reaches the foreground and the
     * process is killed for it (the native gate's start-path case, in a test APK with no launcher icon).
     */
    static int smallIcon(int appIcon) {
        return appIcon != 0 ? appIcon : android.R.drawable.ic_menu_mylocation;
    }

    private static int getAppResourceIdentifier(String name, String defType, Context context) {
        return context.getResources().getIdentifier(name, defType, context.getPackageName());
    }

    // Gets a string from the app's strings.xml file, resorting to a fallback if it is not defined.
    public static String getAppString(String name, String fallback, Context context) {
        int id = getAppResourceIdentifier(name, "string", context);
        return id == 0 ? fallback : context.getString(id);
    }

    private static double haversine(double[] point1, double[] point2) {
        double lon1 = point1[0];
        double lat1 = point1[1];
        double lon2 = point2[0];
        double lat2 = point2[1];

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_M * c;
    }

    private static double distancePointToLineSegment(double[] point, double[] lineStart, double[] lineEnd) {
        // Calculate the distances between the three points using Haversine
        double dist_A_B = haversine(point, lineStart);
        double dist_A_C = haversine(point, lineEnd);
        double dist_B_C = haversine(lineStart, lineEnd);

        // Handle the edge case where the line segment is a single point
        if (dist_B_C == 0) {
            return dist_A_B;
        }

        // Check if the angles at the line segment's endpoints are obtuse.
        // We use the Law of Cosines (c^2 = a^2 + b^2 - 2ab*cos(C))
        // If cos(C) < 0, the angle is obtuse.

        // Angle at B (lineStart)
        // Use a small epsilon to handle floating point inaccuracies in division by zero
        double cos_B = (Math.pow(dist_A_B, 2) + Math.pow(dist_B_C, 2) - Math.pow(dist_A_C, 2)) / (2 * dist_A_B * dist_B_C);
        if (cos_B < 0) {
            return dist_A_B;
        }

        // Angle at C (lineEnd)
        double cos_C = (Math.pow(dist_A_C, 2) + Math.pow(dist_B_C, 2) - Math.pow(dist_A_B, 2)) / (2 * dist_A_C * dist_B_C);
        if (cos_C < 0) {
            return dist_A_C;
        }

        // If both angles are acute, the closest point is on the line segment itself.
        // We can calculate the distance (height of the triangle) using its area.

        // 1. Calculate the semi-perimeter of the triangle ABC
        double s = (dist_A_B + dist_A_C + dist_B_C) / 2;

        // 2. Calculate the area using Heron's formula
        double area = Math.sqrt(Math.max(0, s * (s - dist_A_B) * (s - dist_A_C) * (s - dist_B_C)));

        // 3. The distance is the height of the triangle from point A to the base BC
        // Area = 0.5 * base * height  =>  height = 2 * Area / base
        return (2 * area) / dist_B_C;
    }

    public double distancePointToRoute(double[] point) {
        // If the polyline has less than 2 points, we can't form a segment.
        if (this.route.length < 2) {
            if (this.route.length == 1) {
                return haversine(point, this.route[0]);
            }
            return Double.POSITIVE_INFINITY; // No line segments to measure against
        }

        double minDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < this.route.length - 1; i++) {
            double[] lineStart = this.route[i];
            double[] lineEnd = this.route[i + 1];
            double distance = distancePointToLineSegment(point, lineStart, lineEnd);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }

        return minDistance;
    }
}
