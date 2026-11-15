package com.dynedge

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** MEDIA window: art circle (tap = play/pause), prev/next on the ring, title/artist on arcs, progress arc. */
internal class WinMedia(h: EdgeView) : Win(h, "media", 3) {

    private val media = MediaBridge(h.context, h.dp(76f).toInt()) { onMedia() }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = h.sp(14f)
        typeface = h.face(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = EdgeView.C_GREY
        textSize = h.sp(12f)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = h.face(android.graphics.Typeface.DEFAULT)
        color = EdgeView.C_GREY
        textAlign = Paint.Align.CENTER
        textSize = h.sp(10f)
    }

    // geometry
    var artRad = 0f
        private set
    private val artIconRect = Rect()
    private val playIconRect = Rect()
    private val mArcOval = RectF()
    private var mArcR = 0f
    private val titlePath = Path()
    private val artistPath = Path()
    private var titleLen = 0f
    private var artistLen = 0f
    private var elapsedX = 0f
    private var elapsedY = 0f
    private var totalX = 0f
    private var totalY = 0f

    // state
    var playing = false
        private set
    private var hasSession = false
    private var rawTitle: String? = null
    private var rawArtist: String? = null
    private var titleShown: String = ""
    private var artistShown: String = ""
    private var titleOff = 0f
    private var artistOff = 0f
    private var artBmp: Bitmap? = null
    private val shaderMatrix = Matrix()
    private var progressFrac = 0f
    private var lastElapsedSec = -1L
    private var lastTotalSec = -1L
    private var elapsedStr: String = "0:00"
    private var totalStr: String = "0:00"
    private val sb = StringBuilder(8)
    private var seekActive = false
    private var seekFrac = 0f
    private var shown = false
    // window editor options (Prefs.KEY_MEDIA_*)
    private var optArt = true
    private var optSkip = true
    private var optText = true
    private var optSeek = true

