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
 * Honeycomb packing: the outermost ring is the first row; each ring inward sits in the gaps of the ring outside it. App order: innermost ring first, each ring from 0 toward 90 deg.
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
     *
     * Honeycomb: ring 0 (outermost, at the rim / ribbon clearance) is the first row, its icons evenly spread between
     * equal insets from both edges. Each further ring sits in the gaps of the ring outside it: icons on the bisector
     * angles (one fewer icon), at the radius where every icon is 2 * itemR + gap from both neighbours outside:
     * r = R cos(s/2) - sqrt(D^2 - (R sin(s/2))^2), R = outer ring radius, s = its angular step, D = 2 * itemR + gap.
     * Rings shorter than the one outside would make neighbours touch; then every second (third) gap is used.
     */
    fun compute(out: Out, n: Int, rad: Float, itemR0: Float, gap: Float, ribbon: Boolean, d: Float) {
        val cnt = min(n, out.r.size)
        out.n = cnt
        out.itemR = itemR0
        if (cnt <= 0) return
        val rr = FloatArray(8)    // ring radius, outermost first
        val rn = IntArray(8)      // lattice capacity of the ring
        val rs = FloatArray(8)    // angular step of the ring in degrees
        var itemR = itemR0
        var rings = 0
        while (true) {
            val rMax = if (ribbon) ribbonR(rad, d) - RIB_ICON_DP * d - gap - itemR
            else rad - 8f * d - itemR
            val rMin = max(0.34f * rad, 50f * d + itemR)
            val dd = 2f * itemR + gap
            rings = 1
            rr[0] = max(rMax, rMin)
            val win0 = 90f - 2f * margin(rr[0], itemR, d)
            rn[0] = capacity(win0, rr[0], itemR, gap)
            rs[0] = if (rn[0] > 1) win0 / (rn[0] - 1) else 0f
            var total = rn[0]
            while (rings < rr.size) {
                val p = rings - 1
                val m = rn[p] - 1
                if (m < 1) break
                val half = Math.toRadians(rs[p] / 2.0)
                val sh = rr[p] * Math.sin(half).toFloat()
                val ch = rr[p] * Math.cos(half).toFloat()
                val rHex = ch - Math.sqrt(max(0f, dd * dd - sh * sh).toDouble()).toFloat()
                var done = false
                for (s in 1..3) {
                    val r = if (s > 1) min(rHex, rr[p] - dd) else rHex
                    if (r < rMin) break
                    var c = (m - 1) / s + 1
                    val step = s * rs[p]
                    if (c > 1 && 2f * r * Math.sin(Math.toRadians(step / 2.0)).toFloat() < dd - 0.5f) continue
                    // keep the first / last icon clear of the screen edges (drop two at a time: stays symmetric)
                    while (c > 0) {
                        val aFirst = 45.0 - (c - 1) / 2.0 * step
                        if (r * Math.sin(Math.toRadians(aFirst.coerceAtLeast(1.0))).toFloat() >= itemR + MARGIN_DP * d) break
                        c -= 2
                    }
                    if (c <= 0) continue
                    rr[rings] = r
                    rn[rings] = c
                    rs[rings] = step
                    total += c
                    rings++
                    done = true
                    break
                }
                if (!done) break
            }
            if (total >= cnt || itemR <= 12f * d) break
            itemR -= d
        }
        out.itemR = itemR
        val dd = 2f * itemR + gap
        val rMin = max(0.34f * rad, 50f * d + itemR)
        // fewest rings from the outermost inward that hold every app
        var k = 0
        var sum = 0
        while (k < rings && sum < cnt) {
            sum += rn[k]
            k++
        }
        var idx = 0
        if (k <= 1) {
            // all in the first row: evenly spread between equal insets (single app on the diagonal)
            val c = min(cnt, rn[0].coerceAtLeast(1))
            val win0 = 90f - 2f * margin(rr[0], itemR, d)
            val step = if (c > 1) win0 / (c - 1) else 0f
            for (j in 0 until c) {
                val a = 45f + (j - (c - 1) / 2f) * step
                out.r[idx] = rr[0]
                out.ang[idx] = a
                out.rot[idx] = a - 90f
                idx++
            }
            out.n = idx
            return
        }
        // innermost ring first, each ring from low to high angle; only the last (innermost) used ring may be partial
        var left = cnt
        val used = IntArray(k)
        for (i in 0 until k - 1) {
            used[i] = rn[i]
            left -= rn[i]
        }
        used[k - 1] = left.coerceIn(0, rn[k - 1])
        for (i in k - 1 downTo 0) {
            val c = used[i]
            if (c == 0) continue
            var r = rr[i]
            var step = rs[i]
            if (c != rn[i] && (rn[i] - c) % 2 != 0) {
                // off the lattice (half a step): sit straight inside the ring outside, clear of its icons
                r = max(min(r, rr[i - 1] - dd), rMin)
                val ms = minStep(r, itemR, gap)
                step = max(step, ms)
                if (c > 1) step = min(step, (90f - 2f * margin(r, itemR, d)) / (c - 1))
            }
            for (j in 0 until c) {
                if (idx >= cnt) break
                val a = 45f + (j - (c - 1) / 2f) * step
                out.r[idx] = r
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
