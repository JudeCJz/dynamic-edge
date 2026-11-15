package com.dynedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** Marker for controls that own horizontal drags: the tab pager never steals a gesture that starts on one. */
interface HGrab

/**
 * Icon-led flat slider: an optional Lucide icon on the left (no text label), a 4 dp track with the accent
 * fill and a 20 dp thumb. While dragging the track eases up to 8 dp and the thumb grows into a pill that
 * shows the value - the only time a number is on screen. The touch cell is 48 dp tall.
 *
 * Vertical scrolling wins: the slider only starts dragging after clear horizontal movement beyond the touch
 * slop, and only then asks the parents to stop intercepting. A vertical gesture over the slider is left to
 * the enclosing ScrollView. A plain tap on the track still sets the value. No allocations while drawing.
 */
class FlatSlider(ctx: Context) : View(ctx), HGrab {
    var min = 0f
    var max = 100f
    /** Optional default value, drawn as a small tick; reaching it while dragging gives a haptic tick. */
    var tick: Float? = null
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    /** Leading icon (tinted [Skin.TEXT_2], accent while dragging). */
    var icon: Drawable? = null
        set(v) { field = v?.mutate(); invalidate() }
    var onStart: () -> Unit = {}
    var onChange: (Float) -> Unit = {}
    var onStop: () -> Unit = {}

    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.TRACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val chars = CharArray(8)
    private var dragging = false
    // 0 = thin at rest, 1 = thick while dragging (eased)
    private var g = 0f
    private var gMs = 0L
    private var started = false
    private var downX = 0f
    private var downY = 0f
    private var decided = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (48 * d + 0.5f).toInt())
    }

    // inset by the grown thumb so it is never clipped at either end
    private fun left() = paddingLeft + (if (icon != null) 52f else 18f) * d
    private fun right() = width - paddingRight - 14 * d

    override fun onDraw(c: Canvas) {
        val tg = if (dragging) 1f else 0f
        if (g != tg) {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (gMs == 0L) 8L else (now - gMs).coerceIn(0L, 50L)
            gMs = now
            g = if (g < tg) kotlin.math.min(tg, g + dt / 140f) else kotlin.math.max(tg, g - dt / 140f)
            if (g != tg) postInvalidateOnAnimation() else gMs = 0L
        }
        val e = g * g * (3f - 2f * g)
        val cy = height / 2f
        icon?.let {
            val s = (22 * d).toInt()
            val x = paddingLeft + (12 * d).toInt()
            val y = (cy - s / 2f).toInt()
            it.setBounds(x, y, x + s, y + s)
            it.setTint(if (dragging) accent else Skin.TEXT_2)
            it.draw(c)
        }
        val th = (4 + 4 * e) * d
        val l = left()
        val r = right()
        rect.set(l, cy - th / 2, r, cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, track)
        val f = if (max > min) (value - min) / (max - min) else 0f
        val fr = l + (r - l) * f
        fill.color = accent
        rect.set(l, cy - th / 2, fr, cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, fill)
        val t = tick
        if (t != null && max > min) {
            val tx = l + (r - l) * (t - min) / (max - min)
            fill.color = Skin.TEXT_3
            c.drawRect(tx - 0.75f * d, cy - th / 2 - 5 * d, tx + 0.75f * d, cy - th / 2 - 2 * d, fill)
        }
        fill.color = accent
        if (e > 0.02f) {
            // the thumb becomes a value pill while dragging
            val n = digits(Math.round(value))
            val tw = text.measureText(chars, 0, n)
            val hw = 10 * d + (maxOf(16 * d, tw / 2 + 9 * d) - 10 * d) * e
            val hh = (10 + 4 * e) * d
            rect.set(fr - hw, cy - hh, fr + hw, cy + hh)
            c.drawRoundRect(rect, hh, hh, fill)
            text.color = Skin.onColor(accent)
            text.alpha = (255f * e + 0.5f).toInt()
            c.drawText(chars, 0, n, fr, cy - (text.ascent() + text.descent()) / 2f, text)
        } else {
            c.drawCircle(fr, cy, 10 * d, fill)
        }
    }

    private fun digits(v0: Int): Int {
        var v = if (v0 < 0) -v0 else v0
        var n = 0
        do {
            chars[n++] = '0' + v % 10
            v /= 10
        } while (v > 0 && n < chars.size - 1)
        if (v0 < 0) chars[n++] = '-'
        var i = 0
        var j = n - 1
        while (i < j) {
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
            i++; j--
        }
        return n
    }

    private fun setFromX(x: Float) {
        val l = left()
        val r = right()
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        set(Math.round(min + (max - min) * f).toFloat())
    }

    private fun set(nv0: Float) {
        val nv = nv0.coerceIn(min, max)
        if (nv != value) {
            value = nv
            if (tick != null && nv == tick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(nv)
        }
    }

    private fun begin() {
        if (!started) {
            started = true
            onStart()
        }
    }

    private fun finish(up: Boolean) {
        if (started) {
            started = false
            if (up) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onStop()
        }
        if (dragging) {
            dragging = false
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                decided = false
                dragging = false
                started = false
                // a slider inside a scrolling list must not be claimed before the direction is known
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (dx > slop && dx > dy) {
                        decided = true
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        begin()
                        setFromX(e.x)
                        invalidate()
                    } else if (dy > slop) {
                        decided = true // vertical: leave it to the ScrollView
                    }
                } else if (dragging) {
                    setFromX(e.x)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!decided && e.x >= left() - 14 * d) {
                    // a tap on the track sets the value (a tap on the icon does nothing)
                    begin()
                    setFromX(e.x)
                }
                finish(true)
            }
            MotionEvent.ACTION_CANCEL -> finish(false)
        }
        return true
    }

    // ---------------------------------------------------------------- accessibility

    @Suppress("DEPRECATION") // the replacements are API 36+; minSdk is 30
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min, max, value
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.stateDescription = Math.round(value).toString()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = maxOf(1f, Math.round((max - min) / 20f).toFloat())
        val nv = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> value + step
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> value - step
            else -> return super.performAccessibilityAction(action, arguments)
        }
        onStart()
        set(nv)
        onStop()
        return true
    }
}
