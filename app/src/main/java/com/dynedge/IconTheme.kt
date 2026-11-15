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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * App icons the way the home screen shows them. Preference "icon_source" (String):
 *  - "system": the app's own icon (default)
 *  - "themed" (shown as Monochrome): the Android 13 monochrome layer in the overlay foreground colour on a dark
 *    rounded square; apps without a monochrome layer (or below Android 13) get their full icon desaturated.
 * Any other stored value (for example a "pack:<package>" id from an older build) reads as "system".
 * Every icon ends up in one shape: a full-bleed rounded square (corner = Prefs.iconCorner), optionally pixelated
 * to a coarse grid (Prefs.iconPixel).
 *
 * Icons are cached (LRU) per source + component + size + corner + pixel and the cache is cleared whenever one of
 * those preferences changes. Building an icon allocates bitmaps: call [icon] off the draw path and keep the result.
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
        val corner = Prefs.iconCorner(app)
        // pixel mode pixelates every icon at least to a 24-cell grid
        val pixel = pixelLevel(app)
        val key = src + "|" + cn.flattenToShortString() + "|" + px + "|" + corner + "|" + pixel
        cache.get(key)?.let { return it }
        val b = try {
            if (src == THEMED) themed(app, cn, px, corner, pixel) else plain(app, cn, px, corner, pixel)
        } catch (e: Throwable) {
            null
        } ?: try {
            plain(app, cn, px, corner, pixel)
        } catch (e: Throwable) {
            null
        } ?: Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        cache.put(key, b)
        return b
    }

    private fun pixelLevel(ctx: Context): Int =
        if (Prefs.pixelMode(ctx)) maxOf(Prefs.iconPixel(ctx), 60) else Prefs.iconPixel(ctx)

    private fun cells(pixel: Int): Int = (48 - (pixel.coerceIn(1, 100) - 1) * 40f / 99f).roundToInt().coerceAtLeast(2)

    /** True when icons are pixelated (icon pixel option or pixel mode): draw them with bitmap filtering OFF. */
    fun pixelated(ctx: Context): Boolean = pixelLevel(ctx.applicationContext ?: ctx) > 0

    /**
     * Draw size for an icon: with pixelation on, [size] snapped to a whole number of device pixels per grid cell
     * (floor, or ceil when [up]) so every cell is drawn the same size; unchanged otherwise.
     */
    fun snapSize(ctx: Context, size: Float, up: Boolean = false): Float {
        val lv = pixelLevel(ctx.applicationContext ?: ctx)
        if (lv <= 0) return size
        val n = cells(lv)
        val c = (if (up) kotlin.math.ceil(size / n) else kotlin.math.floor(size / n)).coerceAtLeast(1f)
        return c * n
    }

    /** A bitmap as a drawable for an ImageView: nearest-neighbour when [pixelated], smooth otherwise. */
    fun drawable(ctx: Context, b: Bitmap?): Drawable? {
        if (b == null) return null
        val d = android.graphics.drawable.BitmapDrawable(ctx.resources, b)
        d.isFilterBitmap = !pixelated(ctx)
        return d
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

    /** The rounded-square outline for a [px] square; [cornerPct] 0..50 is the corner radius in % of the size. */
    fun shapePath(px: Float, cornerPct: Int): Path {
        val r = px * cornerPct.coerceIn(0, 50) / 100f
        return Path().apply { addRoundRect(RectF(0f, 0f, px, px), r, r, Path.Direction.CW) }
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

    /** Clip an opaque full-bleed square to the shape; with [pixel] > 0 also pixelate it with a stepped edge. */
    private fun finish(src: Bitmap, corner: Int, pixel: Int): Bitmap {
        val px = src.width
        val out: Bitmap
        if (pixel <= 0) {
            out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Canvas(out).drawPath(shapePath(px.toFloat(), corner), p)
        } else {
            val n = cells(pixel).coerceAtMost(px)
            // output = exactly cell * n pixels, every grid cell the same whole number of pixels
            val cellPx = ceil(px / n.toFloat()).toInt().coerceAtLeast(1)
            val size = cellPx * n
            val small = Bitmap.createScaledBitmap(src, n, n, true)
            val cell = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val p = Paint() // no anti-alias, no filter: hard stepped edge on the grid
            p.isAntiAlias = false
            p.isFilterBitmap = false
            p.shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Canvas(cell).drawPath(shapePath(n.toFloat(), corner), p)
            out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val up = Paint()
            up.isAntiAlias = false
            up.isFilterBitmap = false
            up.isDither = false
            Canvas(out).drawBitmap(cell, Rect(0, 0, n, n), Rect(0, 0, size, size), up)
            if (small !== src) small.recycle()
            cell.recycle()
        }
        src.recycle()
        return out
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

    private fun med(a: IntArray, n: Int): Int {
        if (n == 0) return 255
        java.util.Arrays.sort(a, 0, n)
        return a[n / 2]
    }

    /**
     * Normalise a legacy (non-adaptive) drawable into an opaque full-bleed square, unmasked:
     *  - square (bbox corners opaque): bbox scaled to cover;
     *  - solid shape (coverage >= 0.7): bbox x1.08 over a fill of the shape's edge colour, so no old outline shows;
     *  - glyph (low coverage): glyph at 66% on a dark plate (light glyph) or white plate (dark/colourful glyph).
     */
    private fun legacy(d: Drawable, px: Int): Bitmap {
        val w = maxOf(px, 128)
        val src = render(d, w)
        val a = IntArray(w * w)
        src.getPixels(a, 0, w, 0, 0, w, w)
        var x0 = w
        var y0 = w
        var x1 = -1
        var y1 = -1
        var cnt = 0
        for (y in 0 until w) for (x in 0 until w) if ((a[y * w + x] ushr 24) > 128) {
            cnt++
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        if (cnt == 0) {
            src.recycle()
            out.eraseColor(disc)
            return out
        }
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val cov = cnt / (bw.toFloat() * bh)
        val ix = (bw * 0.03f).toInt()
        val iy = (bh * 0.03f).toInt()
        fun op(x: Int, y: Int) = (a[y * w + x] ushr 24) > 200
        val square = op(x0 + ix, y0 + iy) && op(x1 - ix, y0 + iy) && op(x0 + ix, y1 - iy) && op(x1 - ix, y1 - iy)
        val c = Canvas(out)
        val scale: Float
        if (square) {
            scale = px / minOf(bw, bh).toFloat()
        } else if (cov >= 0.7f) {
            val t = maxOf(2, (minOf(bw, bh) * 0.06f).toInt())
            val r = IntArray(cnt)
            val g = IntArray(cnt)
            val b = IntArray(cnt)
            var n = 0
            for (y in y0..y1) for (x in x0..x1) {
                val v = a[y * w + x]
                if ((v ushr 24) <= 128) continue
                val edge = x - t < x0 || x + t > x1 || y - t < y0 || y + t > y1 ||
                    (a[y * w + x - t] ushr 24) <= 128 || (a[y * w + x + t] ushr 24) <= 128 ||
                    (a[(y - t) * w + x] ushr 24) <= 128 || (a[(y + t) * w + x] ushr 24) <= 128
                if (edge) {
                    r[n] = Color.red(v)
                    g[n] = Color.green(v)
                    b[n] = Color.blue(v)
                    n++
                }
            }
            c.drawColor(Color.rgb(med(r, n), med(g, n), med(b, n)))
            scale = px * 1.08f / maxOf(bw, bh)
        } else {
            var lum = 0.0
            for (y in y0..y1) for (x in x0..x1) {
                val v = a[y * w + x]
                lum += (0.299 * Color.red(v) + 0.587 * Color.green(v) + 0.114 * Color.blue(v)) * (v ushr 24)
            }
            lum /= 255.0 * cnt
            c.drawColor(if (lum > 0.7) disc else Color.WHITE)
            scale = px * 0.66f / maxOf(bw, bh)
        }
        val m = Matrix()
        m.postTranslate(-(x0 + x1 + 1) / 2f, -(y0 + y1 + 1) / 2f)
        m.postScale(scale, scale)
        m.postTranslate(px / 2f, px / 2f)
        c.drawBitmap(src, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** Opaque unmasked full-bleed square for any drawable. */
    private fun content(d: Drawable, px: Int): Bitmap =
        if (d is AdaptiveIconDrawable) adaptiveRaw(d, px, null) else legacy(d, px)

    private fun plain(ctx: Context, cn: ComponentName, px: Int, corner: Int, pixel: Int): Bitmap? {
        val d = sysDrawable(ctx, cn) ?: return null
        return finish(content(d, px), corner, pixel)
    }

    private fun themed(ctx: Context, cn: ComponentName, px: Int, corner: Int, pixel: Int): Bitmap? {
        val d = sysDrawable(ctx, cn) ?: return null
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) {
            val mono = d.monochrome
            if (mono != null) {
                // own copy: loadIcon may hand out a shared drawable, the tint must not leak into it
                val m = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
                m.setTint(fg)
                return finish(adaptiveRaw(d, px, m), corner, pixel)
            }
        }
        // no monochrome layer: the normalised full-size icon, desaturated
        val src = content(d, px)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return finish(out, corner, pixel)
    }

    // ---------------------------------------------------------------- cache invalidation

    private fun ensureListener(ctx: Context) {
        if (listening) return
        synchronized(this) {
            if (listening) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == KEY || k == Prefs.KEY_ICON_CORNER || k == Prefs.KEY_ICON_PIXEL || k == Prefs.KEY_PIXEL_MODE) clear()
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