    val artX: Float get() = cx[ART]
    val artY: Float get() = cy[ART]

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (shown && started) {
                refresh()
                h.invalidate()
                if (playing) h.postDelayed(this, 1000L)
            }
        }
    }

    override fun layout(hw: Float, brad: Float) {
        val ctx = h.context
        optArt = Prefs.flag(ctx, Prefs.KEY_MEDIA_ART)
        optSkip = Prefs.flag(ctx, Prefs.KEY_MEDIA_SKIP)
        optText = Prefs.flag(ctx, Prefs.KEY_MEDIA_TEXT)
        optSeek = Prefs.flag(ctx, Prefs.KEY_MEDIA_SEEK)
        val artD = (0.30f * rad).coerceIn(dp(52f), dp(76f))
        artRad = artD / 2f
        val rArt = 0.52f * rad
        place(ART, rArt, 45f, artRad)
        place(PREV, rArt, 14f, min(brad, dp(18f)))
        place(NEXT, rArt, 76f, min(brad, dp(18f)))
        val artEdge = sqrt(cx[ART] * cx[ART] + cy[ART] * cy[ART]) + artRad
        val artistR = max(0.72f * rad, artEdge + dp(8f))
        val titleR = artistR + dp(17f)
        mArcR = min(max(0.88f * rad, titleR + dp(29f)), rad - dp(10f))
        mArcOval.set(-mArcR, -mArcR, mArcR, mArcR)
        textArcPath(titlePath, titleR, MT_A0, MT_A1)
        textArcPath(artistPath, artistR, MT_A0, MT_A1)
        val span = Math.toRadians((MT_A1 - MT_A0).toDouble()).toFloat()
        titleLen = titleR * span
        artistLen = artistR * span

        val ih = dp(10f).toInt()
        artIconRect.set((cx[ART] - ih).toInt(), (cy[ART] - ih).toInt(), (cx[ART] + ih).toInt(), (cy[ART] + ih).toInt())
        val ph = dp(12f).toInt()
        playIconRect.set((cx[ART] - ph).toInt(), (cy[ART] - ph).toInt(), (cx[ART] + ph).toInt(), (cy[ART] + ph).toInt())

        val tr = mArcR - dp(22f)
        val a8 = Math.toRadians(8.0)
        val a82 = Math.toRadians(82.0)
        elapsedX = -tr * cos(a8).toFloat()
        elapsedY = -tr * sin(a8).toFloat() + dp(3.5f)
        totalX = -tr * cos(a82).toFloat()
        totalY = -tr * sin(a82).toFloat() + dp(3.5f)


        // force caches to rebuild against the new geometry
        rawTitle = null
        rawArtist = null
        artBmp = null
        lastElapsedSec = -1L
        lastTotalSec = -1L
        if (started) refresh()
    }

    override fun start() {
        media.start()
        refresh()
    }

    override fun stop() {
        shown = false
        h.removeCallbacks(tickRunnable)
        media.stop()
    }

    override fun onShown(on: Boolean) {
        shown = on
        h.removeCallbacks(tickRunnable)
        if (on && playing) h.postDelayed(tickRunnable, 1000L)
    }

    private fun onMedia() {
        if (!started) return
        refresh()
        h.invalidate()
        if (shown) {
            h.removeCallbacks(tickRunnable)
            if (playing) h.postDelayed(tickRunnable, 1000L)
        }
    }

    private fun refresh() {
        dirty = true
        val has = media.hasSession
        hasSession = has
        val newTitle: String
        val newArtist: String
        if (has) {
            val t = media.title
            val idleSession = t.isNullOrBlank() && !media.isPlaying
            newTitle = if (!t.isNullOrBlank()) t else if (idleSession) "Not playing" else "Playing"
            newArtist = media.artist ?: if (idleSession) "Tap play to resume" else ""
        } else {
            newTitle = "No music"
            newArtist = "Nothing playing"
        }
        if (newTitle != rawTitle) {
            rawTitle = newTitle
            titleShown = TextUtils.ellipsize(newTitle, titlePaint, titleLen, TextUtils.TruncateAt.END).toString()
            titleOff = (titleLen - titlePaint.measureText(titleShown)) / 2f
        }
        if (newArtist != rawArtist) {
            rawArtist = newArtist
            artistShown = TextUtils.ellipsize(newArtist, artistPaint, artistLen, TextUtils.TruncateAt.END).toString()
            artistOff = (artistLen - artistPaint.measureText(artistShown)) / 2f
        }
        val b = if (has && optArt) media.art else null
        if (b !== artBmp) {
            artBmp = b
            if (b == null) {
                artPaint.shader = null
            } else {
                val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderMatrix.setScale(2f * artRad / b.width, 2f * artRad / b.height)
                shaderMatrix.postTranslate(-artRad, -artRad)
                s.setLocalMatrix(shaderMatrix)
                artPaint.shader = s
            }
        }
        playing = has && media.isPlaying
        if (seekActive) return
        val dur = if (has) media.durationMs else 0L
        val pos = if (has) media.positionMs() else 0L
        progressFrac = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val es = pos / 1000L
        if (es != lastElapsedSec) {
            lastElapsedSec = es
            elapsedStr = fmt(es)
        }
        val ts = dur / 1000L
        if (ts != lastTotalSec) {
            lastTotalSec = ts
            totalStr = fmt(ts)
        }
    }

    private fun fmt(totalSec: Long): String {
        val s = if (totalSec < 0L) 0L else totalSec
        sb.setLength(0)
        sb.append(s / 60L).append(':')
        val r = s % 60L
        if (r < 10L) sb.append('0')
        sb.append(r)
        return sb.toString()
    }

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        val fill = h.fillP
        titlePaint.color = h.ink
        artistPaint.color = h.cGrey
        timePaint.color = h.cGrey
        // art circle (tap = play/pause)
        run {
            val x = cx[ART]
            val y = cy[ART]
            val ps = isPressed(ART)
            if (ps) {
                c.save()
                c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
            }
            if (artBmp != null) {
                c.save()
                c.translate(x, y)
                h.unflip(c)
                artPaint.alpha = ai
                c.drawCircle(0f, 0f, artRad, artPaint)
                c.restore()
            } else {
                fill.color = h.cFill
                fill.alpha = ai
                c.drawCircle(x, y, artRad, fill)
            }
            val pi = h.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, false)
            pi.bounds = playIconRect
            pi.alpha = (255f * 0.75f * a + 0.5f).toInt()
            h.drawIc(c, pi)
            if (ps) {
                h.ringPaint.alpha = ai
                c.drawCircle(x, y, artRad + dp(3f), h.ringPaint)
                c.restore()
            }
        }
        if (optSkip) {
            drawBtn(c, PREV, h.cFill, R.drawable.ic_skip_back, false, a, a)
            drawBtn(c, NEXT, h.cFill, R.drawable.ic_skip_forward, false, a, a)
        }

        // title / artist on arcs
        titlePaint.alpha = ai
        artistPaint.alpha = ai
        timePaint.alpha = ai
        if (optText) {
            h.arcText(c, titleShown, titlePath, titleOff, titlePaint)
            h.arcText(c, artistShown, artistPath, artistOff, artistPaint)
        }
        if (!optSeek) return

        // progress arc
        val ap = h.arcPaint
        ap.strokeWidth = h.sw(dp(3.5f))
        ap.color = h.cTrack
        ap.alpha = ai
        c.drawArc(mArcOval, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
        if (progressFrac > 0.002f) {
            ap.color = h.accent
            ap.alpha = ai
            c.drawArc(mArcOval, 180f + SL_A0, (SL_A1 - SL_A0) * progressFrac, false, ap)
        }
        val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * progressFrac).toDouble())
        fill.color = h.accent
        fill.alpha = ai
        c.drawCircle(-mArcR * cos(ang).toFloat(), -mArcR * sin(ang).toFloat(), dp(4.5f), fill)
        h.text(c, elapsedStr, elapsedX, elapsedY, timePaint)
        h.text(c, totalStr, totalX, totalY, timePaint)
    }

    // ---------------------------------------------------------------- touch
    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        consider(ART, lx, ly)
        if (optSkip) {
            consider(PREV, lx, ly)
            consider(NEXT, lx, ly)
        }
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = item == ART

    override fun onTap(item: Int) {
        when (item) {
            ART -> media.playPause()
            PREV -> media.prev()
            NEXT -> media.next()
        }
    }

    override fun onLongPress(item: Int): Boolean = if (item == ART) media.openPlayer() else false

    override fun capture(lx: Float, ly: Float): Boolean {
        if (!optSeek || !started || !media.canSeek || media.durationMs <= 0L) return false
        polar(lx, ly)
        if (!(kotlin.math.abs(pr - mArcR) < dp(14f) && pa > SL_A0 - 4f && pa < SL_A1 + 4f)) return false
        seekActive = true
        return true
    }

    override fun dragTo(lx: Float, ly: Float) {
        polar(lx, ly)
        val f = ((pa - SL_A0) / (SL_A1 - SL_A0)).coerceIn(0f, 1f)
        seekFrac = f
        progressFrac = f
        dirty = true
        val es = (f * media.durationMs / 1000f).toLong()
        if (es != lastElapsedSec) {
            lastElapsedSec = es
            elapsedStr = fmt(es)
        }
        h.invalidate()
    }

    override fun dragEnd(cancel: Boolean) {
        if (!seekActive) return
        seekActive = false
        if (!cancel && started) {
            media.seekTo((seekFrac * media.durationMs).toLong())
            h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private companion object {
        const val ART = 0
        const val PREV = 1
        const val NEXT = 2
        const val MT_A0 = 16f
        const val MT_A1 = 74f
    }
}
