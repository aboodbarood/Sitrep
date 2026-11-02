package com.sitrep.ulm

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Ulm edition of the on-device sitrep backend. Weather is unchanged from the
 * Munich build; transit moves from MVG to DING (Donau-Iller-Nahverkehrsverbund,
 * covers Ulm/Neu-Ulm and the surrounding counties) via its public EFA endpoint.
 * Prayer times, OpenSky flights and the METAR/ATIS readout are gone.
 */
object SitrepApi {

    // ---- Fallback location (Ulm Münster) — used until a GPS fix arrives ----
    private const val DEFAULT_CITY_NAME = "Ulm"
    private const val DEFAULT_LAT = 48.3985
    private const val DEFAULT_LON = 9.9925

    // Live reference location — LocationTracker overwrites these as GPS fixes arrive.
    @Volatile var currentLat: Double = DEFAULT_LAT
    @Volatile var currentLon: Double = DEFAULT_LON
    @Volatile var currentCityName: String = DEFAULT_CITY_NAME

    @Volatile var cityFromGeocoder = false
    /** When the current position was measured (fix time), 0 = still the Ulm default. */
    @Volatile var locationAt: Long = 0
    /** Where the current position came from ("gps"/"network"/…) and its accuracy radius in m. */
    @Volatile var locationSource: String = ""
    @Volatile var locationAccuracyM: Float = 0f
    /** "auto" = periodic Wi-Fi/cell location; "manual" = only when the user taps refresh. */
    @Volatile var locMode: String = "auto"

    /** User-chosen destination for the route-home guide; loaded/saved by HomeStore. */
    @Volatile var home: Place? = null


    /**
     * Fixed work destination for the "Best way to work" widget. Neutral default
     * (Ulm Hauptbahnhof); the real one comes from the git-ignored local_config.json
     * via LocalConfig.
     */
    @Volatile var WORK = Place(
        "Ulm, Hauptbahnhof", 48.399121, 9.984181,
        "de:08421:1008", "Ulm, Hauptbahnhof", 48.399121, 9.984181,
    )

    private const val EFA_BASE = "https://www.ding.eu/ding3"
    private const val COORD_FMT = "WGS84[DD.ddddd]"
    // Within this distance of the destination station, the route guide is
    // pointless — the widget says "you're there" instead.
    private const val AT_DEST_RADIUS_M = 400.0
    private val TZ: ZoneId = ZoneId.of("Europe/Berlin")

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Hit from NanoHTTPD's worker threads and the notification loop at the same time.
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    private fun getJson(url: String, cacheS: Long = 0): Any {
        val now = System.currentTimeMillis()
        if (cacheS > 0) {
            cache[url]?.let { (ts, v) -> if (now - ts < cacheS * 1000) return v }
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (sitrep personal dashboard)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val parsed = JSONTokener(resp.body?.string() ?: "{}").nextValue()
            if (cacheS > 0) {
                // Keys include GPS coords and the current minute, so prune stale ones
                // or the map grows for as long as the service runs.
                if (cache.size > 200) cache.entries.removeIf { now - it.value.first > 3_600_000 }
                cache[url] = now to parsed
            }
            return parsed
        }
    }

    /** EFA call with outputFormat/coordOutputFormat preset; the builder handles encoding of ids like de:08421:1002. */
    private fun efa(endpoint: String, params: Map<String, String>, cacheS: Long): JSONObject {
        val b = "$EFA_BASE/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("outputFormat", "rapidJSON")
            .addQueryParameter("coordOutputFormat", COORD_FMT)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return getJson(b.build().toString(), cacheS) as JSONObject
    }

    // EFA wants lon:lat for input but returns [lat, lon]. Rounded to ~10 m so
    // small GPS jitter still hits the cache.
    private fun coordParam(lat: Double, lon: Double) =
        String.format(Locale.US, "%.4f:%.4f:%s", lon, lat, COORD_FMT)

    /** optString returns the literal "null" for JSON nulls; this doesn't. */
    private fun JSONObject?.str(key: String): String =
        if (this == null || !has(key) || isNull(key)) "" else optString(key, "").trim()

    private fun parseTime(s: String): Long? =
        if (s.isEmpty()) null else try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { null }

