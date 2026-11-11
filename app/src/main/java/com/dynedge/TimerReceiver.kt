package com.dynedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Own-timer alarm (fires at zero) and the Stop action of the "Done" notification. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val c = context ?: return
        when (intent?.action) {
            TimerControl.ACTION_FIRE -> TimerControl.finish(c)
            TimerControl.ACTION_STOP -> TimerControl.stopAlarm(c)
        }
    }
}
