package com.dynedge

import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The edge shape's gradient, in one place.
 *
 * The overlay (EdgeView) and the settings thumbnails (EdgeThumb) both paint the shape through
 * here, so what you see while picking a colour is exactly what the widget will draw.
 *
 * The shape is a radial gradient anchored at the bottom-right corner:
 * - [inner] is the hot core colour (0 = Auto: follows the accent),
 * - [outer] is the outer band colour (0 = Edge: follows the base colour),
 * - [base] is the solid edge colour the gradient fades back into,
 * - [pct] (0..100) is the gradient strength: 0 means the shape is the pure base colour.
 *
 * With inner = 0 and outer = 0 this reproduces the original shader exactly, so an install that never
 * touched the new colours looks unchanged.
 *
 */
object EdgePaint {

    /** The warm colour the original gradient mixed with the accent. */
    const val WARM = 0xFF2E1B1A.toInt()

    /** Stop offsets, hot core -> outer band -> solid edge. */
    val POSITIONS = floatArrayOf(0f, 0.38f, 0.72f, 1f)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * k + 0.5f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * k + 0.5f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k + 0.5f).toInt()
        return Color.rgb(r, g, bl)
    }

    /** The "Auto" inner colour: the original warm mix with the accent. */
    fun autoInner(accent: Int): Int = mix(WARM, accent or (0xFF shl 24), 0.25f)

    /** The four radial stops, hot core -> outer band -> solid edge. */
    fun stops(base: Int, inner: Int, outer: Int, pct: Int, accent: Int): IntArray {
        val o = if (outer != 0) (outer or (0xFF shl 24)) else (base or (0xFF shl 24))
        val i = if (inner != 0) (inner or (0xFF shl 24)) else autoInner(accent)
        val core = mix(o, i, pct.coerceIn(0, 100) / 100f)
        return intArrayOf(
            core,
            mix(o, core, 0.55f),
            mix(o, core, 0.24f),
            o
        )
    }

    /** Radial shader in unit space (radius [radius]), anchored at the origin. */
    fun shapeShader(base: Int, inner: Int, outer: Int, pct: Int, accent: Int, radius: Float): Shader =
        RadialGradient(0f, 0f, radius, stops(base, inner, outer, pct, accent), POSITIONS, Shader.TileMode.CLAMP)

    /** Radial shader centred on a point - used by the settings preview thumbnails. */
    fun shapeShaderAt(
        cx: Float, cy: Float, radius: Float,
        base: Int, inner: Int, outer: Int, pct: Int, accent: Int
    ): Shader = RadialGradient(cx, cy, radius, stops(base, inner, outer, pct, accent), POSITIONS, Shader.TileMode.CLAMP)

    /**
     * The glow shader for a colour and a strength. Strength 1 with the accent colour is the app's own
     * glow, bit for bit.
     */
    fun glowShader(color: Int, strength: Float, radius: Float): Shader {
        val rgb = color and 0xFFFFFF
        val s = strength.coerceIn(0f, 2f)
        val a0 = (0x88 * s).toInt().coerceIn(0, 255)
        val a1 = (0x30 * s).toInt().coerceIn(0, 255)
        return RadialGradient(
            0f, 0f, radius,
            intArrayOf((a0 shl 24) or rgb, (a1 shl 24) or rgb, rgb),
            floatArrayOf(0f, 0.38f, 0.85f), Shader.TileMode.CLAMP
        )
    }

}
