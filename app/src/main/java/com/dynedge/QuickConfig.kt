package com.dynedge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry of the QUICK window's two tile rings (0 inner, 1 outer), shared by the overlay (WinQuick), the editor
 * preview (QuickEditorActivity), so both agree on slot positions.
 *
 * Each ring has a fixed number of evenly spaced slots between 12 and 78 degrees ([Win.slotAngle] with n = capacity).
 * The capacity is the largest slot count whose neighbouring centres are at least 44 dp apart (min touch target),
 * capped at [QuickConfig.RING_MAX]. The outer ring sits just inside the innermost slider arc; the inner ring keeps
 * clear of the close X and page dots.
 */
internal class QuickGeom {
    var br = 0f       // chip radius before the per-ring clamp
    var ri = 0f       // inner ring radius
    var ro = 0f       // outer ring radius
    val cap = IntArray(2)
    val chip = FloatArray(2)   // chip radius per ring
    val arcR = FloatArray(2)   // slider arc radii (index 1 only with two arcs)

    fun ringR(r: Int): Float = if (r == 0) ri else ro

    /** [rad] open radius px, [d] density, [idlePx] idle bean size px, [brad] max chip radius px. */
    fun compute(rad: Float, d: Float, idlePx: Float, nArcs: Int, brad: Float, inPct: Float = 100f, outPct: Float = 100f) {
        arcR[0] = min(0.88f * rad, rad - 14f * d)
        arcR[1] = if (nArcs >= 2) arcR[0] - (2f * 13f * d + 4f * d) else arcR[0]
        br = min(brad, 20f * d)
        val top = if (nArcs <= 0) min(0.94f * rad, rad - 8f * d)
        else arcR[if (nArcs >= 2) 1 else 0] - (ArcSet.STROKE_DP / 2f + 11f) * d // clear gap from the slider (user: tiles touched it)
        ro = top - br
        // close X (idle bean centroid) + page dots + half a touch target
        val x = min(0.4244f * idlePx, 0.16f * rad) * 1.4142f
        ri = max(0.40f * rad, x + 56f * d)
        if (ro - ri < 44f * d) ri = ro - 44f * d
        ri = ri.coerceAtLeast(br + 8f * d)
        // user ring radius (percent; 100 = the layout above). Outer never goes past its slider clearance,
        // inner keeps the close X clear and stays one tile (44 dp) inside the outer ring.
        val roMax = ro
        val riDef = ri
        ro = if (outPct >= 100f) roMax else max(roMax * outPct / 100f, min(roMax, br + 52f * d))
        val riFloor = max(br + 8f * d, min(riDef, x + 40f * d))
        ri = (riDef * inPct / 100f).coerceAtLeast(riFloor)
        if (ro - ri < 44f * d) ri = (ro - 44f * d).coerceAtLeast(br + 8f * d)
        // each ring gets its own angular window: margin = asin((chip + 6 dp + press ring) / r) so the full chip
        // never touches the bottom or side edge; both windows are centred on 45 degrees, so the rings line up on
        // the diagonal and the outer ring keeps room for more tiles than the inner one
        for (r in 0..1) {
            val rr = ringR(r).toDouble().coerceAtLeast(1.0)
            val m = Math.toDegrees(kotlin.math.asin(((br + 9f * d) / rr).coerceAtMost(1.0))).coerceAtMost(40.0)
            margin[r] = m.toFloat()
            span[r] = 90.0 - 2.0 * m
            cap[r] = capFor(rr.toFloat(), span[r], d)
            chip[r] = chipFor(rr.toFloat(), cap[r], span[r], d)
        }
    }

    val margin = floatArrayOf(12f, 12f)   // degrees from each screen edge to the first/last slot, per ring
    val span = doubleArrayOf(66.0, 66.0)  // 90 - 2 * margin, per ring

    /** Angle in degrees of slot [k] of [n] on ring [r]: evenly inside that ring's window (45 when n is 1). */
    fun angle(r: Int, k: Int, n: Int): Float = if (n <= 1) 45f else margin[r] + (k * span[r] / (n - 1)).toFloat()

    private fun capFor(r: Float, span: Double, d: Float): Int {
        var n = QuickConfig.RING_MAX
        while (n > 1 && 2f * r * sin(Math.toRadians(span / (n - 1) / 2.0)).toFloat() < 44f * d) n--
        return n
    }

    private fun chipFor(r: Float, n: Int, span: Double, d: Float): Float {
        if (n <= 1) return br
        val half = r * sin(Math.toRadians(span / (n - 1) / 2.0)).toFloat()
        return min(br, half - 1.5f * d).coerceAtLeast(10f * d)
    }

    companion object {
        const val SPAN = 66.0

        /** The overlay's max chip radius for an open radius (EdgeView.layoutAll: hw / brad). */
        fun bradFor(rad: Float, d: Float): Float {
            val hw = min(24.5f * d, 0.12f * rad - 1.5f * d).coerceAtLeast(12f * d)
            return min(20f * d, hw - 2f * d)
        }

        /** Geometry for the current prefs (open / idle size) at density [d]. */
        fun forPrefs(ctx: Context, nArcs: Int, d: Float = ctx.resources.displayMetrics.density): QuickGeom {
            val rad = Prefs.openDp(ctx) * d
            val g = QuickGeom()
            // idle as EdgeView.idleSizePx (ceil) so the editor and the overlay always get the same capacities
            g.compute(rad, d, kotlin.math.ceil(Prefs.idleDp(ctx) * d), nArcs, bradFor(rad, d),
                Prefs.ringPct(ctx, Prefs.KEY_QUICK_RING_IN, Prefs.RING_IN_MAX), Prefs.ringPct(ctx, Prefs.KEY_QUICK_RING_OUT))
            return g
        }
    }
}

