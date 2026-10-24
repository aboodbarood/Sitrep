package com.sitrep.ulm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the ~15-min inexact alarm SitrepMonitorService keeps armed, so the
 * lock-screen summary (and, in automatic mode, a Wi-Fi/cell position) stays
 * reasonably fresh while the screen is off. goAsync() keeps the broadcast alive
 * until the service has finished its refresh.
 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        SitrepMonitorService.backgroundTick { pending.finish() }
    }
}
