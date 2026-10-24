package com.sitrep.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Kotlin port of sitrep.py's api_* functions, run natively on-device instead
 * of through a home-network proxy so the app works over mobile data too.
 * Same upstreams, same config, same shapes the page's JS already expects.
 */
object SitrepApi {

    // ---- Fallback location — used until a GPS fix arrives ----
    private const val DEFAULT_CITY_NAME = "Munich"
    private const val DEFAULT_LAT = 48.1374
    private const val DEFAULT_LON = 11.5755

    // ---- Personal config: neutral defaults here, real values from the git-ignored
    // asset local_config.json (see local_config.example.json), loaded at startup. ----
    @Volatile var ICAO = "EDDM"
    @Volatile var AVWX_TOKEN = ""
    @Volatile var PRAYER_METHOD = 3
    /** Home stop: notification shows its departures when near it, else the route there. */
    @Volatile var HOME_GLOBAL_ID = "de:09162:2"
    @Volatile var HOME_NAME = "Marienplatz"
    @Volatile var HOME_LAT = 48.137245
    @Volatile var HOME_LON = 11.575421
    @Volatile var HOME_RADIUS_M = 400.0
    /** Lines worth catching at home, for the notification. Empty = whatever leaves first. */
    @Volatile var HOME_LINES: Set<String> = emptySet()

    fun loadLocalConfig(context: android.content.Context) {
        val o = try {
            context.assets.open("local_config.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: Exception) { return } // no local file: keep the neutral defaults
        ICAO = o.optString("icao", ICAO)
        AVWX_TOKEN = o.optString("avwxToken", AVWX_TOKEN)
        PRAYER_METHOD = o.optInt("prayerMethod", PRAYER_METHOD)
        o.optJSONObject("home")?.let { h ->
            HOME_GLOBAL_ID = h.optString("globalId", HOME_GLOBAL_ID)
            HOME_NAME = h.optString("name", HOME_NAME)
            HOME_LAT = h.optDouble("lat", HOME_LAT)
            HOME_LON = h.optDouble("lon", HOME_LON)
            HOME_RADIUS_M = h.optDouble("radiusM", HOME_RADIUS_M)
            h.optJSONArray("lines")?.let { a -> HOME_LINES = (0 until a.length()).map { a.getString(it) }.toSet() }
        }
    }

    // Live reference location — LocationTracker overwrites these as GPS fixes
    // arrive. Weather/prayer/flights/map/transit all read through these
    // instead of the fixed constants above, so the whole dashboard follows
    // wherever the phone actually is.
    @Volatile var currentLat: Double = DEFAULT_LAT
    @Volatile var currentLon: Double = DEFAULT_LON
    @Volatile var currentCityName: String = DEFAULT_CITY_NAME

    private const val MVG_BASE = "https://www.mvg.de/api/bgw-pt/v3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cache = HashMap<String, Pair<Long, Any>>()

    private fun getJson(url: String, headers: Map<String, String> = emptyMap(), cacheS: Long = 0): Any {
        val now = System.currentTimeMillis()
        if (cacheS > 0) {
            cache[url]?.let { (ts, val_) -> if (now - ts < cacheS * 1000) return val_ }
        }
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (sitrep personal dashboard)")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            val parsed = JSONTokener(body).nextValue()
            if (cacheS > 0) cache[url] = now to parsed
            return parsed
        }
    }

    private val WMO = mapOf(
        0 to "Clear", 1 to "Mainly clear", 2 to "Partly cloudy", 3 to "Overcast", 45 to "Fog",
        48 to "Rime fog", 51 to "Light drizzle", 53 to "Drizzle", 55 to "Dense drizzle",
        61 to "Light rain", 63 to "Rain", 65 to "Heavy rain", 71 to "Light snow", 73 to "Snow",
        75 to "Heavy snow", 80 to "Light showers", 81 to "Showers", 82 to "Violent showers",
        85 to "Snow showers", 86 to "Heavy snow showers", 95 to "Thunderstorm",
        96 to "Thunderstorm, hail", 99 to "Severe thunderstorm"
    )

    fun weather(): JSONObject {
        val lat = currentLat; val lon = currentLon
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code," +
            "relative_humidity_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m," +
            "surface_pressure,cloud_cover" +
            "&hourly=precipitation_probability,precipitation" +
            "&forecast_days=2&timezone=auto"
        // Weather is the least time-critical of the three data types (open-meteo's
        // own source data only refreshes hourly anyway), so it gets the longest cache.
        val data = getJson(url, cacheS = 900) as JSONObject
        val c = data.getJSONObject("current")
        val h = data.getJSONObject("hourly")
        val hTimes = h.getJSONArray("time")
        val currentHour = c.getString("time").substring(0, 13) + ":00"
        var idx = 0
        for (i in 0 until hTimes.length()) {
            if (hTimes.getString(i) == currentHour) { idx = i; break }
        }
        val probsArr = h.getJSONArray("precipitation_probability")
        val mmsArr = h.getJSONArray("precipitation")
        val end = minOf(idx + 12, hTimes.length())
        val probs = (idx until end).map { probsArr.optInt(it, 0) }
        val mms = (idx until end).map { mmsArr.optDouble(it, 0.0) }
        val times = (idx until end).map { hTimes.getString(it) }

        var onset: Int? = null
        for (i in probs.indices) {
            if (probs[i] >= 50 || mms[i] >= 0.1) { onset = i; break }
        }

        val weatherCode = c.getInt("weather_code")
        val rainHours = JSONArray()
        for (i in times.indices) {
            rainHours.put(JSONObject().apply {
                put("t", times[i].takeLast(5))
                put("prob", probs[i])
                put("mm", mms[i])
            })
        }

        return JSONObject().apply {
            put("temp", Math.round(c.getDouble("temperature_2m")))
            put("feels", Math.round(c.getDouble("apparent_temperature")))
            put("precip", c.getDouble("precipitation"))
            put("desc", WMO[weatherCode] ?: "")
            put("humidity", c.getInt("relative_humidity_2m"))
            put("windSpeed", Math.round(c.getDouble("wind_speed_10m")))
            put("windDir", c.getInt("wind_direction_10m"))
            put("windGust", Math.round(c.getDouble("wind_gusts_10m")))
            put("pressure", Math.round(c.getDouble("surface_pressure")))
            put("cloud", c.getInt("cloud_cover"))
            put("rainExpected", onset != null)
            put("rainIn", onset ?: JSONObject.NULL)
            put("rainMaxProb", probs.maxOrNull() ?: 0)
            put("rainTotal", Math.round(mms.sum() * 10) / 10.0)
            put("rainHours", rainHours)
        }
    }

    /** Nearest real stations to the live reference location, closest first (MVG sorts by distanceInMeters already). */
    private fun mvgNearbyStations(lat: Double, lon: Double): List<Pair<String, String>> {
        val url = "$MVG_BASE/stations/nearby?latitude=$lat&longitude=$lon"
        // Matches LocationTracker's own 8-min GPS refresh — no point re-querying
        // "what's nearby" more often than the position itself actually changes.
        val arr = getJson(url, cacheS = 480) as JSONArray
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val loc = arr.getJSONObject(i)
            if (loc.has("globalId")) out.add(loc.getString("globalId") to loc.optString("name", ""))
        }
        return out
    }

