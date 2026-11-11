package com.dynedge

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Google Assistant handling.
 * - The corner-swipe Assistant gesture fights with the widget in the same corner, so it is switched
 *   off whenever the service starts (needs WRITE_SECURE_SETTINGS, granted once over adb).
 * - The widget offers its own on-screen Assistant button instead (launch()).
 */
object AssistControl {

    // vivo/Funtouch key, then the AOSP key used by Pixel-style gesture nav.
    private val GESTURE_KEYS = arrayOf("navigation_google_assist", "assist_touch_gesture_enabled")

    fun disableCornerGesture(ctx: Context) {
        val cr = ctx.contentResolver
        for (k in GESTURE_KEYS) {
            try {
                if (Settings.Secure.getInt(cr, k, 0) != 0) Settings.Secure.putInt(cr, k, 0)
            } catch (e: SecurityException) {
                Log.w("DynamicEdge", "no WRITE_SECURE_SETTINGS; corner Assistant gesture left on")
                return
            } catch (e: Exception) {
                // key not present on this ROM
            }
        }
    }

    /** Opens the default assistant. Returns true if an Activity was launched. */
    fun launch(ctx: Context): Boolean {
        val intents = arrayOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (e: Exception) {
                // try next
            }
        }
        return false
    }
}
