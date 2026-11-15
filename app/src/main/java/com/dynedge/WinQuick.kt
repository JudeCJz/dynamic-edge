package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.HapticFeedbackConstants

/**
 * QUICK window: two tile rings (inner, outer) of fixed, evenly spaced slots and up to two outer-arc sliders.
 * Slot counts and radii come from [QuickGeom] (same maths as the editor preview); the layout from [QuickConfig].
 * Third-party tiles live in either ring like any other tile.
 */
internal class WinQuick(h: EdgeView) : Win(h, "quick", ITEMS) {

    private val tiles = Tiles(h.context) { onTiles() }
    internal val arcSet = ArcSet(h, this, ARC0, 2, h.dp(13f))
    private var raw: QuickConfig.Layout = QuickConfig.loadRaw(h.context)
    private var lay: QuickConfig.Layout = raw
    private val geom = QuickGeom()
    private val slotId = arrayOfNulls<String>(ARC0)   // ring r slot k -> r * RING_MAX + k
    private val sOn = BooleanArray(ARC0)
    private val sAvail = BooleanArray(ARC0)
    private val sTog = BooleanArray(ARC0)
    private val sPend = BooleanArray(ARC0)  // a tap on this tile is settling: drawn dimmed
    private val sIcon = IntArray(ARC0)
    private val cusBmp = arrayOfNulls<Bitmap>(ARC0)   // third-party glyph per slot
    private val cusTint = BooleanArray(ARC0)
    private val sysBmp = arrayOfNulls<Bitmap>(ARC0)   // the phone's own SystemUI glyph per slot (white), if any
    private val cusWhite = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
    private val cusBlack = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN) }
    private val cusPlain = Paint(Paint.FILTER_BITMAP_FLAG)
    private var tintInk = Color.WHITE

    init {
        arcSet.set(raw.arcs.take(2))
        // a QS click opened a dialog / app, or fell back to a settings screen: get out of the way
        tiles.onLaunch = { h.collapse() }
    }

    fun setService(s: AccessibilityService?) {
        tiles.service = s
    }

    /** QuickConfig.KEY changed (the editor saves on every change): reload and relayout live. */
    fun reload() {
        raw = QuickConfig.loadRaw(h.context)
        arcSet.set(raw.arcs.take(2))
        h.relayout()
        dirty = true
    }

    /** Fits the stored layout to the ring capacities of the current geometry and rebuilds the slot arrays. */
    private fun applyModel() {
        lay = QuickConfig.normalize(raw, geom.cap)
        for (i in 0 until ARC0) {
            slotId[i] = null
            sIcon[i] = 0
            sOn[i] = false
            sysBmp[i] = null
            cusBmp[i] = null
            sTog[i] = false
            sPend[i] = false
            sAvail[i] = true
        }
        shownN[0] = 0
        shownN[1] = 0
        for (r in 0..1) {
            val ring = lay.rings[r]
            var kk = 0 // slot index after tiles that cannot work on this phone are dropped (empty slots stay)
            for (k in ring.indices) {
                val id = ring[k]
                if (id == null) {
                    kk++
                    continue
                }
                if (ShortcutStore.isId(id) && ShortcutStore.get(h.context, id) == null) continue // its data is gone
                if (!TileSupport.usable(h.context, id)) continue // checked once per layout, not per frame
                val i = r * QuickConfig.RING_MAX + kk
                kk++
                slotId[i] = id
                sIcon[i] = if (ShortcutStore.isId(id)) ShortcutStore.iconRes(h.context, id) else Tiles.staticIcon(id)
                shownN[r] = kk
            }
        }
        loadCustomIcons()
        dirty = true
    }

    /** Third-party tile glyphs, rendered once per layout change. */
    private fun loadCustomIcons() {
        val px = glyphPx()
        for (i in 0 until ARC0) {
            val id = slotId[i] ?: continue
            if (ShortcutStore.isId(id)) {
                // a shortcut that uses its target app's own icon (drawn on a white disc like a third-party glyph)
                cusBmp[i] = ShortcutStore.appBitmap(h.context, id, px)
                cusTint[i] = false
                continue
            }
            if (!QsCatalog.isCustom(id)) continue
            val e = QsCatalog.entry(h.context, id) ?: continue
            cusBmp[i] = QsCatalog.bitmap(h.context, e, px)
            cusTint[i] = e.tinted
        }
    }

    private fun glyphPx(): Int = (2f * dp(20f) * 0.475f).toInt().coerceAtLeast(8)

    private fun slotValid(i: Int): Boolean = slotId[i] != null

    /** Per ring: slots up to the last shown tile; the shown tiles spread evenly over the whole ring window. */
    private val shownN = IntArray(2)

    override fun layout(hw: Float, brad: Float) {
        cusPlain.isFilterBitmap = !IconTheme.pixelated(h.context) // app icons: sharp when pixelated
        geom.compute(rad, h.dp(1f), h.idleSizePx.toFloat(), arcSet.n, brad,
            Prefs.ringPct(h.context, Prefs.KEY_QUICK_RING_IN, Prefs.RING_IN_MAX), Prefs.ringPct(h.context, Prefs.KEY_QUICK_RING_OUT))
        applyModel()
        for (r in 0..1) {
            val n = shownN[r]
            for (k in 0 until n) {
                val i = r * QuickConfig.RING_MAX + k
                place(i, geom.ringR(r), geom.angle(r, k, n), geom.chip[r])
            }
        }
        arcSet.layout(geom.arcR, geom.margin[1])
        if (started) {
            refreshTiles()
            arcSet.refresh()
        }
    }

    override fun start() {
        tiles.service = h.service
        tiles.start()
        refreshTiles()
        arcSet.refresh()
    }

    override fun stop() {
        tiles.stop()
        arcSet.release()
    }

    override fun onShown(on: Boolean) {
        // re-read the outer arcs when the page appears, in case a system observer was unavailable
        if (on && started) {
            arcSet.refresh()
            h.invalidate()
        }
    }

    override fun onArcs() {
        if (!started) return
        arcSet.refresh()
        h.invalidate()
    }

    private fun onTiles() {
        if (!started) return
        refreshTiles()
        h.invalidate()
    }

    private fun refreshTiles() {
        dirty = true
        for (i in 0 until ARC0) {
            val id = slotId[i] ?: continue
            sTog[i] = tiles.isToggle(id)
            sOn[i] = tiles.isOn(id)
            sPend[i] = tiles.isPending(id)
            sAvail[i] = tiles.isAvailable(id)
            val ic = tiles.iconRes(id)
            sIcon[i] = ic
            h.warm(ic)
            // real SystemUI glyph (cached per id + state), built here and never on the draw path
            sysBmp[i] = if (QsCatalog.isCustom(id) || ShortcutStore.isId(id)) null else TileIcons.system(h.context, id, sTog[i] && sOn[i], glyphPx())
        }
    }

    /** The tile's own long-press target (settings page); true if an Activity was launched. */
    internal fun openTileSettings(id: String): Boolean {
        if (id == "clipboard") {
            h.openClipboard()
            return true
        }
        return tiles.openSettings(id)
    }

    // ---------------------------------------------------------------- drawing

    override fun draw(c: Canvas, a: Float) {
        // Inactive controls are monochrome; the accent marks only active / selected state.
        for (i in 0 until ARC0) {
            if (!slotValid(i)) continue
            val on = sTog[i] && sOn[i]
            val ba = (if (!sAvail[i]) 0.35f else if (sPend[i]) PENDING_ALPHA else 1f) * a
            var bmp = cusBmp[i]
            val isCus = bmp != null
            var isSys = false
            if (bmp == null) {
                bmp = sysBmp[i]
                isSys = bmp != null
            }
            if (bmp == null) {
                drawBtn(c, i, if (on) h.ink else h.cFill, sIcon[i], on, a, ba)
                continue
            }
            // third-party glyph: tinted glyphs follow the on/off colours, app icons sit on a white disc
            val plain = isCus && !isSys && !cusTint[i]
            val ps = isPressed(i)
            drawBtn(c, i, if (on || plain) h.ink else h.cFill, 0, on, a, ba)
            if (tintInk != h.ink) {
                tintInk = h.ink
                cusWhite.colorFilter = PorterDuffColorFilter(h.ink, PorterDuff.Mode.SRC_IN)
                cusBlack.colorFilter = PorterDuffColorFilter(h.inkOn, PorterDuff.Mode.SRC_IN)
            }
            val p = if (plain) cusPlain else if (on) cusBlack else cusWhite
            p.alpha = (255f * ba + 0.5f).toInt()
            if (ps) {
                c.save()
                c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, cx[i], cy[i])
            }
            if (h.mirror) {
                c.save()
                c.scale(-1f, 1f, cx[i], cy[i])
                c.drawBitmap(bmp, null, iconRect[i], p)
                c.restore()
            } else {
                c.drawBitmap(bmp, null, iconRect[i], p)
            }
            if (ps) c.restore()
        }
        arcSet.draw(c, a, h.accent)
    }

    // ---------------------------------------------------------------- touch
    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        for (i in 0 until ARC0) if (slotValid(i)) consider(i, lx, ly)
        val k = arcSet.badgeHit(lx, ly)
        if (k >= 0) forceHit(ARC0 + k)
        return bestHit()
    }

    override fun usable(item: Int): Boolean = if (item < ARC0) sAvail[item] else true

    override fun longPressable(item: Int): Boolean = item in 0 until ARC0

    override fun capture(lx: Float, ly: Float): Boolean = arcSet.capture(lx, ly)

    override fun dragTo(lx: Float, ly: Float) {
        arcSet.dragTo(lx, ly)
    }

    override fun dragEnd(cancel: Boolean) {
        arcSet.dragEnd(cancel)
    }

    override fun onTap(item: Int) {
        if (item >= ARC0) {
            arcSet.tap(item - ARC0)
            return
        }
        val id = slotId[item] ?: return
        if (id == "clipboard") {
            // jump to the CLIPBOARD window when it is enabled, else the floating clipboard popup
            if (!h.jumpTo("clipboard")) {
                h.openClipboard()
                h.collapse()
            }
            h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        if (Tiles.needsCollapseDelay(id)) {
            // e.g. screenshot: let the panel get out of the picture first
            h.collapse()
            val t = tiles
            h.postDelayed({ t.perform(id) }, 350L)
            return
        }
        val collapse = tiles.perform(id)
        refreshTiles()
        if (collapse) h.collapse()
    }

    /** Long-press: the tile's own settings page (collapse when an Activity launched). */
    override fun onLongPress(item: Int): Boolean {
        if (item >= ARC0) return false
        val id = slotId[item] ?: return false
        return openTileSettings(id)
    }

    private companion object {
        const val ARC0 = 2 * QuickConfig.RING_MAX
        const val ITEMS = ARC0 + 2
        const val PENDING_ALPHA = 0.6f
    }
}