    /**
     * Generalized version of sitrep.py's fixed-district transit lookup: instead of a
     * fixed place-name query filtered to four pre-chosen lines, this finds whatever
     * real station is nearest the live GPS position and shows whatever actually
     * departs from it — so it reads correctly away from home too (e.g. another town),
     * not just at the one commute stop the desktop version was built around.
     */
    fun transit(): JSONObject {
        val lat = currentLat; val lon = currentLon
        val stations = mvgNearbyStations(lat, lon).take(3)
        if (stations.isEmpty()) throw RuntimeException("no nearby stations")

        data class Row(val line: String, val dest: String, val stop: String, val at: Long,
                        val delay: Long, val cancelled: Boolean, val type: String)

        val rows = mutableListOf<Row>()
        val errors = mutableListOf<String>()
        for ((gid, name) in stations) {
            try {
                val url = "$MVG_BASE/departures?globalId=${java.net.URLEncoder.encode(gid, "UTF-8")}&limit=20"
                val arr = getJson(url, cacheS = 30) as JSONArray
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val label = d.optString("label", "").trim()
                    if (label.isEmpty()) continue
                    val planned = if (d.has("plannedDepartureTime") && !d.isNull("plannedDepartureTime")) d.getLong("plannedDepartureTime") else null
                    val real = if (d.has("realtimeDepartureTime") && !d.isNull("realtimeDepartureTime")) d.getLong("realtimeDepartureTime") else planned
                    if (real == null) continue
                    var delay = if (d.has("delayInMinutes") && !d.isNull("delayInMinutes")) d.getLong("delayInMinutes") else null
                    if (delay == null && planned != null) delay = Math.round((real - planned) / 60000.0)
                    rows.add(Row(label, d.optString("destination", ""), name, real,
                        delay ?: 0, d.optBoolean("cancelled", false), d.optString("transportType", "")))
                }
            } catch (e: Exception) {
                errors.add("$name: ${e.message}")
            }
        }

