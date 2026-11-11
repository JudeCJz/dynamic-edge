package com.dynedge

import kotlin.math.abs
import kotlin.math.exp

/**
 * The continuous position of the window pages.
 *
 * - While the finger is down the position eases toward the finger-driven target ([dragTo]) with a short
 *   exponential smoothing, so the page tracks the finger almost 1:1 but never jitters.
 * - On release ([glideTo]) it settles into the destination page with a critically damped spring that starts
 *   with the finger's velocity. The release velocity is clamped so the spring can never overshoot, and the
 *   settle takes roughly 250-350 ms.
 * No allocations in [step].
 */
class PageAnim {

    /** Current position. Fractional while a page is in flight. */
    var value = 0f
        private set

    /** The page we are heading for (the finger target while dragging). */
    var target = 0f
        private set

    /** Release velocity in pages per second (signed). */
    var velocity = 0f
        private set

    /** The user's Speed setting (0.55 .. 1.6). A faster speed shortens the settle a little. */
    var speedScale = 1f

    private var following = false
    private var running = false
    private var amp = 0f       // value - target at release
    private var lin = 0f       // (v0 + w * amp)
    private var w = 19f
    private var t = 0f         // seconds since release

    /** True when nothing is in flight (no drag smoothing, no settle). */
    val settled: Boolean get() = !running && !following

    /** True while a settle is in flight. */
    val gliding: Boolean get() = running

    /** Jump straight to [v]; no animation. */
    fun snapTo(v: Float) {
        value = v
        target = v
        following = false
        running = false
        velocity = 0f
        t = 0f
    }

    /** Start following the finger from the current position (cancels a settle in flight). */
    fun followTo(v: Float) {
        value = v
        target = v
        following = true
        running = false
        velocity = 0f
    }

    /** Finger-driven target; [value] eases toward it in [step]. */
    fun dragTo(v: Float) {
        target = v
        following = true
        running = false
    }

    /** Keep the destination in step with [p] without starting a settle. */
    fun syncTarget(p: Float) {
        if (p != target) target = p
    }

    /** Settle to [p]. [vel] is the release velocity in pages per second (signed). */
    fun glideTo(p: Float, vel: Float) {
        following = false
        target = p
        amp = value - p
        if (abs(amp) < 0.0005f) {
            snapTo(p)
            return
        }
        w = W_BASE * (0.75f + 0.35f * speedScale) / 1.1f
        // velocity toward the target only up to w * distance: beyond that the spring would overshoot
        val dir = if (amp < 0f) 1f else -1f
        var v0 = vel * dir
        val cap = w * abs(amp)
        if (v0 > cap) v0 = cap
        velocity = v0 * dir
        lin = velocity + w * amp
        t = 0f
        running = true
    }

    /** Advance by [dtMs]. Returns true while something is still moving. */
    fun step(dtMs: Float): Boolean {
        val dt = dtMs * 0.001f
        if (following) {
            val k = 1f - exp(-dt / FOLLOW_TAU)
            value += (target - value) * k
            if (abs(target - value) < 0.0004f) value = target
            return true
        }
        if (!running) return false
        t += dt
        val e = exp(-w * t)
        value = target + (amp + lin * t) * e
        velocity = (lin - w * (amp + lin * t)) * e
        if (abs(value - target) < 0.0015f && abs(velocity) < 0.02f || t > MAX_S) {
            snapTo(target)
            return false
        }
        return true
    }

    companion object {
        /** Spring rate: 2% settle in about 5.8 / w seconds (about 300 ms). */
        const val W_BASE = 19f
        const val FOLLOW_TAU = 0.045f
        const val MAX_S = 0.7f
    }
}
