package com.sitrep.ulm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the local server alive and refreshes a one-line summary (route home,
 * medication, weather) into an ongoing, lock-screen-visible notification.
 *
 * Power: the summary is refreshed every 5 min while the screen is on, once the
 * moment it turns on, and every ~15 min while it's off (inexact alarm →
 * TickReceiver). Each refresh costs a DING trip request + a radio wake-up.
 */
class SitrepMonitorService : Service() {

    companion object {
        // 8010, not the Munich build's 8000, so both apps can run on one phone.
        const val PORT = 8010
        const val CHANNEL_ID = "sitrep_ulm_summary"
        const val NOTIF_ID = 1
        const val EXTRA_FROM_BOOT = "fromBoot"
        const val ACTION_REFRESH = "com.sitrep.ulm.REFRESH_LOCATION"
        @Volatile var serverReady = false
        @Volatile private var instance: SitrepMonitorService? = null
        /** Screen-off refresh interval (inexact alarm; Android may batch it with other wake-ups). */
        const val TICK_MS = 15 * 60_000L

        /** Test hook (local API): fire the next screen-off tick after [ms] instead of 15 min. */
        fun tickIn(ms: Long) {
            SitrepApi.locationAt = 0 // stale, so the tick must really fetch a position
            instance?.scheduleTick(ms)
        }

        /** Called by TickReceiver; [done] must run when the refresh is finished. */
        fun backgroundTick(done: () -> Unit) {
            val s = instance ?: return done()
            s.onTick(done)
        }

        /** Rebuild the summary notification now (e.g. a dose was just marked taken) instead of at the next tick. */
        fun requestRefresh() { instance?.refreshNow() }

        /** One-shot location fix + summary refresh; used by the app's refresh button. False if no fix. */
        suspend fun refreshLocation(): Boolean = instance?.doRefreshLocation() ?: false

        /** Auto/manual location mode changed: re-register location updates and redraw the notification. */
        fun applyLocationMode() { instance?.let { it.locationTracker?.restart(); it.redraw() } }
    }

    private var server: LocalServer? = null
    private var locationTracker: LocationTracker? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loopJob: Job? = null
    private val refreshing = Mutex()
    @Volatile private var lastText = "Sitrep — loading…"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) refreshNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        MedAlarms.ensureChannel(this)
        LocalConfig.load(applicationContext) // work stop + default meds (git-ignored asset)
        HomeStore.load(applicationContext)
        LocationTracker.loadMode(applicationContext)
        MedAlarms.scheduleAll(applicationContext) // also loads MedStore
        instance = this
        try {
            server = LocalServer(applicationContext, PORT).also { it.start(30_000, false) }
            serverReady = true
        } catch (_: Exception) {
            serverReady = false
        }
        locationTracker = LocationTracker(applicationContext)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundTyped(intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false)
        // Permission may have just been granted in MainActivity after this service
        // was first created, so re-attempt on every start rather than only onCreate.
        locationTracker?.start()
        if (intent?.action == ACTION_REFRESH) scope.launch { doRefreshLocation() }
        if (loopJob == null) {
            loopJob = scope.launch {
                refreshSummary()
                val pm = getSystemService(PowerManager::class.java)
                while (true) {
                    delay(5 * 60_000L)
                    if (pm.isInteractive) refreshSummary()
                }
            }
        }
        scheduleTick()
        return START_STICKY
    }

    private fun tickIntent() = PendingIntent.getBroadcast(
        this, 2, Intent(this, TickReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Keeps a 15-min alarm armed. Exact, not inexact: on HyperOS the inexact
     * setAndAllowWhileIdle alarm was held back well past Android's own batching
     * window (measured: 14+ min late with the phone awake and charging), while the
     * exact medication alarms arrive within seconds. Batching would save little
     * anyway, since every tick does real work. Falls back to inexact if exact
     * alarms aren't permitted.
     */
    private fun scheduleTick(delayMs: Long = TICK_MS) {
        val am = getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent())
        }
    }

    /** Screen off: refresh the lock-screen summary (plus a Wi-Fi/cell position) under a short wake lock. */
    private fun onTick(done: () -> Unit) {
        scheduleTick()
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) { done(); return } // screen on: the 5-min loop already covers it
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sitrep:tick").apply { acquire(60_000) } // position ≤30 s (network 10 + GPS fallback 20) + requests
        scope.launch {
            try { withTimeoutOrNull(55_000) { refreshSummary() } }
            finally { if (wl.isHeld) wl.release(); done() }
        }
    }

    /**
     * Android 14+ throws if a service claims the `location` FGS type without
     * while-in-use location access — which a service started from BOOT_COMPLETED
     * never has. So boot starts as dataSync only; opening the app, or tapping the
     * notification's refresh button (a user interaction, which grants while-in-use),
     * re-sends the start intent and upgrades it to dataSync|location.
     */
    private fun startForegroundTyped(fromBoot: Boolean) {
        val notif = buildNotification(lastText)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif)
            return
        }
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val withLocation = dataSync or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val canLocate = !fromBoot && LocationTracker.hasPermission(this)
        val type = if (canLocate) withLocation else dataSync
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (_: SecurityException) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, dataSync)
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_DEFAULT (not LOW) so it's shown on the lock screen rather than
        // bucketed as silent; sound/vibration off so the refreshes don't buzz.
        val channel = NotificationChannel(
            CHANNEL_ID, "Sitrep summary", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ongoing route-home/medication/weather summary, visible on the lock screen"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sitrep Ulm")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
        if (SitrepApi.locMode == "manual") {
            // Manual location mode: the position only changes via this button (or the app's).
            val refresh = PendingIntent.getForegroundService(
                this, 1, Intent(this, SitrepMonitorService::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "📍 Refresh location", refresh)
            val at = SitrepApi.locationAt
            b.setSubText(if (at > 0) "location from " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)) else "no location yet")
        }
        return b.build()
    }

    private suspend fun refreshSummary() {
        // A screen-on refresh and the timer can coincide; one request is enough.
        if (!refreshing.tryLock()) return
        try {
            // Automatic mode: a quick Wi-Fi/cell position if the last one is >5 min old.
            // Only ever runs while the screen is on (see onStartCommand/screenReceiver).
            try { locationTracker?.quickFix() } catch (_: Exception) { }
            lastText = try { SitrepApi.shortSummary() } catch (e: Exception) { "Sitrep — ${e.message}" }
            redraw()
        } finally { refreshing.unlock() }
    }

    private fun refreshNow() { scope.launch { refreshSummary() } }

    private suspend fun doRefreshLocation(): Boolean {
        val tracker = locationTracker ?: return false
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("📍 Updating location…"))
        val fix = tracker.freshFix()
        refreshSummary()
        if (fix == null) redraw() // restore the text even if nothing changed
        return fix != null
    }

    private fun redraw() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(lastText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        getSystemService(AlarmManager::class.java).cancel(tickIntent())
        loopJob?.cancel()
        server?.stop()
        locationTracker?.stop()
        serverReady = false
        super.onDestroy()
    }
}
