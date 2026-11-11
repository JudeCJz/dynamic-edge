package com.dynedge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock

/**
 * The edge's own, standalone timer (no clock app needed) plus helpers for the clock app's timers.
 *
 * Own timer: the end is kept as elapsedRealtime (immune to clock changes) plus a wall-clock copy used only if the
 * device rebooted in between; both are persisted in Prefs. An exact alarm (AlarmManager) fires [TimerReceiver] at
 * zero even when the app process was dormant. At zero: default alarm ringtone (max [RING_MS]), vibration, a
 * notification with Stop and a pulsing bean (live activity [KEY_DONE]) until it is stopped.
 *
 * A running timer of the clock app is NOT recorded here: MediaListener turns its ongoing countdown notification
 * into a live activity ("n:" key) and [LiveBus.linkedTimer] exposes it read-only.
 */
object TimerControl {
    const val KEY = "timer_local"
    const val KEY_DONE = "timer_done"
    /** Optimistic live entry for a timer just started in the clock app, until its notification appears. */
    const val PENDING = "timer_pending"
    const val ACTION_FIRE = "com.dynedge.TIMER_FIRE"
    const val ACTION_STOP = "com.dynedge.TIMER_STOP"

    /** [remainingMs] result when no own timer is recorded. */
    const val NONE = Long.MIN_VALUE

    private const val P_END_E = "timer_end_e"
    private const val P_START_E = "timer_start_e"
    private const val P_END_W = "timer_end_w"
    private const val P_TOTAL = "timer_total"
    private const val P_PAUSED = "timer_paused_ms"

    private const val RING_MS = 20000L
    private const val CHANNEL = "timer_done"
    private const val NOTIF_ID = 4101
    private const val REQ_FIRE = 7
    private const val REQ_STOP = 8
    private const val REQ_SHOW = 9

    private val main = Handler(Looper.getMainLooper())
    private var ringing = false
    private var ringtone: Ringtone? = null
    private var appCtx: Context? = null
    private val stopRunnable = Runnable { appCtx?.let { stopAlarm(it) } }

    // ---------------------------------------------------------------- state

    fun isRinging(): Boolean = ringing

    fun isPaused(ctx: Context): Boolean = Prefs.prefs(ctx).getLong(P_PAUSED, 0L) > 0L

    /** Milliseconds left (<= 0 once it ended), the paused remainder while paused, or [NONE]. */
    fun remainingMs(ctx: Context): Long {
        val p = Prefs.prefs(ctx)
        val paused = p.getLong(P_PAUSED, 0L)
        if (paused > 0L) return paused
        val endE = p.getLong(P_END_E, 0L)
        if (endE == 0L) return NONE
        val nowE = SystemClock.elapsedRealtime()
        return if (nowE >= p.getLong(P_START_E, 0L)) endE - nowE else p.getLong(P_END_W, 0L) - System.currentTimeMillis()
    }

    /** Length of the recorded countdown in ms (0 when none). */
    fun totalMs(ctx: Context): Long = Prefs.prefs(ctx).getLong(P_TOTAL, 0L)

    // ---------------------------------------------------------------- own timer

    /** Starts the edge's own countdown. */
    fun startOwn(ctx: Context, seconds: Int) {
        val app = ctx.applicationContext ?: ctx
        stopAlarm(app)
        val total = seconds.coerceIn(1, 86400) * 1000L
        writeRunning(app, total, total)
    }

    fun pause(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (isPaused(app)) return
        val r = remainingMs(app)
        if (r == NONE || r <= 0L) return
        val total = Prefs.prefs(app).getLong(P_TOTAL, r).coerceAtLeast(r)
        Prefs.prefs(app).edit().remove(P_END_E).remove(P_START_E).remove(P_END_W).putLong(P_PAUSED, r).apply()
        cancelAlarm(app)
        publishPaused(r, total)
    }

    fun resume(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val p = Prefs.prefs(app).getLong(P_PAUSED, 0L)
        if (p <= 0L) return
        writeRunning(app, p, Prefs.prefs(app).getLong(P_TOTAL, p).coerceAtLeast(p))
    }

