package com.sitrep.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the summary service after a reboot, so the lock-screen/AOD summary comes back without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // fromBoot: start without the location FGS type (not allowed at boot on Android 14+).
            context.startForegroundService(
                Intent(context, SitrepMonitorService::class.java)
                    .putExtra(SitrepMonitorService.EXTRA_FROM_BOOT, true)
            )
        }
    }
}
