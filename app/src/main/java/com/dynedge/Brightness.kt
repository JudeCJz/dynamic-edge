package com.dynedge

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Reads and writes system brightness on a perceptual (gamma 2.2) curve. */
class Brightness(private val ctx: Context, private val onChange: () -> Unit) {

    private val resolver = ctx.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var wasAuto = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChange.invoke()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onChange.invoke()
        }
    }

    val isAuto: Boolean
        get() = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Exception) {
            false
        }

    fun start() {
        if (started) return
        started = true
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
    }

    fun stop() {
        if (!started) return
        started = false
        resolver.unregisterContentObserver(observer)
    }

    fun level(): Float {
        val raw = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            return 0f
        }
        val lin = ((raw - MIN).toFloat() / (MAX - MIN).toFloat()).coerceIn(0f, 1f)
        return Math.pow(lin.toDouble(), 1.0 / 2.2).toFloat()
    }

    fun setLevel(f: Float) {
        if (!Settings.System.canWrite(ctx)) return
        try {
            if (isAuto) {
                wasAuto = true
                Settings.System.putInt(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            val lin = Math.pow(f.coerceIn(0f, 1f).toDouble(), 2.2)
            val raw = Math.round(MIN + lin * (MAX - MIN)).toInt().coerceIn(MIN, MAX)
            val cur = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                -1
            }
            if (raw != cur) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        } catch (e: Exception) {
        }
    }

    fun setAuto(on: Boolean) {
        if (!Settings.System.canWrite(ctx)) return
        try {
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            wasAuto = false
        } catch (e: Exception) {
        }
    }

    private companion object {
        val MIN: Int = frameworkInt("config_screenBrightnessSettingMinimum", 1)
        val MAX: Int = frameworkInt("config_screenBrightnessSettingMaximum", 255).coerceAtLeast(MIN + 1)

        fun frameworkInt(name: String, def: Int): Int {
            return try {
                val res = Resources.getSystem()
                val id = res.getIdentifier(name, "integer", "android")
                if (id != 0) res.getInteger(id) else def
            } catch (e: Exception) {
                def
            }
        }
    }
}
