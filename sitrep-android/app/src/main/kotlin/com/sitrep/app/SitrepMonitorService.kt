package com.sitrep.app

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

/**
 * Keeps the local proxy server alive and refreshes a short one-line summary
 * (same facts as View1's widgets) into an ongoing, lock-screen-visible
 * notification. MIUI/HyperOS mirrors ongoing notification content onto the
 * Always-On Display when the system's own "show notifications on AOD"
 * setting is enabled (Settings > Always-on display & lock screen) — there is
 * no public API on these devices for an app to draw its own AOD canvas, so
 * this notification is the actual mechanism behind that.
 *
 * Power: the summary is refreshed every 5 min while the screen is on, once the
 * moment it turns on, and every ~15 min while it's off (inexact alarm →
 * TickReceiver). Each refresh costs several MVG/weather requests and a radio wake-up.
 */
class SitrepMonitorService : Service() {

    companion object {
        const val PORT = 8000
        // Was "sitrep_summary" at IMPORTANCE_LOW — HyperOS bucketed that as a
        // "silent" notification and kept it off the actual lock screen. Importance
        // can't be raised on an existing channel, so this is a fresh id at
        // IMPORTANCE_DEFAULT instead, with sound/vibration explicitly disabled
        // below so the 5-minute refreshes don't actually buzz.
        const val CHANNEL_ID = "sitrep_summary_v2"
        const val OLD_CHANNEL_ID = "sitrep_summary"
        const val NOTIF_ID = 1
        const val EXTRA_FROM_BOOT = "fromBoot"
        @Volatile var serverReady = false
        @Volatile private var instance: SitrepMonitorService? = null
        /** Screen-off refresh interval (inexact alarm; Android may batch it with other wake-ups). */
        const val TICK_MS = 15 * 60_000L

        /** Test hook (local API): fire the next screen-off tick after [ms] instead of 15 min. */
        fun tickIn(ms: Long) {
            instance?.locationTracker?.markStale() // so the tick must really fetch a position
            instance?.scheduleTick(ms)
        }

        /** Called by TickReceiver; [done] must run when the refresh is finished. */
        fun backgroundTick(done: () -> Unit) {
            val s = instance ?: return done()
            s.onTick(done)
        }

        /** App came to the foreground: one accurate location fix (GPS released right after). */
        fun onAppOpened() {
            instance?.let { s -> s.scope.launch { s.locationTracker?.freshFix() } }
        }
    }

    private var server: LocalServer? = null
    private var locationTracker: LocationTracker? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loopJob: Job? = null
    private val refreshing = Mutex()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) scope.launch { refreshSummary() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SitrepApi.loadLocalConfig(applicationContext) // personal values; git-ignored asset
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
     * Android 14+ throws a SecurityException if a service claims the `location`
     * FGS type without while-in-use location access, which a service started from
     * BOOT_COMPLETED never has. The old code passed no type (= every type in the
     * manifest, incl. location) and crashed on such starts (seen in the phone's
     * crash log). Boot now starts as dataSync only; opening the app re-sends the
     * start intent and upgrades it to dataSync|location.
     */
    private fun startForegroundTyped(fromBoot: Boolean) {
        val notif = buildNotification("Sitrep — loading…")
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
        mgr.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID, "Sitrep summary", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ongoing weather/transit/prayer summary, visible on the lock screen"
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sitrep")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
            .build()
    }

    private suspend fun refreshSummary() {
        // A screen-on refresh and the timer can coincide; one round of requests is enough.
        if (!refreshing.tryLock()) return
        try {
            // A quick Wi-Fi/cell position if the last one is >5 min old. Only ever
            // runs while the screen is on (see onStartCommand/screenReceiver).
            try { locationTracker?.quickFix() } catch (_: Exception) { }
            val text = try { SitrepApi.shortSummary() } catch (e: Exception) { "Sitrep — ${e.message}" }
            updateNotification(text)
        } finally { refreshing.unlock() }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
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
