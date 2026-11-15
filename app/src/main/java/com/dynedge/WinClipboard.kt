package com.dynedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** CLIPBOARD window: up to 4 recent clips as arc rows; tap = paste, long-press = pin, badge = floating ClipPopup. */
internal class WinClipboard(h: EdgeView) : Win(h, "clipboard", 5) {

    private val clipPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = Color.WHITE
        textSize = h.sp(13f)
    }
    private val rowR = FloatArray(4)
    private val rowLen = FloatArray(4)
    private val rowPath = Array(4) { Path() }
    private val emptyRect = Rect()
    private var rowHalf = 0f
    private var clips: List<ClipStore.Clip> = emptyList()
    private var nClip = 0
    private val clipShown = Array(4) { "" }
    private val clipPin = BooleanArray(4)
    private var store: ClipStore? = null
    private val listener: () -> Unit = {
        if (started) {
            refresh()
            h.invalidate()
        }
    }

    private var maxRows = 4

    override fun layout(hw: Float, brad: Float) {
        maxRows = Prefs.rows(h.context, Prefs.KEY_CLIP_ROWS)
        for (k in 0 until 4) {
            rowR[k] = min(CLIP_F[k] * rad, rad - dp(24f))
            textArcPath(rowPath[k], rowR[k], CL_A0, CL_A1)
            rowLen[k] = rowR[k] * Math.toRadians((CL_A1 - CL_A0).toDouble()).toFloat()
        }
        rowHalf = min(dp(16f), (rowR[1] - rowR[0]) / 2f)
        place(MORE, 0.92f * rad, 80f, dp(13f))
        // empty state icon
        val ea = Math.toRadians(45.0)
        val er = 0.55f * rad
        val ex = (-er * cos(ea)).toInt()
        val ey = (-er * sin(ea)).toInt()
        val eh = dp(18f).toInt()
        emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
        if (started) refresh()
    }

    override fun start() {
        val cs = ClipStore.get(h.context)
        store = cs
        cs.addListener(listener)
        refresh()
    }

    override fun stop() {
        store?.removeListener(listener)
        store = null
    }

    override fun onShown(on: Boolean) {
        // capture the system clipboard when this window becomes the visible one
        if (on) h.requestCapture()
    }

    private fun refresh() {
        dirty = true
        val list = (store ?: ClipStore.get(h.context)).list()
        clips = list
        nClip = min(maxRows, list.size)
        for (k in 0 until nClip) {
            val c = list[k]
            clipPin[k] = c.pinned
            val flat = c.text.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
            clipShown[k] = TextUtils.ellipsize(flat, clipPaint, rowLen[k], TextUtils.TruncateAt.END).toString()
        }
    }

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        if (nClip == 0) {
            val ic = h.icon(R.drawable.ic_clipboard, false)
            ic.bounds = emptyRect
            ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
            h.drawIc(c, ic)
        } else {
            clipPaint.color = h.ink
            clipPaint.alpha = ai
            val ap = h.arcPaint
            val lp = h.linePaint
            for (k in 0 until nClip) {
                val r = rowR[k]
                if (isPressed(k)) {
                    val rr = r + dp(5f)
                    h.ovalScratch.set(-rr, -rr, rr, rr)
                    ap.strokeWidth = h.sw(rowHalf * 2f)
                    ap.color = h.ink
                    ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
                    c.drawArc(h.ovalScratch, 180f + CL_A0 - 2f, CL_A1 - CL_A0 + 4f, false, ap)
                }
                if (clipPin[k]) {
                    val ta = Math.toRadians(4.5)
                    val ct = cos(ta).toFloat()
                    val st = sin(ta).toFloat()
                    lp.color = h.accent
                    lp.alpha = ai
                    lp.strokeWidth = h.sw(dp(2.2f))
                    c.drawLine(-(r - dp(3f)) * ct, -(r - dp(3f)) * st, -(r + dp(13f)) * ct, -(r + dp(13f)) * st, lp)
                }
                h.arcText(c, clipShown[k], rowPath[k], 0f, clipPaint)
            }
        }
        // "more" badge: opens the floating clipboard popup
        drawBtn(c, MORE, h.cFill, 0, false, a, a)
        h.fillP.color = h.ink
        h.fillP.alpha = ai
        val mx = cx[MORE]
        val my = cy[MORE]
        for (j in -1..1) c.drawCircle(mx + j * dp(5f), my, dp(1.6f), h.fillP)
    }

    // ---------------------------------------------------------------- touch
    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        consider(MORE, lx, ly)
        if (bestHit() < 0) {
            polar(lx, ly)
            if (pa > CL_A0 - 4f && pa < CL_A1 + 4f) {
                for (k in 0 until nClip) {
                    if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
                        forceHit(k)
                        break
                    }
                }
            }
        }
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = item in 0..3

    override fun onTap(item: Int) {
        if (item == MORE) {
            h.openClipboard()
            h.collapse()
            return
        }
        if (item >= clips.size) return
        val text = clips[item].text
        h.collapse()
        h.postDelayed({
            val svc = h.service
            val ok = svc != null && ClipPaste.paste(svc, text)
            if (!ok) h.showCopied()
        }, 120L)
    }

    override fun onLongPress(item: Int): Boolean {
        if (item >= clips.size) return false
        val c = clips[item]
        (store ?: ClipStore.get(h.context)).pin(c.id, !c.pinned)
        return false
    }

    private companion object {
        const val MORE = 4
        const val CL_A0 = 8f
        const val CL_A1 = 72f
        val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
    }
}
