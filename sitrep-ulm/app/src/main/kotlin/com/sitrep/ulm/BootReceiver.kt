package com.sitrep.ulm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or app update: restart the summary service and re-arm the
 * medication alarms (AlarmManager forgets everything on reboot). A clock or
 * timezone change only needs the alarms re-armed, since they're wall-clock based.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MedAlarms.scheduleAll(context)
                context.startForegroundService(
                    Intent(context, SitrepMonitorService::class.java)
                        .putExtra(SitrepMonitorService.EXTRA_FROM_BOOT, true)
                )
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> MedAlarms.scheduleAll(context)
        }
    }
}