        val nowMs = System.currentTimeMillis()
        val sorted = rows.sortedBy { it.at }.filter { it.at > nowMs - 60000 }.take(12)

        val seen = HashSet<String>()
        val perLine = mutableListOf<Row>()
        for (r in sorted) if (seen.add(r.line)) perLine.add(r)

        fun toJson(r: Row) = JSONObject().apply {
            put("line", r.line); put("dest", r.dest); put("stop", r.stop)
            put("at", r.at); put("delay", r.delay); put("cancelled", r.cancelled); put("type", r.type)
        }

        return JSONObject().apply {
            put("next", JSONArray(sorted.take(6).map { toJson(it) }))
            put("perLine", JSONArray(perLine.map { toJson(it) }))
            put("station", stations.first().second)
            put("stops", JSONArray(stations.map { it.second }))
            put("errors", JSONArray(errors))
        }
    }

    /**
     * MVG journey planner from the nearest live station to the home stop, picking whichever
     * returned itinerary has the shortest total travel time (MVG's /routes doesn't sort
     * by duration, just by departure time, so the fastest option isn't always first).
     */
    fun routeHome(): JSONObject {
        val stations = mvgNearbyStations(currentLat, currentLon)
        if (stations.isEmpty()) throw RuntimeException("no nearby stations")
        val (originId, originName) = stations.first()

        if (originId == HOME_GLOBAL_ID) {
            return JSONObject().apply { put("sameStation", true); put("origin", originName) }
        }

        val now = java.time.Instant.now().toString()
        val url = "$MVG_BASE/routes?originStationGlobalId=${java.net.URLEncoder.encode(originId, "UTF-8")}" +
            "&destinationStationGlobalId=${java.net.URLEncoder.encode(HOME_GLOBAL_ID, "UTF-8")}" +
            "&routingDateTime=${java.net.URLEncoder.encode(now, "UTF-8")}&routingDateTimeIsArrival=false"
        // Location-based transport: fresher than weather since it depends on where
        // you are, but doesn't need transit()'s 30s cadence since it's a journey
        // plan, not a live departure board.
        val journeys = getJson(url, cacheS = 240) as JSONArray
        if (journeys.length() == 0) throw RuntimeException("no route found")

        data class Leg(val line: String, val dest: String, val from: String, val fromPlatform: Int?,
                        val to: String, val toPlatform: Int?, val depAt: Long, val arrAt: Long)

        fun atMs(stop: JSONObject): Long =
            java.time.OffsetDateTime.parse(stop.getString("plannedDeparture")).toInstant().toEpochMilli()
        fun platformOf(stop: JSONObject): Int? =
            if (stop.has("platform") && !stop.isNull("platform")) stop.getInt("platform") else null

        var best: Pair<Long, List<Leg>>? = null
        for (i in 0 until journeys.length()) {
            val parts = journeys.getJSONObject(i).optJSONArray("parts") ?: continue
            val legs = mutableListOf<Leg>()
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                val line = part.optJSONObject("line") ?: continue
                val from = part.getJSONObject("from")
                val to = part.getJSONObject("to")
                legs.add(Leg(line.optString("label", "?"), line.optString("destination", ""),
                    from.optString("name", ""), platformOf(from),
                    to.optString("name", ""), platformOf(to), atMs(from), atMs(to)))
            }
            if (legs.isEmpty()) continue
            val duration = legs.last().arrAt - legs.first().depAt
            val current = best
            if (current == null || duration < current.first) best = duration to legs
        }
        val (durationMs, legs) = best ?: throw RuntimeException("no usable route")

        return JSONObject().apply {
            put("sameStation", false)
            put("origin", originName)
            put("destination", HOME_NAME)
            put("departAt", legs.first().depAt)
            put("arriveAt", legs.last().arrAt)
            put("durationMin", Math.round(durationMs / 60000.0))
            put("legs", JSONArray(legs.map { l ->
                JSONObject().apply {
                    put("line", l.line); put("dest", l.dest)
                    put("from", l.from); put("fromPlatform", l.fromPlatform ?: JSONObject.NULL)
                    put("to", l.to); put("toPlatform", l.toPlatform ?: JSONObject.NULL)
                    put("depAt", l.depAt); put("arrAt", l.arrAt)
                }
            }))
        }
    }

    fun metar(): JSONObject {
        if (AVWX_TOKEN.isBlank()) throw RuntimeException("no AVWX token set")
        val url = "https://avwx.rest/api/metar/$ICAO?format=json"
        val m = getJson(url, headers = mapOf("Authorization" to AVWX_TOKEN), cacheS = 600) as JSONObject
        val u = m.optJSONObject("units") ?: JSONObject()

        fun valNum(k: String): Double? {
            val v = m.optJSONObject(k) ?: return null
            return if (v.has("value") && !v.isNull("value")) v.getDouble("value") else null
        }

        val wd = valNum("wind_direction")
        val ws = valNum("wind_speed")
        val wind = if (wd != null) "${wd.toInt().toString().padStart(3, '0')}/${ws?.toInt() ?: "—"}${u.optString("wind_speed", "")}" else "—"
        val vis = valNum("visibility")?.let { "${it.toInt()}${u.optString("visibility", "")}" } ?: "—"
        val qnh = valNum("altimeter")?.let { "$it${u.optString("altimeter", "")}" } ?: "—"

        val clouds = JSONArray()
        m.optJSONArray("clouds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val repr = arr.optJSONObject(i)?.optString("repr")
                if (!repr.isNullOrEmpty()) clouds.put(repr)
            }
        }
        val wx = JSONArray()
        m.optJSONArray("wx_codes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val repr = if (item is JSONObject) item.optString("repr") else item?.toString()
                if (!repr.isNullOrEmpty()) wx.put(repr)
            }
        }

        return JSONObject().apply {
            put("rules", m.optString("flight_rules", "—"))
            put("summary", "wind $wind · vis $vis · QNH $qnh")
            put("raw", m.optString("raw", ""))
            put("temp", valNum("temperature") ?: JSONObject.NULL)
            put("dewpoint", valNum("dewpoint") ?: JSONObject.NULL)
            put("gust", valNum("wind_gust") ?: JSONObject.NULL)
            put("clouds", clouds)
            put("wx", wx)
            put("remarks", m.optString("remarks", ""))
        }
    }

    val PRAYERS = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    private fun prayerDay(cal: Calendar): JSONObject {
        val fmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        val url = "https://api.aladhan.com/v1/timings/${fmt.format(cal.time)}" +
            "?latitude=$currentLat&longitude=$currentLon&method=$PRAYER_METHOD"
        val data = getJson(url, cacheS = 3600) as JSONObject
        return data.getJSONObject("data").getJSONObject("timings")
    }

    fun prayer(): JSONObject {
        val today = Calendar.getInstance()
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val t1 = prayerDay(today)
        val t2 = prayerDay(tomorrow)

        val out = JSONArray()
        val timeRe = Regex("""(\d{1,2}):(\d{2})""")
        fun addNames(base: Calendar, times: JSONObject, names: List<String>) {
            for (n in names) {
                val raw = times.optString(n, "")
                val m = timeRe.find(raw) ?: continue
                val (h, mi) = m.destructured
                val at = (base.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, h.toInt()); set(Calendar.MINUTE, mi.toInt())
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                out.put(JSONObject().apply { put("name", n); put("at", at.timeInMillis) })
            }
        }
        addNames(today, t1, PRAYERS)
        addNames(tomorrow, t2, listOf("Fajr"))

        val nowMs = System.currentTimeMillis()
        var next: JSONObject? = null
        for (i in 0 until out.length()) {
            val p = out.getJSONObject(i)
            if (p.getLong("at") > nowMs) { next = p; break }
        }

        return JSONObject().apply {
            put("all", out)
            put("next", next ?: JSONObject.NULL)
        }
    }

    fun flights(): JSONObject {
        val lat = currentLat; val lon = currentLon
        val d = 0.7
        val url = "https://opensky-network.org/api/states/all?" +
            "lamin=${lat - d}&lomin=${lon - d * 1.4}&lamax=${lat + d}&lomax=${lon + d * 1.4}"
        val data = getJson(url, cacheS = 1200) as JSONObject
        val states = data.optJSONArray("states") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until states.length()) {
            val s = states.getJSONArray(i)
            if (s.isNull(5) || s.isNull(6)) continue
            val cs = s.optString(1, "").trim().ifEmpty { s.optString(0, "") }
            out.put(JSONObject().apply {
                put("cs", cs)
                put("lon", s.getDouble(5)); put("lat", s.getDouble(6))
                put("alt", if (s.isNull(7)) JSONObject.NULL else s.getDouble(7))
                put("ground", !s.isNull(8) && s.getBoolean(8))
                put("spd", if (s.isNull(9)) JSONObject.NULL else s.getDouble(9))
                put("hdg", if (s.isNull(10)) JSONObject.NULL else s.getDouble(10))
            })
        }
        return JSONObject().apply { put("count", out.length()); put("flights", out) }
    }

    fun config(): JSONObject = JSONObject().apply {
        put("city", currentCityName); put("lat", currentLat); put("lon", currentLon); put("icao", ICAO)
        put("home", HOME_NAME)
    }

    private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    // Flat-earth approximation is fine at these distances (a few hundred metres).
    // Nearest-station lookup isn't reliable for "am I at home": a station and its
    // adjacent bus stops are separate MVG globalIds only steps apart, so matching on
    // station id alone flips to the "away" branch even when standing on the platform.
    // Straight-line distance avoids that.
    private fun metersFromHome(): Double {
        val dLat = (currentLat - HOME_LAT) * 111_320.0
        val dLon = (currentLon - HOME_LON) * 111_320.0 * Math.cos(Math.toRadians(HOME_LAT))
        return Math.hypot(dLat, dLon)
    }

    /**
     * Notification-specific transit line, relative to the home stop: near it, only
     * HOME_LINES (the lines actually worth catching; all if empty) are useful; away
     * from it, the soonest departure elsewhere is meaningless and the route home is
     * what matters instead.
     */
    private fun transitSummaryLine(): String {
        if (metersFromHome() <= HOME_RADIUS_M) {
            val perLine = transit().getJSONArray("perLine")
            val matches = mutableListOf<String>()
            for (i in 0 until perLine.length()) {
                val r = perLine.getJSONObject(i)
                if (HOME_LINES.isEmpty() || r.getString("line") in HOME_LINES) {
                    matches.add("${r.getString("line")} → ${r.getString("dest")} ${hhmm(r.getLong("at"))}")
                }
            }
            val shown = if (HOME_LINES.isEmpty()) matches.take(3) else matches
            return if (shown.isNotEmpty()) shown.joinToString(" · ") else "nothing due at $HOME_NAME"
        }

        val rt = routeHome()
        if (rt.optBoolean("sameStation", false)) return "at $HOME_NAME"
        val legs = rt.getJSONArray("legs")
        if (legs.length() == 0) return "no route to $HOME_NAME"
        val chain = (0 until legs.length()).joinToString("→") { legs.getJSONObject(it).getString("line") }
        return "$chain to $HOME_NAME ${hhmm(rt.getLong("arriveAt"))} (${rt.getInt("durationMin")}m)"
    }
    /**
     * Short one-line summary for the lock-screen / AOD notification. Ordered by how
     * time-critical each fact is, since a locked/AOD screen often only has room for
     * the front of the string before it gets cut off: transit first (you need to
     * leave now or you don't), then weather with a rain alert taking priority over
     * the plain temperature, then the next prayer countdown. METAR trails last since
     * it's the least actionable of the four away from a cockpit.
     */
    fun shortSummary(): String {
        val parts = mutableListOf<String>()
        try { parts.add(transitSummaryLine()) } catch (_: Exception) { parts.add("transit —") }
        try {
            val w = weather()
            if (w.getBoolean("rainExpected")) {
                val inH = w.getInt("rainIn")
                val whenStr = if (inH <= 0) "now" else "in ${inH}h"
                parts.add("☔ rain $whenStr · ${w.getLong("temp")}°")
            } else {
                parts.add("${w.getLong("temp")}° ${w.getString("desc")}")
            }
        } catch (_: Exception) { parts.add("weather —") }
        try {
            val p = prayer()
            val next = p.opt("next")
            if (next is JSONObject) {
                val left = next.getLong("at") - System.currentTimeMillis()
                if (left > 0) {
                    val hh = left / 3600000
                    val mm = (left % 3600000) / 60000
                    val cd = if (hh > 0) "${hh}h${mm}m" else "${mm}m"
                    parts.add("${next.getString("name")} in $cd")
                }
            }
        } catch (_: Exception) { }
        try {
            parts.add(metar().getString("rules"))
        } catch (_: Exception) { }
        return if (parts.isEmpty()) "Sitrep — no data" else parts.joinToString("  ·  ")
    }
}
