package com.dynedge

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * "Pause in sensitive apps". Banking / UPI apps refuse to open while a third-party accessibility service is
 * enabled, so EdgeService switches itself off (through [A11yGate]) on the first window of a paused app, and
 * this object switches it back on once that app has left the front.
 *
 * While paused (and only then) it polls UsageStatsManager.queryEvents every 700 ms on the main looper. The
 * process stays alive because MediaListener (a bound NotificationListenerService) lives in it. Without
 * notification access the process may die, so the fallback is an AlarmManager chain every 2 s to
 * [GateReceiver] (which also gets BOOT_COMPLETED). Screen off stops the polling (the alarm path drops to one
 * non-wakeup alarm every 30 s so a dead process is still revived); screen on / user present checks at once
 * and resumes watching. The [Prefs.KEY_PAUSED_BY_GATE] flag survives process death and reboot, so
 * the overlay is never left off for good. Idle cost when not paused: zero (no handler, no receiver, no alarm).
 */
object PauseGate {
    private const val TAG = "DynEdgeGate"
    private const val POLL = 700L
    private const val ALARM = 2000L
    private const val ALARM_OFF = 30000L
    /** Events this long before the pause still count (the paused app's own resume may be just before it). */
    private const val SLACK = 500L
    /** Overlap between two queries so an event written late is not missed. */
    private const val OVERLAP = 200L

    private val h = Handler(Looper.getMainLooper())

