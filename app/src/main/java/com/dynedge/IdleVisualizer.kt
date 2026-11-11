package com.dynedge

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Four-band level meter for the idle bean. Uses the global output mix (Visualizer session 0, needs
 * RECORD_AUDIO) and falls back to a synthetic animation when that is unavailable or silent.
 * start()/stop() create and release the Visualizer; tick() runs from the owner's throttled frame loop.
 */
class IdleVisualizer(private val ctx: Context) {

    /** Smoothed bar heights, 0..1. */
    val levels = FloatArray(4)

    private val target = FloatArray(4)
    private val mags = FloatArray(4)
    private var viz: Visualizer? = null
    private var peak = 6f
    private var lastRealMs = 0L
    private var startMs = 0L
    private var phase = 0f
    private var seed = 12345

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft != null) reduce(fft)
        }
    }

    fun start() {
        if (viz != null) return
        startMs = 0L
        phase = 0f
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        var v: Visualizer? = null
        try {
            v = Visualizer(0)
            v.setEnabled(false)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[0])
            v.setDataCaptureListener(listener, min(20000, Visualizer.getMaxCaptureRate()), false, true)
            v.setEnabled(true)
            viz = v
        } catch (e: Throwable) {
            try {
                v?.setEnabled(false)
                v?.release()
            } catch (e2: Throwable) {
            }
            viz = null
        }
    }

    fun stop() {
        val v = viz ?: return
        viz = null
        try {
            v.setEnabled(false)
            v.setDataCaptureListener(null, 0, false, false)
            v.release()
        } catch (e: Throwable) {
        }
    }

    /** Bins: [re0 (DC), re(n/2), re1, im1, re2, im2, ...]. Four bands, low to high. */
    private fun reduce(fft: ByteArray) {
        val n = fft.size / 2
        if (n < 8) return
        var top = 0f
        var lo = 1
        for (b in 0 until 4) {
            val hi = max(lo + 1, min(n, EDGES[b] * n / 64))
            var sum = 0f
            for (k in lo until hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += sqrt(re * re + im * im)
            }
            mags[b] = sum / (hi - lo)
            if (mags[b] > top) top = mags[b]
            lo = hi
        }
        peak = max(max(peak * 0.97f, top), 6f)
        for (b in 0 until 4) target[b] = min(1f, mags[b] / peak * WEIGHT[b])
        if (top > 3f) lastRealMs = System.currentTimeMillis()
    }

    fun tick(dt: Float, nowMs: Long) {
        if (startMs == 0L) startMs = nowMs
        phase += dt
        val real = viz != null && System.currentTimeMillis() - lastRealMs < 1200L && nowMs - startMs > 0L
        for (b in 0 until 4) {
            val t: Float
            if (real) {
                t = target[b]
            } else {
                seed = seed * 1103515245 + 12345
                val noise = ((seed ushr 16) and 0x7FFF) / 32767f
                t = (0.5f + 0.38f * sin(phase * SPEED[b] + b * 1.7f) + 0.12f * noise).coerceIn(0.08f, 1f)
            }
            val k = if (t > levels[b]) 0.65f else 0.22f
            levels[b] += (t - levels[b]) * min(1f, k * dt * 30f)
        }
    }

    private companion object {
        val EDGES = intArrayOf(3, 7, 17, 48)
        val WEIGHT = floatArrayOf(0.9f, 1.1f, 1.5f, 2.2f)
        val SPEED = floatArrayOf(6.3f, 8.1f, 5.2f, 9.4f)
    }
}
