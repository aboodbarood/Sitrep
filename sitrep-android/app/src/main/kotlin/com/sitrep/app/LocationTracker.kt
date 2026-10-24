package com.sitrep.app

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
 * Feeds SitrepApi's currentLat/currentLon/currentCityName from the device's own
 * location. Falls back to the hardcoded Munich defaults if location is unavailable.
 *
 * No location request is ever left open: every position is a one-shot request,
 * released as soon as it answers. quickFix() (Wi-Fi/cell, GPS only if that's poor) runs before each
 * summary refresh (screen on: at screen-on and every 5 min; screen off: every
 * ~15 min) if the last fix is >5 min old; freshFix() (GPS + network) once per
 * app open. Xiaomi's privacy indicator — which shows any open request, even the
 * cheap network one — therefore only flashes briefly instead of staying on.
 *
 * History: the first version kept GPS_PROVIDER registered permanently (8 min /
 * 300 m). This phone's MediaTek GNSS has the SCHEDULING capability, so Android
 * kept one GPS session open: the engine stayed started for 10 h straight, and
 * the identical code in the Ulm build measured ~49% GPS on-time.
 */
class LocationTracker(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var lastFixAt = 0L

    /** Seeds the position from what the system already knows — free, no radio, no open request. */
    fun start() {
        if (!hasPermission(context)) return
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
            ?.let { if (it.time > lastFixAt) onNewLocation(it) }
    }

    // Nothing is ever registered, so there is nothing to stop.
    fun stop() {}

    /** Test hook: forget the last fix time so the next quickFix() really fetches one. */
    fun markStale() { lastFixAt = 0 }

    /**
     * Background position, skipped if the last fix is recent. Wi-Fi/cell first —
     * cheap and usually 20–100 m in a city. GPS only as a fallback when that's
     * missing or coarser than ~150 m (cell-tower-only fixes can be off by km),
     * capped at 20 s and released as soon as it has a ≤50 m fix.
     */
    suspend fun quickFix(maxAgeMs: Long = 5 * 60_000L): Location? {
        if (System.currentTimeMillis() - lastFixAt < maxAgeMs) return null
        val net = oneShot(listOf(LocationManager.NETWORK_PROVIDER), 10_000)
        if (net != null && net.accuracy <= 150f) return net
        return oneShot(listOf(LocationManager.GPS_PROVIDER), 20_000) ?: net
    }

    /**
     * One-shot accurate position: asks GPS and network at once, takes the first
     * fix within 50 m, otherwise the best received before the timeout, then
     * releases both providers. Null if nothing answered in time.
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
        lastFixAt = if (loc.time > 0) loc.time else System.currentTimeMillis()
        SitrepApi.currentLat = loc.latitude
        SitrepApi.currentLon = loc.longitude
        try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            val name = results?.firstOrNull()?.locality ?: results?.firstOrNull()?.subAdminArea
            if (!name.isNullOrBlank()) SitrepApi.currentCityName = name
        } catch (_: Exception) { /* geocoder backend not available on this device — keep last known name */ }
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
