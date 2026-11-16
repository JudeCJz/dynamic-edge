package com.dynedge

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Four-band level meter for the idle bean, the open rim and every other visualizer consumer. Uses the global
 * output mix (Visualizer session 0, needs RECORD_AUDIO) and falls back to a synthetic animation when that is
 * unavailable or silent.
 *
 * Pipeline, all driven from one tick() per vsync so every consumer reads the same numbers at the same time:
 *  1. poll getFft() (no listener: the listener is capped at ~20 Hz, polling is not) and reduce to four raw bands;
 *  2. detect the bass onset on the RAW signal, so the kick fires on the real onset frame;
 *  3. push (raw bands, kick) into a short ring and read it back [delayMs] later, which lines the picture up
 *     with what is heard (the Visualizer sees audio before the output path plays it);
 *  4. the one and only smoothing stage: near-instant attack, ~70 ms release.
 */
class IdleVisualizer(private val ctx: Context) {

    /** Display levels 0..1 (delayed + smoothed once). Read by everything that draws. */
    val levels = FloatArray(4)

    /** Bass onset strength 0..1 for the frame just ticked (0 when there was none). Not smoothed. */
    var kick = 0f
        private set

    private val mags = FloatArray(4)
    private val raw = FloatArray(4)
    private val out = FloatArray(4)
    private var fft = ByteArray(0)
    private var viz: Visualizer? = null
    private var peak = 6f
    private var lastRealNs = 0L
    private var startMs = 0L
    private var phase = 0f
    private var seed = 12345
    private var bassAvg = 0f
    private var kickCd = 0f
    private var binHz = 0f

    // delay line: ring of recent frames (timestamp ms, 4 levels, kick)
    private val ringT = LongArray(RING)
    private val ringL = FloatArray(RING * 4)
    private val ringK = FloatArray(RING)
    private var head = 0
    private var count = 0
    private var readIdx = -1

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var delayMs = DELAY_WIRED
    private var delayCheckMs = 0L

