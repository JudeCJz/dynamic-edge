package com.dynedge

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.VectorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel mode: the whole app drawn as pixel art. Toggled at the bottom of the settings page
 * ([Prefs.pixelMode]); the size of one art pixel is [Prefs.pixelSizeDp].
 *
 * Settings screens: [apply] restyles an activity (pixel font, square corners, pixelated icons) and keeps
 * restyling views that are added later. Overlay: [PixelLayer] renders a frame at low resolution and scales
 * it up without filtering.
 */
object PixelMode {

    fun on(ctx: Context): Boolean = Prefs.pixelMode(ctx)

    @Volatile private var font: Typeface? = null

    /** The pixel font when pixel mode is on, else null. */
    fun typeface(ctx: Context): Typeface? {
        if (!on(ctx)) return null
        var f = font
        if (f == null) {
            f = try { ctx.resources.getFont(R.font.pixel) } catch (e: Exception) { null }
            font = f
        }
        return f
    }

    /** One art pixel in px as a whole number of px (at least 1): the grid the settings snap their shapes to. */
    fun gridPx(ctx: Context): Float = max(1f, ctx.dpf(Prefs.pixelSizeDp(ctx)).roundToInt().toFloat())

    /** One art pixel in px, exact (what the overlay layer uses). */
    fun cellPx(ctx: Context): Float = max(1f, ctx.dpf(Prefs.pixelSizeDp(ctx)))

    /** Rounds [v] to a multiple of [grid]. */
    fun snap(v: Float, grid: Float): Float = Math.round(v / grid) * grid

    // ---------------------------------------------------------------- activities

    /**
     * Restyles [activity]'s window for pixel mode. Call right after setContentView. Walks the whole view tree
     * now and again after every layout pass, so rows and pages built later are restyled as well. No-op when
     * pixel mode is off.
     */
    fun apply(activity: Activity) {
        FrameRate.apply(activity) // every activity calls this after setContentView: ask for 90 Hz too
        if (!on(activity)) return
        val decor = activity.window.decorView
        applyTo(decor)
        decor.viewTreeObserver.addOnGlobalLayoutListener { applyTo(decor) }
    }

    /** Restyles [root] and everything under it once (also used for dialogs). No-op when pixel mode is off. */
    fun applyTo(root: View) {
        val ctx = root.context
        if (!on(ctx)) return
        val tf = typeface(ctx) ?: return
        walk(root, tf)
    }

    private fun walk(v: View, tf: Typeface) {
        if (v.getTag(R.id.pixel_done) == null) {
            v.setTag(R.id.pixel_done, true)
            squareBackground(v)
            if (v is TextView) {
                v.typeface = tf
                // one font pixel is 1/10 of the text size: keep it a whole number of px so the text stays crisp
                val s = max(10f, Math.round(v.textSize / 10f) * 10f)
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX, s)
                v.paint.isAntiAlias = false
            } else if (v is ImageView) {
                val d = v.drawable
                if (d is VectorDrawable) v.setImageDrawable(PixelIconDrawable(d, gridPx(v.context)))
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), tf)
    }

    private fun squareBackground(v: View) {
        val bg = v.background
        if (bg is GradientDrawable) {
            val m = bg.mutate() as GradientDrawable
            m.cornerRadius = 0f
            if (m.shape == GradientDrawable.OVAL || m.shape == GradientDrawable.RING) m.shape = GradientDrawable.RECTANGLE
        }
    }

    // ---------------------------------------------------------------- icons

    /** A Lucide vector drawable at [sizePx], rendered at about 16-24 px and scaled up nearest-neighbour when pixel mode is on. */
    fun icon(ctx: Context, res: Int, sizePx: Int): Drawable {
        val d = ctx.getDrawable(res)!!.mutate()
        val out = wrapIcon(ctx, d)
        out.setBounds(0, 0, sizePx, sizePx)
        return out
    }

    /** [d] pixelated when pixel mode is on and it is a vector, else [d] itself. */
    fun wrapIcon(ctx: Context, d: Drawable): Drawable =
        if (on(ctx) && d is VectorDrawable) PixelIconDrawable(d, gridPx(ctx)) else d
}

