package com.sitrep.ulm

import android.content.Context
import org.json.JSONObject

/** A route destination: an address plus the station the route guide goes to (home, or the fixed work stop). */
data class Place(
    val address: String, val lat: Double, val lon: Double,
    val stationId: String, val stationName: String, val stationLat: Double, val stationLon: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address); put("lat", lat); put("lon", lon)
        put("stationId", stationId); put("stationName", stationName)
        put("stationLat", stationLat); put("stationLon", stationLon)
    }

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.getString("address"), o.getDouble("lat"), o.getDouble("lon"),
            o.getString("stationId"), o.getString("stationName"),
            o.getDouble("stationLat"), o.getDouble("stationLon"),
        )
    }
}

/** Persists the home Place in SharedPreferences and mirrors it into SitrepApi.home, which everything else reads. */
object HomeStore {
    private const val PREFS = "sitrep"
    private const val KEY = "home"

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        SitrepApi.home = try { raw?.let { Place.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
    }

    fun save(context: Context, home: Place) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, home.toJson().toString()).apply()
        SitrepApi.home = home
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        SitrepApi.home = null
    }
}
