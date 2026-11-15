package com.dynedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DEVICE window: display-only arcs for battery, RAM and storage. All three arcs start on one horizontal line
 * (mirrored end), their icon badges sit in one horizontal row at the start of each arc, and the battery percentage and the
 * temperature are set along arcs inside the innermost track. DeviceStats runs only while visible.
 */
internal class WinDevice(h: EdgeView) : Win(h, "device", 1) {

    private val stats = DeviceStats(h.context) {
        if (shown) {
            refresh()
            h.invalidate()
        }
    }
    private var shown = false
    private val oval = Array(3) { RectF() }
    private val r = FloatArray(3)
    private val a0 = FloatArray(3)
    private val a1 = FloatArray(3)
    private val labelPath = Array(3) { Path() }
    private val label = arrayOf("Battery", "RAM", "Storage")
    private val frac = FloatArray(3)
    private val bx = FloatArray(3)
    private val by = FloatArray(3)
    private val badgeRect = Array(3) { Rect() }
    private var badgeR = 0f
    private var pctStr = "0%"
    private var tempStr = ""
    private var charging = false
    private val pctPath = Path()
    private val tempPath = Path()
    private var pctLen = 0f
    private var tempLen = 0f
    private var pctOff = 0f
    private var tempOff = 0f
    private val sb = StringBuilder(16)
    private val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = h.face(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    private val tempPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = EdgeView.C_GREY
        textSize = h.sp(12f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = EdgeView.C_GREY
        textSize = h.sp(10f)
    }

    // window editor options: which stats show (Prefs.KEY_DEV_*)
    private val showArc = booleanArrayOf(true, true, true)
    private var showTemp = true

    override fun layout(hw: Float, brad: Float) {
        val ctx = h.context
        showArc[0] = Prefs.flag(ctx, Prefs.KEY_DEV_BAT)
        showArc[1] = Prefs.flag(ctx, Prefs.KEY_DEV_RAM)
        showArc[2] = Prefs.flag(ctx, Prefs.KEY_DEV_DISK)
        showTemp = Prefs.flag(ctx, Prefs.KEY_DEV_TEMP)
        badgeR = dp(10f)
        val r0 = min(0.86f * rad, rad - dp(14f))
        val step = max(0.14f * rad, 2f * badgeR + dp(4f)) // icons never touch
        for (k in 0 until 3) {
            r[k] = r0 - k * step
            oval[k].set(-r[k], -r[k], r[k], r[k])
        }
        // All three badge centres sit on one horizontal line (the battery badge's height); every arc starts on
        // one horizontal line just above the badges, so the badges line up in a row and each sits at the start
        // of its own arc. The angles differ per ring, the start lines do not.
        val rmin = r[2]
        val badgeA = Math.toDegrees(asin(((badgeR + dp(4f)) / rmin).toDouble().coerceAtMost(1.0))).toFloat()
        val yBadge = min(r[0] * sin(Math.toRadians(badgeA.toDouble())).toFloat(), rmin * 0.5f)
        val yStart = yBadge + badgeR + dp(2f)
        val s = (badgeR * 0.6f).toInt()
        for (k in 0 until 3) {
            a0[k] = Math.toDegrees(asin((yStart / r[k]).toDouble().coerceAtMost(1.0))).toFloat()
            a1[k] = mirror(a0[k])
            bx[k] = -sqrt(max(r[k] * r[k] - yBadge * yBadge, 0f))
            by[k] = -yBadge
            badgeRect[k].set((bx[k] - s).toInt(), (by[k] - s).toInt(), (bx[k] + s).toInt(), (by[k] + s).toInt())
            // labels sit just inside each track, after the icon
            textArcPath(labelPath[k], r[k] - dp(15f), a0[k] + 3f, a1[k] - 3f)
        }
        // percentage and temperature follow the circle inside the innermost label, centred on the diagonal
        pctPaint.textSize = min(h.sp(30f), 0.14f * rad)
        val capP = 0.72f * pctPaint.textSize
        val capT = 0.72f * tempPaint.textSize
        val rp = max(r[2] - dp(15f) - dp(5f) - capP, dp(30f))
        val rt = max(rp - dp(8f) - capT, dp(14f))
        textArcPath(pctPath, rp, 8f, 82f)
        textArcPath(tempPath, rt, 8f, 82f)
        val span = Math.toRadians(74.0).toFloat()
        pctLen = rp * span
        tempLen = rt * span
        h.warm(R.drawable.ic_battery)
        h.warm(R.drawable.ic_memory)
        h.warm(R.drawable.ic_hard_drive)
        h.warm(R.drawable.ic_bolt)
        if (started) refresh()
    }

    override fun start() {
        // DeviceStats itself runs only while this window is the visible one (onShown)
    }

    override fun stop() {
        shown = false
        stats.stop()
    }

    override fun onShown(on: Boolean) {
        shown = on
        if (on) stats.start() else stats.stop()
    }

    private fun refresh() {
        dirty = true
        frac[0] = stats.batteryPct / 100f
        frac[1] = stats.ramUsedFrac
        frac[2] = stats.storageUsedFrac
        charging = stats.charging
        sb.setLength(0)
        sb.append(stats.batteryPct).append('%')
        pctStr = sb.toString()
        sb.setLength(0)
        sb.append(Math.round(stats.tempC)).append(" C")
        tempStr = sb.toString()
        pctOff = max(0f, (pctLen - pctPaint.measureText(pctStr)) / 2f)
        tempOff = max(0f, (tempLen - tempPaint.measureText(tempStr)) / 2f)
    }

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        val ap = h.arcPaint
        pctPaint.color = h.ink
        tempPaint.color = h.cGrey
        labelPaint.color = h.cGrey
        for (k in 0 until 3) {
            if (!showArc[k]) continue
            ap.strokeWidth = h.sw(dp(5f))
            ap.color = h.cTrack
            ap.alpha = ai
            c.drawArc(oval[k], 180f + a0[k], a1[k] - a0[k], false, ap)
            val lv = frac[k].coerceIn(0f, 1f)
            if (lv > 0.001f) {
                ap.color = if (k == 0) h.accent else h.ink
                ap.alpha = if (k == 0) ai else (255f * 0.85f * a + 0.5f).toInt()
                c.drawArc(oval[k], 180f + a0[k], (a1[k] - a0[k]) * lv, false, ap)
            }
            labelPaint.alpha = ai
            h.arcText(c, label[k], labelPath[k], 0f, labelPaint)
            // icon badge on the common radial line
            val hot = k == 0 && charging
            h.fillP.color = if (hot) h.accent else h.cFill
            h.fillP.alpha = ai
            c.drawCircle(bx[k], by[k], badgeR, h.fillP)
            val res = when (k) {
                0 -> if (charging) R.drawable.ic_bolt else R.drawable.ic_battery
                1 -> R.drawable.ic_memory
                else -> R.drawable.ic_hard_drive
            }
            val ic = h.icon(res, hot)
            ic.bounds = badgeRect[k]
            ic.alpha = ai
            h.drawIc(c, ic)
        }
        pctPaint.alpha = ai
        if (showArc[0]) h.arcText(c, pctStr, pctPath, pctOff, pctPaint)
        tempPaint.alpha = ai
        if (showTemp) h.arcText(c, tempStr, tempPath, tempOff, tempPaint)
    }
}
