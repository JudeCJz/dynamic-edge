package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Clicks a tile of the real quick settings panel through the accessibility service.
 *
 * Flow: GLOBAL_ACTION_QUICK_SETTINGS -> poll the SystemUI windows for the tile -> click -> wait ~350 ms -> if the
 * click opened a dialog or another app (a new window appeared) leave it to the user, else collapse the shade with
 * GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (never BACK). One job at a time; a job that cannot find its tile within
 * [FIND_MS] collapses the shade and reports [FAILED] (the caller opens the tile's settings screen instead). A hard
 * deadline guarantees the shade is never left open by a stuck job.
 *
 * Matching, in order:
 * 1. Android 16+ panels (Nothing OS 4, AOSP 16/17): every tile container has the content description
 *    "<spec>, <W>x<H>, row R column C". The spec is matched exactly (custom(...) by component). When the panel uses
 *    this format nothing else is tried, so a wrong tile can never be clicked. Tiles on later pages are reached by
 *    scrolling the pager (direction from the sysui_qs_tiles order), ACTION_SCROLL_* first, a swipe gesture after.
 *    In a container the click goes to the single clickable child, or for 2x1 tiles to the square icon clickable
 *    (the body opens the details dialog).
 * 2. Older panels (vivo): label / view-id scoring with the catalog keys, plus a positional fallback for Wi-Fi and
 *    mobile data, as before.
 *
 * Tile states seen in the panel (checkable / stateDescription) are cached per spec for tiles without a readable state.
 */
internal object QsDriver {
    const val UNKNOWN = 0
    const val OFF = 1
    const val ON = 2

    /** Results passed to the job callback. */
    const val DONE = 0
    const val LEFT_OPEN = 1
    const val FAILED = 2

    private const val TAG = "QsDriver"
    private const val OPEN_WAIT_MS = 150L
    private const val FIND_MS = 3600L
    private const val HARD_MS = 5400L
    private const val POLL_MS = 70L
    private const val SCROLL_AFTER_MS = 380L
    private const val SCROLL_GAP_MS = 650L
    private const val MAX_SCROLLS = 5
    private const val SETTLE_MS = 350L
    private const val ACTION_SETTLE_MS = 1000L
    private const val NODE_CAP = 1500

    private val main = Handler(Looper.getMainLooper())
    private val states = HashMap<String, Int>()
    private val rect = Rect()
    private val rect2 = Rect()
    private var job: Job? = null
    private val SPEC_DESC = Regex("^(.+), (\\d+)x(\\d+), row (\\d+) column (\\d+)$")

    val busy: Boolean get() = job != null

    /** Last state seen in the panel for a SystemUI spec, or [UNKNOWN]. */
    fun stateOfSpec(spec: String?): Int = if (spec == null) UNKNOWN else states[spec] ?: UNKNOWN

    /** Last state seen for a tile id. */
    fun state(id: String): Int = stateOfSpec(QsCatalog.specOf(id))

    private class Job(
        val ctx: Context,
        val svc: AccessibilityService,
        val id: String,
        val entry: QsCatalog.Entry,
        val specs: Array<String>,
        val comp: ComponentName?,
        val posIndex: Int,
        val order: List<String>,
        val minPx: Int,
        val action: Boolean,
        val done: (Int) -> Unit
    ) {
        val t0 = SystemClock.uptimeMillis()
        var scrolls = 0
        var lastScroll = 0L
        var newFormat = false
        /** Smallest sysui_qs_tiles index among the visible tiles (new format), for the paging direction. */
        var firstVisible = Int.MAX_VALUE
        var reversed = false
        var firstAtScroll = -2
        var clicked = false
        var clickAt = 0L
        var preState = UNKNOWN
        val preWindows = HashSet<Int>()
        val seen = HashMap<String, String>()
        val step = Runnable { stepFind(this) }
        val settle = Runnable { stepSettle(this) }
        val hard = Runnable { if (job === this) { Log.w(TAG, "hard deadline $id"); finish(this, FAILED, true) } }
    }

    /**
     * Starts clicking tile [id]. False when it cannot even start (no service, a job is running, unknown tile);
     * the caller then falls back. [done] gets [DONE], [LEFT_OPEN] or [FAILED] on the main thread.
     */
    fun run(ctx: Context, svc: AccessibilityService?, id: String, done: (Int) -> Unit): Boolean {
        if (svc == null || job != null) return false
        val app = ctx.applicationContext ?: ctx
        val e = QsCatalog.entry(app, id) ?: return false
        val specs = if (e.specs.isNotEmpty()) e.specs else QsCatalog.specOf(id)?.let { arrayOf(it) } ?: emptyArray()
        if (specs.isEmpty() && e.keys.isEmpty()) return false
        val order = QsCatalog.allSpecs(app)
        var pos = -1
        if (id == "wifi" || id == QsCatalog.SYS + "cell") {
            for (s in specs) {
                val k = order.indexOf(s)
                if (k >= 0) { pos = k; break }
            }
        }
        val action = QsCatalog.isAction(id) || QsCatalog.isCustom(id)
        val j = Job(app, svc, id, e, specs, e.comp, pos, order,
            (40f * app.resources.displayMetrics.density).toInt(), action, done)
        job = j
        val ok = try {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        } catch (ex: Exception) {
            false
        }
        if (!ok) {
            job = null
            return false
        }
        main.postDelayed(j.hard, HARD_MS)
        main.postDelayed(j.step, OPEN_WAIT_MS)
        return true
    }

    // ---------------------------------------------------------------- find + click

    private fun stepFind(j: Job) {
        if (job !== j) return
        val now = SystemClock.uptimeMillis()
        val n = try { find(j) } catch (e: Exception) { Log.w(TAG, "find failed", e); null }
        if (n != null) {
            j.preState = readState(n)
            snapshotWindows(j)
            var ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { false }
            if (!ok) ok = tapGesture(j, n)
            if (!ok) {
                finish(j, FAILED, true)
                return
            }
            j.clicked = true
            j.clickAt = SystemClock.uptimeMillis()
            main.postDelayed(j.settle, SETTLE_MS)
            return
        }
        if (now - j.t0 >= FIND_MS) {
            Log.w(TAG, "not found ${j.id} specs=${j.specs.joinToString()} newFormat=${j.newFormat}")
            finish(j, FAILED, true)
            return
        }
        if (now - j.t0 >= SCROLL_AFTER_MS && j.scrolls < MAX_SCROLLS && now - j.lastScroll >= SCROLL_GAP_MS) {
            j.lastScroll = now
            j.scrolls++
            try { scrollPanel(j) } catch (e: Exception) { }
        }
        main.postDelayed(j.step, POLL_MS)
    }

    /** After the click: something new on screen -> leave the shade open; else read the state back and collapse. */
    private fun stepSettle(j: Job) {
        if (job !== j) return
        if (openedSomething(j)) {
            finish(j, LEFT_OPEN, false)
            return
        }
        // action tiles (dialogs, apps) can take a little longer to show their window
        if (j.action && SystemClock.uptimeMillis() - j.clickAt < ACTION_SETTLE_MS) {
            main.postDelayed(j.settle, 120L)
            return
        }
        val n = try { find(j) } catch (e: Exception) { null }
        var s = if (n != null) readState(n) else UNKNOWN
        if ((s == UNKNOWN || s == j.preState) && j.preState != UNKNOWN && !j.action) {
            s = if (j.preState == ON) OFF else ON // state not updated yet: best guess
        }
        if (s != UNKNOWN) for (sp in j.specs) states[sp] = s
        finish(j, DONE, true)
    }

    private fun finish(j: Job, result: Int, collapse: Boolean) {
        if (job !== j) return
        job = null
        main.removeCallbacks(j.step)
        main.removeCallbacks(j.settle)
        main.removeCallbacks(j.hard)
        if (collapse) collapseShade(j.svc)
        if (j.seen.isNotEmpty()) {
            try { QsCatalog.remember(j.ctx, j.seen) } catch (e: Exception) { }
        }
        if (result == FAILED) tick(j.ctx)
        // the editor marks tiles the driver could not find (cleared by the next success)
        if (result == FAILED && !j.clicked) TileSupport.recordFailure(j.ctx, j.id)
        else if (result != FAILED) TileSupport.recordSuccess(j.ctx, j.id)
        try {
            j.done(result)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        }
    }

    private fun collapseShade(svc: AccessibilityService) {
        try {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } catch (e: Exception) {
        }
    }

    private fun tick(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- windows

    private fun snapshotWindows(j: Job) {
        j.preWindows.clear()
        try {
            for (w in j.svc.windows) j.preWindows.add(w.id)
        } catch (e: Exception) {
        }
    }

    /** A window that was not there before the click: an app / activity, or a SystemUI dialog. */
    private fun openedSomething(j: Job): Boolean {
        return try {
            for (w in j.svc.windows) {
                if (w.id in j.preWindows) continue
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
                    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> continue
                }
                val pkg = w.root?.packageName?.toString()
                if (pkg == j.ctx.packageName) continue
                Log.i(TAG, "click opened window type=${w.type} pkg=$pkg")
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun isUi(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString()?.contains("systemui", true) == true

    private fun uiRoots(j: Job): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(3)
        for (w in j.svc.windows) {
            val root = w.root ?: continue
            if (isUi(root)) out.add(root)
        }
        if (out.isEmpty()) {
            val r = j.svc.rootInActiveWindow
            if (r != null && isUi(r)) out.add(r)
        }
        return out
    }

    // ---------------------------------------------------------------- node search

    /** The node to click for the job's tile, or null. Also records every spec / label / state seen. */
    private fun find(j: Job): AccessibilityNodeInfo? {
        val roots = uiRoots(j)
        // 1. spec content descriptions (Android 16+)
        var container: AccessibilityNodeInfo? = null
        var minIdx = Int.MAX_VALUE
        var anyFormat = false
        for (root in roots) {
            val st = ArrayList<AccessibilityNodeInfo>()
            st.add(root)
            var visited = 0
            while (st.isNotEmpty() && ++visited < NODE_CAP) {
                val nd = st.removeAt(st.size - 1)
                val d = nd.contentDescription
                if (d != null && d.length > 8) {
                    val m = SPEC_DESC.matchEntire(d)
                    if (m != null) {
                        anyFormat = true
                        val spec = m.groupValues[1]
                        if (nd.isVisibleToUser) {
                            val k = j.order.indexOf(spec)
                            if (k in 0 until minIdx) minIdx = k
                        }
                        record(j, spec, nd)
                        if (container == null && nd.isVisibleToUser && specMatches(j, spec)) container = nd
                        continue // a tile container: its children are not tiles
                    }
                }
                for (i in nd.childCount - 1 downTo 0) nd.getChild(i)?.let { st.add(it) }
            }
        }
        if (anyFormat) {
            j.newFormat = true
            j.firstVisible = minIdx
            val c = container ?: return null
            return clickTarget(c)
        }
        if (j.newFormat) return null // the panel is (was) in the new format: never fall back to label guessing
        // 2. older panels: label / id scoring
        return legacyFind(j, roots)
    }

    private fun specMatches(j: Job, spec: String): Boolean {
        for (s in j.specs) if (s == spec) return true
        val cn = j.comp ?: return false
        if (!spec.startsWith("custom(") || !spec.endsWith(")")) return false
        val other = ComponentName.unflattenFromString(spec.substring(7, spec.length - 1)) ?: return false
        return other == cn
    }

    /** Caches the label and state of a spec container seen in the panel. */
    private fun record(j: Job, spec: String, c: AccessibilityNodeInfo) {
        val s = subtreeState(c, 0)
        if (s != UNKNOWN) states[spec] = s
        if (!j.seen.containsKey(spec)) {
            val l = firstLabel(c, 0)
            if (l != null) j.seen[spec] = l
        }
    }

    private fun firstLabel(n: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 0) {
            val d = n.contentDescription?.toString()
            if (!d.isNullOrBlank()) return d
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) return t
        }
        if (depth > 4) return null
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            firstLabel(ch, depth + 1)?.let { return it }
        }
        return null
    }

    private fun subtreeState(n: AccessibilityNodeInfo, depth: Int): Int {
        if (n.isCheckable) return if (n.isChecked) ON else OFF
        val sd = n.stateDescription?.toString()?.lowercase()
        if (sd != null) {
            if (sd.startsWith("off") || sd.startsWith("inactive") || sd.startsWith("disabled")) return OFF
            if (sd.startsWith("on") || sd.startsWith("active") || sd.startsWith("enabled")) return ON
        }
        if (depth >= 3) return UNKNOWN
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            val s = subtreeState(ch, depth + 1)
            if (s != UNKNOWN) return s
        }
        return UNKNOWN
    }

    /**
     * In a tile container: the only clickable descendant, or among several the square one (the icon of a 2x1
     * tile, which toggles; the wide body opens the details dialog). Falls back to the smallest clickable.
     */
    private fun clickTarget(c: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val list = ArrayList<AccessibilityNodeInfo>(4)
        val st = ArrayList<AccessibilityNodeInfo>()
        st.add(c)
        var visited = 0
        while (st.isNotEmpty() && ++visited < 200) {
            val nd = st.removeAt(st.size - 1)
            if (nd.isClickable && nd.isVisibleToUser) list.add(nd)
            for (i in nd.childCount - 1 downTo 0) nd.getChild(i)?.let { st.add(it) }
        }
        if (list.isEmpty()) return c
        if (list.size == 1) return list[0]
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var small: AccessibilityNodeInfo? = null
        var smallArea = Long.MAX_VALUE
        for (n in list) {
            n.getBoundsInScreen(rect)
            val w = rect.width()
            val h = rect.height()
            if (w <= 0 || h <= 0) continue
            val area = w.toLong() * h
            if (area < smallArea) { smallArea = area; small = n }
            val square = kotlin.math.abs(w - h) <= maxOf(w, h) / 5
            if (square && area < bestArea) { bestArea = area; best = n }
        }
        return best ?: small ?: list[0]
    }

    // ---------------------------------------------------------------- legacy (vivo) matching

    private var bestScore = 0
    private var bestNode: AccessibilityNodeInfo? = null
    private var visitedLegacy = 0
    private var curKeys: Array<String> = emptyArray()
    private var curMin = 0
    private var curTokens: Array<String> = emptyArray()

    private fun legacyFind(j: Job, roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        bestScore = 0
        bestNode = null
        curKeys = j.entry.keys
        curTokens = j.entry.tokens
        curMin = j.minPx
        for (r in roots) {
            visitedLegacy = 0
            scan(r, 0)
        }
        if (bestNode == null && j.posIndex >= 0) positional(j, roots)?.let { bestNode = it; bestScore = 1 }
        val n = bestNode ?: return null
        bestNode = null
        return legacyTarget(n)
    }

    private fun scan(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 40 || ++visitedLegacy > NODE_CAP) return
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        val rid = n.viewIdResourceName
        if (t != null || d != null || rid != null) {
            val sc = maxOf(score(t), score(d), idScore(rid))
            if (sc > bestScore && n.isVisibleToUser) {
                // the label text is small: size-check the tappable tile around it, not the text node
                legacyTarget(n).getBoundsInScreen(rect)
                if (rect.width() >= curMin && rect.height() >= curMin) {
                    bestScore = sc
                    bestNode = n
                }
            }
        }
        val c = n.childCount
        for (i in 0 until c) {
            val ch = n.getChild(i) ?: continue
            scan(ch, depth + 1)
        }
    }

    /** A view id that contains one of the tile's tokens (e.g. "tile_wifi"): 60. Generic label ids never match. */
    private fun idScore(rid: String?): Int {
        if (rid == null || curTokens.isEmpty()) return 0
        val low = rid.substringAfter('/').lowercase()
        if (low == "tile_label" || low == "label" || low == "icon") return 0
        for (k in curTokens) if (k.length >= 3 && low.contains(k)) return 60
        return 0
    }

    private val OTHERS = arrayOf("bluetooth", "flashlight", "torch", "airplane", "aeroplane", "rotate", "hotspot",
        "location", "battery", "dark", "do not disturb")

    /**
     * Last resort for the dynamic big tiles on vivo (Wi-Fi shows the network name, mobile data shows "No SIM"): the
     * tile at the spec's position among the visible, tile-sized, clickable nodes, unless its text names another tile.
     */
    private fun positional(j: Job, roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val tiles = ArrayList<AccessibilityNodeInfo>()
        val boxes = ArrayList<Rect>()
        val sw = j.ctx.resources.displayMetrics.widthPixels
        for (root in roots) {
            var visited = 0
            val st = ArrayList<AccessibilityNodeInfo>()
            st.add(root)
            while (st.isNotEmpty() && ++visited < NODE_CAP) {
                val nd = st.removeAt(st.size - 1)
                if (nd.isClickable && nd.isVisibleToUser) {
                    val r = Rect()
                    nd.getBoundsInScreen(r)
                    if (r.width() >= j.minPx && r.height() >= j.minPx && r.width() < sw * 0.95f && r.height() < sw * 0.6f) {
                        var inside = false
                        for (b in boxes) if (b.contains(r)) { inside = true; break }
                        if (!inside) {
                            boxes.add(r)
                            tiles.add(nd)
                            continue
                        }
                    }
                }
                for (i in nd.childCount - 1 downTo 0) nd.getChild(i)?.let { st.add(it) }
            }
        }
        if (tiles.isEmpty()) return null
        val order = (0 until tiles.size).sortedWith(compareBy({ (boxes[it].top / (j.minPx * 0.8f)).toInt() }, { boxes[it].left }))
        if (j.posIndex >= order.size) return null
        val cand = tiles[order[j.posIndex]]
        val txt = (collect(cand, 0) ?: "").lowercase()
        for (o in OTHERS) if (txt.contains(o)) return null
        // Wi-Fi must never land on the mobile data tile and the other way round
        if (j.id == "wifi" && (txt.contains("mobile") || txt.contains("sim") || txt.contains("data"))) return null
        if (j.id != "wifi" && (txt.contains("wi-fi") || txt.contains("wifi") || txt.contains("wlan"))) return null
        return cand
    }

    private fun collect(n: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 6) return null
        val sb = StringBuilder()
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1)?.let { s -> sb.append(s).append(' ') } }
        return sb.toString()
    }

    /** Equal 100, prefix 80, contains 40 (minus key rank), 0 = no match. */
    private fun score(s: String?): Int {
        if (s.isNullOrEmpty()) return 0
        var best = 0
        val low = s.lowercase()
        for (k in curKeys.indices) {
            val key = curKeys[k]
            if (key.isEmpty()) continue
            val sc = when {
                low == key -> 100
                low.startsWith(key) -> 80
                low.contains(key) -> 40
                else -> 0
            }
            if (sc > 0 && sc - k > best) best = sc - k
        }
        return best
    }

    private fun legacyTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo = n
        for (i in 0 until 5) {
            if (cur.isClickable) return cur
            cur = cur.parent ?: break
        }
        return n
    }

    private fun readState(n: AccessibilityNodeInfo): Int {
        var cur: AccessibilityNodeInfo? = n
        for (i in 0 until 3) {
            val c = cur ?: break
            val s = subtreeState(c, 2)
            if (s != UNKNOWN) return s
            cur = c.parent
        }
        return UNKNOWN
    }

    // ---------------------------------------------------------------- paging

    /**
     * Turns the QS page. New format: backward when every visible tile comes after the target in sysui_qs_tiles,
     * else forward; a forward scroll that does nothing turns the direction around once. Old format: forward on the
     * largest scrollable. ACTION_SCROLL_* first, then a horizontal swipe gesture across the pager.
     */
    private fun scrollPanel(j: Job) {
        var best: AccessibilityNodeInfo? = null
        var area = 0
        for (root in uiRoots(j)) {
            var visited = 0
            val st = ArrayList<AccessibilityNodeInfo>()
            st.add(root)
            while (st.isNotEmpty() && ++visited < NODE_CAP) {
                val n = st.removeAt(st.size - 1)
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(rect)
                    val a = rect.width() * rect.height()
                    if (a > area) {
                        area = a
                        best = n
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
            }
        }
        val node = best ?: return
        var forward = true
        if (j.newFormat) {
            // the last scroll did not change the page: we are at the end that way, turn around
            if (j.firstAtScroll != -2 && j.firstVisible == j.firstAtScroll) j.reversed = !j.reversed
            j.firstAtScroll = j.firstVisible
            var target = -1
            for (s in j.specs) {
                val k = j.order.indexOf(s)
                if (k >= 0) { target = k; break }
            }
            if (target >= 0 && j.firstVisible != Int.MAX_VALUE && j.firstVisible > target) forward = false
            if (j.reversed) forward = !forward
        }
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var ok = false
        if (j.newFormat) {
            // Compose QS pagers (Android 16+, e.g. Nothing OS) report ACTION_SCROLL as handled but do not move:
            // swipe the pager for real first, the scroll action only when no gesture could be sent
            node.getBoundsInScreen(rect2)
            ok = swipe(j, rect2, forward)
        }
        if (!ok) ok = try { node.performAction(act) } catch (e: Exception) { false }
        Log.i(TAG, "scroll ${if (forward) "fwd" else "back"} ok=$ok")
    }

    private fun swipe(j: Job, r: Rect, forward: Boolean): Boolean {
        if (r.width() < 50 || r.height() < 50) return false
        val y = r.centerY().toFloat()
        val x0 = r.left + r.width() * (if (forward) 0.85f else 0.15f)
        val x1 = r.left + r.width() * (if (forward) 0.15f else 0.85f)
        return try {
            val p = Path()
            p.moveTo(x0, y)
            p.lineTo(x1, y)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 220L)).build()
            j.svc.dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }

    private fun tapGesture(j: Job, n: AccessibilityNodeInfo): Boolean {
        n.getBoundsInScreen(rect2)
        if (rect2.width() <= 0 || rect2.height() <= 0) return false
        return try {
            val p = Path()
            p.moveTo(rect2.exactCenterX(), rect2.exactCenterY())
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 50L)).build()
            j.svc.dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
