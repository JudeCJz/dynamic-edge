package com.dynedge

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextPaint
import android.util.Size
import kotlin.math.min

/**
 * SCREENSHOTS window: the most recent images from the Screenshots folder as rounded thumbnails on rings
 * (same layout as the Apps grid). Tap = open in the gallery, long-press = share. Without photo access a single
 * hint is shown and a tap opens the app details settings.
 */
internal class WinScreenshots(h: EdgeView) : Win(h, "screenshots", ITEMS) {

    private val ring = AppRing.Out(MAX)
    private val rot = FloatArray(ITEMS)
    private val uris = arrayOfNulls<Uri>(MAX)
    private val bmps = arrayOfNulls<Bitmap>(MAX)
    private val paints = Array(MAX) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val mat = Matrix()
    private val rect = RectF()
    private var n = 0
    private var gen = 0
    private var denied = false
    private var iconR = 0f
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        textAlign = Paint.Align.CENTER
        textSize = h.sp(14f)
    }

    private fun hasPermission(): Boolean {
        val c = h.context
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        for (p in perms) if (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true
        return false
    }

    override fun layout(hw: Float, brad: Float) {
        val cnt = if (n > 0) n else MAX
        AppRing.compute(ring, min(cnt, MAX), rad, dp(26f), dp(8f), false, h.dp(1f))
        iconR = ring.itemR
        for (k in 0 until MAX) {
            if (k < ring.n) {
                place(k, ring.r[k], ring.ang[k], iconR)
                rot[k] = ring.rot[k]
            } else {
                place(k, 0.6f * rad, 45f, iconR)
                rot[k] = -45f
            }
        }
        place(HINT, 0.6f * rad, 45f, dp(44f))
        if (started) bind()
    }

    override fun start() {
        load()
    }

    override fun onShown(on: Boolean) {
        if (on) load()
    }

    override fun stop() {
        gen++
        n = 0
        for (i in 0 until MAX) {
            uris[i] = null
            bmps[i] = null
            paints[i].shader = null
        }
    }

    private fun load() {
        val g = ++gen
        if (!hasPermission()) {
            denied = true
            n = 0
            dirty = true
            h.invalidate()
            return
        }
        denied = false
        val app = h.context.applicationContext ?: h.context
        val px = (dp(26f) * 2f).toInt().coerceAtLeast(64)
        val t = Thread({
            val us = ArrayList<Uri>()
            val bs = ArrayList<Bitmap>()
            var failed = false
            try {
                val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val sel = MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ? OR " +
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
                val args = arrayOf("%Screenshot%", "%Screenshot%")
                app.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), sel, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
                    while (cur.moveToNext() && us.size < MAX) {
                        val uri = ContentUris.withAppendedId(col, cur.getLong(0))
                        try {
                            bs.add(app.contentResolver.loadThumbnail(uri, Size(px, px), null))
                            us.add(uri)
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: SecurityException) {
                failed = true
            } catch (e: Exception) {
            }
            h.post {
                if (g == gen && started) {
                    denied = failed
                    set(us, bs)
                }
            }
        }, "edge-shots")
        t.isDaemon = true
        t.start()
    }

    private fun set(us: List<Uri>, bs: List<Bitmap>) {
        n = min(us.size, MAX)
        for (i in 0 until MAX) {
            uris[i] = if (i < n) us[i] else null
            bmps[i] = if (i < n) bs[i] else null
        }
        layout(0f, 0f)
        h.invalidate()
    }

    private fun bind() {
        dirty = true
        val r = iconR * 0.92f
        for (i in 0 until MAX) {
            val b = bmps[i]
            if (b == null || i >= n) {
                paints[i].shader = null
                continue
            }
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = 2f * r / min(b.width, b.height)
            mat.setScale(s, s)
            mat.postTranslate(-b.width * s / 2f, -b.height * s / 2f)
            sh.setLocalMatrix(mat)
            paints[i].shader = sh
        }
    }

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        if (n == 0) {
            val x = cx[HINT]
            val y = cy[HINT]
            c.save()
            c.translate(x, y)
            h.unflip(c)
            hint.color = h.cGrey
            hint.alpha = ai
            c.drawText(if (denied) "Allow photos" else "No screenshots", 0f, hint.textSize / 3f, hint)
            c.restore()
            return
        }
        val r = iconR * 0.92f
        val rr = iconR + dp(3f)
        for (k in 0 until n) {
            val ps = isPressed(k)
            c.save()
            c.translate(cx[k], cy[k])
            c.rotate(rot[k])
            h.unflip(c)
            if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
            val p = paints[k]
            if (p.shader != null) {
                p.alpha = ai
                rect.set(-r, -r, r, r)
                c.drawRoundRect(rect, r * 0.3f, r * 0.3f, p)
            }
            if (ps) {
                h.ringPaint.alpha = ai
                c.drawRoundRect(-rr, -rr, rr, rr, rr * 0.3f, rr * 0.3f, h.ringPaint)
            }
            c.restore()
        }
    }

    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        if (n == 0) {
            if (denied) consider(HINT, lx, ly)
        } else {
            for (k in 0 until n) consider(k, lx, ly)
        }
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = item in 0 until n

    override fun onLongPress(item: Int): Boolean {
        val u = uris.getOrNull(item) ?: return false
        return try {
            val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ch = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            h.context.startActivity(ch)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onTap(item: Int) {
        val ctx = h.context
        try {
            if (n == 0) {
                if (!denied) return
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                h.collapse()
                return
            }
            val u = uris.getOrNull(item) ?: return
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            h.collapse()
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val MAX = 9
        const val HINT = MAX
        const val ITEMS = MAX + 1
    }
}
