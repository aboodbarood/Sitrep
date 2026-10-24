package com.sitrep.ulm

import android.content.Context
import org.json.JSONObject

/**
 * Personal values that don't belong in the public source: the work stop and the
 * default medication list. They come from the git-ignored asset
 * `local_config.json` (see `local_config.example.json` in the project root);
 * without it the app uses the neutral defaults in SitrepApi / MedStore.
 *
 * Idempotent and cheap, so every entry point that needs these values (service,
 * alarm receivers — each may start in a fresh process) can simply call load().
 */
object LocalConfig {
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val o = try {
            context.assets.open("local_config.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: Exception) { return }

        o.optJSONObject("work")?.let { w ->
            try { SitrepApi.WORK = Place.fromJson(w) } catch (_: Exception) { }
        }
        o.optJSONArray("defaultMeds")?.let { a ->
            MedStore.defaultMeds = (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                Med(m.getString("id"), m.getString("name"), m.getString("time"), m.optBoolean("enabled", true))
            }
        }
    }
}
