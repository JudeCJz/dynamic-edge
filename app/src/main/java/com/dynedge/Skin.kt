package com.dynedge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

/**
 * The settings app's visual system.
 *
 * One flat dark-grey surface scale (never pure black) plus the user's accent colour, which drives
 * every interactive element: toggles, slider fills, selection rings, section headers and chips.
 * No AndroidX, no XML themes - every screen paints these values by hand.
 */
object Skin {

    // ---- surfaces: light-ish dark grey, deliberately not black -------------------------------
    /** Page background. */
    const val BG = 0xFF232427.toInt()
    /** Row / panel background. */
    const val SURFACE = 0xFF2C2D31.toInt()
    /** Background of a settings category box (between BG and SURFACE so surfaces inside still read). */
    const val BOX = 0xFF28292D.toInt()
    /** Pressed or selected row. */
    const val SURFACE_HI = 0xFF3A3B40.toInt()
    /** Hairline between rows. */
    const val DIVIDER = 0xFF34353A.toInt()
    /** Unfilled part of a slider track. */
    const val TRACK = 0xFF45464C.toInt()

    // ---- text ----------------------------------------------------------------------------------
    const val TEXT = 0xFFF1F1F3.toInt()
    const val TEXT_2 = 0xFFA6A7AD.toInt()
    const val TEXT_3 = 0xFF707176.toInt()

    // ---- metrics -------------------------------------------------------------------------------
    const val ROW_MIN = 44          // dp, minimum touch target
    const val PAD = 20              // dp, screen gutter
    const val GAP = 12              // dp, between rows in a group
    const val RADIUS = 14           // dp, corner radius of the few rounded surfaces
    const val CHIP_RADIUS = 18      // dp, pill radius of the section chips

    /** [r] corner radius, or 0 in pixel mode (everything square). */
    fun corner(ctx: Context, r: Float): Float = if (PixelMode.on(ctx)) 0f else r

    /** The user's theme colour: headers, toggles, sliders, chips, selection rings. */
    fun accent(ctx: Context): Int = Prefs.accent(ctx)

    /**
     * The accent as the settings app paints it. A very dark accent would make the UI unreadable, so it is
     * lightened towards white until it clears 3:1 against the page background.
     */
    fun accentUi(raw: Int): Int {
        var c = raw or (0xFF shl 24)
        var i = 0
        while (i < 8 && contrast(c, BG) < 3.0f) {
            c = mix(c, Color.WHITE, 0.08f)
            i++
        }
        return c
    }

    /** WCAG relative luminance. */
    fun luminance(c: Int): Float {
        fun ch(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * ch(Color.red(c)) + 0.7152f * ch(Color.green(c)) + 0.0722f * ch(Color.blue(c))
    }

    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    fun ripple(ctx: Context, pressed: Int = 0x33FFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(pressed), null, ColorDrawable(Color.WHITE))

    /** [color] with the given alpha (0..1). */
    fun alpha(color: Int, a: Float): Int {
        val v = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (color and 0x00FFFFFF) or (v shl 24)
    }

    /** Blend [a] -> [b] by [t] (0..1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * k + 0.5f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * k + 0.5f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k + 0.5f).toInt()
        return Color.rgb(r, g, bl)
    }

    /** Legible ink for a coloured surface: near-black on light colours, near-white on dark ones. */
    fun onColor(color: Int): Int {
        val lum = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
        return if (lum > 150f) 0xFF16161A.toInt() else Color.WHITE
    }
}

/** dp -> px. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** dp -> px (float). */
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
