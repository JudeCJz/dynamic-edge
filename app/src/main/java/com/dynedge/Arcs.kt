package com.dynedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** Outer-ring sliders: brightness and the three volume streams. */
class Arcs(ctx: Context, private val onChange: () -> Unit) {

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val bright = Brightness(app) { onChange() }

    private var started = false
    private var obsOk = false
    private var rcvOk = false
    private val heldLevel = HashMap<String, Float>()
    private val heldUntil = HashMap<String, Long>()
    private val savedVolume = HashMap<String, Int>()

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            onChange()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onChange()
        }
    }

    fun start() {
        if (started) return
        started = true
        bright.start()
        obsOk = try {
            app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            true
        } catch (e: Exception) {
            android.util.Log.w("DynEdgeArcs", "settings observer not registered", e)
            false
        }
        rcvOk = false
        try {
            val f = IntentFilter(VOLUME_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, f)
            }
            rcvOk = true
        } catch (e: Exception) {
            android.util.Log.w("DynEdgeArcs", "volume receiver not registered", e)
        }
    }

    /** False when a live source could not be registered; callers then re-read values whenever a window is shown. */
    val live: Boolean get() = started && obsOk && rcvOk

    fun stop() {
        if (!started) return
        started = false
        bright.stop()
        try {
            if (obsOk) app.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
        }
        try {
            if (rcvOk) app.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        obsOk = false
        rcvOk = false
    }

    fun level(id: String): Float {
        val until = heldUntil[id] ?: 0L
        if (SystemClock.uptimeMillis() < until) {
            heldLevel[id]?.let { return it }
        }
        return read(id)
    }

    fun setLevel(id: String, f: Float) {
        val v = f.coerceIn(0f, 1f)
        hold(id, v)
        write(id, v)
    }

    fun commit(id: String) {
        val v = heldLevel[id] ?: return
        write(id, v)
        heldUntil[id] = SystemClock.uptimeMillis() + HOLD_MS
    }

    fun iconRes(id: String): Int = when (id) {
        "brightness" -> R.drawable.ic_sun
        "media_volume" -> if (iconActive(id)) R.drawable.ic_volume_off else R.drawable.ic_volume
        "ring_volume" -> R.drawable.ic_ring
        "alarm_volume" -> R.drawable.ic_alarm
        "notification_volume" -> R.drawable.ic_bell
        "call_volume" -> R.drawable.ic_phone
        else -> R.drawable.ic_volume
    }

    fun iconActive(id: String): Boolean = when (id) {
        "brightness" -> bright.isAuto
        else -> {
            val s = stream(id)
            s >= 0 && (audio?.getStreamVolume(s) ?: 1) == 0
        }
    }

    fun tapIcon(id: String) {
        if (id == "brightness") {
            bright.setAuto(!bright.isAuto)
            heldUntil.remove(id)
            onChange()
            return
        }
        val am = audio ?: return
        val s = stream(id)
        if (s < 0) return
        val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
        val cur = am.getStreamVolume(s)
        val target = if (cur == 0) (savedVolume[id] ?: (max / 2).coerceAtLeast(1)) else {
            savedVolume[id] = cur
            0
        }
        try {
            am.setStreamVolume(s, target, 0)
        } catch (e: SecurityException) {
        }
        heldUntil.remove(id)
        onChange()
    }

    // ---------------------------------------------------------------- internals

    private fun hold(id: String, v: Float) {
        heldLevel[id] = v
        heldUntil[id] = SystemClock.uptimeMillis() + HOLD_MS
    }

    private fun read(id: String): Float {
        if (id == "brightness") return bright.level()
        val am = audio ?: return 0f
        val s = stream(id)
        if (s < 0) return 0f
        val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
        return (am.getStreamVolume(s).toFloat() / max).coerceIn(0f, 1f)
    }

    private fun write(id: String, v: Float) {
        if (id == "brightness") {
            bright.setLevel(v)
            return
        }
        val am = audio ?: return
        val s = stream(id)
        if (s < 0) return
        val max = am.getStreamMaxVolume(s)
        val target = Math.round(v * max)
        try {
            if (am.getStreamVolume(s) != target) am.setStreamVolume(s, target, 0)
        } catch (e: SecurityException) {
        }
    }

    private fun stream(id: String): Int = when (id) {
        "media_volume" -> AudioManager.STREAM_MUSIC
        "ring_volume" -> AudioManager.STREAM_RING
        "alarm_volume" -> AudioManager.STREAM_ALARM
        "notification_volume" -> AudioManager.STREAM_NOTIFICATION
        "call_volume" -> AudioManager.STREAM_VOICE_CALL
        else -> -1
    }

    companion object {
        private const val HOLD_MS = 500L
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

        val ALL = listOf("brightness", "media_volume", "ring_volume", "alarm_volume", "notification_volume", "call_volume")

        fun label(id: String): String = when (id) {
            "brightness" -> "Brightness"
            "media_volume" -> "Media"
            "ring_volume" -> "Ring"
            "alarm_volume" -> "Alarm"
            "notification_volume" -> "Notification volume"
            "call_volume" -> "Call"
            else -> id
        }
    }
}
