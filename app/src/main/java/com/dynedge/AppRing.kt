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

    /**
     * User ring radii: the outermost ring goes to [outPct] of its radius, the innermost to [inPct], rings between
     * interpolate (a single ring uses [outPct]). Angles stay as computed, so each ring keeps at least the radius
     * that holds its icons apart and clear of the screen edges, never goes past the original outer ring (rim /
     * ribbon clearance), stays one icon plus gap off its neighbour and clear of the corner. 100 / 100 is a no-op.
     */
    fun scale(ring: Out, inPct: Float, outPct: Float, gap: Float, d: Float) {
        if (inPct >= 100f && outPct >= 100f) return
        val n = ring.n
        if (n == 0) return
        val rs = ArrayList<Float>()
        for (k in 0 until n) if (rs.none { kotlin.math.abs(it - ring.r[k]) < 0.5f }) rs.add(ring.r[k])
        rs.sort()
        val m = rs.size
        val iconR = ring.itemR
        val sep = 2f * iconR + gap
        val lo = FloatArray(m)
        for (j in 0 until m) {
            var minA = 90f
            var maxA = 0f
            var cnt = 0
            for (k in 0 until n) if (kotlin.math.abs(ring.r[k] - rs[j]) < 0.5f) {
                minA = kotlin.math.min(minA, ring.ang[k]); maxA = kotlin.math.max(maxA, ring.ang[k]); cnt++
            }
            var need = 50f * d + iconR
            val edge = Math.toRadians(kotlin.math.min(minA, 90f - maxA).toDouble().coerceIn(1.0, 89.0))
            need = kotlin.math.max(need, ((iconR + MARGIN_DP * d) / Math.sin(edge)).toFloat())
            if (cnt > 1) {
                val step = Math.toRadians(((maxA - minA) / (cnt - 1)).toDouble().coerceAtLeast(1.0))
                need = kotlin.math.max(need, (sep / (2.0 * Math.sin(step / 2.0))).toFloat())
            }
            lo[j] = need
        }
        // floors from the inside out (never above the ring's own radius), then settle outside in
        val fl = FloatArray(m)
        for (j in 0 until m) {
            val prev = if (j == 0) 0f else fl[j - 1] + sep
            fl[j] = kotlin.math.min(rs[j], kotlin.math.max(lo[j], prev))
        }
        val nr = FloatArray(m)
        for (j in m - 1 downTo 0) {
            val t = if (m == 1) 1f else j / (m - 1f)
            val pct = inPct + (outPct - inPct) * t
            val ceil = if (j == m - 1) rs[j] else kotlin.math.min(rs[j], nr[j + 1] - sep)
            nr[j] = kotlin.math.min(kotlin.math.max(rs[j] * pct / 100f, fl[j]), kotlin.math.max(ceil, fl[j]))
        }
        for (k in 0 until n) for (j in 0 until m) if (kotlin.math.abs(ring.r[k] - rs[j]) < 0.5f) {
            ring.r[k] = nr[j]
            break
        }
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
                rings++
                rc -= step
            }
            if (rings == 0) {
                ring[0] = max(rMax, rMin)
                rings = 1
            }
            // one shared angular window for every ring: the innermost ring has the widest margin
            val win = 90f - 2f * margin(ring[rings - 1], itemR, d)
            for (i in 0 until rings) {
                cap[i] = capacity(win, ring[i], itemR, gap)
                total += cap[i]
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
        // assign: innermost ring first, each ring from low to high angle, centred on 45 in the shared window
        val span = 90f - 2f * margin(ring[k - 1], itemR, d)
        var idx = 0
        for (i in k - 1 downTo 0) {
            val c = use[i]
            if (c == 0) continue
            val rc = ring[i]
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
        Math.toDegrees(asin(((itemR + MARGIN_DP * d) / rc).toDouble().coerceAtMost(1.0))).toFloat().coerceAtMost(45f)

    /** Smallest angle (deg) between two neighbour centres on a ring so their icons keep [gap] apart. */
    private fun minStep(rc: Float, itemR: Float, gap: Float): Float =
        (2.0 * Math.toDegrees(asin(((2f * itemR + gap) / (2f * rc)).toDouble().coerceAtMost(1.0)))).toFloat()

    private fun capacity(win: Float, rc: Float, itemR: Float, gap: Float): Int {
        if (win <= 0f) return 1
        return (floor(win / minStep(rc, itemR, gap)).toInt() + 1).coerceIn(1, 12)
    }

    /** Extra clearance (dp) between an icon's edge and the screen edge. */
    private const val MARGIN_DP = 6f
}
