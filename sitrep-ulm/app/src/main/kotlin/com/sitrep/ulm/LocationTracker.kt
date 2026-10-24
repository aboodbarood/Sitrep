package com.sitrep.ulm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Feeds SitrepApi's position. Uses the plain AOSP LocationManager rather than
 * Google's fused provider, so it works on the de-Googled /e/OS Fairphone 6 too.
 *
 * No location request is ever left open. Every position is a one-shot request
 * that is released as soon as it answers, so Xiaomi's privacy indicator (which
 * shows any open request, even the cheap Wi-Fi/cell one, as "accessing
 * location") only flashes briefly instead of showing permanent access.
 *
 * Two modes (Settings sheet, persisted in prefs "sitrep"/"locMode"):
 *  - auto:   quickFix() — Wi-Fi/cell, GPS only if that's poor — before each summary refresh (screen
 *            on: at screen-on and every 5 min; screen off: every ~15 min), if
 *            the last fix is >5 min old; plus one GPS+network freshFix()
 *            whenever the app is opened.
 *  - manual: the position only changes when the user taps refresh (app header
 *            or notification) → freshFix().
 *
 * History: the first version kept GPS_PROVIDER registered permanently (8 min /
 * 300 m). This phone's MediaTek GNSS has the SCHEDULING capability, so Android
 * kept one GPS session open and the chip searched ~49% of the time. The second
 * version replaced that with a standing NETWORK registration — cheap, but still
 * shown as permanent location access.
 */
class LocationTracker(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Seeds the position from what the system already knows — free, no radio, no open request. */
    fun start() {
        if (!hasPermission(context)) return
        val seed = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (seed != null && seed.time > SitrepApi.locationAt) onNewLocation(seed)
    }

    // Nothing is ever registered, so there is nothing to stop or re-register.
    fun stop() {}
    fun restart() {}

    /**
     * Automatic mode's background position, skipped if the last fix is recent.
     * Wi-Fi/cell first — cheap and, in a city with Wi-Fi, 20–100 m. GPS only as a
     * fallback when that's missing or coarser than ~150 m (cell-tower-only fixes can
     * be off by kilometres, which would pick the wrong stop), capped at 20 s and
     * released the moment it has a ≤50 m fix. Worst case (GPS needed every time)
     * ≈ 20 s per 15 min ≈ 32 min/day, vs ~12 h/day for the original standing request.
     */
    suspend fun quickFix(maxAgeMs: Long = 5 * 60_000L): Location? {
        if (SitrepApi.locMode != "auto") return null
        if (System.currentTimeMillis() - SitrepApi.locationAt < maxAgeMs) return null
        val net = oneShot(listOf(LocationManager.NETWORK_PROVIDER), 10_000)
        if (net != null && net.accuracy <= GOOD_ENOUGH_M) return net
        return oneShot(listOf(LocationManager.GPS_PROVIDER), 20_000) ?: net
    }

    /**
     * One-shot accurate position: asks GPS and network at once, takes the first
     * fix within 50 m, otherwise the best one received before the timeout. Used
     * for the refresh buttons (manual mode) and when the app is opened (auto mode).
     * Returns null if no provider answered in time.
     */
    suspend fun freshFix(timeoutMs: Long = 20_000): Location? =
        oneShot(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), timeoutMs)

    private suspend fun oneShot(wanted: List<String>, timeoutMs: Long): Location? {
        if (!hasPermission(context)) return null
        val providers = wanted
            .filter { p -> try { locationManager.isProviderEnabled(p) } catch (_: Exception) { false } }
        if (providers.isEmpty()) return null

        val results = Channel<Location?>(Channel.UNLIMITED)
        val signals = providers.map { p ->
            CancellationSignal().also { sig ->
                try {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager, p, sig, ContextCompat.getMainExecutor(context),
                        Consumer { loc -> results.trySend(loc) })
                } catch (_: SecurityException) { results.trySend(null) }
            }
        }
        var best: Location? = null
        withTimeoutOrNull(timeoutMs) {
            repeat(providers.size) {
                val loc = results.receive() ?: return@repeat
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
                if (loc.accuracy <= 50f) return@withTimeoutOrNull
            }
        }
        signals.forEach { it.cancel() } // stops the GPS engine as soon as we're done
        results.close()
        best?.let { onNewLocation(it) }
        return best
    }

    private fun onNewLocation(loc: Location) {
        SitrepApi.currentLat = loc.latitude
        SitrepApi.currentLon = loc.longitude
        SitrepApi.locationAt = if (loc.time > 0) loc.time else System.currentTimeMillis()
        SitrepApi.locationSource = loc.provider ?: ""
        SitrepApi.locationAccuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f
        if (!Geocoder.isPresent()) return // /e/OS: SitrepApi.transit() fills the city from DING instead
        try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            val name = results?.firstOrNull()?.locality ?: results?.firstOrNull()?.subAdminArea
            if (!name.isNullOrBlank()) {
                SitrepApi.currentCityName = name
                SitrepApi.cityFromGeocoder = true
            }
        } catch (_: Exception) { }
    }

    companion object {
        /** A network fix at least this good is used as is; worse triggers the GPS fallback. */
        private const val GOOD_ENOUGH_M = 150f

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private const val PREFS = "sitrep"
        fun loadMode(context: Context) {
            SitrepApi.locMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("locMode", "auto") ?: "auto"
        }
        fun saveMode(context: Context, mode: String) {
            val m = if (mode == "manual") "manual" else "auto"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("locMode", m).apply()
            SitrepApi.locMode = m
        }
    }
}