/**
 * User-editable layout of the QUICK section. Stored as JSON in Prefs under [KEY]:
 * `{"v":2,"rings":[[id or "", ...],[...]],"arcs":[...]}`. Rings are fixed slot arrays (empty slot = ""), the slot
 * count of each ring follows [QuickGeom] (it depends on the open size), so a stored ring is normalised to the current
 * capacity on load: extra empty slots go first, then overflowing tiles move to the other ring's empty slots, and what
 * still does not fit is simply not placed (it shows in the editor's tray again).
 *
 * Migration from v1 (`inner`, `middle`, `custom`): the three lists are flattened in that order and poured into the
 * inner ring, then the outer ring. The v1 JSON stays until the first save, so nothing is lost before that.
 */
object QuickConfig {
    const val KEY = "quick_layout"
    const val ARCS_MAX = 2
    const val RING_MAX = 8

    /** [rings] = 2 slot arrays (null = empty slot); [arcs] = slider ids, outermost first. */
    data class Layout(val rings: List<List<String?>>, val arcs: List<String>) {
        fun placed(id: String): Boolean = rings.any { id in it } || id in arcs
        fun tiles(): List<String> = rings.flatMap { it.filterNotNull() }
    }

    private val DEFAULT_RAW = Layout(
        listOf(listOf("rotate", "assist", "vibrate"), listOf("wifi", "bluetooth", "torch", "dnd")),
        listOf("brightness")
    )

    /** The default layout at the current capacity. */
    fun default(ctx: Context): Layout = normalize(DEFAULT_RAW, caps(ctx, DEFAULT_RAW.arcs.size))

    fun caps(ctx: Context, nArcs: Int): IntArray = QuickGeom.forPrefs(ctx, nArcs).cap.copyOf()

    /** The stored layout, not yet normalised (ring sizes as saved). */
    fun loadRaw(ctx: Context): Layout {
        val s = try { Prefs.prefs(ctx).getString(KEY, null) } catch (e: Exception) { null } ?: return DEFAULT_RAW
        return try {
            val o = JSONObject(s)
            val arcs = ArrayList<String>()
            o.optJSONArray("arcs")?.let { a ->
                for (i in 0 until a.length()) {
                    val id = a.optString(i, "")
                    if (id in Arcs.ALL && id !in arcs && arcs.size < ARCS_MAX) arcs.add(id)
                }
            }
            val seen = HashSet<String>()
            if (o.optInt("v", 1) >= 2) {
                val ra = o.optJSONArray("rings")
                val rings = ArrayList<List<String?>>(2)
                for (r in 0..1) {
                    val a = ra?.optJSONArray(r)
                    val out = ArrayList<String?>()
                    if (a != null) for (i in 0 until a.length()) {
                        val id = a.optString(i, "")
                        out.add(if (valid(id) && seen.add(id)) id else null)
                    }
                    rings.add(out)
                }
                Layout(rings, arcs)
            } else {
                // v1: inner + middle + custom, flattened in order
                val flat = ArrayList<String>()
                for (k in arrayOf("inner", "middle", "custom")) {
                    val a = o.optJSONArray(k) ?: continue
                    for (i in 0 until a.length()) {
                        val id = a.optString(i, "")
                        if (valid(id) && seen.add(id)) flat.add(id)
                    }
                }
                val c0 = caps(ctx, arcs.size)[0]
                Layout(listOf(flat.take(c0), flat.drop(c0)), arcs)
            }
        } catch (e: Exception) {
            DEFAULT_RAW
        }
    }

    /** The stored layout normalised to the current capacity. */
    fun load(ctx: Context): Layout {
        val raw = loadRaw(ctx)
        return normalize(raw, caps(ctx, raw.arcs.size))
    }

    fun save(ctx: Context, l: Layout) {
        val o = JSONObject()
        o.put("v", 2)
        val ra = JSONArray()
        for (r in 0..1) {
            val a = JSONArray()
            for (id in l.rings.getOrElse(r) { emptyList() }.take(RING_MAX)) a.put(id ?: "")
            ra.put(a)
        }
        o.put("rings", ra)
        o.put("arcs", JSONArray(l.arcs.take(ARCS_MAX)))
        Prefs.prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    fun reset(ctx: Context) {
        Prefs.prefs(ctx).edit().remove(KEY).apply()
    }

    private fun valid(id: String): Boolean =
        id.isNotEmpty() && (id in Tiles.ALL || id.startsWith(QsCatalog.SYS) || id.startsWith(QsCatalog.CUS) ||
            ShortcutStore.isId(id))

    /**
     * Fits [l] to ring capacities [cap]: each ring becomes exactly cap[r] slots. A ring that is too long drops its
     * empty slots (from the end first), then its last tiles overflow into the other ring's empty slots; anything left
     * is not placed.
     */
    fun normalize(l: Layout, cap: IntArray): Layout {
        val rings = Array(2) { r -> ArrayList(l.rings.getOrElse(r) { emptyList() }) }
        val over = ArrayList<String>()
        for (r in 0..1) {
            val x = rings[r]
            while (x.size > cap[r]) {
                val k = x.lastIndexOf(null)
                if (k >= 0) x.removeAt(k) else x.removeAt(x.size - 1)?.let { over.add(it) }
            }
            while (x.size < cap[r]) x.add(null)
        }
        for (id in over) {
            for (r in 0..1) {
                val k = rings[r].indexOf(null)
                if (k >= 0) {
                    rings[r][k] = id
                    break
                }
            }
        }
        return Layout(rings.map { it.toList() }, l.arcs.take(ARCS_MAX))
    }
}
