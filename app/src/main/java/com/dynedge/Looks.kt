package com.dynedge

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared drawing for the idle designs, the fill patterns and the visualizer styles. Used by EdgeView (the
 * widget) and by the settings canvas and thumbnails (EdgeThumb), so a preview is drawn by exactly the same code.
 *
 * Canvas convention: origin = the screen corner, the shape lies toward negative x / y. Polar angle a:
 * 0 = along the bottom edge, 90 = along the right edge. [s] is the bean radius in px (idle size, or the
 * open radius for the rim styles); design numbers are given in dp at a 48 dp bean, so k = s / 48 px per dp.
 * Nothing is allocated while drawing: paints, paths and tile shaders are built once (patterns are rebuilt
 * only when the pattern extent or the unit changes).
 */
internal class Looks {

    var accent = Color.WHITE

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val scratch = Path()
    private var patUnit = -1f
    private var dotsShader: BitmapShader? = null
    private var dotsBmp: Bitmap? = null

    private fun px(r: Float, a: Float): Float = -r * cos(a * D2R)
    private fun py(r: Float, a: Float): Float = -r * sin(a * D2R)
    private fun al(a: Float): Int = (255f * a + 0.5f).toInt().coerceIn(0, 255)

    private fun paint(color: Int, a: Float) {
        p.color = color or (0xFF shl 24)
        p.alpha = al(a)
    }

    private fun stroke(w: Float, cap: Paint.Cap = Paint.Cap.ROUND) {
        p.style = Paint.Style.STROKE
        p.strokeCap = cap
        p.strokeWidth = max(1f, w)
    }

    private fun fillStyle() {
        p.style = Paint.Style.FILL
    }

    private fun arc(c: Canvas, r: Float, a1: Float, a2: Float) {
        rect.set(-r, -r, r, r)
        c.drawArc(rect, 180f + a1, a2 - a1, false, p)
    }

    private fun radial(c: Canvas, r1: Float, r2: Float, a: Float) {
        c.drawLine(px(r1, a), py(r1, a), px(r2, a), py(r2, a), p)
    }

    // ================================================================ idle designs

    /**
     * One idle design on a bean of radius [s] px ("none" draws nothing). [ia] = overall alpha, [beat] 0..1 adds a
     * small brightness lift to the focal mark.
     *
     * "glyph" is the app's own design; an unknown id draws nothing.
     */
    fun drawIdle(c: Canvas, id: String, s: Float, ia: Float, beat: Float) {
        when (id) {
            "glyph" -> {
                val k = s / 48f
                val r = 0.58f * s
                for (i in 0..2) {
                    val a = 35f + 10f * i
                    val mid = i == 1
                    stroke(1.8f * k * (if (mid) 1f + 0.25f * beat else 1f))
                    if (mid) paint(accent, ia) else paint(Color.WHITE, (0.9f + 0.1f * beat) * ia)
                    radial(c, r - 5f * k, r, a)
                }
            }
            "pulse" -> {
                // three rings, the inner one in the accent; the beat lifts the outer ring
                val k = s / 48f
                for (i in 0 until 3) {
                    val r = s * (0.20f + 0.13f * i)
                    stroke(1.6f * k)
                    val a = (0.85f - 0.18f * i + (if (i == 2) 0.25f * beat else 0f)) * ia
                    if (i == 0) paint(accent, a) else paint(Color.WHITE, a)
                    arc(c, r, 22f, 68f)
                }
            }
            "orbit" -> {
                // one arc with a marker on it
                val k = s / 48f
                val r = 0.40f * s
                stroke(1.5f * k)
                paint(Color.WHITE, 0.5f * ia)
                arc(c, r, 12f, 78f)
                fillStyle()
                paint(accent, (0.9f + 0.1f * beat) * ia)
                c.drawCircle(px(r, 45f), py(r, 45f), 2.6f * k, p)
            }
            else -> {
                // a dot art has its own id (art_dotclock, art_dotviz, art_glyphpulse, art_dotbattery):
                // it is a static pixel frame - the edge must do no work while it is closed
                if (id != "none") DotArt.drawArt(c, id, s, accent, ia, 0)
                return
            }
        }
    }

    // ================================================================ fill patterns

    /**
     * Pattern over the base fill ("dots"). The caller clips to the shape and translates to the corner. [extent] is a
     * constant upper bound of the shape radius (keeps the cached tile valid while the shape animates), [unit]
     * is px per dp, [boost] scales every alpha, [b] 0..1 is the bass energy (brightens, adds accent tint).
     */
    fun drawPattern(c: Canvas, id: String, extent: Float, unit: Float, boost: Float, b: Float) {
        if (id != "dots") return
        ensurePattern(unit)
        val sh = dotsShader ?: return
        fillStyle()
        p.shader = sh
        paint(Color.WHITE, 0.07f * boost * (1f + 0.9f * b))
        c.drawRect(-extent, -extent, 0f, 0f, p)
        if (b > 0.03f) {
            paint(accent, 0.1f * boost * b)
            c.drawRect(-extent, -extent, 0f, 0f, p)
        }
        p.shader = null
    }

