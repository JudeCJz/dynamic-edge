package com.dynedge

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Quick layout editor: direct manipulation on a true-to-scale preview of the open quarter circle.
 *
 * - The preview is drawn at the overlay's real size (QuickGeom: same rings, slot counts and chip sizes as WinQuick),
 *   anchored at the view's bottom-right like the screen corner. Every slot of both rings shows as a faint circle.
 * - Long-press or drag a placed tile to pick it up (framework drag and drop, the shadow follows the finger); the other
 *   tiles slide out of the way live to show where it will land; drop it on any slot of either ring. Dropping it
 *   outside the quarter circle (tray, anywhere else) removes it.
 * - The tray below (search, filter) lists the tiles that are not placed: tap = first free slot, long-press = drag it
 *   into any slot. Sliders are toggled in the tray and selected / swapped / removed on the preview.
 * - Tap a placed tile = select: the row under the preview shows its support reason (placed tiles this phone cannot run keep a red marker), Grant and Remove.
 * - The tray offers only tiles that can work here ([TileSupport.usable]), in headed groups: My tiles, Connections,
 *   Sound and display, Tools, App tiles.
 * - Every change is saved at once (the overlay reloads live through the prefs listener); Undo steps back.
 */
class QuickEditorActivity : Activity() {

    private var accent = Color.WHITE
    private val d: Float get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- model
    private var geom = QuickGeom()
    private var rings: Array<Array<String?>> = arrayOf(arrayOfNulls(0), arrayOfNulls(0))
    private val arcs = ArrayList<String>(2)
    private val undo = ArrayList<QuickConfig.Layout>()
    /** Shortcut tiles removed this session (data deleted), kept so Undo can restore them. */
    private val removed = HashMap<String, ShortcutStore.Shortcut>()

