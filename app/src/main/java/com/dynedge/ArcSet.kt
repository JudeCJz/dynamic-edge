package com.dynedge

import kotlin.math.max

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A stack of concentric outer-ring sliders (brightness / volume streams) with a badge at the low end of each.
 * Shared by the QUICK and VOLUME windows. Track and fill are round-capped strokes, [REST_DP] (5 dp) at rest that grow to [STROKE_DP] (9 dp) while dragged.
 * A DOWN inside an arc band claims the whole gesture (robust drag).
 */
internal class ArcSet(
    private val h: EdgeView,
    private val win: Win,
    private val badgeBase: Int,
    max: Int,
    private val badgeR: Float
) {
    var n = 0
        private set
    private val ids = arrayOfNulls<String>(max)
    private val level = FloatArray(max)
    private val icon = IntArray(max)
    private val act = BooleanArray(max)
    private val bright = BooleanArray(max)
    private val r = FloatArray(max)
    private val a0 = FloatArray(max)
    private val a1 = FloatArray(max) // mirrored about the 45 degree diagonal: a1 = 90 - a0
    private val oval = Array(max) { RectF() }
    private val bx = FloatArray(max)
    private val by = FloatArray(max)
    private val iconRect = Array(max) { Rect() }
    private val sent = FloatArray(max) { -1f }
    private val sentMs = LongArray(max)
    private val hold = LongArray(max)
    private var drag = -1
    private var tol = 0f
    // 0 = thin at rest, 1 = thick while dragged; eased per arc, frames only while it moves
    private val grow = FloatArray(max)
    private var growMs = 0L
    private val growTick = Runnable {
        win.dirty = true
        h.invalidate()
    }
    private val autoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, h.resources.displayMetrics)
    }
    private val refreshRunnable = Runnable {
        if (win.started) {
            refresh()
            h.invalidate()
        }
    }

    val dragging: Boolean get() = drag >= 0

    fun set(list: List<String>) {
        n = min(list.size, ids.size)
        for (k in ids.indices) ids[k] = if (k < n) list[k] else null
        for (k in ids.indices) {
            bright[k] = ids[k] == "brightness"
            icon[k] = 0
            act[k] = false
            level[k] = 0.5f
        }
        win.dirty = true
    }

    /**
     * Radii in px (outermost first); consecutive radii must differ by at least 2 * badgeR + 3 dp. Every arc starts
     * on ONE line: the same start angle (and so the same mirrored end angle), with all badges centred on one
     * radial line at that angle, just clear of the bottom edge.
     */
    fun layout(radii: FloatArray) {
        val sunR = badgeR
        tol = h.dp(14f)
        var rmin = Float.MAX_VALUE
        for (k in 0 until n) rmin = min(rmin, radii[k])
        if (n == 0) return
        val badgeA = Math.toDegrees(asin(((sunR + h.dp(4f)) / rmin).toDouble().coerceAtMost(1.0))).toFloat()
        val sunHalf = Math.toDegrees(asin((sunR / rmin).toDouble().coerceAtMost(1.0))).toFloat()
        // the round cap reaches half a stroke past the arc's start: keep it clear of the badge too
        val capHalf = Math.toDegrees(asin((h.dp(STROKE_DP) / 2f / rmin).toDouble().coerceAtMost(1.0))).toFloat()
        val start = badgeA + sunHalf + 2f + capHalf
        val ba = Math.toRadians(badgeA.toDouble())
        for (k in 0 until n) {
            val rr = radii[k]
            r[k] = rr
            oval[k].set(-rr, -rr, rr, rr)
            a0[k] = start
            a1[k] = Win.mirror(start)
            bx[k] = -rr * cos(ba).toFloat()
            by[k] = -rr * sin(ba).toFloat()
            val sh = (sunR * 0.6f).toInt()
            iconRect[k].set((bx[k] - sh).toInt(), (by[k] - sh).toInt(), (bx[k] + sh).toInt(), (by[k] + sh).toInt())
        }
        if (n > 1) {
            var sp = Float.MAX_VALUE
            for (k in 1 until n) sp = min(sp, abs(r[k - 1] - r[k]))
            tol = min(tol, sp / 2f + h.dp(2f))
        }
    }

    fun radius(k: Int): Float = r[k]

    fun refresh() {
        win.dirty = true
        val now = SystemClock.uptimeMillis()
        for (k in 0 until n) {
            val id = ids[k] ?: continue
            icon[k] = h.arcs.iconRes(id)
            h.warm(icon[k])
            act[k] = h.arcs.iconActive(id)
            if (drag != k && now >= hold[k]) level[k] = h.arcs.level(id).coerceIn(0f, 1f)
        }
    }

    fun draw(c: Canvas, a: Float, accent: Int) {
        val ai = (255f * a + 0.5f).toInt()
        val p = h.arcPaint
        val now = SystemClock.uptimeMillis()
        val dt = if (growMs == 0L) 16L else (now - growMs).coerceIn(0L, 50L)
        growMs = now
        var moving = false
        for (k in 0 until n) {
            val tg = if (drag == k) 1f else 0f
            var g = grow[k]
            if (g != tg) {
                val st = dt / GROW_MS
                g = if (g < tg) min(tg, g + st) else max(tg, g - st)
                grow[k] = g
                if (g != tg) moving = true
            }
            val e = g * g * (3f - 2f * g)
            p.strokeWidth = h.dp(REST_DP + (STROKE_DP - REST_DP) * e)
            p.color = EdgeView.C_TRACK
            p.alpha = ai
            c.drawArc(oval[k], 180f + a0[k], a1[k] - a0[k], false, p)
            val lv = level[k].coerceIn(0f, 1f)
            if (lv > 0.001f) {
                p.color = accent
                p.alpha = ai
                c.drawArc(oval[k], 180f + a0[k], (a1[k] - a0[k]) * lv, false, p)
            }
            drawBadge(c, k, a, accent)
        }
        if (moving) h.postOnAnimation(growTick) else growMs = 0L
    }

    private fun drawBadge(c: Canvas, k: Int, a: Float, accent: Int) {
        val x = bx[k]
        val y = by[k]
        val ps = win.isPressed(badgeBase + k)
        val ai = (255f * a + 0.5f).toInt()
        if (ps) {
            c.save()
            c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
        }
        val on = act[k]
        h.fillP.color = if (on) accent else EdgeView.C_FILL
        h.fillP.alpha = ai
        c.drawCircle(x, y, badgeR, h.fillP)
        if (ps) {
            h.ringPaint.alpha = ai
            c.drawCircle(x, y, badgeR + h.dp(3f), h.ringPaint)
        }
        if (icon[k] != 0) {
            val ic = h.icon(icon[k], on)
            ic.bounds = iconRect[k]
            ic.alpha = ai
            ic.draw(c)
        }
        if (ps) c.restore()
        if (on && bright[k]) {
            autoPaint.alpha = ai
            c.drawText("A", x + h.dp(6f), y + h.dp(11f), autoPaint)
        }
    }

    /** Badge index under the point or -1. */
    fun badgeHit(lx: Float, ly: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val hr = h.dp(22f)
        for (k in 0 until n) {
            val dx = lx - bx[k]
            val dy = ly - by[k]
            val d2 = dx * dx + dy * dy
            if (d2 <= hr * hr && d2 < bestD) {
                best = k
                bestD = d2
            }
        }
        return best
    }

    /** Arc whose band is under the point, or -1. */
    fun bandAt(lx: Float, ly: Float): Int {
        if (n == 0) return -1
        val pr = Geo.radius(lx, ly)
        val pa = Geo.angle(lx, ly)
        var best = -1
        var bestD = tol
        for (k in 0 until n) {
            val d = abs(pr - r[k])
            if (d < bestD && pa > a0[k] - 3f && pa < a1[k] + 3f) {
                best = k
                bestD = d
            }
        }
        return best
    }

    fun capture(lx: Float, ly: Float): Boolean {
        val best = bandAt(lx, ly)
        if (best < 0) return false
        drag = best
        h.postOnAnimation(growTick)
        return true
    }

    fun badgeX(k: Int): Float = bx[k]
    fun badgeY(k: Int): Float = by[k]
    val badgeRadius: Float get() = badgeR

    fun dragTo(lx: Float, ly: Float) {
        val k = drag
        if (k < 0) return
        val pa = Geo.angle(lx, ly)
        val lv = ((pa - a0[k]) / (a1[k] - a0[k])).coerceIn(0f, 1f)
        if (level[k] != lv) {
            level[k] = lv
            win.dirty = true
        }
        val now = SystemClock.uptimeMillis()
        if (abs(lv - sent[k]) > 0.004f && now - sentMs[k] >= 16L) {
            sent[k] = lv
            sentMs[k] = now
            val id = ids[k]
            if (id != null) h.arcs.setLevel(id, lv)
        }
        h.invalidate()
    }

    fun dragEnd(cancel: Boolean) {
        val k = drag
        if (k < 0) return
        drag = -1
        h.postOnAnimation(growTick)
        if (!cancel) {
            val id = ids[k]
            if (id != null) {
                h.arcs.setLevel(id, level[k])
                h.arcs.commit(id)
            }
            // keep the dragged value as truth while the setting catches up
            hold[k] = SystemClock.uptimeMillis() + 500L
            h.removeCallbacks(refreshRunnable)
            h.postDelayed(refreshRunnable, 520L)
            h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (win.started) {
            refresh()
        }
    }

    fun tap(k: Int) {
        val id = ids.getOrNull(k) ?: return
        h.arcs.tapIcon(id)
        refresh()
        h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun release() {
        h.removeCallbacks(refreshRunnable)
        h.removeCallbacks(growTick)
        drag = -1
        grow.fill(0f)
        growMs = 0L
    }

    companion object {
        /** Stroke width (dp) while an arc is dragged; layout keeps neighbours clear of this width. */
        const val STROKE_DP = 9f
        /** Stroke width (dp) at rest. */
        const val REST_DP = 5f
        /** Time (ms) to grow from rest to the dragged width. */
        const val GROW_MS = 140f
    }
}