    private fun ensurePattern(unit: Float) {
        if (unit == patUnit && dotsShader != null) return
        patUnit = unit
        val t = max(6, Math.round(8f * unit))
        dotsBmp?.recycle()
        val d = Bitmap.createBitmap(t, t, Bitmap.Config.ALPHA_8)
        val dc = Canvas(d)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG)
        dp.color = Color.WHITE
        dc.drawCircle(t / 2f, t / 2f, max(0.8f, 0.6f * unit), dp)
        dotsBmp = d
        dotsShader = BitmapShader(d, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun band(lv: FloatArray, f: Float): Float {
        val x = f.coerceIn(0f, 1f) * 3f
        val i0 = x.toInt().coerceAtMost(2)
        return lv[i0] + (lv[i0 + 1] - lv[i0]) * (x - i0)
    }

    /**
     * One visualizer style. Idle (open = false): [s] is the bean radius and the design scales with it. Open
     * rim (open = true): [s] is the live open radius, [unit] px per dp; styles that are idle-only draw
     * "spectrum" instead (only "wave" has a rim form; "bars", "rays" and "dots" are idle-only). [lv] = four band
     * levels 0..1 (at least 4 entries: [band] reads lv[0..3]).
     */
    fun drawViz(c: Canvas, id0: String, s: Float, open: Boolean, lv: FloatArray, ia: Float, unit: Float) {
        var id = id0
        if (open && id != "wave") id = "spectrum"
        val k = if (open) unit else s / 48f
        when (id) {
            "bars" -> {
                // four radial bars on one arc around the bean centroid: each follows the circle (tilted, not vertical)
                val rc = 0.5f * s
                val minH = 3f * k
                val maxH = 0.34f * s
                stroke(2.5f * k)
                for (i in 0 until 4) {
                    val h = minH + (maxH - minH) * lv[i]
                    paint(if (i == 2) accent else Color.WHITE, 0.85f * ia)
                    radial(c, rc - h / 2f, rc + h / 2f, 29f + i * 10.7f)
                }
            }
            "spectrum" -> {
                val n = if (open) 24 else 12
                val rimR = if (open) s - 7f * unit else 0.82f * s
                stroke((if (open) 2f else 1.8f) * k)
                val amp = if (open) 4.5f else 5f
                for (i in 0 until n) {
                    val b = band(lv, i / (n - 1f))
                    val a = if (open) 10f + i * 3.3f else 12f + i * 5.5f + 1.9f
                    val half = (1.5f + amp * b) * k / 2f
                    paint(if (b > 0.7f) accent else Color.WHITE, (if (open) 0.6f else 0.8f) * ia)
                    radial(c, rimR - half, rimR + half, a)
                }
            }
            "rays" -> {
                stroke(1.4f * k)
                for (i in 0 until 8) {
                    val a = 18f + i * 7.7f
                    val b = lv[i % 4]
                    paint(if (i % 2 == 0) accent else Color.WHITE, 0.9f * ia)
                    radial(c, 0.3f * s, 0.3f * s + (0.08f + 0.3f * b) * s, a)
                }
            }
            "wave" -> {
                val rimR = if (open) s - 12f * unit else 0.82f * s
                scratch.rewind()
                val n = 24
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val a = 8f + 74f * t
                    val r = rimR + (band(lv, t) - 0.5f) * 8f * k
                    if (i == 0) scratch.moveTo(px(r, a), py(r, a)) else scratch.lineTo(px(r, a), py(r, a))
                }
                stroke(1.6f * k)
                paint(accent, 0.8f * ia)
                c.drawPath(scratch, p)
            }
            "dots" -> {
                // dot matrix (idle only; open falls back to spectrum above): 8 radial columns of 5 discrete dots
                // on 16..74 degrees (symmetric about the 45 degree diagonal), lit from the inside out by the
                // band level; the topmost lit dot takes the accent on a strong band. Rows step 3.2 dp with
                // 1.2 dp dots so neighbours never touch (outer edge ~0.77 s, inside the bean).
                val nCols = 8
                val nRows = 5
                val dotRadius = 1.2f * k
                val baseR = 0.48f * s
                val stepR = 3.2f * k
                fillStyle()
                for (cIdx in 0 until nCols) {
                    val frac = cIdx / (nCols - 1f)
                    val aDeg = 16f + frac * 58f
                    val b = band(lv, frac)
                    val lit = (b * (nRows + 0.5f)).toInt().coerceIn(1, nRows)
                    val x = px(1f, aDeg)
                    val y = py(1f, aDeg)
                    for (rIdx in 0 until nRows) {
                        val r = baseR + rIdx * stepR
                        if (rIdx < lit) {
                            paint(if (rIdx == lit - 1 && b > 0.8f) accent else Color.WHITE, 0.9f * ia)
                        } else {
                            paint(Color.WHITE, 0.25f * ia)
                        }
                        c.drawCircle(x * r, y * r, dotRadius, p)
                    }
                }
            }
        }
    }

    private companion object {
        const val D2R = (Math.PI / 180.0).toFloat()
    }
}
