package com.dynedge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Method
import java.util.Random

/**
 * Glass mode helpers: the real backdrop blur (Android 12+ cross-window blur through the hidden
 * ViewRootImpl.createBackgroundBlurDrawable, reached by reflection) and the shared grain bitmap.
 * Log tag DynEdgeGlass. [Prefs.KEY_BLUR_OK] mirrors the result for the settings app.
 */
class GlassBlur(private val view: View) {
    private var drawable: Drawable? = null
    private var mRadius: Method? = null
    private var mCorners: Method? = null
    private var mColor: Method? = null
    private var tried = false

    /** True once a blur drawable exists and cross-window blur is enabled right now. */
    var active = false
        private set

    private var lastRadius = -1
    private var lastBlurOn = false

    /** Creates the drawable once the view is attached. Returns whether real blur is usable. */
    fun ensure(ctx: Context): Boolean {
        if (drawable != null) {
            active = blurEnabled(ctx)
            return active
        }
        if (tried) return false
        if (!view.isAttachedToWindow) return false
        tried = true
        if (Build.VERSION.SDK_INT < 31) {
            Log.i(TAG, "path=fallback reason=sdk<31")
            publish(ctx, false)
            return false
        }
        if (!blurEnabled(ctx)) {
            Log.i(TAG, "path=fallback reason=cross-window blur disabled (battery saver, gpu, or disable_window_blurs)")
            tried = false // may become enabled later
            publish(ctx, false)
            return false
        }
        try {
            val getRoot = findMethod(View::class.java, "getViewRootImpl")
            val root = getRoot.invoke(view)
            if (root == null) {
                Log.i(TAG, "path=fallback reason=no ViewRootImpl yet")
                tried = false
                return false
            }
            val create = findMethod(root.javaClass, "createBackgroundBlurDrawable")
            val d = create.invoke(root) as Drawable
            val c = d.javaClass
            mRadius = findMethod(c, "setBlurRadius", Int::class.javaPrimitiveType!!)
            mCorners = findMethod(
                c, "setCornerRadius", Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!
            )
            mColor = try { findMethod(c, "setColor", Int::class.javaPrimitiveType!!) } catch (e: Exception) { null }
            drawable = d
            active = true
            Log.i(TAG, "path=reflection ok drawable=" + c.name)
            publish(ctx, true)
            return true
        } catch (t: Throwable) {
            Log.i(TAG, "path=fallback reason=reflection blocked: " + t)
            publish(ctx, false)
            return false
        }
    }

    /** Positions and styles the blur; coordinates are real view pixels (not mirrored). */
    fun update(l: Int, t: Int, r: Int, b: Int, tl: Float, tr: Float, br: Float, bl: Float, radiusPx: Int) {
        val d = drawable ?: return
        try {
            d.setBounds(l, t, r, b)
            mCorners?.invoke(d, tl, tr, br, bl)
            if (radiusPx != lastRadius) {
                mRadius?.invoke(d, radiusPx)
                lastRadius = radiusPx
            }
            if (!lastBlurOn) {
                mColor?.invoke(d, 0x01000000) // practically clear: the tint is drawn by the view
                lastBlurOn = true
            }
        } catch (t2: Throwable) {
            Log.w(TAG, "blur update failed, falling back", t2)
            drawable = null
            active = false
        }
    }

    fun draw(c: android.graphics.Canvas) {
        val d = drawable ?: return
        if (!active) return
        d.draw(c)
    }

    fun release() {
        drawable = null
        active = false
        tried = false
        lastRadius = -1
        lastBlurOn = false
    }

    private fun blurEnabled(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 31 &&
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
    } catch (e: Throwable) {
        false
    }

    private fun publish(ctx: Context, ok: Boolean) {
        try {
            val p = Prefs.prefs(ctx)
            val v = if (ok) 1 else 2
            if (p.getInt(Prefs.KEY_BLUR_OK, 0) != v) p.edit().putInt(Prefs.KEY_BLUR_OK, v).apply()
        } catch (e: Throwable) {
        }
    }

    private fun findMethod(c: Class<*>, name: String, vararg args: Class<*>): Method {
        var last: Throwable? = null
        try {
            return c.getMethod(name, *args)
        } catch (e: Throwable) {
            last = e
        }
        var k: Class<*>? = c
        while (k != null) {
            try {
                return k.getDeclaredMethod(name, *args).also { it.isAccessible = true }
            } catch (e: Throwable) {
                last = e
            }
            k = k.superclass
        }
        throw last ?: NoSuchMethodException(name)
    }

    companion object {
        private const val TAG = "DynEdgeGlass"

        @Volatile
        private var grain: Bitmap? = null

        /** A tiled monochrome noise (white and black specks of random alpha), made once per process. */
        fun grainShader(): Shader {
            var b = grain
            if (b == null) {
                val n = 96
                val px = IntArray(n * n)
                val rnd = Random(7)
                for (i in px.indices) {
                    val a = rnd.nextInt(256)
                    px[i] = if (rnd.nextBoolean()) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
                }
                b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
                grain = b
            }
            return BitmapShader(b!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}
