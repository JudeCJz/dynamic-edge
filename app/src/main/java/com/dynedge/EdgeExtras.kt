package com.dynedge

import android.app.AlarmManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.graphics.Paint
import java.util.Calendar

/** Pref keys for the bean extras handled inside EdgeView (EdgeService routes them to applyLooks). */
object EdgeExtras {
    const val KEY_DOUBLE_TAP_BACK = "double_tap_back" // default true
    const val KEY_IDLE_INFO = "idle_info"             // default false
    const val KEY_FADE_IDLE = "fade_idle"             // default false

    const val INFO_PERIOD_MS = 6000L
    const val INFO_FADE_MS = 200L
    const val FADE_DELAY_MS = 5000L
    const val FADE_MS = 300L
    const val FADE_LEVEL = 0.35f
    const val DOUBLE_TAP_MAX_MS = 220L

    private const val RECENT_WINDOW_MS = 2L * 60L * 60L * 1000L

    /** Package of the app to switch back to, or null without usage access / history. Blocking: call off the UI thread. */
    fun previousApp(ctx: Context): String? {
        if (!RecentApps.hasAccess(ctx)) return null
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - RECENT_WINDOW_MS, now) ?: return null
            val last = HashMap<String, Long>()
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            if (last.isEmpty()) return null
            val pm = ctx.packageManager
            val skip = HashSet<String>()
            skip.add(ctx.packageName)
            skip.add("com.dynedge")
            skip.add("com.android.systemui")
            skip.add("android")
            try {
                val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                home?.activityInfo?.packageName?.let { skip.add(it) }
            } catch (x: Exception) {
            }
            val sorted = last.entries.sortedByDescending { it.value }
            val fg = sorted[0].key
            for (en in sorted) {
                val pkg = en.key
                if (pkg in skip) continue
                if (pkg == fg) continue
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                return pkg
            }
        } catch (e: Exception) {
        }
        return null
    }
}

/** Caches the auto-shrunk text size of one chip so measuring happens once per text change. */
class ChipFit {
    private var text = ""
    private var base = 0f
    private var maxW = 0f
    private var size = 0f

    fun reset() {
        text = ""
    }

    /** Returns the text size (px) that fits [t] in [maxWidth]; leaves p.textSize untouched afterwards callers set it. */
    fun fit(p: Paint, t: String, baseSize: Float, maxWidth: Float): Float {
        if (t == text && baseSize == base && maxWidth == maxW) return size
        p.textSize = baseSize
        val w = p.measureText(t)
        size = if (w > maxWidth && w > 0f) baseSize * maxWidth / w else baseSize
        text = t
        base = baseSize
        maxW = maxWidth
        return size
    }
}

/** Source of the short idle chips: time, battery, next alarm, network speed. */
class IdleInfo(private val ctx: Context) {
    private var idx = -1
    private var lastRx = -1L
    private var lastT = 0L

    fun reset() {
        idx = -1
        lastRx = -1L
    }

    /** Samples the network counter; call once per period even when the speed is not shown. */
    private fun netSpeed(): String? {
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val prevRx = lastRx
        val prevT = lastT
        lastRx = rx
        lastT = now
        if (rx < 0L || prevRx < 0L || now <= prevT) return null
        val rate = (rx - prevRx) * 1000f / (now - prevT)
        if (rate < 10_000f) return null
        return if (rate >= 1_000_000f) {
            val m = rate / 1_000_000f
            if (m >= 10f) "${m.toInt()}M" else "${(m * 10f).toInt() / 10}.${(m * 10f).toInt() % 10}M"
        } else {
            "${(rate / 1000f).toInt()}K"
        }
    }

    private fun time(ms: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        val m = cal.get(Calendar.MINUTE)
        val mm = if (m < 10) "0$m" else "$m"
        return if (DateFormat.is24HourFormat(ctx)) {
            val h = cal.get(Calendar.HOUR_OF_DAY)
            "${if (h < 10) "0$h" else "$h"}:$mm"
        } else {
            val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            "$h:$mm"
        }
    }

    private fun battery(): String? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l < 0 || s <= 0) return null
        return "${l * 100 / s}%"
    }

    private fun alarm(): String? {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
        val a = am.nextAlarmClock ?: return null
        return time(a.triggerTime)
    }

    /** The next chip in the cycle (time, battery, alarm, speed), skipping the unavailable ones; the clock never is. */
    fun next(): String {
        val net = netSpeed()
        for (k in 0 until 4) {
            idx = (idx + 1) and 3
            val t = when (idx) {
                0 -> time(System.currentTimeMillis())
                1 -> try { battery() } catch (e: Exception) { null }
                2 -> try { alarm() } catch (e: Exception) { null }
                else -> net
            }
            if (t != null) return t
        }
        return time(System.currentTimeMillis())
    }
}