    /** Adds one minute to the running or paused own timer. */
    fun addMinute(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val r = remainingMs(app)
        if (r == NONE || r <= 0L) return
        val total = Prefs.prefs(app).getLong(P_TOTAL, r).coerceAtLeast(r) + 60000L
        if (isPaused(app)) {
            Prefs.prefs(app).edit().putLong(P_PAUSED, r + 60000L).putLong(P_TOTAL, total).apply()
            publishPaused(r + 60000L, total)
        } else {
            writeRunning(app, r + 60000L, total)
        }
    }

    /** Cancels the own timer (and a ringing alarm) and clears its live activity at once. */
    fun cancel(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        clear(app)
        stopAlarm(app)
    }

    /** The own timer reached zero (UI tick or alarm): clears it and rings. Idempotent and ignores stale calls. */
    fun finish(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val r = remainingMs(app)
        if (r == NONE) {
            LiveBus.remove(KEY)
            return
        }
        if (isPaused(app) || r > 1500L) return
        clear(app)
        ring(app)
    }

    /** Service start: re-publishes (and re-arms) a timer that is still running, drops one that ended while away. */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val r = remainingMs(app)
        if (r == NONE) return
        val total = Prefs.prefs(app).getLong(P_TOTAL, r).coerceAtLeast(r)
        if (isPaused(app)) {
            publishPaused(r, total)
        } else if (r <= 0L) {
            clear(app)
        } else {
            writeRunning(app, r, total)
        }
    }

    private fun writeRunning(ctx: Context, remaining: Long, total: Long) {
        val nowE = SystemClock.elapsedRealtime()
        val nowW = System.currentTimeMillis()
        Prefs.prefs(ctx).edit()
            .putLong(P_START_E, nowE)
            .putLong(P_END_E, nowE + remaining)
            .putLong(P_END_W, nowW + remaining)
            .putLong(P_TOTAL, total)
            .remove(P_PAUSED)
            .apply()
        LiveBus.remove(KEY_DONE)
        schedule(ctx, nowE + remaining, nowW + remaining)
        // chronoBase is the wall-clock end the bean counts down to (same contract as a clock-app notification)
        LiveBus.put(
            LiveActivity(
                KEY, LiveActivity.Kind.TIMER, "Timer", -1f,
                nowW + remaining, true,
                R.drawable.ic_timer, true, null, null, null, total
            )
        )
    }

    private fun publishPaused(remaining: Long, total: Long) {
        LiveBus.put(
            LiveActivity(
                KEY, LiveActivity.Kind.TIMER, fmt(remaining), (remaining.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f),
                0L, false, R.drawable.ic_timer, true, null, null, null, total
            )
        )
    }

    private fun clear(ctx: Context) {
        Prefs.prefs(ctx).edit().remove(P_END_E).remove(P_START_E).remove(P_END_W).remove(P_TOTAL).remove(P_PAUSED).apply()
        cancelAlarm(ctx)
        LiveBus.remove(KEY)
    }

    private fun fmt(ms: Long): String {
        val t = if (ms < 0L) 0L else (ms + 999L) / 1000L
        val h = t / 3600L
        val m = (t % 3600L) / 60L
        val s = t % 60L
        val sb = StringBuilder(8)
        if (h > 0L) {
            sb.append(h).append(':')
            if (m < 10L) sb.append('0')
        }
        sb.append(m).append(':')
        if (s < 10L) sb.append('0')
        sb.append(s)
        return sb.toString()
    }

    // ---------------------------------------------------------------- alarm

    private fun receiverIntent(ctx: Context, action: String): Intent =
        Intent(ctx, TimerReceiver::class.java).setAction(action)

    private fun firePi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_FIRE, receiverIntent(ctx, ACTION_FIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Exact alarm that also fires in Doze. targetSdk is 28, so SCHEDULE_EXACT_ALARM is normally granted; when it
     * is not (Android 12+ user revoked it) an alarm-clock alarm is used, which needs no permission.
     */
    private fun schedule(ctx: Context, endElapsed: Long, endWall: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePi(ctx)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, pi)
                return
            }
        } catch (e: SecurityException) {
        }
        try {
            val show = PendingIntent.getActivity(
                ctx, REQ_SHOW, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(endWall, show), pi)
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, pi)
            } catch (e2: Exception) {
            }
        }
    }

    private fun cancelAlarm(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(firePi(ctx))
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- ringing

    private fun ring(ctx: Context) {
        appCtx = ctx
        ringing = true
        LiveBus.put(
            LiveActivity(
                KEY_DONE, LiveActivity.Kind.TIMER, "Done", 1f, 0L, false,
                R.drawable.ic_timer, true, null, null, null
            )
        )
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val rt = RingtoneManager.getRingtone(ctx, uri)
            if (rt != null) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                rt.isLooping = true
                rt.play()
                ringtone = rt
            }
        } catch (e: Exception) {
        }
        try {
            ctx.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 500L, 300L, 500L, 300L, 500L, 1200L), 0))
        } catch (e: Exception) {
        }
        notifyDone(ctx)
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, RING_MS)
    }

    private fun notifyDone(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(CHANNEL, "Timer", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
            val stop = PendingIntent.getBroadcast(
                ctx, REQ_STOP, receiverIntent(ctx, ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("Timer")
                .setContentText("Done")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_x), "Stop", stop).build())
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    /** Stops the sound, vibration, notification and the pulsing bean. Idempotent. */
    fun stopAlarm(ctx: Context) {
        main.removeCallbacks(stopRunnable)
        val app = ctx.applicationContext ?: ctx
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        } catch (e: Exception) {
        }
        if (!ringing) return
        ringing = false
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        ringtone = null
        try {
            app.getSystemService(Vibrator::class.java)?.cancel()
        } catch (e: Exception) {
        }
        LiveBus.remove(KEY_DONE)
    }

    // ---------------------------------------------------------------- clock app

    // THE timer lives in the clock app. This is our mirror of it, bound to its notification (key "n:...").
    private var bKey: String? = null
    private var bEnd = 0L // wall-clock end, 0 = unknown (started in the clock app and not readable)
    private var bTotal = 0L
    private var bRem = -1L // remaining while paused, -1 unknown
    private var bPaused = false
    private var bExpired = false
    private var bToggle: PendingIntent? = null
    private var bAdd: PendingIntent? = null
    private var bContent: PendingIntent? = null
    // optimistic entry for a timer the edge just started in the clock app
    private var pendEnd = 0L
    private var pendTotal = 0L
    private var pendAt = 0L
    private const val PENDING_MS = 10000L

    /**
     * Called by MediaListener for each post of the clock's running-timer notification. Binds it to the edge's
     * optimistic entry (length known) or to [readEnd] (chronometer in its custom view), tracks pause / resume and
     * keeps our end time across reposts (the notification's `when` is only the post time). Returns the live entry,
     * or null when the timer is over.
     */
    fun linkClock(
        key: String, paused: Boolean, toggle: PendingIntent?, add: PendingIntent?, content: PendingIntent?,
        readEnd: () -> Long
    ): LiveActivity? {
        val now = System.currentTimeMillis()
        val pendFresh = pendEnd != 0L && now - pendAt < PENDING_MS
        val finished = bKey == key && !bPaused && bEnd != 0L && bEnd <= now
        if (bKey != key || pendFresh || finished || bExpired) {
            val e = if (pendFresh) pendEnd else readEnd()
            if (!pendFresh && bKey == key && (finished || bExpired) && e <= now) {
                // same notification after it ran out and nothing new to bind: stay silent, never a stale timer
                bKey = key
                bExpired = true
                return null
            }
            bKey = key
            bExpired = false
            bPaused = false
            bRem = -1L
            bEnd = if (e > now) e else 0L
            bTotal = if (pendFresh) pendTotal else if (bEnd != 0L) bEnd - now else 0L
            if (pendFresh) dropPending()
        } else if (bEnd == 0L && !paused && !bPaused) {
            val e = readEnd()
            if (e > now) {
                bEnd = e
                bTotal = e - now
            }
        }
        if (paused && !bPaused) {
            bRem = if (bEnd != 0L) (bEnd - now).coerceAtLeast(0L) else -1L
            bPaused = true
        } else if (!paused && bPaused) {
            bPaused = false
            if (bRem >= 0L) bEnd = now + bRem
            bRem = -1L
        }
        bToggle = toggle
        bAdd = add
        bContent = content
        return buildLinked()
    }

    private fun buildLinked(): LiveActivity? {
        val key = bKey ?: return null
        if (bPaused) {
            val tot = bTotal
            return LiveActivity(
                key, LiveActivity.Kind.TIMER, if (bRem >= 0L) fmt(bRem) else "Paused",
                if (bRem >= 0L && tot > 0L) (bRem.toFloat() / tot).coerceIn(0f, 1f) else -1f,
                0L, false, R.drawable.ic_timer, true, bContent, bAdd, null, tot,
                linked = true, paused = true, remainingMs = bRem
            )
        }
        if (bEnd != 0L) {
            return LiveActivity(
                key, LiveActivity.Kind.TIMER, "Timer", -1f, bEnd, true, R.drawable.ic_timer, true,
                bContent, bAdd, null, bTotal, linked = true
            )
        }
        // end unknown (started in the clock app, not readable): running, no numbers
        return LiveActivity(
            key, LiveActivity.Kind.TIMER, "Timer", -2f, 0L, false, R.drawable.ic_timer, true,
            bContent, bAdd, null, 0L, linked = true
        )
    }

    /** The clock's notification is gone: the timer ended or was deleted there. Forget it. */
    fun unlink(key: String) {
        if (key != bKey) return
        bKey = null
        bEnd = 0L
        bTotal = 0L
        bRem = -1L
        bPaused = false
        bExpired = false
        bToggle = null
        bAdd = null
        bContent = null
    }

    private fun dropPending() {
        pendEnd = 0L
        main.removeCallbacks(pendingDrop)
        LiveBus.remove(PENDING)
    }

    /** True when the shown linked timer has a pause / resume action in the clock's notification. */
    fun canToggleLinked(): Boolean = bToggle != null && LiveBus.linkedTimer()?.key == bKey

    /** Pause / resume on the edge = the clock's own Pause / Resume action; the repost updates our state. */
    fun toggleLinked(): Boolean {
        if (!canToggleLinked()) return false
        return try {
            bToggle?.send()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop on the edge: the clock's notification has no stop action, so open its timers. Nothing is cleared here;
     * the timer disappears from the edge only when the clock's notification really does.
     */
    fun stopLinked(ctx: Context): Boolean = openTimers(ctx)

    /** "+1 min": fires the clock's "Add 1 Minute" action and adds 60 s to our end time. */
    fun addMinuteLinked(): Boolean {
        val l = LiveBus.linkedTimer() ?: return false
        val pi = l.answer ?: return false
        try {
            pi.send()
        } catch (e: Exception) {
            return false
        }
        if (l.key == bKey) {
            if (bPaused && bRem >= 0L) bRem += 60000L else if (bEnd != 0L) bEnd += 60000L
            if (bTotal > 0L) bTotal += 60000L
            buildLinked()?.let { LiveBus.put(it) }
        }
        return true
    }

    /**
     * Starts THE timer: in the clock app (ACTION_SET_TIMER, no UI), mirrored here as a linked timer. Until the
     * clock's notification shows up the bean uses an optimistic local end time ([PENDING]); the first timer
     * notification after this binds to it (our known length is its end time). If none appears within 10 s it
     * cannot be tracked and the optimistic entry is dropped. Without a clock app that handles the intent, the
     * edge's own standalone timer ([startOwn]) is the fallback.
     */
    fun start(ctx: Context, seconds: Int) {
        val app = ctx.applicationContext ?: ctx
        val s = seconds.coerceIn(1, 86400)
        if (!startInClock(app, s)) {
            startOwn(app, s)
            return
        }
        val total = s * 1000L
        val now = System.currentTimeMillis()
        pendEnd = now + total
        pendTotal = total
        pendAt = now
        LiveBus.put(
            LiveActivity(
                PENDING, LiveActivity.Kind.TIMER, "Timer", -1f,
                pendEnd, true, R.drawable.ic_timer, true, null, null, null, total, linked = true
            )
        )
        main.removeCallbacks(pendingDrop)
        main.postDelayed(pendingDrop, PENDING_MS)
    }

    private val pendingDrop = Runnable {
        // no clock notification took over: stop showing a timer we can no longer verify
        pendEnd = 0L
        LiveBus.remove(PENDING)
    }

    /** Optional: starts a countdown in the system clock app (it then shows up here as a linked timer). */
    fun startInClock(ctx: Context, seconds: Int): Boolean {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(ctx, i)
    }

    fun openTimers(ctx: Context): Boolean =
        launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
