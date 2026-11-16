package com.dynedge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Settings "Live test": triggers every dynamic effect of the edge on the real overlay with fake [LiveActivity]
 * entries (keys prefixed "test:", same kinds and fields as the real ones). Nothing real is touched: no
 * notifications, timers, alarms, calls, media or system settings. All calls are on the main thread.
 * Tapping an effect again stops it; every effect also ends by itself.
 */
object LiveTest {
    const val PREFIX = "test:"

    const val DL = "dl"
    const val DL_IND = "dl_ind"
    const val UPLOAD = "upload"
    const val TIMER = "timer"
    const val STOPWATCH = "stopwatch"
    const val DONE = "done"
    const val CALL_IN = "call_in"
    const val CALL_ON = "call_on"
    const val CHARGING = "charging"
    const val TRACK = "track"
    const val COPIED = "copied"
    const val MUSIC = "music"
    const val TWO = "two"
    const val HIDE = "hide"
    const val OPEN = "open"

    /** While true the idle visualiser and beat bounce run from the synthetic beat and music counts as playing. */
    @Volatile
    var demoMusic = false
        private set

    /** The live overlay, set while it is attached (same process). */
    @Volatile
    var view: EdgeView? = null

    /** Called whenever the set of running effects changes (the settings rows refresh their state). */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val stops = HashMap<String, Runnable>()
    private val tickers = HashMap<String, Runnable>()
    private val owned = HashMap<String, List<String>>()
    private var pi: PendingIntent? = null

    fun isRunning(id: String): Boolean = stops.containsKey(id)

    /** True when [key] belongs to a running test entry (the Timer done test uses the real done key). */
    fun owns(key: String): Boolean {
        if (key.startsWith(PREFIX)) return true
        for (ks in owned.values) if (ks.contains(key)) return true
        return false
    }

    /** A tap on the bean (or a call dot) ends the test that owns [key]. */
    fun endKey(key: String) {
        for ((id, ks) in owned.entries.toList()) if (ks.contains(key)) stop(id)
    }

    /** Starts [id], or stops it when it is already running. Returns a short problem text, or null when started. */
    fun toggle(ctx: Context, id: String): String? {
        if (isRunning(id)) {
            stop(id)
            return null
        }
        if (!EdgeService.connected) return "Edge service is off"
        val v = view ?: return "Edge is not showing"
        // one test at a time: starting a new one ends whatever was running
        if (stops.isNotEmpty() || demoMusic) stopAll()
        start(ctx.applicationContext, v, id)
        return null
    }

    /** True while any effect is running. */
    fun anyRunning(): Boolean = stops.isNotEmpty() || demoMusic

    /** Ends every effect at once and clears any test entry left on the bus. */
    fun stopAll() {
        for (id in stops.keys.toList()) stop(id)
        for ((_, r) in tickers) main.removeCallbacks(r)
        tickers.clear()
        owned.clear()
        LiveBus.removeIf { it.startsWith(PREFIX) }
        if (demoMusic) {
            demoMusic = false
            view?.testRefreshIdle()
        }
        listener?.invoke()
    }

    // ---------------------------------------------------------------- effects

