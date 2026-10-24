package com.sitrep.ulm

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Serves the dashboard page from assets and answers its /api/... calls, backed
 * by SitrepApi. Bound to 127.0.0.1 explicitly — NanoHTTPD(port) alone listens on
 * every interface, which would expose the home address to anyone on the same Wi-Fi.
 */
class LocalServer(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val pageHtml: String by lazy {
        context.assets.open("index.html").use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        fun param(k: String) = session.parameters[k]?.firstOrNull() ?: ""
        return try {
            when (uri) {
                "/", "/index.html" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml)
                "/api/config" -> jsonResponse(SitrepApi.config())
                "/api/weather" -> jsonResponse(SitrepApi.weather())
                "/api/transit" -> jsonResponse(SitrepApi.transit())
                "/api/route" -> jsonResponse(SitrepApi.routeHome())
                "/api/route_work" -> jsonResponse(SitrepApi.routeWork())
                // Test hook (loopback only): next screen-off tick in N s instead of 15 min.
                "/api/test/tick" -> {
                    SitrepMonitorService.tickIn((param("inS").toLongOrNull() ?: 30L) * 1000)
                    jsonResponse(JSONObject().put("ok", true))
                }
                "/api/settings" -> {
                    if (session.method == Method.POST) {
                        val b = JSONObject(postBody(session))
                        if (b.has("locMode")) {
                            LocationTracker.saveMode(context, b.getString("locMode"))
                            SitrepMonitorService.applyLocationMode()
                        }
                    }
                    jsonResponse(SitrepApi.config())
                }
                "/api/refresh" -> {
                    // Blocks this NanoHTTPD worker for up to ~20 s while a fix is taken;
                    // the page shows a spinner meanwhile. Returns the updated config.
                    val ok = kotlinx.coroutines.runBlocking { SitrepMonitorService.refreshLocation() }
                    jsonResponse(SitrepApi.config().put("refreshed", ok))
                }
                "/api/meds" -> {
                    if (session.method == Method.POST) {
                        MedStore.save(context, JSONObject(postBody(session)))
                        MedAlarms.scheduleAll(context)
                        SitrepMonitorService.requestRefresh()
                    }
                    jsonResponse(SitrepApi.meds())
                }
                "/api/meds/taken" -> {
                    if (session.method == Method.POST) {
                        val b = JSONObject(postBody(session))
                        val id = b.getString("id")
                        MedStore.markTaken(context, id, java.time.LocalDate.parse(b.getString("date")))
                        // Silence a ringing alarm for it too.
                        context.getSystemService(android.app.NotificationManager::class.java).cancel(MedAlarms.notifId(id))
                        SitrepMonitorService.requestRefresh()
                    }
                    jsonResponse(SitrepApi.meds())
                }
                "/api/geocode" -> jsonResponse(SitrepApi.geocode(param("q")))
                "/api/stops_near" -> jsonResponse(SitrepApi.stopsNear(param("lat").toDouble(), param("lon").toDouble()))
                "/api/home" -> {
                    if (session.method == Method.POST) {
                        HomeStore.save(context, Place.fromJson(JSONObject(postBody(session))))
                    }
                    jsonResponse(SitrepApi.config())
                }
                "/api/home/clear" -> {
                    if (session.method == Method.POST) HomeStore.clear(context)
                    jsonResponse(SitrepApi.config())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            jsonResponse(JSONObject().apply { put("error", e.message ?: e.toString()) })
        }
    }

    /**
     * Raw body, always decoded as UTF-8. NanoHTTPD's parseBody() uses the request's
     * declared charset and falls back to US-ASCII, which turns "ß"/"ä" in an address
     * or medication name into "??" whenever a client omits "; charset=utf-8".
     */
    private fun postBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return "{}"
        val buf = ByteArray(len)
        var off = 0
        val input = session.inputStream
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    // Explicit charset so no client has to guess (EFA names are full of umlauts).
    private fun jsonResponse(body: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())
}
