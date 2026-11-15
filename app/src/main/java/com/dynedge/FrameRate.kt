package com.dynedge

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs

/**
 * Asks the display for 90 Hz. Some ROMs (vivo) drop apps that do not ask to 60 Hz.
 * Activities ask for as long as they are shown; the overlay asks only while it moves or is open, so it never
 * overrides a game's own rate while the edge sits idle.
 */
object FrameRate {
    const val HZ = 90f

    /** Id of the display mode closest to [HZ] at the current resolution (0 = none / no preference). */
    fun modeId(ctx: Context): Int {
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 0
        val cur = d.mode
        var best: Display.Mode? = null
        for (m in d.supportedModes) {
            if (m.physicalWidth != cur.physicalWidth || m.physicalHeight != cur.physicalHeight) continue
            if (m.refreshRate < HZ - 1f) continue
            if (best == null || abs(m.refreshRate - HZ) < abs(best.refreshRate - HZ)) best = m
        }
        return best?.modeId ?: 0
    }

    /** Applies the vote to window params; returns true when anything changed. */
    fun set(ctx: Context, p: WindowManager.LayoutParams, on: Boolean): Boolean {
        val id = if (on) modeId(ctx) else 0
        val hz = if (on && id != 0) HZ else 0f
        if (p.preferredDisplayModeId == id && p.preferredRefreshRate == hz) return false
        p.preferredDisplayModeId = id
        p.preferredRefreshRate = hz
        return true
    }

    fun apply(activity: Activity) {
        val w = activity.window
        val p = w.attributes
        if (set(activity, p, true)) w.attributes = p
    }
}