    private fun start(ctx: Context, v: EdgeView, id: String) {
        val now = System.currentTimeMillis()
        when (id) {
            DL -> run(id, 9500L, "dl") { ramp(id, "dl", "Downloading", R.drawable.ic_download, 8000L) }
            UPLOAD -> run(id, 9500L, "up") { ramp(id, "up", "Uploading", R.drawable.ic_upload, 8000L) }
            DL_IND -> run(id, 12000L, "dli") {
                put(act("dli", LiveActivity.Kind.PROGRESS, "Downloading", -2f, R.drawable.ic_download))
            }
            TIMER -> run(id, 30500L, "timer") {
                put(act("timer", LiveActivity.Kind.TIMER, "Timer", -1f, R.drawable.ic_timer, now + 30000L, true, total = 30000L))
            }
            STOPWATCH -> run(id, 15000L, "sw") {
                put(act("sw", LiveActivity.Kind.TIMER, "Stopwatch", -1f, R.drawable.ic_timer, now, false))
            }
            DONE -> run(id, 15000L, TimerControl.KEY_DONE) {
                put(act(TimerControl.KEY_DONE, LiveActivity.Kind.TIMER, "Done", 1f, R.drawable.ic_timer))
            }
            CALL_IN -> run(id, 15000L, "call_in") {
                val p = harmless(ctx)
                put(act("call_in", LiveActivity.Kind.CALL, "Test caller", -1f, R.drawable.ic_phone, answer = p, decline = p))
            }
            CALL_ON -> run(id, 15000L, "call_on") {
                put(act("call_on", LiveActivity.Kind.CALL, "Test call", -1f, R.drawable.ic_phone, now, false,
                    decline = harmless(ctx)))
            }
            CHARGING -> run(id, 8000L, "charging") {
                put(act("charging", LiveActivity.Kind.CHARGING, "87%", 0.87f, R.drawable.ic_bolt))
            }
            TRACK -> run(id, 8000L, "media") {
                put(act("media", LiveActivity.Kind.MEDIA, "Test track", -1f, R.drawable.ic_music))
            }
            COPIED -> run(id, 8000L, "copied") {
                put(act("copied", LiveActivity.Kind.PROGRESS, "Copied", -1f, R.drawable.ic_clipboard))
            }
            TWO -> run(id, 15000L, "two_call", "two_timer") {
                put(act("two_timer", LiveActivity.Kind.TIMER, "Timer", -1f, R.drawable.ic_timer, now + 45000L, true, total = 45000L))
                put(act("two_call", LiveActivity.Kind.CALL, "Test call", -1f, R.drawable.ic_phone, now, false))
            }
            MUSIC -> run(id, 15000L) {
                demoMusic = true
                v.testRefreshIdle()
            }
            HIDE -> run(id, Prefs.hideSeconds(ctx) * 1000L + 600L) { v.testHide() }
            OPEN -> run(id, 8000L) { v.expand() }
        }
        listener?.invoke()
    }

    private fun stop(id: String) {
        stops.remove(id)?.let { main.removeCallbacks(it) }
        tickers.remove(id)?.let { main.removeCallbacks(it) }
        for (k in owned.remove(id).orEmpty()) LiveBus.remove(k)
        when (id) {
            MUSIC -> {
                demoMusic = false
                view?.testRefreshIdle()
            }
            HIDE -> view?.testUnhide()
            OPEN -> view?.collapse()
        }
        listener?.invoke()
    }

    private fun run(id: String, ms: Long, vararg keys: String, body: () -> Unit) {
        owned[id] = keys.toList().map { realKey(it) }
        val r = Runnable { stop(id) }
        stops[id] = r
        main.postDelayed(r, ms)
        body()
    }

    /** Progress that climbs 0 to 100 percent over [ms], then holds full until the effect ends. */
    private fun ramp(id: String, key: String, title: String, icon: Int, ms: Long) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!stops.containsKey(id)) return
                val f = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
                put(act(key, LiveActivity.Kind.PROGRESS, title, f, icon))
                if (f < 1f) main.postDelayed(this, 80L)
            }
        }
        tickers[id] = r
        r.run()
    }

    // ---------------------------------------------------------------- helpers

    private fun realKey(k: String): String = if (k == TimerControl.KEY_DONE) k else PREFIX + k

    private fun put(a: LiveActivity) = LiveBus.put(a)

    private fun act(
        key: String, kind: LiveActivity.Kind, title: String, progress: Float, icon: Int,
        base: Long = 0L, down: Boolean = false, total: Long = 0L,
        answer: PendingIntent? = null, decline: PendingIntent? = null
    ) = LiveActivity(
        realKey(key), kind, title, progress, base, down, icon, true, null, answer, decline, total
    )

    /** A broadcast nobody receives: gives the call dots something to show without doing anything. */
    private fun harmless(ctx: Context): PendingIntent {
        pi?.let { return it }
        val p = PendingIntent.getBroadcast(
            ctx, 0, Intent("com.dynedge.LIVE_TEST_NOOP").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE
        )
        pi = p
        return p
    }
}
