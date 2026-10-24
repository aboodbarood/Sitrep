package com.sitrep.ulm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Real Android alarms for medication reminders, independent of the page and the
 * summary loop: they fire with the app closed, after a reboot (BootReceiver
 * reschedules), and through Doze (setExactAndAllowWhileIdle). Each enabled med
 * has one pending alarm at (dose time − lead); when it fires it rings, then
 * schedules the next day's.
 */
object MedAlarms {
    const val CHANNEL_ID = "sitrep_med_alarm"
    const val EXTRA_ID = "medId"
    const val EXTRA_DATE = "doseDate"
    const val ACTION_TAKEN = "com.sitrep.ulm.MED_TAKEN"
    const val ACTION_SNOOZE = "com.sitrep.ulm.MED_SNOOZE"
    private const val SNOOZE_MIN = 5L
    private const val KEY_SCHEDULED = "scheduledIds"

    private fun reqCode(id: String) = id.hashCode() and 0x7fff_ffff
    fun notifId(id: String) = 1000 + (id.hashCode() and 0xffff)

    private fun alarmIntent(ctx: Context, id: String, date: LocalDate, snooze: Boolean) =
        PendingIntent.getBroadcast(
            ctx, reqCode(id) xor (if (snooze) 0x4000_0000 else 0),
            Intent(ctx, MedAlarmReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_DATE, date.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun setExact(ctx: Context, atMs: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // USE_EXACT_ALARM is granted at install on API 33+; below 31 exact alarms need
        // nothing. The inexact fallback only matters if a ROM revokes it anyway.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    /** Cancels everything previously scheduled and schedules the next reminder for each enabled med. */
    fun scheduleAll(ctx: Context) {
        MedStore.load(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("sitrep_meds", Context.MODE_PRIVATE)
        for (old in prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()) {
            am.cancel(alarmIntent(ctx, old, LocalDate.now(), false))
        }
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val lead = MedStore.leadMin.toLong()
        val ids = HashSet<String>()
        for (m in MedStore.meds.filter { it.enabled }) {
            // Walk forward from the dose, not the reminder: for a 00:00 dose the
            // reminder is 23:50 the day before, and "today's reminder + 1 day" landed
            // on a time already past. A past exact alarm fires at once, rang, and
            // rescheduled itself into the past again — which also overrode Snooze.
            var dose = now.toLocalDate().atTime(m.localTime).atZone(now.zone)
            while (!dose.minusMinutes(lead).isAfter(now)) dose = dose.plusDays(1)
            val remindAt = dose.minusMinutes(lead)
            val doseDate = dose.toLocalDate()
            setExact(ctx, remindAt.toInstant().toEpochMilli(), alarmIntent(ctx, m.id, doseDate, false))
            ids.add(m.id)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, ids).apply()
    }

    fun snooze(ctx: Context, id: String, date: LocalDate) {
        setExact(ctx, System.currentTimeMillis() + SNOOZE_MIN * 60_000, alarmIntent(ctx, id, date, true))
    }

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Medication alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings before each daily medication time"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true) // only honoured if the user allows it for this channel
        }
        mgr.createNotificationChannel(channel)
    }

    fun ring(ctx: Context, med: Med, date: LocalDate) {
        ensureChannel(ctx)
        val doseAt = date.atTime(med.localTime)
        val hhmm = med.time
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, reqCode(med.id) xor code,
            Intent(ctx, MedActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ID, med.id).putExtra(EXTRA_DATE, date.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Round up: an alarm delivered a few seconds late should still say "10 min", not "9".
        val secs = java.time.Duration.between(java.time.LocalDateTime.now(), doseAt).seconds.coerceAtLeast(0)
        val mins = (secs + 59) / 60
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("💊 ${med.name} at $hhmm")
            .setContentText(if (mins > 0) "Take it in $mins min" else "Take it now")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(false)
            .addAction(0, "Taken", action(ACTION_TAKEN, 0x1000_0000))
            .addAction(0, "Snooze $SNOOZE_MIN min", action(ACTION_SNOOZE, 0x2000_0000))
            .build()
        // Keep ringing until it's acknowledged, like an alarm clock rather than a ping.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        ctx.getSystemService(NotificationManager::class.java).notify(notifId(med.id), n)
    }
}

/** Fires at (dose − lead) or after a snooze: rings unless already taken, then queues tomorrow's. */
class MedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        MedStore.load(ctx)
        val id = intent.getStringExtra(MedAlarms.EXTRA_ID) ?: return
        val date = intent.getStringExtra(MedAlarms.EXTRA_DATE)?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val med = MedStore.meds.firstOrNull { it.id == id && it.enabled }
        if (med != null && !MedStore.isTaken(id, date)) MedAlarms.ring(ctx, med, date)
        MedAlarms.scheduleAll(ctx)
    }
}

/** "Taken" / "Snooze" buttons on the alarm notification. */
class MedActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(MedAlarms.EXTRA_ID) ?: return
        val date = intent.getStringExtra(MedAlarms.EXTRA_DATE)?.let { LocalDate.parse(it) } ?: LocalDate.now()
        MedStore.load(ctx)
        when (intent.action) {
            MedAlarms.ACTION_TAKEN -> MedStore.markTaken(ctx, id, date)
            MedAlarms.ACTION_SNOOZE -> MedAlarms.snooze(ctx, id, date)
        }
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(MedAlarms.notifId(id))
        SitrepMonitorService.requestRefresh()
    }
}
