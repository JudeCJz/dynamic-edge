package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache

/**
 * App icons the way the home screen shows them. Preference "icon_source" (String):
 *  - "system": the app's own icon (default)
 *  - "themed" (shown as Monochrome): the Android 13 monochrome layer in the overlay foreground colour on a dark
 *    rounded square; apps without a monochrome layer (or below Android 13) get their full icon desaturated.
 * Any other stored value (for example a "pack:<package>" id from an older build) reads as "system".
 * All icons are full size in a rounded square.
 *
 * Icons are cached (LRU) per source + component + size and the cache is cleared whenever the preference changes.
 * Building an icon allocates bitmaps: call [icon] off the draw path and keep the result.
 */
object IconTheme {
    const val KEY = "icon_source"
    const val SYSTEM = "system"
    const val THEMED = "themed"

    private val fg = Color.WHITE
    private val disc = Color.rgb(0x2C, 0x2D, 0x31)

    private val cache = LruCache<String, Bitmap>(72)
    @Volatile private var listening = false
    private var listenerRef: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** The current source id: [SYSTEM] or [THEMED]. */
    fun source(ctx: Context): String {
        ensureListener(ctx)
        return if (Prefs.prefs(ctx).getString(KEY, SYSTEM) == THEMED) THEMED else SYSTEM
    }

    /** Icon for a component, square [px0]. Never null (falls back to the plain system icon, then a blank square). */
    fun icon(ctx: Context, cn: ComponentName, px0: Int): Bitmap {
        val app = ctx.applicationContext ?: ctx
        val px = px0.coerceAtLeast(1)
        val src = source(app)
        val key = src + "|" + cn.flattenToShortString() + "|" + px
        cache.get(key)?.let { return it }
        val b = try {
            if (src == THEMED) themed(app, cn, px) else plain(app, cn, px)
        } catch (e: Throwable) {
            null
        } ?: try {
            plain(app, cn, px)
        } catch (e: Throwable) {
            null
        } ?: Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        cache.put(key, b)
        return b
    }

    /** Icon for a package's launcher activity (recent apps, notifications). */
    fun icon(ctx: Context, pkg: String, px: Int): Bitmap {
        val app = ctx.applicationContext ?: ctx
        val cn = try {
            app.packageManager.getLaunchIntentForPackage(pkg)?.component
        } catch (e: Exception) {
            null
        } ?: ComponentName(pkg, "$pkg.")
        return icon(app, cn, px)
    }

    fun clear() {
        cache.evictAll()
    }

    // ---------------------------------------------------------------- build

    private fun sysDrawable(ctx: Context, cn: ComponentName): Drawable? {
        val pm = ctx.packageManager
        return try {
            pm.getActivityInfo(cn, 0).loadIcon(pm)
        } catch (e: Exception) {
            try {
                pm.getApplicationIcon(cn.packageName)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun render(d: Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(b))
        return b
    }

    private const val RADIUS = 0.28f

    private fun rounded(src: Bitmap): Bitmap {
        val px = src.width
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val r = px * RADIUS
        Canvas(out).drawRoundRect(0f, 0f, px.toFloat(), px.toFloat(), r, r, p)
        src.recycle()
        return out
    }

    /** True when all four corners are (nearly) opaque, i.e. the drawable is a plain square that needs rounding. */
    private fun squareCorners(b: Bitmap): Boolean {
        val px = b.width
        val o = (px * 0.03f).toInt().coerceAtLeast(0)
        val e = px - 1 - o
        return Color.alpha(b.getPixel(o, o)) > 200 && Color.alpha(b.getPixel(e, o)) > 200 &&
            Color.alpha(b.getPixel(o, e)) > 200 && Color.alpha(b.getPixel(e, e)) > 200
    }

    /**
     * Draw an adaptive icon's layers like a launcher (108 dp canvas, 72 dp window = the cell), unmasked. With
     * [mono] the dark square plus the tinted monochrome layer is drawn instead of background + foreground.
     */
    private fun adaptiveRaw(d: AdaptiveIconDrawable, px: Int, mono: Drawable?): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val q = (px * 0.25f).toInt()
        if (mono != null) {
            c.drawColor(disc)
            mono.setBounds(-q, -q, px + q, px + q)
            mono.draw(c)
        } else {
            d.background?.let {
                it.setBounds(-q, -q, px + q, px + q)
                it.draw(c)
            }
            d.foreground?.let {
                it.setBounds(-q, -q, px + q, px + q)
                it.draw(c)
            }
        }
        return b
    }

    /** Full-size icon in a rounded square (no disc, no shrink). */
    private fun roundIcon(d: Drawable, px: Int): Bitmap {
        if (d is AdaptiveIconDrawable) return rounded(adaptiveRaw(d, px, null))
        val b = render(d, px)
        return if (squareCorners(b)) rounded(b) else b
    }

    private fun plain(ctx: Context, cn: ComponentName, px: Int): Bitmap? {
        val d = sysDrawable(ctx, cn) ?: return null
        return roundIcon(d, px)
    }

    private fun themed(ctx: Context, cn: ComponentName, px: Int): Bitmap? {
        val d = sysDrawable(ctx, cn) ?: return null
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) {
            val mono = d.monochrome
            if (mono != null) {
                // own copy: loadIcon may hand out a shared drawable, the tint must not leak into it
                val m = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
                m.setTint(fg)
                return rounded(adaptiveRaw(d, px, m))
            }
        }
        // no monochrome layer: the real full-size rounded icon, desaturated
        val src = roundIcon(d, px)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    // ---------------------------------------------------------------- cache invalidation

    private fun ensureListener(ctx: Context) {
        if (listening) return
        synchronized(this) {
            if (listening) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == KEY) clear()
            }
            listenerRef = l // strong reference: SharedPreferences keeps listeners weakly
            try {
                Prefs.prefs(ctx.applicationContext ?: ctx).registerOnSharedPreferenceChangeListener(l)
            } catch (e: Exception) {
            }
            listening = true
        }
    }
}