    private lateinit var preview: PreviewView
    private lateinit var tray: LinearLayout
    private lateinit var undoBtn: ImageView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        accent = Skin.accentUi(Prefs.accent(this))
        readModel()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Skin.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        val bar = topBar("Quick layout") { finish() } as LinearLayout
        undoBtn = iconButton(R.drawable.ic_undo, "Undo", accent) { undoLast() }
        bar.addView(undoBtn)
        bar.addView(TextView(this).apply {
            text = "Reset"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(4), 0)
            minimumHeight = dp(48)
            isClickable = true
            background = Skin.ripple(this@QuickEditorActivity)
            setOnClickListener {
                QuickConfig.reset(this@QuickEditorActivity)
                val l = QuickConfig.default(this@QuickEditorActivity)
                commit(l.rings.map { it.toTypedArray() }.toTypedArray(), l.arcs, clearSel = true)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(buildSelRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // tray: search, then the scrollable grid of tiles that are not placed
        val search = EditText(this).apply {
            hint = "Search tiles"
            setHintTextColor(Skin.TEXT_3)
            setTextColor(Skin.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            background = null
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_search)?.mutate()?.apply { setTint(Skin.TEXT_3); setBounds(0, 0, dp(18), dp(18)) }, null, null, null
            )
            compoundDrawablePadding = dp(10)
            setPadding(dp(Skin.PAD), dp(10), dp(Skin.PAD), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    buildTray()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            leftMargin = dp(Skin.PAD)
            rightMargin = dp(Skin.PAD)
        })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        tray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Skin.PAD - 8), dp(4), dp(Skin.PAD - 8), dp((Prefs.idleDp(this@QuickEditorActivity) + 24f).toInt()))
        }
        scroll.addView(tray)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        PixelMode.apply(this)
        buildTray()
        updateUndo()
    }

    override fun onResume() {
        super.onResume()
        // a permission granted from the selection row (or QS edited) changes the markers
        if (resumedOnce) {
            support.clear()
            usableCache.clear()
            buildTray()
            preview.invalidate()
            showSel()
        }
        resumedOnce = true
    }

    private fun readModel() {
        val l = QuickConfig.load(this)
        arcs.clear()
        arcs.addAll(l.arcs)
        geom = QuickGeom.forPrefs(this, arcs.size)
        rings = Array(2) { r -> Array(geom.cap[r]) { k -> l.rings[r].getOrNull(k) } }
    }

    private fun snapshot(): QuickConfig.Layout = QuickConfig.Layout(rings.map { it.toList() }, arcs.toList())

    private fun placed(id: String): Boolean = rings.any { id in it } || id in arcs

    /** Applies a new arrangement: undo entry, capacity refit (slider count changes the rings), save, redraw. */
    private fun commit(next: Array<Array<String?>>, nextArcs: List<String> = arcs, clearSel: Boolean = false) {
        undo.add(snapshot())
        if (undo.size > 30) undo.removeAt(0)
        apply(QuickConfig.Layout(next.map { it.toList() }, nextArcs.toList()))
        if (clearSel) selId = null
        afterChange()
    }

    private fun apply(l: QuickConfig.Layout) {
        val g = QuickGeom.forPrefs(this, l.arcs.size)
        val n = QuickConfig.normalize(l, g.cap)
        // Undo of a removed shortcut tile: bring its data back
        for (id in n.tiles()) if (ShortcutStore.isId(id) && ShortcutStore.get(this, id) == null) {
            removed[id]?.let { ShortcutStore.put(this, it) }
        }
        geom = g
        arcs.clear()
        arcs.addAll(n.arcs)
        rings = Array(2) { r -> n.rings[r].toTypedArray() }
        QuickConfig.save(this, n)
    }

    private fun afterChange() {
        val s = selId
        if (s != null && !s.startsWith(ARC_SEL) && !placed(s)) selId = null
        if (s != null && s.startsWith(ARC_SEL) && s.removePrefix(ARC_SEL) !in arcs) selId = null
        preview.invalidate()
        buildTray()
        showSel()
        updateUndo()
    }

    private fun undoLast() {
        if (undo.isEmpty()) return
        val l = undo.removeAt(undo.size - 1)
        apply(l)
        preview.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        afterChange()
    }

    private fun updateUndo() {
        undoBtn.alpha = if (undo.isEmpty()) 0.35f else 1f
        undoBtn.isEnabled = undo.isNotEmpty()
    }

    private fun copyRings(): Array<Array<String?>> = Array(2) { r -> rings[r].copyOf() }

    /** Tray tap: the first free slot, inner ring first. */
    private fun addToFree(id: String) {
        val next = copyRings()
        for (r in 0..1) {
            val k = next[r].indexOf(null)
            if (k >= 0) {
                next[r][k] = id
                commit(next)
                return
            }
        }
        selId = id
        showSel("No free slot - long-press and drag it onto a tile to swap")
    }

    private fun remove(id: String) {
        if (ShortcutStore.isId(id)) ShortcutStore.delete(this, id)?.let { removed[id] = it }
        val next = copyRings()
        for (r in 0..1) for (k in next[r].indices) if (next[r][k] == id) next[r][k] = null
        commit(next)
    }

    /**
     * Arrangement after dropping [id] (from ring [fr] slot [fk], or the tray when fr < 0) on slot [k] of ring [t]:
     * an empty slot takes it; otherwise the ring's tiles shift one step toward its nearest empty slot; a full ring
     * swaps with the source slot (from the tray: the displaced tile moves to the other ring's first free slot, or back
     * to the tray).
     */
    private fun arrange(id: String, fr: Int, fk: Int, t: Int, k: Int): Array<Array<String?>> {
        val b = copyRings()
        if (fr >= 0 && fk < b[fr].size) b[fr][fk] = null
        val ring = b[t]
        if (k !in ring.indices) return b
        if (ring[k] == null) {
            ring[k] = id
            return b
        }
        var e = -1
        for (dist in 1 until ring.size) {
            if (k + dist < ring.size && ring[k + dist] == null) { e = k + dist; break }
            if (k - dist >= 0 && ring[k - dist] == null) { e = k - dist; break }
        }
        if (e >= 0) {
            if (e > k) for (i in e downTo k + 1) ring[i] = ring[i - 1] else for (i in e until k) ring[i] = ring[i + 1]
            ring[k] = id
            return b
        }
        val displaced = ring[k]
        ring[k] = id
        if (fr >= 0 && fk < b[fr].size) {
            b[fr][fk] = displaced
        } else {
            val o = b[1 - t]
            val f = o.indexOf(null)
            if (f >= 0) o[f] = displaced
        }
        return b
    }

    // ---------------------------------------------------------------- support markers

    private var resumedOnce = false
    private val support = HashMap<String, TileSupport.Support>()
    private val usableCache = HashMap<String, Boolean>()

    /** Can the tile work on this phone? Unusable ones are not offered in the tray. */
    private fun usable(id: String): Boolean = usableCache.getOrPut(id) { TileSupport.usable(this, id) }

    private fun sup(id: String): TileSupport.Support = support.getOrPut(id) { TileSupport.support(this, id) }

    // ---------------------------------------------------------------- selection row

    /** Selected tile id, or ARC_SEL + slider id. */
    private var selId: String? = null
    private lateinit var selRow: LinearLayout
    private lateinit var selIcon: ImageView
    private lateinit var selText: TextView
    private lateinit var selGrant: TextView
    private lateinit var selSwap: ImageView
    private lateinit var selRemove: ImageView

    private fun buildSelRow(): View {
        selRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(Skin.PAD), dp(4), dp(8), dp(4))
        }
        selIcon = icon(R.drawable.ic_alert_circle, BAD_RED, 20)
        selRow.addView(selIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
        selText = txt("", 13f, Skin.TEXT_2, false)
        selRow.addView(selText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selGrant = txt("Grant", 14f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(44)
            isClickable = true
            background = Skin.ripple(this@QuickEditorActivity)
        }
        selRow.addView(selGrant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        selSwap = iconButton(R.drawable.ic_swipe, "Swap sliders", accent) {
            if (arcs.size >= 2) commit(copyRings(), listOf(arcs[1], arcs[0]))
        }
        selRow.addView(selSwap)
        selRemove = iconButton(R.drawable.ic_trash, "Remove", accent) {
            val s = selId ?: return@iconButton
            if (s.startsWith(ARC_SEL)) commit(copyRings(), arcs - s.removePrefix(ARC_SEL), clearSel = true)
            else {
                selId = null
                remove(s)
            }
        }
        selRow.addView(selRemove)
        showSel()
        return selRow
    }

    /** Fills the row for the selection; [note] overrides the text (e.g. while dragging out = remove). */
    private fun showSel(note: String? = null, warn: Boolean = false) {
        val s = selId
        selGrant.visibility = View.GONE
        selGrant.text = "Grant"
        selSwap.visibility = View.GONE
        selRemove.visibility = View.GONE
        selIcon.visibility = View.INVISIBLE
        selRow.setOnClickListener(null)
        selRow.isClickable = false
        selText.setTextColor(if (warn) BAD_RED else Skin.TEXT_2)
        if (note != null) {
            selText.text = note
            if (warn) {
                selIcon.setImageResource(R.drawable.ic_trash)
                selIcon.setColorFilter(BAD_RED)
                selIcon.visibility = View.VISIBLE
            }
            return
        }
        if (s == null) {
            selText.text = "Tap a tile to select it. Long-press or drag to move it, drag it out to remove it."
            return
        }
        if (s.startsWith(ARC_SEL)) {
            selText.setTextColor(Skin.TEXT)
            selText.text = Arcs.label(s.removePrefix(ARC_SEL)) + " slider"
            selSwap.visibility = if (arcs.size >= 2) View.VISIBLE else View.GONE
            selRemove.visibility = View.VISIBLE
            return
        }
        if (ShortcutStore.isId(s)) {
            selText.setTextColor(Skin.TEXT)
            selText.text = ShortcutStore.get(this, s)?.let { it.label + ": " + it.target } ?: "Shortcut"
            selRemove.visibility = if (placed(s)) View.VISIBLE else View.GONE
            selGrant.text = "Edit"
            selGrant.visibility = View.VISIBLE
            val edit = View.OnClickListener { openPicker(s) }
            selGrant.setOnClickListener(edit)
            selRow.setOnClickListener(edit)
            return
        }
        val sp = sup(s)
        selRemove.visibility = if (placed(s)) View.VISIBLE else View.GONE
        if (sp.ok) {
            selText.setTextColor(Skin.TEXT)
            selText.text = tileLabel(s)
            return
        }
        selIcon.setImageResource(R.drawable.ic_alert_circle)
        selIcon.setColorFilter(BAD_RED)
        selIcon.visibility = View.VISIBLE
        selText.setTextColor(Skin.TEXT)
        selText.text = tileLabel(s) + ": " + sp.reason()
        val need = (sp as? TileSupport.Support.NeedsPermission)?.which
        val fix = need?.let { TileSupport.intent(this, it) }
        if (fix != null) {
            selGrant.visibility = View.VISIBLE
            val open = View.OnClickListener {
                try {
                    startActivity(fix)
                } catch (e: Exception) {
                }
            }
            selGrant.setOnClickListener(open)
            selRow.setOnClickListener(open)
        }
    }

    private fun select(id: String?) {
        selId = if (selId == id) null else id
        preview.invalidate()
        showSel()
    }

    // ---------------------------------------------------------------- shortcut tiles

    /** Opens the shortcut picker: a new shortcut ([id] null) or editing [id]. The result comes back in onActivityResult. */
    private fun openPicker(id: String?) {
        startActivityForResult(ShortcutPickerActivity.intent(this, id), REQ_SHORTCUT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SHORTCUT || resultCode != RESULT_OK) return
        val id = data?.getStringExtra(ShortcutPickerActivity.EXTRA_ID) ?: return
        iconCache.remove(id)
        if (placed(id)) {
            afterChange()
        } else {
            addToFree(id) // no free slot: it stays in the tray
            buildTray()
        }
    }

    // ---------------------------------------------------------------- icons

    private val iconCache = HashMap<String, Drawable?>()

    private fun tileLabel(id: String): String = if (ShortcutStore.isId(id)) ShortcutStore.label(this, id) else Tiles.label(id)

    /** The tile's glyph: the phone's SystemUI icon, the third-party tile icon, else the Lucide icon (white). */
    private fun tileIcon(id: String): Drawable? = iconCache.getOrPut(id) {
        if (ShortcutStore.isId(id)) {
            val bmp = ShortcutStore.appBitmap(this, id, dp(48))
            if (bmp != null) BitmapDrawable(resources, bmp)
            else getDrawable(ShortcutStore.iconRes(this, id))?.mutate()?.apply { setTint(Color.WHITE) }
        } else if (QsCatalog.isCustom(id)) {
            QsCatalog.entry(this, id)?.let { QsCatalog.drawable(this, it) } ?: getDrawable(R.drawable.ic_tile)?.mutate()?.apply { setTint(Color.WHITE) }
        } else {
            val bmp = try { TileIcons.system(this, id, false, dp(24)) } catch (e: Exception) { null }
            if (bmp != null) BitmapDrawable(resources, bmp)
            else getDrawable(Tiles.staticIcon(id))?.mutate()?.apply { setTint(Color.WHITE) }
        }
    }

    private fun arcIcon(id: String): Int = when (id) {
        "brightness" -> R.drawable.ic_sun
        "media_volume" -> R.drawable.ic_volume
        "ring_volume" -> R.drawable.ic_ring
        "notification_volume" -> R.drawable.ic_bell
        "call_volume" -> R.drawable.ic_phone
        else -> R.drawable.ic_alarm
    }

    // ---------------------------------------------------------------- drag and drop

    /** Local state of a drag: the tile and where it came from (ring -1 = tray). */
    private class DragInfo(val id: String, val fr: Int, val fk: Int) {
        var done = false
    }

    private inner class ChipShadow(private val id: String, private val r: Float) : View.DragShadowBuilder() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val size = (2f * r * 1.18f + 4f * d).toInt()

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(size, size)
            outShadowTouchPoint.set(size / 2, size / 2)
        }

        override fun onDrawShadow(c: Canvas) {
            val cx = size / 2f
            val rr = r * 1.18f
            p.color = EdgeView.C_FILL
            c.drawCircle(cx, cx, rr, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = accent
            c.drawCircle(cx, cx, rr, p)
            p.style = Paint.Style.FILL
            val dr = tileIcon(id)?.constantState?.newDrawable()?.mutate() ?: return
            val h = (rr * 0.5f).toInt()
            dr.setBounds((cx - h).toInt(), (cx - h).toInt(), (cx + h).toInt(), (cx + h).toInt())
            dr.draw(c)
        }
    }

    private fun startTileDrag(from: View, id: String, fr: Int, fk: Int): Boolean {
        val r = geom.chip[if (fr >= 0) fr else 1]
        val ok = from.startDragAndDrop(ClipData.newPlainText("tile", id), ChipShadow(id, r), DragInfo(id, fr, fk), 0)
        if (ok) {
            from.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            preview.onDragBegin(id, fr, fk)
        }
        return ok
    }

    // ---------------------------------------------------------------- tray

    private class Item(val id: String, val icon: Drawable?, val label: String, val arc: Boolean = false)

    private fun header(text: String) {
        tray.addView(sectionLabel(text).apply { setPadding(dp(8), dp(18), 0, dp(6)) })
    }

    private fun cell(t: Item, active: Boolean): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            background = Skin.ripple(this@QuickEditorActivity)
        }
        val img = ImageView(this).apply {
            val dr = if (t.arc) getDrawable(arcIcon(t.id))?.mutate() else t.icon?.constantState?.newDrawable()?.mutate()
            if (t.arc) dr?.setTint(if (active) Skin.onColor(accent) else Color.WHITE)
            setImageDrawable(dr)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) accent else Skin.SURFACE_HI)
            }
        }
        val f = FrameLayout(this)
        f.addView(img, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        if (ShortcutStore.isId(t.id)) {
            // my tiles: a small X deletes the shortcut (it is not placed, so nothing else refers to it)
            f.addView(ImageView(this).apply {
                setImageDrawable(getDrawable(R.drawable.ic_x)?.mutate()?.apply { setTint(Color.WHITE) })
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE5484D.toInt())
                }
                contentDescription = "Delete ${t.label}"
                setOnClickListener {
                    confirmDialog("Delete \"${t.label}\"?", "This shortcut tile is removed.", "Delete") {
                        ShortcutStore.delete(this@QuickEditorActivity, t.id)
                        buildTray()
                    }
                }
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END))
        }
        c.addView(f, LinearLayout.LayoutParams(dp(52), dp(50)))
        c.addView(TextView(this).apply {
            text = t.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Skin.TEXT_2)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        })
        if (t.arc) {
            c.setOnClickListener {
                if (t.id in arcs) commit(copyRings(), arcs - t.id)
                else if (arcs.size < QuickConfig.ARCS_MAX) commit(copyRings(), arcs + t.id)
                else showSel("Two sliders at most - remove one on the preview first")
            }
        } else if (t.id == NEW_ID) {
            c.setOnClickListener { openPicker(null) }
        } else {
            c.setOnClickListener {
                addToFree(t.id)
            }
            c.setOnLongClickListener { startTileDrag(it, t.id, -1, -1) }
        }
        return c
    }

    /** A heading and its grid; nothing when no tile passes the search. [always] keeps the section (My tiles). */
    private fun section(title: String, all: List<Item>, always: Boolean = false) {
        val items = all.filter { t -> t.id == NEW_ID || query.isEmpty() || t.label.contains(query, ignoreCase = true) }
        if (items.isEmpty() && !always) return
        header(title)
        grid(items)
    }

    private fun grid(items: List<Item>) {
        val cols = 4
        if (items.isEmpty()) return
        var row: LinearLayout? = null
        for ((i, t) in items.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                tray.addView(row)
            }
            row!!.addView(cell(t, t.arc && t.id in arcs), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rem = items.size % cols
        if (rem != 0) for (k in rem until cols) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun group(id: String): Int {
        val key = if (id.startsWith(QsCatalog.SYS)) id.removePrefix(QsCatalog.SYS) else id
        return when (key) {
            "wifi", "bluetooth", "cell", "airplane", "hotspot", "nfc", "location", "cast", "upnp_server", "vivo_share",
            "dataSaver", "saver", "internet", "bt" -> 0
            "dnd", "vibrate", "rotate", "ringer", "silent_mode", "dark", "night", "reduce_brightness", "inversion",
            "color_correction", "dream", "font_scaling" -> 1
            else -> 2
        }
    }

    /** False until the first tray build: then the phone's tile lists are re-read (new tiles / apps). */
    private var catalogFresh = false

    /** Ring radius slider (percent, 100 = default). Refits the slots and redraws the preview live. */
    private fun ringSlider(caption: String, key: String, max: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(8), 0, dp(4), 0)
        }
        row.addView(icon(R.drawable.ic_ring, Skin.TEXT_2, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
        row.addView(txt(caption, 15f, Skin.TEXT, false), LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(FlatSlider(this).apply {
            min = Prefs.RING_MIN
            this.max = max
            tick = Prefs.ringDef(key)
            value = Prefs.ringPct(this@QuickEditorActivity, key, max)
            accent = this@QuickEditorActivity.accent
            contentDescription = caption
            onChange = {
                Prefs.prefs(this@QuickEditorActivity).edit().putFloat(key, it).apply()
                this@QuickEditorActivity.apply(snapshot())
                preview.invalidate()
            }
            onStop = { afterChange() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildTray() {
        tray.removeAllViews()
        val force = !catalogFresh
        catalogFresh = true
        val builtIn = (Tiles.ALL.filter { !placed(it) && usable(it) }.map { Item(it, tileIcon(it), tileLabel(it)) } +
            QsCatalog.systems(this, force).filter { !placed(it.id) && usable(it.id) }.map { Item(it.id, tileIcon(it.id), it.label) })
        val cus = QsCatalog.customs(this, force).filter { !placed(it.id) && usable(it.id) }.map { Item(it.id, tileIcon(it.id), it.label) }
        val shortcuts = ShortcutStore.all(this).filter { !placed(it.id) }.map { Item(it.id, tileIcon(it.id), it.label) }
        section("My tiles", listOf(Item(NEW_ID, getDrawable(R.drawable.ic_plus)?.mutate()?.apply { setTint(Color.WHITE) }, "New shortcut")) + shortcuts, always = true)
        section("Connections", builtIn.filter { group(it.id) == 0 })
        section("Sound and display", builtIn.filter { group(it.id) == 1 })
        section("Tools", builtIn.filter { group(it.id) == 2 })
        section("App tiles", cus)
        header("Sliders")
        grid(Arcs.ALL.map { Item(it, null, Arcs.label(it), true) }.filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) })
        header("Rings")
        tray.addView(ringSlider("Inner ring", Prefs.KEY_QUICK_RING_IN, Prefs.RING_IN_MAX))
        tray.addView(ringSlider("Outer ring", Prefs.KEY_QUICK_RING_OUT, Prefs.RING_MAX))
    }

    // ---------------------------------------------------------------- preview

    private inner class PreviewView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        private val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        private val anim = HashMap<String, PointF>()

        private var cx = 0f
        private var cy = 0f
        private val openR: Float get() = Prefs.openDp(context) * d

        // gesture
        private var downX = 0f
        private var downY = 0f
        private var pendId: String? = null
        private var pendR = -1
        private var pendK = -1
        private var pendArc = -1
        private var dragging = false

        // drag state (also for drags that start in the tray)
        private var dragId: String? = null
        private var dragFr = -1
        private var dragFk = -1
        private var hovR = -1
        private var hovK = -1
        private var outside = false
        private var hypo: Array<Array<String?>>? = null

        private val longPress = Runnable {
            val id = pendId
            if (id != null && !dragging) {
                dragging = startTileDrag(this, id, pendR, pendK)
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (openR + 20f * d).toInt())
        }

        // ---- geometry: slot centres exactly like Win.place
        private fun slotX(r: Int, k: Int): Float {
            val rr = min(geom.ringR(r), openR - 6f * d - geom.chip[r])
            return cx - rr * cos(Math.toRadians(geom.angle(r, k).toDouble())).toFloat()
        }

        private fun slotY(r: Int, k: Int): Float {
            val rr = min(geom.ringR(r), openR - 6f * d - geom.chip[r])
            return cy - rr * sin(Math.toRadians(geom.angle(r, k).toDouble())).toFloat()
        }

        /** Badge centre sits [QuickGeom.inset] above the bottom edge, same line as the bottom-most outer tile. */
        private fun badgeAngle(r: Float): Float =
            Math.toDegrees(asin((max(geom.inset, 17f * d) / r).toDouble().coerceAtMost(1.0))).toFloat()

        private fun arcBadgeX(k: Int): Float {
            val r = geom.arcR[k]
            return cx - r * cos(Math.toRadians(badgeAngle(r).toDouble())).toFloat()
        }

        private fun arcBadgeY(k: Int): Float {
            val r = geom.arcR[k]
            return cy - r * sin(Math.toRadians(badgeAngle(r).toDouble())).toFloat()
        }

        // ---- drawing
        private fun drawChip(c: Canvas, id: String, x: Float, y: Float, r: Float, alpha: Float, sel: Boolean) {
            val a = (255f * alpha + 0.5f).toInt()
            p.style = Paint.Style.FILL
            p.color = EdgeView.C_FILL
            p.alpha = a
            c.drawCircle(x, y, r, p)
            val dr = tileIcon(id)
            if (dr != null) {
                val half = (r * 0.475f).toInt().coerceAtLeast(1)
                dr.alpha = a
                dr.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
                dr.draw(c)
                dr.alpha = 255
            }
            if (sel) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = accent
                p.alpha = a
                c.drawCircle(x, y, r + 3f * d, p)
                p.style = Paint.Style.FILL
            }
            if (!sup(id).ok) {
                val bx = x + r * 0.72f
                val by = y - r * 0.72f
                p.color = Skin.BG
                p.alpha = a
                c.drawCircle(bx, by, 8.5f * d, p)
                val ic = context.getDrawable(R.drawable.ic_alert_circle)?.mutate() ?: return
                ic.setTint(BAD_RED)
                ic.alpha = a
                val h = (7f * d).toInt()
                ic.setBounds((bx - h).toInt(), (by - h).toInt(), (bx + h).toInt(), (by + h).toInt())
                ic.draw(c)
            }
        }

        private fun drawArc(c: Canvas, k: Int, id: String) {
            val r = geom.arcR[k]
            val badgeA = badgeAngle(r)
            val half = Math.toDegrees(asin((13f * d / r).toDouble().coerceAtMost(1.0))).toFloat()
            val capA = Math.toDegrees(asin((ArcSet.STROKE_DP * d / 2f / r).toDouble().coerceAtMost(1.0))).toFloat()
            val s = badgeA + half + 2f + capA
            val e = 90f - badgeA
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = ArcSet.STROKE_DP * d
            rect.set(cx - r, cy - r, cx + r, cy + r)
            p.color = EdgeView.C_TRACK
            c.drawArc(rect, 180f + s, e - s, false, p)
            p.color = accent
            c.drawArc(rect, 180f + s, (e - s) * 0.55f, false, p)
            p.strokeCap = Paint.Cap.BUTT
            val bx = arcBadgeX(k)
            val by = arcBadgeY(k)
            p.style = Paint.Style.FILL
            p.color = EdgeView.C_FILL
            c.drawCircle(bx, by, 13f * d, p)
            if (selId == ARC_SEL + id) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = accent
                c.drawCircle(bx, by, 16f * d, p)
                p.style = Paint.Style.FILL
            }
            val ic = context.getDrawable(arcIcon(id))?.mutate() ?: return
            ic.setTint(Color.WHITE)
            val h = (7.5f * d).toInt()
            ic.setBounds((bx - h).toInt(), (by - h).toInt(), (bx + h).toInt(), (by + h).toInt())
            ic.draw(c)
        }

        override fun onDraw(c: Canvas) {
            cx = width.toFloat()
            cy = height.toFloat()
            val rad = openR
            // the open quarter circle in the edge's own colours
            val pct = if (Prefs.gradOn(context)) Prefs.prefs(context).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100) else 0
            p.style = Paint.Style.FILL
            p.shader = EdgePaint.shapeShaderAt(cx, cy, rad, Prefs.base(context), Prefs.grad1(context), Prefs.grad2(context), pct, Prefs.accent(context))
            c.drawCircle(cx, cy, rad, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = d
            p.color = 0x1AFFFFFF
            c.drawCircle(cx, cy, rad, p)

            for ((k, id) in arcs.withIndex()) if (k < 2) drawArc(c, k, id)

            // faint empty slots (capacity), accent ring on the hovered slot
            p.style = Paint.Style.STROKE
            for (r in 0..1) for (k in 0 until geom.cap[r]) {
                val hov = dragId != null && !outside && r == hovR && k == hovK
                p.strokeWidth = (if (hov) 2f else 1.2f) * d
                p.color = if (hov) accent else 0x26FFFFFF
                c.drawCircle(slotX(r, k), slotY(r, k), geom.chip[r], p)
            }

            val shown = hypo ?: rings
            var moving = false
            val di = dragId
            for (r in 0..1) for (k in shown[r].indices) {
                val id = shown[r][k] ?: continue
                val tx = slotX(r, k)
                val ty = slotY(r, k)
                if (id == di) {
                    // where the dragged tile will land
                    if (!outside && hypo != null) drawChip(c, id, tx, ty, geom.chip[r], 0.38f, false)
                    continue
                }
                val pt = anim.getOrPut(id) { PointF(tx, ty) }
                val dx = tx - pt.x
                val dy = ty - pt.y
                if (dx * dx + dy * dy > 0.25f) {
                    pt.x += dx * 0.28f
                    pt.y += dy * 0.28f
                    moving = true
                } else {
                    pt.x = tx
                    pt.y = ty
                }
                drawChip(c, id, pt.x, pt.y, geom.chip[r], 1f, id == selId)
            }
            if (moving) postInvalidateOnAnimation()
        }

        // ---- touch: select, long-press / drag to pick up
        private fun tileAt(x: Float, y: Float): Boolean {
            var best = Float.MAX_VALUE
            pendId = null
            for (r in 0..1) for (k in rings[r].indices) {
                val id = rings[r][k] ?: continue
                val dd = hypot(x - slotX(r, k), y - slotY(r, k))
                val hr = maxOf(geom.chip[r] + 4f * d, 22f * d)
                if (dd <= hr && dd < best) {
                    best = dd
                    pendId = id
                    pendR = r
                    pendK = k
                }
            }
            return pendId != null
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cx = width.toFloat()
                    cy = height.toFloat()
                    downX = e.x
                    downY = e.y
                    dragging = false
                    pendArc = -1
                    if (tileAt(e.x, e.y)) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        postDelayed(longPress, longMs)
                    } else {
                        for (k in arcs.indices) if (k < 2 && hypot(e.x - arcBadgeX(k), e.y - arcBadgeY(k)) < 22f * d) pendArc = k
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = pendId
                    if (!dragging && id != null && hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        dragging = startTileDrag(this, id, pendR, pendK)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!dragging && hypot(e.x - downX, e.y - downY) <= slop * 2) {
                        val id = pendId
                        when {
                            id != null -> select(id)
                            pendArc in arcs.indices -> select(ARC_SEL + arcs[pendArc])
                            else -> select(null)
                        }
                    }
                    pendId = null
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    pendId = null
                    return true
                }
            }
            return true
        }

        // ---- drag and drop (local drags only: placed tiles and tray chips)
        fun onDragBegin(id: String, fr: Int, fk: Int) {
            dragId = id
            dragFr = fr
            dragFk = fk
            hovR = -1
            hovK = -1
            outside = fr >= 0
            hypo = if (fr >= 0) copyRings().also { it[fr][fk] = null } else null
            if (fr >= 0) showSel("Drop on any slot. Drop outside the panel to remove.")
            invalidate()
        }

        private fun target(x: Float, y: Float) {
            val id = dragId ?: return
            cx = width.toFloat()
            cy = height.toFloat()
            val out = hypot(x - cx, y - cy) > openR + 4f * d
            var br = -1
            var bk = -1
            if (!out) {
                var best = Float.MAX_VALUE
                for (r in 0..1) for (k in 0 until geom.cap[r]) {
                    val dd = hypot(x - slotX(r, k), y - slotY(r, k))
                    if (dd < best) {
                        best = dd
                        br = r
                        bk = k
                    }
                }
            }
            if (out == outside && br == hovR && bk == hovK) return
            outside = out
            hovR = br
            hovK = bk
            hypo = if (out || br < 0) {
                if (dragFr >= 0) copyRings().also { it[dragFr][dragFk] = null } else null
            } else arrange(id, dragFr, dragFk, br, bk)
            if (dragFr >= 0) {
                if (out) showSel("Release to remove " + tileLabel(id), warn = true)
                else showSel("Drop on any slot. Drop outside the panel to remove.")
            }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

        override fun onDragEvent(ev: DragEvent): Boolean {
            val info = ev.localState as? DragInfo ?: return false
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> return true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> target(ev.x, ev.y)
                DragEvent.ACTION_DRAG_EXITED -> {
                    if (!outside) {
                        outside = true
                        hovR = -1
                        hovK = -1
                        hypo = if (dragFr >= 0) copyRings().also { it[dragFr][dragFk] = null } else null
                        if (dragFr >= 0) showSel("Release to remove " + tileLabel(info.id), warn = true)
                        invalidate()
                    }
                }
                DragEvent.ACTION_DROP -> {
                    target(ev.x, ev.y)
                    val h = hypo
                    info.done = true
                    if (!outside && h != null && hovR >= 0) {
                        anim[info.id] = PointF(ev.x, ev.y) // it slides from the finger into its slot
                        endDrag()
                        commit(h)
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    } else if (info.fr >= 0) {
                        endDrag()
                        remove(info.id)
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    } else {
                        endDrag()
                    }
                    return true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (!info.done) {
                        info.done = true
                        // dropped anywhere but the preview (tray, bar...): a placed tile is removed
                        endDrag()
                        if (info.fr >= 0) {
                            remove(info.id)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else {
                            showSel()
                        }
                    }
                }
            }
            return true
        }

        private fun endDrag() {
            dragId = null
            dragFr = -1
            dragFk = -1
            hovR = -1
            hovK = -1
            outside = false
            hypo = null
            dragging = false
            showSel()
            invalidate()
        }
    }

    companion object {
        private const val ARC_SEL = "arc:"
        private const val NEW_ID = "new:shortcut"
        private const val REQ_SHORTCUT = 41
        /** Marker red for tiles that will not work by themselves. */
        private const val BAD_RED = 0xFFFF453A.toInt()
    }
}
