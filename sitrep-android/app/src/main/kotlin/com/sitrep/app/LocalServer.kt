package com.sitrep.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loopback-only HTTP server standing in for sitrep.py's server. Serves the
 * (mostly unmodified) dashboard page from assets and answers the same
 * /api/... routes, backed by SitrepApi instead of a home-network proxy. The
 * page's existing fetch('/api/weather') etc. calls need no changes.
 */
// Bound to 127.0.0.1: NanoHTTPD(port) alone listens on every interface, which
// exposed the dashboard's /api/* to anyone on the same Wi-Fi.
class LocalServer(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val pageHtml: String by lazy {
        context.assets.open("index.html").use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when (uri) {
                "/", "/index.html" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml)
                "/api/config" -> jsonResponse(SitrepApi.config())
                // Test hook (loopback only): next screen-off tick in N s instead of 15 min.
                "/api/test/tick" -> {
                    val inS = session.parameters["inS"]?.firstOrNull()?.toLongOrNull() ?: 30L
                    SitrepMonitorService.tickIn(inS * 1000)
                    jsonResponse(JSONObject().put("ok", true))
                }
                "/api/weather" -> jsonResponse(SitrepApi.weather())
                "/api/transit" -> jsonResponse(SitrepApi.transit())
                "/api/route" -> jsonResponse(SitrepApi.routeHome())
                "/api/metar" -> jsonResponse(SitrepApi.metar())
                "/api/prayer" -> jsonResponse(SitrepApi.prayer())
                "/api/flights" -> jsonResponse(SitrepApi.flights())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            jsonResponse(JSONObject().apply { put("error", e.message ?: e.toString()) })
        }
    }

    private fun jsonResponse(body: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
}
