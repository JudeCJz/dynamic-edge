package com.dynedge

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * A synthetic music signal for the settings app (no microphone): four band levels, a bass envelope and a
 * beat flag, at about 118 BPM. Drives the animated visualizer thumbnails.
 */
internal class DemoBeat {
    val levels = FloatArray(4)
    var bass = 0f
        private set
    var energy = 0f
        private set
    /** Onset strength for the step just taken (0 between beats). */
    var kick = 0f
        private set
    private var t = 0f
    private var since = 0f

    fun step(dt: Float) {
        t += dt
        since += dt
        kick = 0f
        if (since >= PERIOD) {
            since -= PERIOD
            kick = 0.9f
        }
        val env = exp(-since * 7f)
        val off = (since + PERIOD * 0.5f) % PERIOD
        val env2 = exp(-off * 10f)
        levels[0] = (0.12f + 0.82f * env + 0.05f * sin(t * 3.1f)).coerceIn(0f, 1f)
        levels[1] = (0.18f + 0.5f * env + 0.22f * abs(sin(t * 5.3f))).coerceIn(0f, 1f)
        levels[2] = (0.14f + 0.4f * env2 + 0.25f * abs(sin(t * 7.7f + 1f))).coerceIn(0f, 1f)
        levels[3] = (0.1f + 0.55f * env2 + 0.15f * abs(sin(t * 11.3f))).coerceIn(0f, 1f)
        bass = env
        energy = (0.65f * env + 0.25f * env2).coerceAtMost(1f)
    }

    private companion object {
        const val PERIOD = 60f / 118f
    }
}

/**
 * Runs the synthetic beat for the studio: it steps the [DemoBeat] on the frame clock and asks the studio
 * to redraw the animated visualizer thumbnails.
 *
 * Frames only run while the music panel is on screen and the screen is awake - [stop] takes the callback off
 * the clock again, so nothing ticks in the background.
 */
internal class BeatDriver(private val onFrame: () -> Unit) {

    /** The live levels array. Share it with the thumbnails: they read it every frame. */
    val beat = DemoBeat()

    private var running = false
    private var lastNs = 0L
    private val frameCb = Choreographer.FrameCallback { now -> frame(now) }

    fun start() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    private fun frame(now: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 1f / 120f else ((now - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNs = now
        beat.step(dt)
        onFrame()
        Choreographer.getInstance().postFrameCallback(frameCb)
    }
}
