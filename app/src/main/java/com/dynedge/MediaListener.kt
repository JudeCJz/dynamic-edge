package com.dynedge

import android.app.Notification
import android.app.PendingIntent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout

/**
 * Enabling it allows MediaSessionManager.getActiveSessions(). It also classifies notifications into
 * live activities (calls, timers, progress) and feeds LiveBus. Because it stays bound, it also keeps the
 * process alive for PauseGate's re-enable polling while the overlay is paused in a sensitive app.
 */
class MediaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // a pause left over from before a restart / reboot is checked (and usually ended) at once
        PauseGate.onListenerConnected(this)
        NotifFeed.listener = this
        rebuildFeed()
        try {
            val all = activeNotifications ?: return
            for (sbn in all) handle(sbn)
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        PauseGate.onListenerDisconnected()
        if (NotifFeed.listener === this) NotifFeed.listener = null
        NotifFeed.publish(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
        rebuildFeed()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            // also ends a linked clock-app timer at once (the TIMER window and the bean follow LiveBus)
            val k = keyOf(sbn)
            LiveBus.remove(k)
            TimerControl.unlink(k)
        }
        rebuildFeed()
    }

    private fun rebuildFeed() {
        try {
            val all = activeNotifications ?: return
            // The media block list is NOT applied here: it is for media sessions only. Media-style
            // notifications are already skipped below (EXTRA_MEDIA_SESSION / CATEGORY_TRANSPORT).
            val out = ArrayList<NotifFeed.Item>()
            for (sbn in all.sortedByDescending { it.postTime }) {
                val n = sbn.notification ?: continue
                if (sbn.packageName == packageName) continue
                if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or
                        Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
                if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_CALL) continue
                val ex = n.extras ?: continue
                if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
                    ?.toString()?.trim().orEmpty()
                if (title.isEmpty() && text.isEmpty()) continue
                out.add(NotifFeed.Item(sbn.key, sbn.packageName, title, text, sbn.postTime, n.contentIntent, sbn.isClearable))
                if (out.size >= NotifFeed.MAX) break
            }
            NotifFeed.publish(out)
        } catch (e: Exception) {
        }
    }

    private fun keyOf(sbn: StatusBarNotification): String = "n:" + sbn.key

    private fun handle(sbn: StatusBarNotification) {
        val key = keyOf(sbn)
        // The media block list (Nothing's Essential News, system widgets) applies only to media-style
        // notifications; timers, alarms, Bluetooth transfers, screen-record etc. from those packages stay live.
        if (!Prefs.live(this) || sbn.packageName == packageName ||
            (isMediaStyle(sbn) && MediaBridge.isBlocked(this, sbn.packageName))) {
            LiveBus.remove(key)
            return
        }
        val a = classify(sbn, key)
        if (a != null) LiveBus.put(a) else LiveBus.remove(key)
    }

    /** A media-player notification: carries a media session token or is a transport-category notification. */
    private fun isMediaStyle(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        return n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun classify(sbn: StatusBarNotification, key: String): LiveActivity? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras ?: return null
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val pkg = sbn.packageName

        // CALL
        if (n.category == Notification.CATEGORY_CALL) {
            var answer: PendingIntent? = null
            var decline: PendingIntent? = null
            val actions = n.actions
            if (actions != null) {
                for (act in actions) {
                    val t = act.title?.toString()?.lowercase() ?: ""
                    if (act.actionIntent == null) continue
                    if (answer == null && (act.semanticAction == Notification.Action.SEMANTIC_ACTION_CALL ||
                            t.contains("answer") || t.contains("accept"))) {
                        answer = act.actionIntent
                    } else if (decline == null && (t.contains("decline") || t.contains("reject") ||
                            t.contains("hang") || t.contains("end") || t.contains("dismiss"))) {
                        decline = act.actionIntent
                    }
                }
            }
            val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            return LiveActivity(
                key, LiveActivity.Kind.CALL, who.ifEmpty { "Call" }.take(18), -1f,
                if (chrono) n.`when` else 0L, false,
                R.drawable.ic_phone, true, n.contentIntent, answer, decline
            )
        }

        // TIMER
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        // Clock apps like vivo / AOSP deskclock draw the countdown in a custom view: no chronometer extras, `when`
        // is only the post time. Their end time comes from the edge's own start (bound in TimerControl).
        if (!chrono && isClockTimer(pkg, n, ongoing)) return classifyClockTimer(n, key)
        if (chrono && isClockApp(pkg, n)) {
            val down = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
            // a countdown whose target is already past is not counting any more (finished / ringing): not live
            if (down && n.`when` <= System.currentTimeMillis()) return null
            // the clock app's own stop / cancel action (kept in the unused `decline` slot) lets the edge stop it there
            var stop: PendingIntent? = null
            var add: PendingIntent? = null // "+1 min" style action, kept in the unused `answer` slot
            val acts = n.actions
            if (acts != null) {
                for (act in acts) {
                    val t = act.title?.toString()?.lowercase() ?: continue
                    val pi = act.actionIntent ?: continue
                    if (stop == null && (t.contains("stop") || t.contains("cancel") || t.contains("reset") ||
                            t.contains("dismiss") || t.contains("delete"))) {
                        stop = pi
                    } else if (add == null && (t.contains("+1") || t.contains("1 min") || t.contains("1:00") ||
                            t.contains("add"))) {
                        add = pi
                    }
                }
            }
            if (down) LiveBus.remove(TimerControl.PENDING) // the real notification replaces the optimistic entry
            return LiveActivity(
                key, LiveActivity.Kind.TIMER, if (down) "Timer" else "Stopwatch", -1f,
                n.`when`, down, R.drawable.ic_timer, true, n.contentIntent, add, stop, linked = down
            )
        }

        // navigation, rides / deliveries, determinate progress, OTP (each switchable in prefs)
        NotifLive.classify(this, sbn, key)?.let { return it }

        // PROGRESS
        val max =ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max > 0 && ongoing && n.category != Notification.CATEGORY_TRANSPORT) {
            val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            val p = ex.getInt(Notification.EXTRA_PROGRESS, 0)
            val f = if (indeterminate) -2f else (p.toFloat() / max).coerceIn(0f, 1f)
            return LiveActivity(
                key, LiveActivity.Kind.PROGRESS, "Downloading", f, 0L, false,
                R.drawable.ic_download, true, n.contentIntent, null, null
            )
        }
        return null
    }

    /** The clock app's running-timer notification: clock package or alarm category, a "timer" channel, pause / resume or ongoing. */
    private fun isClockTimer(pkg: String, n: Notification, ongoing: Boolean): Boolean {
        if (!isClockApp(pkg, n) || n.channelId?.contains("timer", true) != true) return false
        if (ongoing) return true
        return n.actions?.any {
            val t = it.title?.toString()?.lowercase() ?: ""
            t.contains("pause") || t.contains("resume")
        } == true
    }

    private fun classifyClockTimer(n: Notification, key: String): LiveActivity? {
        var pause: PendingIntent? = null
        var resume: PendingIntent? = null
        var add: PendingIntent? = null
        n.actions?.forEach { act ->
            val t = act.title?.toString()?.lowercase() ?: return@forEach
            val pi = act.actionIntent ?: return@forEach
            if (resume == null && t.contains("resume")) resume = pi
            else if (pause == null && t.contains("pause")) pause = pi
            else if (add == null && (t.contains("+1") || t.contains("1 min") || t.contains("add"))) add = pi
        }
        val paused = resume != null && pause == null
        return TimerControl.linkClock(key, paused, resume ?: pause, add, n.contentIntent) { readChronoEnd(n) }
    }

    /** Wall-clock end from a countdown Chronometer inside the notification's custom views, or 0 (public APIs only). */
    @Suppress("DEPRECATION")
    private fun readChronoEnd(n: Notification): Long {
        for (rv in arrayOf(n.contentView, n.bigContentView, n.headsUpContentView)) {
            if (rv == null) continue
            try {
                val c = findChrono(rv.apply(this, FrameLayout(this))) ?: continue
                val rem = c.base - SystemClock.elapsedRealtime()
                if (rem in 1000L..86400000L) return System.currentTimeMillis() + rem
            } catch (e: Exception) {
            }
        }
        return 0L
    }

    private fun findChrono(v: View?): Chronometer? {
        if (v is Chronometer) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findChrono(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** A clock app by exact package or by an alarm / timer / stopwatch notification category (no name guessing). */
    private fun isClockApp(pkg: String, n: Notification): Boolean {
        if (pkg in CLOCK_PACKAGES) return true
        val c = n.category ?: return false
        return c == Notification.CATEGORY_ALARM || c == "stopwatch" || c == "timer"
    }

    private companion object {
        val CLOCK_PACKAGES = hashSetOf(
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
            "com.android.BBKClock", "com.vivo.alarmclock", "com.oneplus.deskclock", "com.coloros.alarmclock",
            "com.oppo.alarmclock", "com.huawei.deskclock", "com.miui.clock", "com.motorola.blur.alarmclock",
            "com.asus.deskclock", "com.lge.clock", "com.sonyericsson.organizer"
        )
    }
}
