package com.dynedge

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** NOTIFICATIONS window: up to 4 recent notifications as arc rows; tap opens, long-press dismisses. */
internal class WinNotifications(h: EdgeView) : Win(h, "notifications", 5) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = h.sp(13f)
        typeface = h.face(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = EdgeView.C_GREY
        textSize = h.sp(12f)
    }
    private val rowR = FloatArray(5)
    private val rowPath = Array(5) { Path() }
    private val rowLen = FloatArray(5)
    private val iconX = FloatArray(5)
    private val iconY = FloatArray(5)
    private val emptyRect = Rect()
    private var rowHalf = 0f
    private var iconD = 0f
    private var textOff = 0f
    private var items: List<NotifFeed.Item> = emptyList()
    private var n = 0
    private val shown = Array(5) { "" }      // title (or the text when there is no title)
    private val bodyShown = Array(5) { "" }   // grey continuation after the title
    private val bodyOff = FloatArray(5)
    private val iconPaint = Array(5) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val iconBmp = arrayOfNulls<Bitmap>(5)
    private val iconMatrix = Matrix()

    // window editor options (Prefs.KEY_NOTIF_*)
    private var maxRows = 5
    private var optBody = true
    private var optIcons = true

    override fun layout(hw: Float, brad: Float) {
        val ctx = h.context
        maxRows = Prefs.rows(ctx, Prefs.KEY_NOTIF_ROWS, 5)
        optBody = Prefs.flag(ctx, Prefs.KEY_NOTIF_BODY)
        optIcons = Prefs.flag(ctx, Prefs.KEY_NOTIF_ICONS)
        iconD = dp(16f)
        textOff = if (optIcons) iconD + dp(8f) else 0f
        placeRows()
        val ea = Math.toRadians(45.0)
        val er = 0.55f * rad
        val ex = (-er * cos(ea)).toInt()
        val ey = (-er * sin(ea)).toInt()
        val eh = dp(18f).toInt()
        emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
        for (k in 0 until 5) iconBmp[k] = null
        if (started) refresh()
    }

    override fun start() {
        NotifFeed.onChange = {
            if (started) {
                refresh()
                h.invalidate()
            }
        }
        refresh()
    }

    override fun stop() {
        NotifFeed.onChange = null
        for (k in 0 until 5) {
            iconBmp[k] = null
            iconPaint[k].shader = null
        }
        items = emptyList()
        n = 0
    }

    /** Rows hug the outer layer: newest on the outermost ring, older ones step inward; pitch adapts to the count. */
    private fun placeRows() {
        val outer = rad - dp(24f)
        val inner = 0.30f * rad
        val cnt = n.coerceAtLeast(1)
        val pitch = min(dp(38f), (outer - inner) / cnt.coerceAtLeast(2))
        rowHalf = pitch / 2f
        iconD = min(dp(18f), pitch - dp(6f))
        textOff = if (optIcons) iconD + dp(8f) else 0f
        for (k in 0 until 5) {
            rowR[k] = (outer - k * pitch).coerceAtLeast(dp(40f))
            textArcPath(rowPath[k], rowR[k], A0, A1)
            rowLen[k] = rowR[k] * Math.toRadians((A1 - A0).toDouble()).toFloat()
            val ia = Math.toRadians((A0 + Math.toDegrees(((iconD / 2f + dp(1f)) / rowR[k]).toDouble())))
            val ro = rowR[k] + dp(5f)
            iconX[k] = -ro * cos(ia).toFloat()
            iconY[k] = -ro * sin(ia).toFloat()
        }
    }

    private fun refresh() {
        dirty = true
        val list = NotifFeed.recent()
        items = list
        n = min(maxRows, list.size)
        placeRows()
        val px = dp(16f).toInt().coerceAtLeast(8)
        val pix = IconTheme.pixelated(h.context)
        val r = IconTheme.snapSize(h.context, iconD) / 2f
        for (k in 0 until 5) {
            iconPaint[k].isFilterBitmap = !pix
            iconPaint[k].isAntiAlias = !pix
        }
        for (k in 0 until n) {
            val it = list[k]
            val primary = NotifFeed.appLabel(h.context, it.pkg)
            val second = flat(if (it.title.isBlank()) it.text else if (it.text.isBlank()) it.title else it.title + " - " + it.text)
            val avail = max0(rowLen[k] - textOff)
            shown[k] = TextUtils.ellipsize(primary, textPaint, avail, TextUtils.TruncateAt.END).toString()
            val used = textPaint.measureText(shown[k]) + dp(8f)
            val left = avail - used
            if (second.isNotEmpty() && left > dp(28f)) {
                bodyShown[k] = TextUtils.ellipsize(second, bodyPaint, left, TextUtils.TruncateAt.END).toString()
                bodyOff[k] = textOff + used
            } else {
                bodyShown[k] = ""
            }
            val b = if (optIcons) NotifFeed.appIcon(h.context, it.pkg, px) else null
            if (b !== iconBmp[k]) {
                iconBmp[k] = b
                if (b == null) {
                    iconPaint[k].shader = null
                } else {
                    val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    iconMatrix.setScale(2f * r / b.width, 2f * r / b.height)
                    iconMatrix.postTranslate(-r, -r)
                    s.setLocalMatrix(iconMatrix)
                    iconPaint[k].shader = s
                }
            }
        }
        for (k in n until 5) {
            iconBmp[k] = null
            iconPaint[k].shader = null
        }
    }

    private fun max0(v: Float) = if (v < 0f) 0f else v

    private fun flat(t: String): String =
        t.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        if (n == 0) {
            val ic = h.icon(R.drawable.ic_bell, false)
            ic.bounds = emptyRect
            ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
            h.drawIc(c, ic)
            return
        }
        textPaint.color = h.ink
        bodyPaint.color = h.cGrey
        textPaint.alpha = ai
        bodyPaint.alpha = ai
        val ap = h.arcPaint
        for (k in 0 until n) {
            val r = rowR[k]
            if (isPressed(k)) {
                val rr = r + dp(5f)
                h.ovalScratch.set(-rr, -rr, rr, rr)
                ap.strokeWidth = h.sw(rowHalf * 2f)
                ap.color = h.ink
                ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
                c.drawArc(h.ovalScratch, 180f + A0 - 2f, A1 - A0 + 4f, false, ap)
            }
            val p = iconPaint[k]
            if (p.shader != null) {
                c.save()
                c.translate(iconX[k], iconY[k])
                h.unflip(c)
                p.alpha = ai
                c.drawCircle(0f, 0f, iconD / 2f, p)
                c.restore()
            }
            h.arcText(c, shown[k], rowPath[k], textOff, textPaint)
            if (bodyShown[k].isNotEmpty()) h.arcText(c, bodyShown[k], rowPath[k], bodyOff[k], bodyPaint)
        }
    }

    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        polar(lx, ly)
        if (pa > A0 - 4f && pa < A1 + 4f) {
            for (k in 0 until n) {
                if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
                    forceHit(k)
                    break
                }
            }
        }
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = true

    override fun onTap(item: Int) {
        val it = items.getOrNull(item) ?: return
        val pi = it.contentIntent
        if (pi == null) {
            h.haptic(HapticFeedbackConstants.REJECT)
            return
        }
        try {
            pi.send()
        } catch (e: PendingIntent.CanceledException) {
            // the app cancelled this notification's intent: show it as unusable instead of closing the panel
            h.haptic(HapticFeedbackConstants.REJECT)
            return
        } catch (e: Exception) {
        }
        h.collapse()
    }

    override fun onLongPress(item: Int): Boolean {
        val it = items.getOrNull(item) ?: return false
        if (it.clearable) NotifFeed.dismiss(it.key)
        return false
    }

    private companion object {
        const val A0 = 6f
        const val A1 = 72f
        val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
    }
}
