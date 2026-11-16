package com.dynedge

import android.os.Handler
import android.os.Looper

data class LiveActivity(
    val key: String, val kind: Kind, val title: String,
    val progress: Float,
    val chronoBase: Long,
    val countDown: Boolean,
    val iconRes: Int, val ongoing: Boolean,
    val contentIntent: android.app.PendingIntent?,
    val answer: android.app.PendingIntent?, val decline: android.app.PendingIntent?,
    /** Full length of a countdown (ms) when known, so the ring starts full even after a restart; 0 = derive. */
    val totalMs: Long = 0L,
    /** A timer that lives in the clock app (its notification, or the optimistic entry before it appears). */
    val linked: Boolean = false,
    /** Linked timer that is paused: frozen at [remainingMs] (-1 = unknown). */
    val paused: Boolean = false,
    val remainingMs: Long = -1L,
    /** Short text (max ~7 chars, e.g. an OTP, "200 m", "5 min") drawn on the idle-sized bean instead of the icon. */
    val chip: String = "",
    /** Tapping the closed bean sends [contentIntent] (opens the app) instead of opening the panel. */
    val tapOpens: Boolean = false
) {
    enum class Kind { CALL, TIMER, PROGRESS, CHARGING, MEDIA }
}

/** Holds the current live activities. Thread-safe; onChange is always delivered on the main thread. */
object LiveBus {
    private class Entry(val seq: Long, val a: LiveActivity)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>()
    private var seq = 0L
    private val main = Handler(Looper.getMainLooper())

    var onChange: (() -> Unit)? = null

    private val notifier = Runnable { onChange?.invoke() }

    fun put(a: LiveActivity) {
        synchronized(lock) {
            map[a.key] = Entry(++seq, a)
        }
        notifyChanged()
    }

    fun remove(key: String) {
        val had = synchronized(lock) { map.remove(key) != null }
        if (had) notifyChanged()
    }

    /** Removes every entry whose key matches [pred]. */
    fun removeIf(pred: (String) -> Boolean) {
        val had = synchronized(lock) {
            val ks = map.keys.filter(pred)
            ks.forEach { map.remove(it) }
            ks.isNotEmpty()
        }
        if (had) notifyChanged()
    }

    /** adb debugging: one line per entry (key, kind, ongoing, has chip); never the chip text itself. */
    fun debugLines(): List<String> = synchronized(lock) {
        map.values.map { e -> "${e.a.key} kind=${e.a.kind} ongoing=${e.a.ongoing} chip=${e.a.chip.isNotEmpty()}" }
    }

    fun top(): LiveActivity? = sorted(0)

    fun second(): LiveActivity? = sorted(1)

    /**
     * The clock app's timer (linked entry: running with time left, paused, or running with an unknown end), newest
     * first. It disappears only with the clock's notification.
     */
    fun linkedTimer(): LiveActivity? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var best: Entry? = null
            for (e in map.values) {
                val a = e.a
                if (a.kind != LiveActivity.Kind.TIMER || !a.linked) continue
                if (a.countDown && a.chronoBase != 0L && a.chronoBase <= now) continue // ran out
                if (best == null || e.seq > best.seq) best = e
            }
            return best?.a
        }
    }

    /** The [index]-th entry (0 = top, 1 = second) by kind then recency, found in one pass without allocating a list. */
    private fun sorted(index: Int): LiveActivity? {
        synchronized(lock) {
            if (map.size <= index) return null
            var best: Entry? = null
            var second: Entry? = null
            for (e in map.values) {
                if (best == null || before(e, best)) {
                    second = best
                    best = e
                } else if (second == null || before(e, second)) {
                    second = e
                }
            }
            return (if (index == 0) best else second)?.a
        }
    }

    private fun before(x: Entry, y: Entry): Boolean {
        val kx = x.a.kind.ordinal
        val ky = y.a.kind.ordinal
        return kx < ky || (kx == ky && x.seq > y.seq)
    }

    private fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChange?.invoke()
        } else {
            main.removeCallbacks(notifier)
            main.post(notifier)
        }
    }
}
