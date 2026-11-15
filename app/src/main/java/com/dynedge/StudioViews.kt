package com.dynedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =====================================================================================================
// Round icon controls
// =====================================================================================================

/**
 * A 48 dp round icon control. As a toggle ([toggle] = true) it is a filled accent disc with dark ink when on and
 * a quiet ring when off; as a button it is always filled ([filled]) or a plain icon.
 */

class RoundIcon(ctx: Context, res: Int, desc: String, private val toggle: Boolean) : View(ctx) {
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var checked = false
        set(v) { field = v; refreshState(); invalidate() }
    /** Button style: a filled accent disc (true) or a plain icon (false). */
    var filled = false
        set(v) { field = v; invalidate() }
    /** Ink when not filled. */
    var ink = Skin.TEXT_2
        set(v) { field = v; invalidate() }
    var onToggle: (Boolean) -> Unit = {}
    var onClick: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val px = PixelMode.on(ctx)
    private val grid = if (px) PixelMode.gridPx(ctx) else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = !px }
    private val icon: Drawable = PixelMode.wrapIcon(ctx, ctx.getDrawable(res)!!.mutate())

    init {
        isClickable = true
        isFocusable = true
        contentDescription = desc
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (toggle) {
                checked = !checked
                onToggle(checked)
            } else {
                onClick()
            }
        }
        refreshState()
    }

    private fun refreshState() {
        if (toggle) stateDescription = if (checked) "On" else "Off"
    }

    @Suppress("DEPRECATION") // the replacements are API 36+; minSdk is 30
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (toggle) {
            info.className = "android.widget.Switch"
            info.isCheckable = true
            info.isChecked = checked
        } else {
            info.className = "android.widget.Button"
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (48 * d + 0.5f).toInt()
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = if (px) PixelMode.snap(20 * d, grid) else 20 * d
        val solid = if (toggle) checked else filled
        if (solid) {
            p.style = Paint.Style.FILL
            p.color = accent
            c.drawDisc(px, cx, cy, r, p)
        } else if (toggle) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (px) grid else 1.5f * d
            p.color = Skin.TRACK
            c.drawDisc(px, cx, cy, r - p.strokeWidth / 2f, p)
        }
        val s = (20 * d).toInt()
        val l = (cx - s / 2f).toInt()
        val t = (cy - s / 2f).toInt()
        icon.setBounds(l, t, l + s, t + s)
        icon.setTint(if (solid) Skin.onColor(accent) else ink)
        icon.alpha = if (isEnabled) 255 else 90
        icon.draw(c)
    }
}

/**
 * A flat on/off switch: a 44 x 26 dp track with a knob. The track is the accent colour when on.
 * Used by the settings rows, so a toggle reads as a toggle and not as a button.
 */
class ToggleSwitch(ctx: Context, private val desc: String) : View(ctx) {
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var checked = false
        set(v) { field = v; refreshState(); invalidate() }
    var onToggle: (Boolean) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val px = PixelMode.on(ctx)
    private val grid = if (px) PixelMode.gridPx(ctx) else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = !px }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = desc
        setOnClickListener {
            checked = !checked
            onToggle(checked)
        }
        refreshState()
    }

    private fun refreshState() {
        stateDescription = if (checked) "On" else "Off"
    }

    @Suppress("DEPRECATION")
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((52 * d).toInt(), w), resolveSize((44 * d).toInt(), h))
    }

    override fun onDraw(c: Canvas) {
        val th = if (px) PixelMode.snap(26 * d, grid * 2f) else 26 * d
        val tw = if (px) PixelMode.snap(44 * d, grid * 2f) else 44 * d
        val l = if (px) PixelMode.snap((width - tw) / 2f, grid) else (width - tw) / 2f
        val t = if (px) PixelMode.snap((height - th) / 2f, grid) else (height - th) / 2f
        val r = th / 2f
        p.style = Paint.Style.FILL
        p.color = if (checked) accent else Skin.TRACK
        c.drawPill(px, l, t, l + tw, t + th, r, p)
        p.color = if (checked) Skin.onColor(accent) else Skin.TEXT_2
        if (px) {
            // a square knob that snaps to the left or right end of the track
            val k = th - 2 * grid
            val kl = if (checked) l + tw - grid - k else l + grid
            c.drawRect(kl, t + grid, kl + k, t + grid + k, p)
        } else {
            val kx = if (checked) l + tw - r else l + r
            c.drawCircle(kx, t + r, r - 2.5f * d, p)
        }
    }
}

/** A colour swatch cell for a custom colour: a hue ring, filled with the colour when it is the chosen one. */
class HueCell(ctx: Context) : View(ctx) {
    var colour = 0
        set(v) { field = v; invalidate() }
    var chosen = false
        set(v) { field = v; invalidate() }
    var ring = Color.WHITE

