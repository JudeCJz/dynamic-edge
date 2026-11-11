package com.dynedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dot-matrix art: the 16 x 16 pixel-grid idle arts (the Nothing-style dot display) and the 3 x 5 dot font the
 * dot windows draw their numbers and labels with.
 *
 * The arts are built once into bit masks (one Int per row: the lit dots) and drawn with one Paint, so nothing
 * is allocated while drawing. The frame index is always passed in: an idle art never starts a timer of its own,
 * the caller decides whether it animates (the edge must do zero work while it is closed).
 *
 * Geometry: the grid is the square inscribed in the quarter disc of radius [s] (the bean's radius), so every
 * dot stays inside the shape. The origin is the screen corner, the art lies toward -x / -y.
 */
internal object DotArt {

    /** The pixel grid is 16 x 16 dots. */
    const val GRID = 16

    /** The built-in idle arts. */
    const val ART_CLOCK = "art_dotclock"
    const val ART_VIZ = "art_dotviz"
    const val ART_PULSE = "art_glyphpulse"
    const val ART_BATTERY = "art_dotbattery"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------------------------------------------------------------- dot font (3 x 5)

    private val font = HashMap<Char, IntArray>()

    private fun put(c: Char, vararg rows: String) {
        val g = IntArray(5)
        for (r in 0 until 5) {
            var v = 0
            val s = rows[r]
            for (q in 0 until 3) if (s[q] == '1') v = v or (1 shl (2 - q))
            g[r] = v
        }
        font[c] = g
    }

    init {
        put('0', "111", "101", "101", "101", "111")
        put('1', "010", "110", "010", "010", "111")
        put('2', "111", "001", "111", "100", "111")
        put('3', "111", "001", "111", "001", "111")
        put('4', "101", "101", "111", "001", "001")
        put('5', "111", "100", "111", "001", "111")
        put('6', "111", "100", "111", "101", "111")
        put('7', "111", "001", "001", "001", "001")
        put('8', "111", "101", "111", "101", "111")
        put('9', "111", "101", "111", "001", "111")
        put('A', "010", "101", "111", "101", "101")
        put('B', "110", "101", "110", "101", "110")
        put('C', "011", "100", "100", "100", "011")
        put('D', "110", "101", "101", "101", "110")
        put('E', "111", "100", "110", "100", "111")
        put('F', "111", "100", "110", "100", "100")
        put('G', "011", "100", "101", "101", "011")
        put('H', "101", "101", "111", "101", "101")
        put('I', "111", "010", "010", "010", "111")
        put('J', "001", "001", "001", "101", "111")
        put('K', "101", "101", "110", "101", "101")
        put('L', "100", "100", "100", "100", "111")
        put('M', "101", "111", "111", "101", "101")
        put('N', "110", "101", "101", "101", "101")
        put('O', "010", "101", "101", "101", "010")
        put('P', "110", "101", "110", "100", "100")
        put('Q', "010", "101", "101", "111", "011")
        put('R', "110", "101", "110", "110", "101")
        put('S', "011", "100", "010", "001", "110")
        put('T', "111", "010", "010", "010", "010")
        put('U', "101", "101", "101", "101", "111")
        put('V', "101", "101", "101", "101", "010")
        put('W', "101", "101", "111", "111", "101")
        put('X', "101", "101", "010", "101", "101")
        put('Y', "101", "101", "010", "010", "010")
        put('Z', "111", "001", "010", "100", "111")
        put(':', "000", "010", "000", "010", "000")
        put('.', "000", "000", "000", "000", "010")
        put(',', "000", "000", "000", "010", "100")
        put('-', "000", "000", "111", "000", "000")
        put('+', "000", "010", "111", "010", "000")
        put('/', "001", "001", "010", "100", "100")
        put('%', "101", "001", "010", "100", "101")
        put(' ', "000", "000", "000", "000", "000")
    }

    /** The 3 x 5 dot pattern of [c] (5 rows of 3 bits, bit 2 = the left column), or null. */
    fun glyph(c: Char): IntArray? = font[c]

    /** The width in px of [text] in the dot font at [pitch] (the distance between two dot centres). */
    fun textWidth(text: String, pitch: Float): Float {
        if (text.isEmpty()) return 0f
        return text.length * 4f * pitch - pitch
    }

    /** Draws [text] in the dot font, centred on (cx, cy). [pitch] is the dot spacing in px. */
    fun drawText(
        c: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        pitch: Float,
        color: Int,
        alpha: Float,
        dotScale: Float = 0.8f
    ) {
        if (text.isEmpty() || pitch <= 0f) return
        val w = textWidth(text, pitch)
        val x0 = cx - w / 2f
        val y0 = cy - 2.5f * pitch
        for (i in text.indices) {
            drawChar(c, text[i], x0 + i * 4f * pitch, y0, pitch, color, alpha, dotScale)
        }
    }

    /** Draws one character of the dot font; its top-left corner is at (x0, y0). */
    private fun drawChar(
        c: Canvas,
        ch: Char,
        x0: Float,
        y0: Float,
        pitch: Float,
        color: Int,
        alpha: Float,
        dotScale: Float
    ) {
        val g = font[ch] ?: return
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (255f * alpha + 0.5f).toInt().coerceIn(0, 255)
        val r = pitch * dotScale / 2f
        for (row in 0 until 5) {
            val bits = g[row]
            if (bits == 0) continue
            for (q in 0 until 3) {
                if (bits and (1 shl (2 - q)) == 0) continue
                c.drawCircle(x0 + (q + 0.5f) * pitch, y0 + (row + 0.5f) * pitch, r, paint)
            }
        }
    }

    /**
     * Draws [text] in the dot font along the arc from [a0] to [a1] degrees at radius [r] (the origin is the
     * corner, the same polar convention as every other element).
     */
    fun drawTextOnArc(
        c: Canvas,
        text: String,
        r: Float,
        a0: Float,
        a1: Float,
        pitch: Float,
        color: Int,
        alpha: Float,
        dotScale: Float = 0.8f
    ) {
        if (text.isEmpty() || pitch <= 0f || r <= 0f) return
        val span = Math.toRadians((a1 - a0).toDouble()).toFloat()
        val len = r * span
        val w = textWidth(text, pitch)
        var off = (len - w) / 2f
        if (off < 0f) off = 0f
        for (i in text.indices) {
            val mid = off + (i * 4f + 1.5f) * pitch
            val deg = a0 + Math.toDegrees((mid / r).toDouble()).toFloat()
            val rad = Math.toRadians(deg.toDouble())
            val x = -r * cos(rad).toFloat()
            val y = -r * sin(rad).toFloat()
            c.save()
            c.translate(x, y)
            c.rotate(deg - 90f)
            drawChar(c, text[i], -1.5f * pitch, -2.5f * pitch, pitch, color, alpha, dotScale)
            c.restore()
            off += 4f * pitch
        }
    }

    // ---------------------------------------------------------------- pixel-grid idle arts

    /** One frame of a pixel art: [on] = the lit dots per row, [acc] = the accent dots per row. */
    internal class Frame {
        val on = IntArray(GRID)
        val acc = IntArray(GRID)
    }

    private val arts = HashMap<String, Array<Frame>>()
    private val artFps = HashMap<String, Int>()

    /** The frames of the art [id], or null when there is no such art. */
    fun frames(id: String): Array<Frame>? {
        var f = arts[id]
        if (f != null) return f
        f = build(id)
        if (f != null) arts[id] = f
        return f
    }

    /** True when [id] is a known dot art. */
    fun has(id: String): Boolean = frames(id) != null

    /** The frame rate of the art [id] (frames per second). */
    fun fps(id: String): Int = artFps[id] ?: 8

    /** The frame to show at [timeSec] seconds. */
    fun frameAt(id: String, timeSec: Float): Int {
        val f = frames(id) ?: return 0
        if (f.size <= 1) return 0
        val n = (timeSec * fps(id)).toInt()
        return if (n < 0) 0 else n % f.size
    }

    /**
     * Draws the art [id] on the bean of radius [s] px. [frame] is the frame index; the caller owns the clock
     * (a closed edge must not animate anything).
     */
    fun drawArt(c: Canvas, id: String, s: Float, accent: Int, alpha: Float, frame: Int) {
        val fs = frames(id) ?: return
        val f = fs[if (frame < 0) 0 else frame % fs.size]
        val cell = 0.7f * s / GRID
        val x0 = -0.7f * s
        val y0 = -0.7f * s
        val r = cell * 0.42f
        paint.style = Paint.Style.FILL
        paint.alpha = (255f * alpha + 0.5f).toInt().coerceIn(0, 255)
        for (row in 0 until GRID) {
            val bits = f.on[row]
            if (bits == 0) continue
            val acc = f.acc[row]
            for (q in 0 until GRID) {
                if (bits and (1 shl q) == 0) continue
                paint.color = if (acc and (1 shl q) != 0) accent else Color.WHITE
                c.drawCircle(x0 + (q + 0.5f) * cell, y0 + (row + 0.5f) * cell, r, paint)
            }
        }
    }

    private fun build(id: String): Array<Frame>? = when (id) {
        ART_CLOCK -> clockArt()
        ART_VIZ -> vizArt()
        ART_PULSE -> pulseArt()
        ART_BATTERY -> batteryArt()
        else -> null
    }

    /** "14" in the dot font with a blinking colon dot. */
    private fun clockArt(): Array<Frame> {
        artFps[ART_CLOCK] = 1
        val out = Array(2) { Frame() }
        for (f in 0 until 2) {
            val fr = out[f]
            drawGlyph(fr, '1', 4, 5)
            drawGlyph(fr, '4', 8, 5)
            if (f == 1) fr.on[8] = fr.on[8] or (1 shl 12)
        }
        return out
    }

    /** Four bars in the middle of the grid, one frame per beat. */
    private fun vizArt(): Array<Frame> {
        artFps[ART_VIZ] = 7
        val h = intArrayOf(3, 6, 4, 7, 5, 2)
        val out = Array(h.size) { Frame() }
        for (f in h.indices) {
            val fr = out[f]
            for (b in 0 until 4) {
                val col = 4 + b * 2
                val len = ((h[f] + b) % 5) + 1
                for (k in 0 until len) {
                    val row = 11 - k
                    fr.on[row] = fr.on[row] or (1 shl col) or (1 shl (col + 1))
                }
                if (len > 0) fr.acc[11 - len + 1] = fr.acc[11 - len + 1] or (1 shl col)
            }
        }
        return out
    }

    /** A ring of dots with one chasing light (the Glyph pulse). */
    private fun pulseArt(): Array<Frame> {
        artFps[ART_PULSE] = 9
        val n = 8
        val out = Array(n) { Frame() }
        for (f in 0 until n) {
            val fr = out[f]
            for (i in 0 until 16) {
                val a = i * 22.5 + 11.25
                val rad = Math.toRadians(a.toDouble())
                val x = 7.5 + 4.6 * cos(rad)
                val y = 7.5 + 4.6 * sin(rad)
                val q = Math.round(x).toInt()
                val row = Math.round(y).toInt()
                if (q < 0 || q >= GRID || row < 0 || row >= GRID) continue
                fr.on[row] = fr.on[row] or (1 shl q)
                if (i == f * 2) fr.acc[row] = fr.acc[row] or (1 shl q)
            }
        }
        return out
    }

    /** A battery outline with a filling bar; the tip blinks while charging. */
    private fun batteryArt(): Array<Frame> {
        artFps[ART_BATTERY] = 2
        val out = Array(2) { Frame() }
        for (f in 0 until 2) {
            val fr = out[f]
            for (row in 4..11) {
                fr.on[row] = fr.on[row] or (1 shl 3) or (1 shl 12)
            }
            for (q in 3..12) {
                fr.on[4] = fr.on[4] or (1 shl q)
                fr.on[11] = fr.on[11] or (1 shl q)
            }
            for (row in 5..10) {
                for (q in 4..7) fr.on[row] = fr.on[row] or (1 shl q)
            }
            for (row in 5..10) {
                fr.acc[row] = fr.acc[row] or (1 shl 9) or (1 shl 10)
            }
            if (f == 1) fr.acc[3] = fr.acc[3] or (1 shl 7) or (1 shl 8)
        }
        return out
    }

    /** Stamps the 3 x 5 glyph of [ch] into [fr] with its top-left corner at (col, row). */
    private fun drawGlyph(fr: Frame, ch: Char, col: Int, row: Int) {
        val g = font[ch] ?: return
        for (r in 0 until 5) {
            val rr = row + r
            if (rr < 0 || rr >= GRID) continue
            val bits = g[r]
            for (q in 0 until 3) {
                if (bits and (1 shl (2 - q)) == 0) continue
                val cc = col + q
                if (cc < 0 || cc >= GRID) continue
                fr.on[rr] = fr.on[rr] or (1 shl cc)
            }
        }
    }

    /** The dot pitch for a grid of [cols] columns over [width] px. */
    fun pitchFor(width: Float, cols: Int): Float = if (cols <= 0) 0f else min(width / cols, width)
}
