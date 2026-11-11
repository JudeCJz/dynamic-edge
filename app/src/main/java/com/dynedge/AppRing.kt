package com.dynedge

import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Ring layout of the APPS window, shared by [WinApps] (the overlay) and the Apps editor preview so both agree.
 *
 * Polar frame with the origin at the screen corner, angles in degrees from the bottom edge (0) to the right
 * edge (90). The apps sit evenly spaced on concentric rings, every ring symmetric about the 45 degree diagonal.
 * Rings are filled from the outermost ring inward; an app goes to the ring with the most arc per app left, so
 * outer rings hold more apps than inner ones. App order: innermost ring first, each ring from 0 toward 90 deg.
 * Every icon is turned so its "up" points away from the corner along its radius: rotation = angle - 90.
 * If the rings cannot hold every app at the chosen icon size, the icons shrink (1 dp steps, down to 12 dp).
 */
internal object AppRing {

    /** Output: centre radius / angle / canvas rotation for each app, plus the icon radius used. */
    class Out(cap: Int) {
        @JvmField val r = FloatArray(cap)
        @JvmField val ang = FloatArray(cap)
        @JvmField val rot = FloatArray(cap)
        @JvmField var n = 0
        @JvmField var itemR = 0f
    }

    /** Radius of the recent-apps ribbon for an open radius [rad] (px) and density [d]. */
    fun ribbonR(rad: Float, d: Float): Float = min(0.92f * rad, rad - 20f * d)
    const val RIB_ICON_DP = 14f

    /**
     * @param n apps to place; @param rad open radius px; @param itemR0 wanted icon radius px; @param gap gap px
     * between icons and between rings; @param ribbon the recent-apps ribbon is on; @param d density.
     */
    fun compute(out: Out, n: Int, rad: Float, itemR0: Float, gap: Float, ribbon: Boolean, d: Float) {
        val cnt = min(n, out.r.size)
        out.n = cnt
        out.itemR = itemR0
        if (cnt <= 0) return
        val ring = FloatArray(8)
        val cap = IntArray(8)
        val use = IntArray(8)
        var itemR = itemR0
        var rings = 0
        while (true) {
            val rMax = if (ribbon) ribbonR(rad, d) - RIB_ICON_DP * d - gap - itemR
            else rad - 8f * d - itemR
            val rMin = max(0.34f * rad, 50f * d + itemR)
            val step = 2f * itemR + gap
            rings = 0
            var total = 0
            var rc = rMax
            while (rc >= rMin - 0.5f && rings < ring.size) {
                ring[rings] = rc
                cap[rings] = capacity(rc, itemR, gap, d)
                total += cap[rings]
                rings++
                rc -= step
            }
            if (rings == 0) {
                ring[0] = max(rMax, rMin)
                cap[0] = capacity(ring[0], itemR, gap, d)
                total = cap[0]
                rings = 1
            }
            if (total >= cnt || itemR <= 12f * d) break
            itemR -= d
        }
        out.itemR = itemR
        // fewest rings from the outermost inward that hold every app
        var k = 0
        var sum = 0
        while (k < rings && sum < cnt) {
            sum += cap[k]
            k++
        }
        // greedy: the next app goes to the ring with the most arc per app after adding it
        for (i in 0 until k) use[i] = 0
        for (j in 0 until cnt) {
            var best = -1
            var bestV = -1f
            for (i in 0 until k) {
                if (use[i] >= cap[i]) continue
                val v = ring[i] / (use[i] + 1)
                if (v > bestV) {
                    bestV = v
                    best = i
                }
            }
            if (best < 0) break
            use[best]++
        }
        // assign: innermost ring first, each ring from low to high angle, symmetric about 45
        var idx = 0
        for (i in k - 1 downTo 0) {
            val c = use[i]
            if (c == 0) continue
            val rc = ring[i]
            val m = margin(rc, itemR, d)
            val span = 90f - 2f * m
            val minStep = minStep(rc, itemR, gap)
            val step = if (c <= 1) 0f else min(span / (c - 1), max(90f / (c + 1), minStep))
            for (j in 0 until c) {
                if (idx >= cnt) break
                val a = 45f + (j - (c - 1) / 2f) * step
                out.r[idx] = rc
                out.ang[idx] = a
                out.rot[idx] = a - 90f
                idx++
            }
        }
        out.n = idx
    }

    /** Angle (deg) an icon centre must keep from each screen edge to stay inside the quarter. */
    private fun margin(rc: Float, itemR: Float, d: Float): Float =
        Math.toDegrees(asin(((itemR + 4f * d) / rc).toDouble().coerceAtMost(1.0))).toFloat().coerceAtMost(45f)

    /** Smallest angle (deg) between two neighbour centres on a ring so their icons keep [gap] apart. */
    private fun minStep(rc: Float, itemR: Float, gap: Float): Float =
        (2.0 * Math.toDegrees(asin(((2f * itemR + gap) / (2f * rc)).toDouble().coerceAtMost(1.0)))).toFloat()

    private fun capacity(rc: Float, itemR: Float, gap: Float, d: Float): Int {
        val span = 90f - 2f * margin(rc, itemR, d)
        if (span <= 0f) return 1
        return (floor(span / minStep(rc, itemR, gap)).toInt() + 1).coerceIn(1, 12)
    }
}