    private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Flat-earth approximation — fine at the few-km scale this is used for.
        val dLat = (lat2 - lat1) * 111_320.0
        val dLon = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        return Math.hypot(dLat, dLon)
    }

    // ------------------------------------------------------------------ weather

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
        // open-meteo's own source data only refreshes hourly, so a long cache is free.
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

    // ------------------------------------------------------------------ DING / EFA

    data class Stop(val id: String, val name: String, val lat: Double, val lon: Double,
                    val distance: Int, val locality: String)

    /** Real stops around a point, closest first. */
    private fun nearbyStops(lat: Double, lon: Double, radius: Int = 1000, max: Int = 6): List<Stop> {
        val data = efa("XML_COORD_REQUEST", mapOf(
            "coord" to coordParam(lat, lon), "inclFilter" to "1",
            "type_1" to "STOP", "radius_1" to "$radius", "max" to "$max",
        ), cacheS = 480) // matches LocationTracker's 8-min automatic update interval
        val locs = data.optJSONArray("locations") ?: return emptyList()
        val out = mutableListOf<Stop>()
        for (i in 0 until locs.length()) {
            val l = locs.getJSONObject(i)
            val id = l.str("id"); if (id.isEmpty()) continue
            val c = l.optJSONArray("coord") ?: continue
            val props = l.optJSONObject("properties")
            val name = props.str("STOP_NAME_WITH_PLACE").ifEmpty { l.str("name") }
            out.add(Stop(id, name, c.getDouble(0), c.getDouble(1),
                props?.optInt("distance", -1) ?: -1, l.optJSONObject("parent").str("name")))
        }
        return out.sortedBy { if (it.distance < 0) Int.MAX_VALUE else it.distance }
    }

    private fun lineLabel(t: JSONObject?): String {
        if (t == null) return "?"
        val cls = t.optJSONObject("product")?.optInt("class", -1) ?: -1
        val name = t.str("name")
        // Long-distance trains have an empty disassembledName and a bare train number
        // ("1060"); the first two words of the name give "ICE 1060" instead.
        if (cls in setOf(0, 14, 15, 16) && name.isNotEmpty()) return name.split(" ").take(2).joinToString(" ")
        return t.str("disassembledName").ifEmpty { t.str("number") }.ifEmpty { name }.ifEmpty { "?" }
    }

    // EFA product classes -> the handful of kinds the page colours differently.
    private fun modeOf(cls: Int): String = when (cls) {
        4 -> "TRAM"
        5, 6, 7, 17, 19 -> "BUS"
        0, 1, 13, 14, 15, 16 -> "TRAIN"
        in 97..100 -> "WALK"
        else -> "OTHER"
    }

    private fun isCancelled(o: JSONObject): Boolean {
        if (o.optBoolean("isCancelled", false)) return true
        val rs = o.optJSONArray("realtimeStatus") ?: return false
        for (i in 0 until rs.length()) if (rs.optString(i).contains("CANCEL")) return true
        return false
    }

    data class Dep(val line: String, val dest: String, val stop: String, val platform: String,
                   val at: Long, val delay: Long, val cancelled: Boolean, val type: String)

    private fun departures(stop: Stop): List<Dep> {
        val data = efa("XML_DM_REQUEST", mapOf(
            "name_dm" to stop.id, "type_dm" to "stop", "mode" to "direct",
            // 12 per stop covers the 12 merged rows shown; 20 was ~60% more JSON (10 → 6 KB gzipped).
            "useRealtime" to "1", "limit" to "12", "depType" to "stopEvents",
            "includeCompleteStopSeq" to "0",
        ), cacheS = 30)
        val events = data.optJSONArray("stopEvents") ?: return emptyList()
        val out = mutableListOf<Dep>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val planned = parseTime(e.str("departureTimePlanned"))
            val real = parseTime(e.str("departureTimeEstimated")) ?: planned ?: continue
            val t = e.optJSONObject("transportation")
            val loc = e.optJSONObject("location")
            val platform = loc?.optJSONObject("properties").str("platform")
            out.add(Dep(lineLabel(t), t?.optJSONObject("destination").str("name"), stop.name, platform,
                real, if (planned != null) Math.round((real - planned) / 60000.0) else 0,
                isCancelled(e), modeOf(t?.optJSONObject("product")?.optInt("class", -1) ?: -1)))
        }
        return out
    }

    private fun depJson(r: Dep) = JSONObject().apply {
        put("line", r.line); put("dest", r.dest); put("stop", r.stop); put("platform", r.platform)
        put("at", r.at); put("delay", r.delay); put("cancelled", r.cancelled); put("type", r.type)
    }

    /** Live departures from the stops nearest the current GPS position. */
    fun transit(): JSONObject {
        val stops = nearbyStops(currentLat, currentLon).take(3)
        if (stops.isEmpty()) throw RuntimeException("no stops within 1 km")
        if (!cityFromGeocoder && stops.first().locality.isNotEmpty()) currentCityName = stops.first().locality

        val rows = mutableListOf<Dep>()
        val errors = mutableListOf<String>()
        for (s in stops) {
            try { rows.addAll(departures(s)) } catch (e: Exception) { errors.add("${s.name}: ${e.message}") }
        }
        val nowMs = System.currentTimeMillis()
        val sorted = rows.sortedBy { it.at }.filter { it.at > nowMs - 60000 }.take(12)
        val seen = HashSet<String>()
        val perLine = sorted.filter { seen.add(it.line + "|" + it.dest) }

        return JSONObject().apply {
            put("next", JSONArray(sorted.take(8).map { depJson(it) }))
            put("perLine", JSONArray(perLine.take(6).map { depJson(it) }))
            put("station", stops.first().name)
            put("stops", JSONArray(stops.map { it.name }))
            put("errors", JSONArray(errors))
        }
    }

    // ------------------------------------------------------------------ route home

    data class Leg(val walk: Boolean, val line: String, val type: String, val dest: String,
                   val from: String, val fromPlatform: String, val to: String, val toPlatform: String,
                   val depAt: Long, val arrAt: Long, val delay: Long, val cancelled: Boolean)

    private data class Journey(val legs: List<Leg>, val interchanges: Int) {
        val depAt get() = legs.first().depAt
        val arrAt get() = legs.last().arrAt
        val cancelled get() = legs.any { it.cancelled }
        val rideLines get() = legs.filter { !it.walk }.map { it.line }
        /** First bus/tram/train leg — what the route widgets show, instead of the walk before it. */
        val firstRide get() = legs.firstOrNull { !it.walk }
        /** When the first vehicle leaves; for an all-walking journey, when you'd set off. */
        val rideDepAt get() = firstRide?.depAt ?: depAt
    }

    private fun parseJourney(j: JSONObject): Journey? {
        val parts = j.optJSONArray("legs") ?: return null
        val legs = mutableListOf<Leg>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val t = p.optJSONObject("transportation")
            val cls = t?.optJSONObject("product")?.optInt("class", -1) ?: -1
            if (cls == 97) continue // "stay seated" pseudo-leg, not an actual step
            val o = p.optJSONObject("origin") ?: continue
            val d = p.optJSONObject("destination") ?: continue
            val depPlanned = parseTime(o.str("departureTimePlanned"))
            val dep = parseTime(o.str("departureTimeEstimated")) ?: depPlanned ?: continue
            val arr = parseTime(d.str("arrivalTimeEstimated")) ?: parseTime(d.str("arrivalTimePlanned")) ?: continue
            val walk = modeOf(cls) == "WALK"
            // Same-stop changes come back as a 0-minute walk (e.g. Rathaus → Rathaus); not a real step.
            if (walk && arr - dep < 30_000) continue
            legs.add(Leg(walk, if (walk) "walk" else lineLabel(t), modeOf(cls),
                t?.optJSONObject("destination").str("name"),
                o.str("name"), o.optJSONObject("properties").str("platformName"),
                d.str("name"), d.optJSONObject("properties").str("platformName"),
                dep, arr, if (depPlanned != null) Math.round((dep - depPlanned) / 60000.0) else 0,
                isCancelled(p)))
        }
        if (legs.isEmpty()) return null
        return Journey(legs, j.optInt("interchanges", legs.count { !it.walk } - 1))
    }

    fun routeHome(): JSONObject = routeTo(home ?: throw RuntimeException("no home station set"))

    fun routeWork(): JSONObject = routeTo(WORK)

    /**
     * DING trip plan from the live GPS position (as a coordinate, so EFA includes
     * the walk to the first stop) to a destination station. Picks the journey
     * that arrives earliest — not the shortest ride, which could mean waiting
     * 40 min for a slightly faster bus.
     */
    private fun routeTo(h: Place): JSONObject {
        val base = JSONObject().apply {
            put("destination", h.stationName)
            put("address", h.address)
            put("stationToAddressM", Math.round(metersBetween(h.stationLat, h.stationLon, h.lat, h.lon)))
        }
        val lat = currentLat; val lon = currentLon
        if (metersBetween(lat, lon, h.stationLat, h.stationLon) <= AT_DEST_RADIUS_M) {
            return base.put("atDest", true)
        }

        val now = ZonedDateTime.now(TZ)
        val data = efa("XML_TRIP_REQUEST2", mapOf(
            "type_origin" to "coord", "name_origin" to coordParam(lat, lon),
            "type_destination" to "stop", "name_destination" to h.stationId,
            "itdDate" to now.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            "itdTime" to now.format(DateTimeFormatter.ofPattern("HHmm")),
            "itdTripDateTimeDepArr" to "dep", "useRealtime" to "1",
            // 5 trips = the best one plus a few *later* options for tap-to-expand.
            // genC/genP=0 drop the path geometry EFA includes by default, which the
            // app never draws: 62 → 27 KB gzipped on the wire, 316 → 202 KB of JSON
            // to parse per request, with every field the app reads unchanged.
            "calcNumberOfTrips" to "5", "useProxFootSearch" to "1",
            "genC" to "0", "genP" to "0",
        ), cacheS = 60) // realtime-aware, so fresher than departures' neighbours but not every tick
        val arr = data.optJSONArray("journeys") ?: throw RuntimeException("no route found")
        val nowMs = System.currentTimeMillis()
        // Judged by when the vehicle leaves, not the walk start: if you're already
        // at the stop, a bus leaving now is still catchable.
        val journeys = (0 until arr.length()).mapNotNull { parseJourney(arr.getJSONObject(it)) }
            .filter { it.rideDepAt > nowMs - 60_000 }
        val usable = journeys.filter { !it.cancelled }
        val best = usable.minWithOrNull(compareBy<Journey>({ it.arrAt }, { it.interchanges }))
            ?: throw RuntimeException(if (journeys.isEmpty()) "no route found" else "all connections cancelled")
        val later = usable.filter { it !== best && it.rideDepAt > best.rideDepAt }
            .sortedBy { it.rideDepAt }.take(3)

        return base.apply {
            put("atDest", false)
            put("best", journeyJson(best))
            put("later", JSONArray(later.map { journeyJson(it) }))
        }
    }

    /**
     * What a route widget shows for one option: the lines to take and the first
     * vehicle's departure (line, stop, platform, time, delay), plus arrival.
     * Deliberately no walking legs or door-to-door durations.
     */
    private fun journeyJson(j: Journey): JSONObject = JSONObject().apply {
        val r = j.firstRide
        put("walkOnly", r == null)
        put("depAt", j.rideDepAt)
        put("arriveAt", j.arrAt)
        put("lines", JSONArray(j.legs.filter { !it.walk }.map { JSONObject().put("line", it.line).put("type", it.type) }))
        if (r != null) {
            put("from", r.from); put("platform", r.fromPlatform); put("dest", r.dest)
            put("delay", r.delay)
        }
    }

    // ------------------------------------------------------------------ home setup

    /** Address / stop / POI search for the home-address field. */
    fun geocode(q: String): JSONObject {
        if (q.isBlank()) throw RuntimeException("empty search")
        val data = efa("XML_STOPFINDER_REQUEST", mapOf("type_sf" to "any", "name_sf" to q.trim()), cacheS = 3600)
        val locs = data.optJSONArray("locations") ?: JSONArray()
        // EFA searches all of Baden-Württemberg and ranks "Stuttgart, Olgastraße 60"
        // above Ulm's even for "Olgastraße 60 Ulm". Put matches near the current
        // position (Ulm by default) first, then EFA's own match quality.
        data class Hit(val o: JSONObject, val local: Boolean, val quality: Int)
        val hits = mutableListOf<Hit>()
        for (i in 0 until locs.length()) {
            val l = locs.getJSONObject(i)
            val c = l.optJSONArray("coord") ?: continue
            val lat = c.getDouble(0); val lon = c.getDouble(1)
            hits.add(Hit(JSONObject().apply {
                put("id", l.str("id")); put("name", l.str("name")); put("type", l.str("type"))
                put("lat", lat); put("lon", lon)
            }, metersBetween(currentLat, currentLon, lat, lon) < 30_000, l.optInt("matchQuality", 0)))
        }
        val sorted = hits.sortedWith(compareBy<Hit>({ !it.local }, { -it.quality })).take(8)
        return JSONObject().put("results", JSONArray(sorted.map { it.o }))
    }

    /** Candidate home stations around a chosen address. */
    fun stopsNear(lat: Double, lon: Double): JSONObject {
        val stops = nearbyStops(lat, lon, radius = 1500, max = 8)
        return JSONObject().put("stops", JSONArray(stops.map { s ->
            JSONObject().apply {
                put("id", s.id); put("name", s.name); put("lat", s.lat); put("lon", s.lon)
                put("distance", if (s.distance >= 0) s.distance else Math.round(metersBetween(lat, lon, s.lat, s.lon)))
            }
        }))
    }

    fun config(): JSONObject = JSONObject().apply {
        put("city", currentCityName); put("lat", currentLat); put("lon", currentLon)
        put("home", home?.toJson() ?: JSONObject.NULL)
        put("work", WORK.toJson())
        put("locMode", locMode)
        put("locAt", locationAt)
        put("locSrc", locationSource)
        put("locAccM", Math.round(locationAccuracyM))
    }

    // ------------------------------------------------------------------ medication

    private fun doseJson(d: Dose) = JSONObject().apply {
        put("id", d.med.id); put("name", d.med.name); put("time", d.med.time)
        put("date", d.date.toString()); put("at", d.atMs); put("taken", MedStore.isTaken(d.med.id, d.date))
    }

    /** Schedule, today's doses with taken-marks, and the next dose, for the widget and its editor. */
    fun meds(): JSONObject = JSONObject().apply {
        put("lead", MedStore.leadMin)
        put("silent", MedStore.silent)
        put("meds", JSONArray(MedStore.meds.map { it.toJson() }))
        put("today", JSONArray(MedStore.todayDoses().map { doseJson(it) }))
        put("next", MedStore.nextDose()?.let { doseJson(it) } ?: JSONObject.NULL)
    }

    private fun medSummary(d: Dose): String {
        val left = d.atMs - System.currentTimeMillis()
        val cd = when {
            left <= 0 -> "due now"
            left < 3_600_000 -> "in ${left / 60000 + 1}m"
            else -> "in ${left / 3_600_000}h${(left % 3_600_000) / 60000}m"
        }
        return "💊 ${d.med.name} ${d.med.time} ($cd)"
    }

    // ------------------------------------------------------------------ notification

    private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    private fun transitSummaryLine(): String {
        val h = home
        val rt = if (h != null) routeHome() else null
        if (rt != null && !rt.getBoolean("atDest")) {
            // Same facts as the "Best way home" widget: first departure, no walking time.
            val b = rt.getJSONObject("best")
            if (b.getBoolean("walkOnly")) return "🏠 walking distance"
            val lines = b.getJSONArray("lines")
            val chain = (0 until lines.length()).joinToString("→") { lines.getJSONObject(it).getString("line") }
            return "🏠 $chain ${hhmm(b.getLong("depAt"))} ${b.getString("from")}, home ${hhmm(b.getLong("arriveAt"))}"
        }
        // At home (or no home set): what's leaving from the nearest stops.
        val perLine = transit().getJSONArray("perLine")
        val parts = (0 until minOf(3, perLine.length())).map { i ->
            val r = perLine.getJSONObject(i)
            "${r.getString("line")}→${r.getString("dest")} ${hhmm(r.getLong("at"))}"
        }
        return if (parts.isEmpty()) "no departures nearby" else parts.joinToString(" · ")
    }

    /**
     * One-line lock-screen summary, most time-critical first since the end of the
     * string is what gets cut off: the next medication jumps to the front when it's
     * within the hour (or due), then transit (route home, or departures when at
     * home), then medication otherwise, then weather.
     */
    fun shortSummary(): String {
        val parts = mutableListOf<String>()
        val dose = MedStore.nextDose()
        val doseUrgent = dose != null && dose.atMs - System.currentTimeMillis() < 3_600_000
        if (dose != null && doseUrgent) parts.add(medSummary(dose))
        try { parts.add(transitSummaryLine()) } catch (e: Exception) { parts.add("transit — ${e.message}") }
        if (dose != null && !doseUrgent) parts.add(medSummary(dose))
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
        return parts.joinToString("  ·  ")
    }
}