    fun start() {
        if (viz != null) return
        startMs = 0L
        phase = 0f
        count = 0
        head = 0
        readIdx = -1
        delayCheckMs = 0L
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        var v: Visualizer? = null
        try {
            v = Visualizer(0)
            v.setEnabled(false)
            // largest FFT (usually 1024): bass bins ~47 Hz wide; the ~21 ms window is worth the sharper bass
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1])
            v.setEnabled(true)
            fft = ByteArray(v.captureSize)
            binHz = v.samplingRate / 1000f / v.captureSize
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
            v.release()
        } catch (e: Throwable) {
        }
        kick = 0f
    }

    /**
     * How long the Visualizer is ahead of what is heard: the phone's own output latency for the music stream
     * (hidden AudioManager.getOutputLatency, includes the mixer, HAL and on most phones the Bluetooth link),
     * plus about one display frame, minus half the FFT window (it is centred on the captured audio).
     * Falls back to 60 ms / 180 ms (Bluetooth) when the phone does not report it.
     */
    private fun refreshDelay() {
        val measured = try {
            val m = AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
            (m.invoke(audio, AudioManager.STREAM_MUSIC) as Int).toLong()
        } catch (e: Throwable) {
            -1L
        }
        var bt = false
        try {
            for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val t = d.type
                // A2DP, hearing aid (23), BLE headset (26) and BLE speaker (27): the BLE ids are API 31+, hence literals
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 23 || t == 26 || t == 27) bt = true
            }
        } catch (e: Throwable) {
        }
        val guess = if (bt) DELAY_BT else DELAY_WIRED
        delayMs = if (measured in 10L..600L) {
            val d = measured + FRAME_MS - fftHalfMs()
            // a Bluetooth link the phone does not count in its latency: never below the plain guess
            (if (bt) max(d, guess) else d).coerceIn(0L, 600L)
        } else guess
    }

    private fun fftHalfMs(): Long {
        val v = viz ?: return 0L
        return try { (v.captureSize * 500L) / (v.samplingRate / 1000L).coerceAtLeast(1L) } catch (e: Throwable) { 0L }
    }

    /** Bins: [re0 (DC), re(n/2), re1, im1, re2, im2, ...]. Four bands by frequency, low to high. */
    private fun reduce(dt: Float) {
        val n = fft.size / 2
        if (n < 8 || binHz <= 0f) return
        var top = 0f
        var lo = 1
        for (b in 0 until 4) {
            val hi = max(lo + 1, min(n, (EDGE_HZ[b] / binHz).toInt()))
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
        peak = max(max(peak * exp(-dt / 1.6f), top), 6f)
        for (b in 0 until 4) raw[b] = min(1f, mags[b] / peak * WEIGHT[b])
        if (top > 3f) lastRealNs = System.nanoTime()
    }

    private val demo = DemoBeat()

    fun tick(dt: Float, nowMs: Long) {
        if (LiveTest.demoMusic) {
            // live test: synthetic beat instead of real audio
            demo.step(dt)
            for (b in 0 until 4) levels[b] = demo.levels[b]
            kick = demo.kick
            return
        }
        if (startMs == 0L) startMs = nowMs
        phase += dt
        if (nowMs - delayCheckMs > 2000L) {
            delayCheckMs = nowMs
            refreshDelay()
        }
        val v = viz
        var fresh = false
        if (v != null) {
            try {
                if (v.getFft(fft) == Visualizer.SUCCESS) {
                    reduce(dt)
                    fresh = true
                }
            } catch (e: Throwable) {
            }
        }
        val real = fresh && System.nanoTime() - lastRealNs < 1_200_000_000L
        var k = 0f
        if (real) {
            // onset on the raw bass: clearly above its own slow average
            bassAvg += (raw[0] - bassAvg) * min(1f, dt / 0.45f)
            kickCd -= dt
            val flux = raw[0] - bassAvg
            if (flux > 0.06f && raw[0] > 0.12f && kickCd <= 0f) {
                k = (flux * 5f).coerceIn(0.4f, 1f)
                kickCd = 0.14f
            }
        } else {
            for (b in 0 until 4) {
                seed = seed * 1103515245 + 12345
                val noise = ((seed ushr 16) and 0x7FFF) / 32767f
                raw[b] = (0.5f + 0.38f * sin(phase * SPEED[b] + b * 1.7f) + 0.12f * noise).coerceIn(0.08f, 1f)
            }
        }
        push(nowMs, k)
        readDelayed(nowMs - (if (real) delayMs else 0L))
        val rel = 1f - exp(-dt / 0.07f)
        val atk = 1f - exp(-dt / 0.008f)
        for (b in 0 until 4) {
            val t = out[b]
            levels[b] += (t - levels[b]) * (if (t > levels[b]) atk else rel)
        }
    }

    private fun push(ms: Long, k: Float) {
        ringT[head] = ms
        for (b in 0 until 4) ringL[head * 4 + b] = raw[b]
        ringK[head] = k
        head = (head + 1) % RING
        if (count < RING) count++
    }

    /** Sets [out] to the newest frame at or before [tMs] and [kick] to the strongest onset passed since the last read. */
    private fun readDelayed(tMs: Long) {
        var best = -1
        for (i in 0 until count) {
            val idx = (head - 1 - i + RING) % RING
            if (ringT[idx] <= tMs) {
                best = idx
                break
            }
        }
        if (best < 0) {
            // still filling the delay line: hold at silence
            for (b in 0 until 4) out[b] = 0f
            kick = 0f
            return
        }
        var kk = 0f
        var i = if (readIdx < 0) best else (readIdx + 1) % RING
        var guard = 0
        while (guard++ < RING) {
            kk = max(kk, ringK[i])
            if (i == best) break
            i = (i + 1) % RING
        }
        readIdx = best
        kick = kk
        for (b in 0 until 4) out[b] = ringL[best * 4 + b]
    }

    private companion object {
        const val RING = 64              // ~1 s at 60 Hz, plenty for 180 ms
        const val DELAY_WIRED = 60L
        const val DELAY_BT = 180L
        const val FRAME_MS = 16L
        val EDGE_HZ = floatArrayOf(250f, 1000f, 4000f, 16000f)
        val WEIGHT = floatArrayOf(0.9f, 1.6f, 2.6f, 4.0f)
        val SPEED = floatArrayOf(6.3f, 8.1f, 5.2f, 9.4f)
    }
}
