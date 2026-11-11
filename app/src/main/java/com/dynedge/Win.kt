package com.dynedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import kotlin.math.max
import kotlin.math.min

/**
 * One panel window (QUICK, MEDIA, ...). A window owns its own layout, drawing and hit testing in the
 * unrotated polar frame whose origin is the screen corner (x and y are negative inside the panel).
 * The host ([EdgeView]) owns gestures, springs, paging, section layers and the shared paints.
 *
 * Item ids are local to the window (0 until its item count); the host adds the window to the pressed state.
 */
internal abstract class Win(@JvmField protected val h: EdgeView, val id: String, items: Int) {

    /** True while the section layer must be re-recorded. */
    @JvmField var dirty = true
    @JvmField var node: RenderNode? = null
    @JvmField var index = -1
    /** True between start() and stop() (panel open, listeners registered). */
    @JvmField var started = false

    @JvmField protected val cx = FloatArray(items)
    @JvmField protected val cy = FloatArray(items)
    @JvmField protected val ir = FloatArray(items)
    @JvmField protected val hitR = FloatArray(items)
    @JvmField protected val iconRect = Array(items) { Rect() }

    @JvmField protected var pr = 0f // polar scratch: radius and angle (deg from the bottom edge)
    @JvmField protected var pa = 0f
    private var hBest = -1
    private var hBestD = 0f
    private val bandOval = RectF()

    protected fun dp(v: Float): Float = h.dp(v)
    protected val rad: Float get() = h.rad

    // ---------------------------------------------------------------- lifecycle
    /** Computes geometry for the current panel radius. */
    open fun layout(hw: Float, brad: Float) {}
    /** Panel opened: register listeners and load data. */
    open fun start() {}
    /** Panel closed or window removed: release everything. */
    open fun stop() {}
    /** This window became (true) or stopped being (false) the visible, settled window. */
    open fun onShown(on: Boolean) {}
    /** A per-window option changed (called before the next layout); reload anything layout() does not. */
    open fun onPrefs() {}
    /** Shared outer-ring sliders (Arcs) reported a change. */
    open fun onArcs() {}

    // ---------------------------------------------------------------- drawing
    abstract fun draw(c: Canvas, a: Float)

    // ---------------------------------------------------------------- touch (local, unrotated coordinates)
    /** Item under the point or -1. */
    open fun hit(lx: Float, ly: Float): Int = -1
    open fun usable(item: Int): Boolean = true
    open fun longPressable(item: Int): Boolean = false
    /** Hold time for a long-press on [item] in ms, or -1 for the system default. */
    open fun holdMs(item: Int): Long = -1L
    open fun onTap(item: Int) {}
    /** Returns true if an Activity was launched (the host collapses). */
    open fun onLongPress(item: Int): Boolean = false
    /** DOWN landed on a draggable control (arc slider, seek bar, dial): claim the whole gesture. */
    open fun capture(lx: Float, ly: Float): Boolean = false
    open fun dragTo(lx: Float, ly: Float) {}
    open fun dragEnd(cancel: Boolean) {}

    fun isPressed(i: Int): Boolean = h.pressed == i && h.pressedWin === this

    // ---------------------------------------------------------------- helpers
    protected fun polar(lx: Float, ly: Float) {
        pr = Geo.radius(lx, ly)
        pa = Geo.angle(lx, ly)
    }

    protected fun beginHit() {
        hBest = -1
        hBestD = Float.MAX_VALUE
    }

    protected fun consider(i: Int, lx: Float, ly: Float) {
        val dx = lx - cx[i]
        val dy = ly - cy[i]
        val d2 = dx * dx + dy * dy
        val hr = hitR[i]
        if (d2 <= hr * hr && d2 < hBestD) {
            hBest = i
            hBestD = d2
        }
    }

    protected fun bestHit(): Int = hBest

    protected fun forceHit(i: Int) {
        hBest = i
    }

    /** Places item i on a ring (radius rc, angle aDeg) shrunk to stay inside the curve. */
    protected fun place(i: Int, rc: Float, aDeg: Float, itemR: Float) {
        val r = min(rc, rad - dp(6f) - itemR)
        val a = Math.toRadians(aDeg.toDouble())
        cx[i] = -r * kotlin.math.cos(a).toFloat()
        cy[i] = -r * kotlin.math.sin(a).toFloat()
        ir[i] = itemR
        hitR[i] = max(itemR + dp(3f), dp(22f)) // 44 dp minimum touch target
        val half = (itemR * 0.475f).toInt().coerceAtLeast(1)
        val x = cx[i].toInt()
        val y = cy[i].toInt()
        iconRect[i].set(x - half, y - half, x + half, y + half)
    }

    protected fun setIconRect(i: Int, half: Int) {
        val x = cx[i].toInt()
        val y = cy[i].toInt()
        iconRect[i].set(x - half, y - half, x + half, y + half)
    }

    protected fun arcPath(p: Path, r: Float, a0: Float, a1: Float) = Geo.arcPath(p, bandOval, r, a0, a1)

    /** Round button: optional fill disc, press ring and icon. */
    protected fun drawBtn(c: Canvas, i: Int, fillColor: Int, res: Int, black: Boolean, a: Float, ba: Float) {
        val x = cx[i]
        val y = cy[i]
        val ps = isPressed(i)
        if (ps) {
            c.save()
            c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
        }
        val ai = (255f * ba + 0.5f).toInt()
        if (fillColor != 0) {
            h.fillP.color = fillColor
            h.fillP.alpha = ai
            c.drawCircle(x, y, ir[i], h.fillP)
        }
        if (ps) {
            // clear pressed state: a translucent white wash over the button plus the accent ring
            h.fillP.color = Color.WHITE
            h.fillP.alpha = (255f * 0.16f * ba + 0.5f).toInt()
            c.drawCircle(x, y, ir[i], h.fillP)
            h.ringPaint.alpha = (255f * a + 0.5f).toInt()
            c.drawCircle(x, y, ir[i] + dp(3f), h.ringPaint)
        }
        if (res != 0) {
            val ic = h.icon(res, black)
            ic.bounds = iconRect[i]
            ic.alpha = ai
            ic.draw(c)
        }
        if (ps) c.restore()
    }

    companion object {
        const val SL_A0 = 8f
        const val SL_A1 = 82f

        fun slotAngle(i: Int, n: Int): Float = if (n <= 1) 45f else 12f + i * 66f / (n - 1)

        /** End angle of an arc that starts at [a0]: mirrored about the 45 degree diagonal. */
        fun mirror(a0: Float): Float = 90f - a0
    }
}

/**
 * Polar helpers shared by the host and every window. The frame has its origin at the screen corner;
 * angles are degrees measured from the bottom edge toward the right edge (0..90 inside the panel).
 */
internal object Geo {
    fun radius(lx: Float, ly: Float): Float = kotlin.math.sqrt(lx * lx + ly * ly)

    fun angle(lx: Float, ly: Float): Float =
        Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()

    /** Rewinds [p] to one open arc of radius [r] from [a0] to [a1] degrees; [oval] is scratch. */
    fun arcPath(p: Path, oval: RectF, r: Float, a0: Float, a1: Float) {
        oval.set(-r, -r, r, r)
        p.rewind()
        p.arcTo(oval, 180f + a0, a1 - a0, true)
    }
}
