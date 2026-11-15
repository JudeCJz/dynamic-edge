package com.dynedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * TIMER window. Standalone: the dial (drag 1..60 min, chips 1/5/10/25) starts the edge's OWN timer, which keeps
 * running without the clock app (see [TimerControl]); while it runs the window shows the countdown arc with
 * +1 min, pause / resume and cancel. If the clock app has a running countdown (read from its notification) it is
 * shown read-only as linked, with a button that opens the clock app. At zero the window shows Stop for the alarm.
 * Long-press start = start the countdown in the clock app instead.
 */
internal class WinTimer(h: EdgeView) : Win(h, "timer", 10) {

    private val dial = RectF()
    private var dialR = 0f
    private var minutes = 10
    private var valueStr = "10:00"
    private var runStr = "0:00"
    private var valueX = 0f
    private var valueY = 0f
    private var dragging = false

    private var mode = M_DIAL
    private var remMs = 0L
    private var totMs = 0L
    private var linkedBase = 0L
    private var linkedTot = 0L
    private var linkedPlus = false
    private var linkedToggle = false
    private var linkedPaused = false
    private var linkedUnknown = false
    private var shown = false
    private val tick = object : Runnable {
        override fun run() {
            if (!shown || !started) return
            refreshState()
            h.invalidate()
            if (mode == M_OWN || mode == M_LINKED) h.postDelayed(this, delayToNextSecond())
        }
    }
    private val sb = StringBuilder(8)
    /** The four preset chips (Prefs.KEY_TIMER_P + 0..3). */
    private val presets = Prefs.TIMER_DEF.copyOf()
    private val presetTxt = Array(4) { presets[it].toString() }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = h.face(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = h.face(Typeface.DEFAULT_BOLD)
        textSize = h.sp(12f)
    }

    init {
        buildValue()
    }

    override fun layout(hw: Float, brad: Float) {
        for (i in 0 until 4) {
            val p = Prefs.timerPreset(h.context, i)
            if (p != presets[i]) {
                presets[i] = p
                presetTxt[i] = p.toString()
            }
        }
        dialR = min(0.86f * rad, rad - dp(14f))
        dial.set(-dialR, -dialR, dialR, dialR)
        val chipR = min(dp(20f), brad) // up to a 40 dp visible chip (hit target is 44 dp)
        val ringR = 0.68f * rad
        for (i in 0 until 4) place(i, ringR, slotAngle(i, 4), chipR)
        val inner = max(0.40f * rad, dp(78f))
        for (id in intArrayOf(START, OPEN, STOP_RING)) {
            place(id, inner, 24f, dp(20f))
            setIconRect(id, dp(10f).toInt())
        }
        // running controls share the chip ring: +1 min, pause / resume, cancel
        place(PLUS, ringR, slotAngle(0, 3), chipR)
        place(PAUSE, ringR, slotAngle(1, 3), chipR)
        setIconRect(PAUSE, dp(10f).toInt())
        place(CANCEL, ringR, slotAngle(2, 3), chipR)
        setIconRect(CANCEL, dp(10f).toInt())
        val va = Math.toRadians(62.0)
        valueX = -inner * cos(va).toFloat()
        valueY = -inner * sin(va).toFloat()
        valuePaint.textSize = min(h.sp(24f), 0.13f * rad)
        valueY -= (valuePaint.ascent() + valuePaint.descent()) / 2f
    }

    private fun delayToNextSecond(): Long = (remMs % 1000L + 8L).coerceIn(16L, 1008L)

    /** Re-reads the state: ringing, own timer (running / paused), linked clock-app timer, else the creation dial. */
    private fun refreshState() {
        val ctx = h.context
        var r = TimerControl.remainingMs(ctx)
        val paused = TimerControl.isPaused(ctx)
        if (r != TimerControl.NONE && r <= 0L && !paused) {
            TimerControl.finish(ctx) // reached zero: clears it and rings
            r = TimerControl.remainingMs(ctx)
        }
        val wasPaused = linkedPaused
        val wasUnknown = linkedUnknown
        var m = M_DIAL
        var rem = 0L
        var tot = 0L
        if (TimerControl.isRinging()) {
            m = M_RING
        } else if (r != TimerControl.NONE) {
            rem = r
            tot = max(TimerControl.totalMs(ctx), r)
            m = if (paused) M_PAUSED else M_OWN
        } else {
            val l = LiveBus.linkedTimer()
            linkedPaused = false
            linkedUnknown = false
            linkedToggle = false
            if (l != null) {
                linkedPaused = l.paused
                linkedUnknown = if (l.paused) l.remainingMs < 0L else l.chronoBase == 0L
                if (linkedUnknown) {
                    rem = 0L
                } else if (l.paused) {
                    rem = l.remainingMs
                    tot = max(l.totalMs, rem)
                } else {
                    rem = l.chronoBase - System.currentTimeMillis()
                    if (l.chronoBase != linkedBase) {
                        linkedBase = l.chronoBase
                        linkedTot = if (l.totalMs > 0L) l.totalMs else max(1000L, rem)
                    }
                    tot = max(linkedTot, rem)
                }
                linkedPlus = l.answer != null
                linkedToggle = TimerControl.canToggleLinked()
                m = M_LINKED
            }
        }
        val changed = m != mode || rem / 1000L != remMs / 1000L || tot != totMs || linkedPaused != wasPaused ||
            linkedUnknown != wasUnknown
        mode = m
        remMs = rem
        totMs = tot
        if (m != M_DIAL && m != M_RING && !(m == M_LINKED && linkedUnknown)) {
            val t = (rem + 999L) / 1000L
            sb.setLength(0)
            if (t >= 3600L) {
                sb.append(t / 3600L).append(':')
                val mm = (t % 3600L) / 60L
                if (mm < 10L) sb.append('0')
                sb.append(mm)
            } else {
                sb.append(t / 60L)
            }
            sb.append(':')
            val sec = t % 60L
            if (sec < 10L) sb.append('0')
            sb.append(sec)
            runStr = sb.toString()
        }
        if (changed) dirty = true
    }

