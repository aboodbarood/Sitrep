package com.sitrep.ulm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** One daily dose: a name and a local time of day ("08:00"). */
data class Med(val id: String, val name: String, val time: String, val enabled: Boolean) {
    val localTime: LocalTime get() = LocalTime.parse(time)
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("time", time); put("enabled", enabled)
    }
}

/** A concrete dose on a given day, which is what "taken" is recorded against. */
data class Dose(val med: Med, val date: LocalDate, val at: ZonedDateTime) {
    val atMs get() = at.toInstant().toEpochMilli()
}

/**
 * Daily medication schedule + per-day "taken" marks, in SharedPreferences.
 * Loaded into memory by whoever needs it (service, alarm receivers — each may
 * run in a fresh process), read by SitrepApi for the widget and notification.
 */
object MedStore {
    private const val PREFS = "sitrep_meds"
    const val DEFAULT_LEAD_MIN = 10
    // After the dose time, a dose stays "due" (red, with a Taken button) this long
    // before the widget moves on to the next one.
    private const val DUE_WINDOW_MIN = 120L

    /**
     * Used until the user saves their own list in the 💊 editor (e.g. on a fresh
     * install). Neutral example here; personal defaults come from the git-ignored
     * local_config.json via LocalConfig.
     */
    @Volatile var defaultMeds: List<Med> = listOf(
        Med("default-example", "Example medication", "08:00", true),
    )

    @Volatile var meds: List<Med> = emptyList()
    @Volatile var leadMin: Int = DEFAULT_LEAD_MIN
    /** Reminders still post, but without sound, vibration or repeat. */
    @Volatile var silent: Boolean = false
    private val taken = HashSet<String>()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun takenKey(id: String, date: LocalDate) = "$date|$id"

    fun load(context: Context) {
        LocalConfig.load(context) // personal defaults, if present
        val p = prefs(context)
        // No saved list yet → defaults. An explicitly saved empty list stays empty.
        meds = if (!p.contains("meds")) defaultMeds else try {
            val arr = JSONArray(p.getString("meds", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Med(o.getString("id"), o.getString("name"), o.getString("time"), o.optBoolean("enabled", true))
            }
        } catch (_: Exception) { emptyList() }
        leadMin = p.getInt("lead", DEFAULT_LEAD_MIN)
        silent = p.getBoolean("silent", false)
        synchronized(taken) {
            taken.clear()
            taken.addAll(p.getStringSet("taken", emptySet()) ?: emptySet())
        }
    }

    /** Replaces the whole schedule from the page's editor; ids are kept where given so taken-marks survive edits. */
    fun save(context: Context, body: JSONObject) {
        val arr = body.optJSONArray("meds") ?: JSONArray()
        val list = mutableListOf<Med>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "").trim()
            val time = o.optString("time", "").trim()
            if (name.isEmpty()) continue
            try { LocalTime.parse(time) } catch (_: Exception) { throw RuntimeException("bad time for $name: '$time'") }
            list.add(Med(o.optString("id", "").ifEmpty { UUID.randomUUID().toString() }, name, time, o.optBoolean("enabled", true)))
        }
        val lead = body.optInt("lead", DEFAULT_LEAD_MIN).coerceIn(0, 120)
        val quiet = body.optBoolean("silent", silent)
        prefs(context).edit()
            .putString("meds", JSONArray(list.map { it.toJson() }).toString())
            .putInt("lead", lead)
            .putBoolean("silent", quiet)
            .apply()
        meds = list; leadMin = lead; silent = quiet
    }

    fun isTaken(id: String, date: LocalDate) = synchronized(taken) { takenKey(id, date) in taken }

    fun markTaken(context: Context, id: String, date: LocalDate) {
        val set = synchronized(taken) {
            taken.add(takenKey(id, date))
            // Only the last few days matter; keep the stored set from growing forever.
            val cutoff = LocalDate.now().minusDays(3).toString()
            taken.removeAll { it.substringBefore('|') < cutoff }
            HashSet(taken)
        }
        prefs(context).edit().putStringSet("taken", set).apply()
    }

    /**
     * The dose this med is "about" right now: today's if it's still upcoming or
     * recently due and untaken, otherwise tomorrow's.
     */
    fun currentDose(m: Med, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Dose {
        val today = now.toLocalDate()
        val atToday = today.atTime(m.localTime).atZone(now.zone)
        val stillRelevant = now.isBefore(atToday) ||
            (!isTaken(m.id, today) && now.isBefore(atToday.plusMinutes(DUE_WINDOW_MIN)))
        return if (stillRelevant) Dose(m, today, atToday)
        else { val d = today.plusDays(1); Dose(m, d, d.atTime(m.localTime).atZone(now.zone)) }
    }

    /** Next dose across all enabled meds (a due-but-untaken one sorts first since its time has passed). */
    fun nextDose(): Dose? = meds.filter { it.enabled }.map { currentDose(it) }.minByOrNull { it.atMs }

    /** Today's full list for the expanded widget. */
    fun todayDoses(): List<Dose> {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return meds.filter { it.enabled }
            .map { Dose(it, now.toLocalDate(), now.toLocalDate().atTime(it.localTime).atZone(now.zone)) }
            .sortedBy { it.atMs }
    }
}