    private val d = resources.displayMetrics.density
    private val px = PixelMode.on(ctx)
    private val grid = if (px) PixelMode.gridPx(ctx) else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = !px }
    private var sweep: Shader? = null

    init {
        isClickable = true
        contentDescription = "Custom colour"
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null
        )
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        val r = if (px) max(grid, PixelMode.snap(half * 0.64f, grid)) else half * 0.64f
        val sw = if (px) 2 * grid else 3.5f * d
        if (chosen) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = colour
            c.drawDisc(px, cx, cy, r - sw, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.shader = sweep
        c.drawDisc(px, cx, cy, r - sw / 2f, p)
        p.shader = null
        if (chosen) {
            p.strokeWidth = if (px) grid else 2 * d
            p.color = ring
            c.drawDisc(px, cx, cy, if (px) r + 2 * grid else half * 0.86f, p)
        }
    }
}

// =====================================================================================================
// Edge thumbnails
// =====================================================================================================

/**
 * A mini bean drawn with the real look (colours through [EdgePaint], texture / glyph / visualizer through
 * [Looks]): texture choices, idle glyphs, animated visualizer styles and saved looks.
 */
class EdgeThumb(ctx: Context, private val kind: Int, val style: String) : View(ctx) {
    companion object {
        const val TEXTURE = 0
        const val IDLE = 1
        const val VIZ = 2
        const val LOOK = 3
        private val STILL = floatArrayOf(0.9f, 0.55f, 0.75f, 0.4f)
    }

    var chosen = false
        set(v) { field = v; stateDescription = if (v) "Selected" else null; invalidate() }
    var ring = Color.WHITE
    var dim = false
        set(v) { field = v; alpha = if (v) 0.4f else 1f }
    /** Visualizer levels (shared with the canvas music demo). */
    var levels: FloatArray = STILL

    private var base = Color.BLACK
    private var grad1 = 0
    private var grad2 = 0
    private var pct = 35
    private var gradOn = true
    private var accent = Color.WHITE
    private var fill = Prefs.FILL_FLAT
    private var idle = "glyph"

    private val d = resources.displayMetrics.density
    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val looks = Looks()
    private var shader: Shader? = null
    private var key = 0L
    var sizeDp = 56f

    init {
        isClickable = true
    }

    /** Read the current look from Prefs. */
    fun load(c: Context) {
        base = Prefs.base(c)
        grad1 = Prefs.grad1(c)
        grad2 = Prefs.grad2(c)
        pct = Prefs.gradient(c)
        gradOn = Prefs.gradOn(c)
        accent = Prefs.accent(c) or (0xFF shl 24)
        fill = Prefs.fill(c)
        idle = Prefs.idleStyle(c)
        invalidate()
    }

    /** Read a saved look (missing keys fall back to the defaults). */
    fun loadMap(m: Map<String, Any?>) {
        // the look is black and white plus the accent: base, gradient, texture and idle glyph are fixed
        base = Color.BLACK
        grad1 = 0
        grad2 = 0
        pct = 0
        gradOn = false
        accent = ((m[Prefs.KEY_ACCENT] as? Int) ?: Prefs.ACCENTS[0]) or (0xFF shl 24)
        fill = Prefs.FILL_FLAT
        idle = "glyph"
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = (sizeDp * d + 0.5f).toInt()
        setMeasuredDimension(resolveSize(s, wSpec), resolveSize(s, hSpec))
    }

    override fun onDraw(c: Canvas) {
        // the bean fills a centred square of at most sizeDp
        val side = min(min(width, height).toFloat(), sizeDp * d)
        c.save()
        c.translate((width - side) / 2f, (height - side) / 2f)
        drawBean(c, side)
        c.restore()
    }

    private fun drawBean(c: Canvas, side: Float) {
        val w = side
        val h = side
        val s = w * 0.84f
        path.rewind()
        path.moveTo(w - s, h)
        path.cubicTo(w - s, h - s * 0.5523f, w - s * 0.5523f, h - s, w, h - s)
        path.lineTo(w, h)
        path.close()
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = base
        c.drawPath(path, p)
        if (gradOn && pct > 0) {
            var k = 7L
            k = k * 1000003L + base; k = k * 1000003L + grad1; k = k * 1000003L + grad2
            k = k * 1000003L + pct; k = k * 1000003L + accent; k = k * 1000003L + s.toLong()
            if (shader == null || k != key) {
                key = k
                shader = EdgePaint.shapeShaderAt(w, h, s * 1.15f, base, grad1, grad2, pct, accent)
            }
            p.shader = shader
            c.drawPath(path, p)
            p.shader = null
        }
        looks.accent = accent
        val unit = s / 48f
        val tex = when (kind) {
            TEXTURE -> style
            LOOK -> fill
            else -> Prefs.FILL_FLAT
        }
        if (tex == Prefs.FILL_DOTS) {
            c.save()
            c.clipPath(path)
            c.translate(w, h)
            looks.drawPattern(c, tex, s * 1.05f, unit, 3.2f, 0f)
            c.restore()
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        if (chosen) {
            p.strokeWidth = 2.5f * d
            p.color = ring
            c.drawPath(path, p)
        }
        c.save()
        c.translate(w, h)
        when (kind) {
            IDLE -> looks.drawIdle(c, style, s, 1f, 0f)
            VIZ -> looks.drawViz(c, style, s, false, levels, 1f, unit)
            LOOK -> looks.drawIdle(c, idle, s, 1f, 0f)
        }
        c.restore()
    }
}