// ===========================================================================================================
// Helpers for custom views
// ===========================================================================================================

/** A disc, or a square of the same size when [square] (pixel mode). */
fun Canvas.drawDisc(square: Boolean, cx: Float, cy: Float, r: Float, p: Paint) {
    if (square) drawRect(cx - r, cy - r, cx + r, cy + r, p) else drawCircle(cx, cy, r, p)
}

/** A rounded rectangle, or a plain one when [square] (pixel mode). */
fun Canvas.drawPill(square: Boolean, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) {
    if (square) drawRect(l, t, r, b, p) else drawRoundRect(l, t, r, b, rad, rad, p)
}

/**
 * A vector icon drawn at low resolution (about 16-24 px, alpha thresholded so every pixel is solid) and scaled
 * up by a whole factor with no filtering. Takes tints and colour filters like any drawable.
 */
class PixelIconDrawable(private val src: Drawable, private val grid: Float) : Drawable() {
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private var bmp: Bitmap? = null
    private var n = 0
    private var tintColor = 0
    private var hasTint = false
    private val dst = Rect()

    override fun getIntrinsicWidth() = src.intrinsicWidth
    override fun getIntrinsicHeight() = src.intrinsicHeight

    private fun ensure(w: Int) {
        val want = (w / (grid * 0.75f)).roundToInt().coerceIn(12, 24).coerceAtMost(max(4, w))
        if (bmp != null && want == n) return
        n = want
        bmp?.recycle()
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val sb = src.bounds
        val l = sb.left; val t = sb.top; val r = sb.right; val bt = sb.bottom
        src.setBounds(0, 0, n, n)
        src.draw(Canvas(b))
        src.setBounds(l, t, r, bt)
        val px = IntArray(n * n)
        b.getPixels(px, 0, n, 0, 0, n, n)
        for (i in px.indices) {
            val a = px[i] ushr 24
            px[i] = if (a >= 90) px[i] or (0xFF shl 24) else 0
        }
        b.setPixels(px, 0, n, 0, 0, n, n)
        bmp = b
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = minOf(b.width(), b.height())
        if (w <= 0) return
        ensure(w)
        val bm = bmp ?: return
        val scale = max(1, w / n)
        val s = n * scale
        val x = b.left + (b.width() - s) / 2
        val y = b.top + (b.height() - s) / 2
        dst.set(x, y, x + s, y + s)
        c.drawBitmap(bm, null, dst, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { hasTint = false; paint.colorFilter = cf; invalidateSelf() }
    override fun setTint(color: Int) {
        if (hasTint && tintColor == color) return
        hasTint = true
        tintColor = color
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        invalidateSelf()
    }
    override fun setTintList(tint: ColorStateList?) {
        if (tint == null) { hasTint = false; paint.colorFilter = null; invalidateSelf() } else setTint(tint.defaultColor)
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun mutate(): Drawable = this
}

// ===========================================================================================================
// Overlay layer
// ===========================================================================================================

/**
 * Renders a frame at low resolution and draws it scaled up with nearest-neighbour filtering, so the whole
 * overlay looks like pixel art. One layer per view: it owns a small bitmap + canvas (reallocated only when
 * the size or cell size changes) and allocates nothing per frame.
 *
 * Shapes are drawn into the low-res bitmap with anti-aliasing and bitmap filtering forced off (a draw filter on
 * the canvas), so every low-res pixel is solid or empty; alpha is then quantised to a few levels ([crisp]).
 *
 * Text is NOT drawn into the low-res bitmap. While the block runs, [recText] / [recPath] record every text draw
 * (with the canvas matrix mapped to view coordinates) into a preallocated pool; after the bitmap is drawn,
 * the recorded text is replayed at full resolution with the pixel font, no anti-aliasing, the size snapped so
 * one glyph pixel is a whole number of device pixels and the position snapped to whole pixels.
 *
 * Usage in a view: `layer.draw(canvas, width, height, PixelMode.cellPx(ctx), drawBlock)` where `drawBlock`
 * is a lambda held in a field (a lambda created inside onDraw that captures state allocates each frame).
 * Draw in normal view pixels inside the block; the layer applies the 1/cell scale. Call [release] when the
 * view detaches.
 */
class PixelLayer {
    var crisp = true

    /** The pixel typeface used to replay text (null: monospace fallback). */
    var face: Typeface? = null

    private class TextRec {
        var s: String = ""
        var isPath = false
        val path = Path()
        var x = 0f
        var y = 0f
        var h = 0f
        var color = 0
        var size = 0f
        var scale = 1f
        var angle = 0f
        var align: Paint.Align = Paint.Align.LEFT
    }

    private var bmp: Bitmap? = null
    private var cv: Canvas? = null
    private var buf = IntArray(0)
    private var bw = 0
    private var bh = 0
    private var bCell = 0
    private val src = Rect()
    private val dst = Rect()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val filter = PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG, 0)

    private val recs = arrayOfNulls<TextRec>(MAX_TEXT)
    private var nRec = 0
    private var inBlock = false
    private var vx = 0f
    private var vy = 0f
    private var cellF = 1f
    private val tm = Matrix()
    private val mv = FloatArray(9)
    private val pts = FloatArray(2)
    private val tp = Paint().apply {
        isAntiAlias = false
        isSubpixelText = false
        isLinearText = false
        isDither = false
        isFilterBitmap = false
    }

    /**
     * The cell is snapped to whole device pixels (at least 2), the low-res bitmap is ceil(w / cell) by ceil(h / cell)
     * and is drawn upscaled by exactly the cell, its bottom-right pixel on the view's bottom-right corner (any
     * excess lies off the top-left), so no fractional cell offsets the visible corner.
     */
    fun draw(target: Canvas, w: Int, h: Int, cellPx: Float, block: (Canvas) -> Unit) {
        if (w <= 0 || h <= 0) return
        val cell = cellInt(cellPx)
        val nw = max(1, ceil(w / cell.toFloat()).toInt())
        val nh = max(1, ceil(h / cell.toFloat()).toInt())
        var b = bmp
        if (b == null || nw != bw || nh != bh) {
            b?.recycle()
            b = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            bmp = b
            cv = Canvas(b).also { it.drawFilter = filter }
            bw = nw
            bh = nh
            if (buf.size < nw * nh) buf = IntArray(nw * nh)
        }
        bCell = cell
        cellF = cell.toFloat()
        val c = cv!!
        b.eraseColor(0)
        // view pixel (x, y) lands at low-res (x + offX) / cell; offX shifts the grid so the right / bottom edge is a cell edge
        val offX = nw * cell - w
        val offY = nh * cell - h
        // anchored bottom-right: left / top may be negative (clipped by the view)
        dst.set(w - nw * cell, h - nh * cell, w, h)
        vx = dst.left.toFloat()
        vy = dst.top.toFloat()
        nRec = 0
        c.save()
        c.scale(1f / cell, 1f / cell)
        c.translate(offX.toFloat(), offY.toFloat())
        inBlock = true
        try {
            block(c)
        } finally {
            inBlock = false
        }
        c.restore()
        if (crisp) {
            val cnt = nw * nh
            b.getPixels(buf, 0, nw, 0, 0, nw, nh)
            for (i in 0 until cnt) {
                val p = buf[i]
                val a = p ushr 24
                if (a == 0 || a == 255) continue
                // a few alpha levels: 0, 85, 170, 255 (nothing visible becomes empty, nothing faint becomes invisible)
                val q = if (a < 20) 0 else if (a > 235) 255 else max(85, ((a + 42) / 85) * 85)
                buf[i] = if (q == 0) 0 else (p and 0xFFFFFF) or (q shl 24)
            }
            b.setPixels(buf, 0, nw, 0, 0, nw, nh)
        }
        src.set(0, 0, nw, nh)
        target.drawBitmap(b, src, dst, paint)
        replayText(target)
    }

    // ---------------------------------------------------------------- text

    /** Records a straight text draw made on the layer canvas; false when the layer is not recording (draw it normally). */
    fun recText(s: String, x: Float, y: Float, p: Paint): Boolean {
        if (!inBlock) return false
        val r = nextRec() ?: return true
        val c = cv ?: return false
        mapMatrix(c)
        pts[0] = x
        pts[1] = y
        tm.mapPoints(pts)
        r.isPath = false
        r.s = s
        r.x = pts[0]
        r.y = pts[1]
        fill(r, p)
        return true
    }

    /** Records a text-on-path draw (the path is copied, transformed to view coordinates, into a pooled Path). */
    fun recPath(s: String, path: Path, hOff: Float, p: Paint): Boolean {
        if (!inBlock) return false
        val r = nextRec() ?: return true
        val c = cv ?: return false
        mapMatrix(c)
        path.transform(tm, r.path)
        r.isPath = true
        r.s = s
        fill(r, p)
        r.h = hOff * r.scale
        return true
    }

    private fun nextRec(): TextRec? {
        if (cv == null || nRec >= MAX_TEXT) return null
        var r = recs[nRec]
        if (r == null) {
            r = TextRec()
            recs[nRec] = r
        }
        nRec++
        return r
    }

    @Suppress("DEPRECATION")
    private fun mapMatrix(c: Canvas) {
        c.getMatrix(tm)
        tm.postScale(cellF, cellF)
        tm.postTranslate(vx, vy)
        tm.getValues(mv)
    }

    private fun fill(r: TextRec, p: Paint) {
        val a = mv[Matrix.MSCALE_X]
        val b = mv[Matrix.MSKEW_Y]
        r.scale = hypot(a, b)
        r.angle = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        r.color = p.color
        r.size = p.textSize
        r.align = p.textAlign
    }

    private fun replayText(t: Canvas) {
        val n = nRec
        if (n == 0) return
        tp.typeface = face ?: Typeface.MONOSPACE
        for (i in 0 until n) {
            val r = recs[i] ?: continue
            // one glyph pixel = 1/10 of the text size: a whole number of device pixels
            val g = max(1, Math.round(r.size * r.scale / 10f))
            tp.textSize = g * 10f
            tp.color = r.color
            tp.textAlign = r.align
            if (r.isPath) {
                t.drawTextOnPath(r.s, r.path, r.h, 0f, tp)
            } else {
                val x = Math.round(r.x).toFloat()
                val y = Math.round(r.y).toFloat()
                if (abs(r.angle) > 0.5f) {
                    t.save()
                    t.translate(x, y)
                    t.rotate(r.angle)
                    t.drawText(r.s, 0f, 0f, tp)
                    t.restore()
                } else {
                    t.drawText(r.s, x, y, tp)
                }
            }
        }
        nRec = 0
    }

    fun release() {
        bmp?.recycle()
        bmp = null
        cv = null
        buf = IntArray(0)
        bw = 0
        bh = 0
        nRec = 0
    }

    companion object {
        private const val MAX_TEXT = 48

        /** The cell the layer really uses: whole device pixels, at least 2. */
        fun cellInt(cellPx: Float): Int = max(2, cellPx.roundToInt())
    }
}

/** Stepped (banded) radial gradients for pixel mode: each stop becomes one flat band, edges at the midpoints. */
object PixelBands {
    fun radial(radius: Float, colors: IntArray, pos: FloatArray): Shader {
        val n = colors.size
        val c2 = IntArray(n * 2)
        val p2 = FloatArray(n * 2)
        for (i in 0 until n) {
            c2[i * 2] = colors[i]
            c2[i * 2 + 1] = colors[i]
            p2[i * 2] = if (i == 0) 0f else (pos[i - 1] + pos[i]) / 2f
            p2[i * 2 + 1] = if (i == n - 1) 1f else (pos[i] + pos[i + 1]) / 2f
        }
        return RadialGradient(0f, 0f, radius, c2, p2, Shader.TileMode.CLAMP)
    }
}