    private fun reschedule() {
        h.removeCallbacks(tick)
        if (shown && started && (mode == M_OWN || mode == M_LINKED)) h.postDelayed(tick, delayToNextSecond())
    }

    /** LiveBus changed (a linked notification came or went, the alarm rang or was stopped). */
    fun onLive() {
        if (!started) return
        val was = mode
        refreshState()
        reschedule()
        if (was != mode || dirty) h.invalidate()
    }

    override fun start() {
        refreshState()
    }

    override fun stop() {
        shown = false
        h.removeCallbacks(tick)
    }

    override fun onShown(on: Boolean) {
        shown = on
        h.removeCallbacks(tick)
        if (on) {
            refreshState()
            h.invalidate()
            reschedule() // 1 Hz, only while this window is the visible one and a countdown runs
        }
    }

    private fun buildValue() {
        sb.setLength(0)
        sb.append(minutes).append(":00")
        valueStr = sb.toString()
    }

    private fun setMinutes(m: Int) {
        val v = m.coerceIn(1, 60)
        if (v == minutes) return
        minutes = v
        buildValue()
        dirty = true
        h.invalidate()
    }

    private fun drawCountdown(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        val ap = h.arcPaint
        valuePaint.color = h.ink
        ap.strokeWidth = h.sw(dp(6f))
        ap.color = h.cTrack
        ap.alpha = ai
        c.drawArc(dial, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
        val ring = mode == M_RING
        val unk = mode == M_LINKED && linkedUnknown
        val f = if (ring || unk) 1f else if (totMs > 0L) (remMs.toFloat() / totMs).coerceIn(0f, 1f) else 0f
        // linked timers are not ours: white arc; ours use the accent
        ap.color = if (mode == M_LINKED) h.ink else h.accent
        ap.alpha = if (mode == M_PAUSED || (mode == M_LINKED && linkedPaused)) (ai * 0.5f).toInt()
        else if (unk) (ai * 0.35f).toInt() else ai
        if (f > 0.002f) c.drawArc(dial, 180f + SL_A0, (SL_A1 - SL_A0) * f, false, ap)
        val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * f).toDouble())
        h.fillP.color = h.ink
        h.fillP.alpha = ai
        if (!unk) c.drawCircle(-dialR * cos(ang).toFloat(), -dialR * sin(ang).toFloat(), dp(7f), h.fillP)
        valuePaint.alpha = if (mode == M_PAUSED || (mode == M_LINKED && linkedPaused)) (ai * 0.6f).toInt() else ai
        h.text(c, if (ring) "0:00" else if (unk) "--:--" else runStr, valueX, valueY, valuePaint)
        when (mode) {
            M_RING -> drawBtn(c, STOP_RING, h.accent, R.drawable.ic_x, true, a, a)
            M_LINKED -> {
                drawBtn(c, OPEN, h.cFill, R.drawable.ic_alarm, false, a, a)
                drawBtn(c, CANCEL, h.cFill, R.drawable.ic_x, false, a, a)
                if (linkedToggle) {
                    drawBtn(c, PAUSE, h.cFill, if (linkedPaused) R.drawable.ic_play else R.drawable.ic_pause, false, a, a)
                }
                if (linkedPlus) {
                    drawBtn(c, PLUS, h.cFill, 0, false, a, a)
                    chipPaint.color = h.ink
                    chipPaint.alpha = ai
                    h.text(c, "+1", cx[PLUS], cy[PLUS] - (chipPaint.ascent() + chipPaint.descent()) / 2f, chipPaint)
                }
            }
            else -> {
                drawBtn(c, PLUS, h.cFill, 0, false, a, a)
                chipPaint.color = h.ink
                chipPaint.alpha = ai
                h.text(c, "+1", cx[PLUS], cy[PLUS] - (chipPaint.ascent() + chipPaint.descent()) / 2f, chipPaint)
                drawBtn(c, PAUSE, h.cFill, if (mode == M_PAUSED) R.drawable.ic_play else R.drawable.ic_pause, false, a, a)
                drawBtn(c, CANCEL, h.cFill, R.drawable.ic_x, false, a, a)
            }
        }
    }

    override fun draw(c: Canvas, a: Float) {
        if (mode != M_DIAL) {
            drawCountdown(c, a)
            return
        }
        val ai = (255f * a + 0.5f).toInt()
        val ap = h.arcPaint
        ap.strokeWidth = h.sw(dp(6f))
        ap.color = h.cTrack
        ap.alpha = ai
        c.drawArc(dial, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
        val f = minutes / 60f
        ap.color = h.accent
        ap.alpha = ai
        c.drawArc(dial, 180f + SL_A0, (SL_A1 - SL_A0) * f, false, ap)
        val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * f).toDouble())
        h.fillP.color = h.ink
        h.fillP.alpha = ai
        c.drawCircle(-dialR * cos(ang).toFloat(), -dialR * sin(ang).toFloat(), dp(if (dragging) 9f else 7f), h.fillP)

        valuePaint.color = h.ink
        valuePaint.alpha = ai
        h.text(c, valueStr, valueX, valueY, valuePaint)

        for (i in 0 until 4) {
            val sel = presets[i] == minutes
            drawBtn(c, i, if (sel) h.accent else h.cFill, 0, false, a, a)
            chipPaint.color = if (sel) Color.BLACK else h.ink
            chipPaint.alpha = ai
            h.text(c, presetTxt[i], cx[i], cy[i] - (chipPaint.ascent() + chipPaint.descent()) / 2f, chipPaint)
        }
        drawBtn(c, START, h.accent, R.drawable.ic_play, true, a, a)
    }

    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        when (mode) {
            M_DIAL -> for (i in 0..START) consider(i, lx, ly)
            M_RING -> consider(STOP_RING, lx, ly)
            M_LINKED -> {
                consider(OPEN, lx, ly)
                consider(CANCEL, lx, ly)
                if (linkedToggle) consider(PAUSE, lx, ly)
                if (linkedPlus) consider(PLUS, lx, ly)
            }
            else -> {
                consider(PLUS, lx, ly)
                consider(PAUSE, lx, ly)
                consider(CANCEL, lx, ly)
            }
        }
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = item == START && mode == M_DIAL

    override fun onTap(item: Int) {
        val ctx = h.context
        when (item) {
            CANCEL -> {
                if (mode == M_LINKED) {
                    // the clock app has no stop action: open its timers; the timer leaves here when its notification does
                    if (TimerControl.stopLinked(ctx)) h.collapse()
                } else {
                    TimerControl.cancel(ctx)
                }
                h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                afterAction()
            }
            PAUSE -> {
                if (mode == M_LINKED) TimerControl.toggleLinked()
                else if (TimerControl.isPaused(ctx)) TimerControl.resume(ctx) else TimerControl.pause(ctx)
                h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                afterAction()
            }
            PLUS -> {
                if (mode == M_LINKED) TimerControl.addMinuteLinked() else TimerControl.addMinute(ctx)
                h.haptic(HapticFeedbackConstants.CLOCK_TICK)
                afterAction()
            }
            OPEN -> if (TimerControl.openTimers(ctx)) h.collapse()
            STOP_RING -> {
                TimerControl.stopAlarm(ctx)
                h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                afterAction()
            }
            START -> {
                TimerControl.start(ctx, minutes * 60)
                h.haptic(HapticFeedbackConstants.CONFIRM)
                h.collapse()
            }
            else -> if (item < 4) {
                setMinutes(presets[item])
                h.haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    private fun afterAction() {
        refreshState()
        dirty = true
        reschedule()
        h.invalidate()
    }

    /** Long-press start: run the standalone edge timer instead of the clock app's. */
    override fun onLongPress(item: Int): Boolean {
        TimerControl.startOwn(h.context, minutes * 60)
        return true
    }

    override fun capture(lx: Float, ly: Float): Boolean {
        if (mode != M_DIAL) return false
        polar(lx, ly)
        if (abs(pr - dialR) < dp(14f) && pa > SL_A0 - 4f && pa < SL_A1 + 4f) {
            dragging = true
            return true
        }
        return false
    }

    override fun dragTo(lx: Float, ly: Float) {
        polar(lx, ly)
        val f = ((pa - SL_A0) / (SL_A1 - SL_A0)).coerceIn(0f, 1f)
        val m = Math.round(f * 60f).coerceIn(1, 60)
        if (m != minutes) {
            // one tick per crossing of a 5 minute mark, redraw only when the snapped value changed
            val crossed = m / 5 != minutes / 5
            minutes = m
            buildValue()
            dirty = true
            if (crossed) h.haptic(HapticFeedbackConstants.CLOCK_TICK)
            h.invalidate()
        }
    }

    override fun dragEnd(cancel: Boolean) {
        if (!dragging) return
        dragging = false
        dirty = true
        h.invalidate()
    }

    private companion object {
        const val START = 4
        const val CANCEL = 5
        const val PAUSE = 6
        const val PLUS = 7
        const val OPEN = 8
        const val STOP_RING = 9
        const val M_DIAL = 0
        const val M_OWN = 1
        const val M_PAUSED = 2
        const val M_LINKED = 3
        const val M_RING = 4
    }
}