    // Main thread only.
    private var app: Context? = null
    private var active = false
    private var polling = false
    private var fg: String? = null
    private var lastQuery = 0L
    private var paused: Set<String> = emptySet()
    private var rxOn = false
    private var listenerUp = false

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            if (check()) return
            h.postDelayed(this, POLL)
        }
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) sleep() else if (!check()) watch()
        }
    }

    /** The gate can work: it can write the accessibility list and read the foreground app to come back. */
    fun ready(c: Context): Boolean = A11yGate.canWrite(c) && RecentApps.hasAccess(c)

    /** The packages EdgeService pauses in (empty when the master switch is off). */
    fun watchSet(c: Context): Set<String> = if (Prefs.pauseOn(c)) Prefs.pauseApps(c) else emptySet()

    fun isPaused(c: Context): Boolean = Prefs.prefs(c).getBoolean(Prefs.KEY_PAUSED_BY_GATE, false)

    /**
     * EdgeService saw a window of [pkg] (a paused app): mark the pause, remove our accessibility entry
     * (the overlay goes away with the service), then watch for the app to leave. False if nothing changed.
     */
    fun pause(c: Context, pkg: String): Boolean {
        val a = c.applicationContext
        if (!ready(a)) return false
        // flag first: whatever happens next, the overlay is brought back later
        Prefs.prefs(a).edit()
            .putBoolean(Prefs.KEY_PAUSED_BY_GATE, true)
            .putString(Prefs.KEY_PAUSED_PKG, pkg)
            .putLong(Prefs.KEY_PAUSED_AT, System.currentTimeMillis())
            .apply()
        if (!A11yGate.set(a, false)) {
            clearFlag(a)
            return false
        }
        Log.i(TAG, "paused for $pkg")
        h.post { begin(a) }
        return true
    }

    /** EdgeService connected (by us or by the user): any pause is over. */
    fun onServiceUp(c: Context) {
        if (active) stop()
        if (isPaused(c)) clearFlag(c.applicationContext)
    }

    /** Our settings screen came to the front: the overlay should be on there. */
    fun resumeNow(c: Context) {
        if (isPaused(c)) resume(c.applicationContext)
    }

    fun onListenerConnected(c: Context) {
        listenerUp = true
        val a = c.applicationContext
        if (!isPaused(a)) return
        if (!active) begin(a)
        if (!check()) watch()
    }

    fun onListenerDisconnected() {
        listenerUp = false
        if (polling) {
            polling = false
            h.removeCallbacks(tick)
            watch()
        }
    }

    /** From [GateReceiver]: the 2 s alarm or boot. */
    fun onAlarm(c: Context) {
        val a = c.applicationContext
        if (!isPaused(a)) return
        if (!active) begin(a)
        if (!check()) watch()
    }

    /** The newest app that came to the front in the last [lookbackMs], not counting us; null if unknown. */
    fun frontApp(c: Context, lookbackMs: Long): String? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return try {
            val ev = usm.queryEvents(now - lookbackMs, now) ?: return null
            val e = UsageEvents.Event()
            var out: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != c.packageName) out = e.packageName
            }
            out
        } catch (ex: SecurityException) {
            null
        }
    }

    // ---------------------------------------------------------------- internals (main thread)

    private fun begin(a: Context) {
        app = a
        active = true
        paused = watchSet(a)
        val sp = Prefs.prefs(a)
        fg = sp.getString(Prefs.KEY_PAUSED_PKG, null)
        val at = sp.getLong(Prefs.KEY_PAUSED_AT, 0L)
        lastQuery = if (at > 0L) at - SLACK else System.currentTimeMillis() - SLACK
        if (!rxOn) {
            try {
                a.registerReceiver(rx, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                })
                rxOn = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "screen receiver failed", e)
            }
        }
        watch()
    }

    /** Polls (listener alive) or arms the alarm (no listener), only while the screen is on. */
    private fun watch() {
        val a = app ?: return
        if (!active) return
        val pm = a.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            // screen off: no polling. Without the listener the process may die, so leave a slow,
            // non-wakeup alarm that fires on the next wake and keeps the chain alive.
            if (!listenerUp) scheduleAlarm(a, ALARM_OFF)
            return
        }
        if (listenerUp) {
            cancelAlarm(a)
            if (!polling) {
                polling = true
                h.removeCallbacks(tick)
                h.postDelayed(tick, POLL)
            }
        } else {
            scheduleAlarm(a)
        }
    }

    private fun sleep() {
        polling = false
        h.removeCallbacks(tick)
        val a = app ?: return
        if (listenerUp) cancelAlarm(a) else if (active) scheduleAlarm(a, ALARM_OFF)
    }

    /** One look at the front app. True when the pause is over (re-enabled or no longer paused). */
    private fun check(): Boolean {
        val a = app ?: return true
        if (!isPaused(a)) {
            stop()
            return true
        }
        refresh(a)
        val f = fg
        if (f == null || (f !in paused && isRealApp(a, f))) {
            resume(a)
            return true
        }
        return false
    }

    /**
     * True when [pkg] is an app the user actually switched to (it has a launcher entry, or it is the home
     * screen) and not a system screen shown on top of the paused app: the fingerprint prompt (SystemUI),
     * permission dialogs, Google sign-in / SMS verification (Play services), the keyboard. Those keep the
     * pause, otherwise the bank sees the service again right after the fingerprint check.
     */
    private fun isRealApp(a: Context, pkg: String): Boolean {
        if (pkg in SYSTEM_OVERLAYS) return false
        val pm = a.packageManager
        return try {
            if (pm.getLaunchIntentForPackage(pkg) != null) return true
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            home?.activityInfo?.packageName == pkg
        } catch (e: RuntimeException) {
            false
        }
    }

    private val SYSTEM_OVERLAYS = setOf(
        "android", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.google.android.gms", "com.android.phone",
        "com.android.server.telecom", "com.google.android.inputmethod.latin", "com.android.inputmethod.latin",
        "com.samsung.android.biometrics.app.setting", "com.android.settings.intelligence"
    )

    /** Reads the usage events since the last look and keeps the newest app that came to the front (not us). */
    private fun refresh(a: Context) {
        val usm = a.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        try {
            val ev = usm.queryEvents(lastQuery - OVERLAP, now) ?: return
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != a.packageName) fg = e.packageName
            }
            lastQuery = now
        } catch (ex: SecurityException) {
            // usage access revoked: nothing can tell us the app left, so come back now
            fg = null
        }
    }

    private fun resume(a: Context) {
        stop()
        A11yGate.set(a, true)
        clearFlag(a)
        Log.i(TAG, "resumed")
    }

    private fun stop() {
        active = false
        polling = false
        h.removeCallbacks(tick)
        val a = app
        if (a != null) {
            cancelAlarm(a)
            if (rxOn) {
                try {
                    a.unregisterReceiver(rx)
                } catch (e: RuntimeException) {
                }
            }
        }
        rxOn = false
        fg = null
        paused = emptySet()
    }

    private fun clearFlag(a: Context) {
        Prefs.prefs(a).edit()
            .remove(Prefs.KEY_PAUSED_BY_GATE)
            .remove(Prefs.KEY_PAUSED_PKG)
            .remove(Prefs.KEY_PAUSED_AT)
            .commit()
    }

    private fun alarmIntent(a: Context): PendingIntent = PendingIntent.getBroadcast(
        a, 0, Intent(a, GateReceiver::class.java).setAction(GateReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Non-wakeup: it never wakes a sleeping phone, it fires on the next wake. */
    private fun scheduleAlarm(a: Context, delay: Long = ALARM) {
        val am = a.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + delay
        try {
            am.setExact(AlarmManager.ELAPSED_REALTIME, at, alarmIntent(a))
        } catch (e: RuntimeException) {
            am.set(AlarmManager.ELAPSED_REALTIME, at, alarmIntent(a))
        }
    }

    private fun cancelAlarm(a: Context) {
        a.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(a))
    }
}

/** The 2 s fallback alarm (no notification access) and BOOT_COMPLETED: re-check a pause. Does nothing otherwise. */
class GateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PauseGate.onAlarm(c)
    }

    companion object {
        const val ACTION = "com.dynedge.GATE_CHECK"
    }
}
