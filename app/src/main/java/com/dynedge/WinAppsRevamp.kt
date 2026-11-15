package com.dynedge

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The APPS window: one text-free 4 x 4 grid of square app icons inside the quarter panel.
 *
 * There is no label, no caption and no section rule: the grid itself is the whole window. The cells sit
 * on the lead-defined grid from `review/SHARED_CONTRACT.md` (R = the open radius, cell = 0.17 R,
 * icon = 0.13 R, the corner of the screen is the origin), so the editor's grid picker and this overlay
 * place a cell in exactly the same spot.
 *
 * Pinned apps (the JSON in [Prefs.KEY_APP_GRID], or the plain [Prefs.apps] list when no grid is stored)
 * own their cell. The free cells are filled with the recently used apps in recency order, drawn at 80 %
 * of the icon side and 60 % alpha: the grid reads as one picture, yet the recent ones are visibly softer.
 * Long-pressing a recent drops it from the grid (and kills its background process, see [RecentApps.stop]).
 *
 * Nothing is allocated on the draw path: the icons are cached bitmaps drawn through stored shaders, the
 * cell positions and the empty-state paint are built in [layout] / the constructor.
 */
internal open class WinAppsRevamp(h: EdgeView) : Win(h, "apps", ITEMS) {

    // ---------------------------------------------------------------- pinned apps
    /** Pinned apps by cell (`row * COLS + col`); null = the cell is free. */
    private val pinComp = arrayOfNulls<ComponentName>(CELLS)
    private val pinBmp = arrayOfNulls<Bitmap>(CELLS)
    private var pinN = 0

    // ---------------------------------------------------------------- recent apps
    /** The recents, newest first, as [RecentApps.query] returned them. */
    private val recPkg = arrayOfNulls<String>(CELLS)
    private val recBmp = arrayOfNulls<Bitmap>(CELLS)
    private var recN = 0

    // ---------------------------------------------------------------- the composed grid
    /** What a cell holds: [EMPTY], [PINNED] or [RECENT]. */
    private val kind = IntArray(CELLS)
    private val cellComp = arrayOfNulls<ComponentName>(CELLS)
    private val cellPkg = arrayOfNulls<String>(CELLS)
    private val cellBmp = arrayOfNulls<Bitmap>(CELLS)
    private val iconPaint = Array(CELLS) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }

    /** The parsed grid: cell -> flattened ComponentName. */
    private val gridRaw = arrayOfNulls<String>(CELLS)
    private val seen = ArrayList<ComponentName>(CELLS)

    private val shaderMatrix = Matrix()
    private var recGen = 0
    /** True while the usage query runs: the empty state is not shown for a grid that is only still loading. */
    private var recLoading = false

    // ---------------------------------------------------------------- geometry
    private var iconR = 0f      // half the side of a pinned icon, px
    private var recR = 0f       // half the side of a recent icon, px
    private var iconPx = 0      // bitmap side requested from [IconTheme]

    // ---------------------------------------------------------------- empty state
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(2.6f), dp(2.6f)), 0f)
    }

    // ---------------------------------------------------------------- lifecycle
    override fun layout(hw: Float, brad: Float) {
        val side = ICON_F * rad
        iconR = 0.5f * side
        recR = RECENT_SCALE * iconR
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val x = -(col + 0.5f) * CELL_F * rad
                val y = -(row + 0.5f) * CELL_F * rad
                val rc = hypot(x, y)
                val aDeg = Math.toDegrees(atan2(-y.toDouble(), -x.toDouble())).toFloat()
                place(row * COLS + col, rc, aDeg, iconR)
            }
        }
        h.warm(R.drawable.ic_plus)
        val px = side.roundToInt().coerceAtLeast(8)
        if (px != iconPx) {
            iconPx = px
            if (started) {
                loadPinned()
                compose()
                loadRecents()
            }
        } else if (started) {
            rebind()
        }
    }

    override fun start() {
        if (iconPx <= 0) iconPx = (ICON_F * rad).roundToInt().coerceAtLeast(8)
        loadPinned()
        compose()
        loadRecents()
    }

    override fun stop() {
        recGen++
        recLoading = false
        pinN = 0
        recN = 0
        for (i in 0 until CELLS) {
            pinComp[i] = null
            pinBmp[i] = null
            recPkg[i] = null
            recBmp[i] = null
            kind[i] = EMPTY
            cellComp[i] = null
            cellPkg[i] = null
            cellBmp[i] = null
            iconPaint[i].shader = null
        }
        dirty = true
    }

    // ---------------------------------------------------------------- data

    /**
     * Reads [Prefs.KEY_APP_GRID] (`{"cols":4,"rows":4,"cells":{"slot":"pkg/cls"}}`). Returns false when
     * there is no grid or it holds no usable slot, in which case the caller falls back to [Prefs.apps].
     */
    private fun readGrid(): Boolean {
        gridRaw.fill(null)
        val raw = Prefs.prefs(h.context).getString(Prefs.KEY_APP_GRID, null)
        if (raw.isNullOrEmpty()) return false
        var n = 0
        try {
            val o = JSONObject(raw)
            val cols = o.optInt("cols", COLS)
            if (cols <= 0) return false
            val cells = o.optJSONObject("cells") ?: return false
            val keys = cells.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val slot = key.toIntOrNull() ?: continue
                val col = slot % cols
                val row = slot / cols
                if (col < 0 || col >= COLS || row < 0 || row >= ROWS) continue
                val flat = cells.optString(key, "")
                if (flat.isEmpty()) continue
                val i = row * COLS + col
                if (i == X_CELL) continue // the host's close X lives in this cell
                if (gridRaw[i] == null) {
                    gridRaw[i] = flat
                    n++
                }
            }
        } catch (e: Exception) {
            return false
        }
        return n > 0
    }

    /** The plain app list, slot order: index i -> col i % 4, row i / 4. The X cell is skipped. */
    private fun fallbackGrid() {
        var i = X_CELL + 1
        for (cn in Prefs.apps(h.context)) {
            if (i >= CELLS) break
            gridRaw[i] = cn.flattenToString()
            i++
        }
    }

    /** Resolves the parsed grid to installed components and their icons. Returns how many cells it filled. */
    private fun buildPins(): Int {
        pinN = 0
        seen.clear()
        for (i in 0 until CELLS) {
            pinComp[i] = null
            pinBmp[i] = null
        }
        val pm = h.context.packageManager
        val px = iconPx
        for (i in X_CELL + 1 until CELLS) {
            val flat = gridRaw[i] ?: continue
            val cn = ComponentName.unflattenFromString(flat) ?: continue
            try {
                pm.getActivityInfo(cn, 0)
            } catch (e: Exception) {
                continue // uninstalled or not an activity: the cell stays free
            }
            if (seen.contains(cn)) continue
            seen.add(cn)
            pinComp[i] = cn
            pinBmp[i] = IconTheme.icon(h.context, cn, px)
            pinN++
        }
        return pinN
    }

    private fun loadPinned() {
        val hadGrid = readGrid()
        if (buildPins() == 0 && hadGrid) {
            // a stored grid that resolves to nothing (all apps uninstalled): fall back to the plain list
            gridRaw.fill(null)
            fallbackGrid()
            buildPins()
        }
    }

    /** Pinned apps keep their cells; the recents fill the free ones in row-major order, newest first. */
    private fun compose() {
        // cell 0 is the corner slot the host's close X sits in: always empty, never hit
        kind[X_CELL] = EMPTY
        cellComp[X_CELL] = null
        cellPkg[X_CELL] = null
        cellBmp[X_CELL] = null
        iconPaint[X_CELL].shader = null
        var k = 0
        for (i in X_CELL + 1 until CELLS) {
            val cn = pinComp[i]
            if (cn != null) {
                kind[i] = PINNED
                cellComp[i] = cn
                cellPkg[i] = null
                cellBmp[i] = pinBmp[i]
                bind(i, iconR)
                continue
            }
            if (k < recN) {
                kind[i] = RECENT
                cellComp[i] = null
                cellPkg[i] = recPkg[k]
                cellBmp[i] = recBmp[k]
                bind(i, recR)
                k++
            } else {
                kind[i] = EMPTY
                cellComp[i] = null
                cellPkg[i] = null
                cellBmp[i] = null
                iconPaint[i].shader = null
            }
        }
        dirty = true
        h.invalidate()
    }

    /** Rebuilds the shaders after the geometry changed; no bitmap is decoded here. */
    private fun rebind() {
        for (i in 0 until CELLS) {
            if (kind[i] == EMPTY) {
                iconPaint[i].shader = null
            } else {
                bind(i, if (kind[i] == RECENT) recR else iconR)
            }
        }
        dirty = true
    }

    /** A cell's bitmap as a shader covering the rounded square of half-side [r]. */
    private fun bind(i: Int, r: Float) {
        val b = cellBmp[i]
        if (b == null) {
            iconPaint[i].shader = null
            return
        }
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shaderMatrix.setScale(2f * r / b.width, 2f * r / b.height)
        shaderMatrix.postTranslate(-r, -r)
        sh.setLocalMatrix(shaderMatrix)
        iconPaint[i].shader = sh
    }

    /** The recent apps are queried off the UI thread (usage access); the result lands on the main thread. */
    private fun loadRecents() {
        val gen = ++recGen
        val app = h.context.applicationContext ?: h.context
        if (pinN >= CELLS - 1 - X_CELL || !RecentApps.hasAccess(app)) {
            recLoading = false
            setRecents(emptyList())
            return
        }
        recLoading = true
        val exclude = HashSet<String>()
        for (c in pinComp) if (c != null) exclude.add(c.packageName)
        val px = iconPx.coerceAtLeast(8)
        val cap = CELLS - 1 - X_CELL
        val t = Thread({
            val res = RecentApps.query(app, exclude, px, cap)
            h.post { if (gen == recGen && started) setRecents(res) }
        }, "edge-apps-recent")
        t.isDaemon = true
        t.start()
    }

    private fun setRecents(list: List<RecentApps.Item>) {
        recLoading = false
        recN = min(list.size, CELLS - 1 - X_CELL)
        for (i in 0 until CELLS) {
            if (i < recN) {
                recPkg[i] = list[i].pkg
                recBmp[i] = list[i].icon
            } else {
                recPkg[i] = null
                recBmp[i] = null
            }
        }
        compose()
    }

    // ---------------------------------------------------------------- drawing

    override fun draw(c: Canvas, a: Float) {
        val ai = (255f * a + 0.5f).toInt()
        for (i in 0 until CELLS) {
            when (kind[i]) {
                PINNED -> drawIcon(c, i, 1f, ai)
                RECENT -> drawIcon(c, i, RECENT_ALPHA, ai)
            }
        }
        if (pinN == 0 && recN == 0 && !recLoading) drawEmpty(c, ai)
    }

    /** One app icon: a flat rounded square, no disc, no label; the accent ring marks the press. */
    private fun drawIcon(c: Canvas, i: Int, alpha: Float, ai: Int) {
        val p = iconPaint[i]
        if (p.shader == null) return
        val recent = kind[i] == RECENT
        val r = if (recent) recR else iconR
        val ps = isPressed(i)
        c.save()
        c.translate(cx[i], cy[i])
        h.unflip(c)
        if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
        p.alpha = if (ps) ai else (ai * alpha + 0.5f).toInt()
        val cr = r * CORNER_F
        c.drawRoundRect(-r, -r, r, r, cr, cr, p)
        if (ps) {
            h.ringPaint.color = h.accent
            h.ringPaint.alpha = ai
            val o = r + dp(2.5f)
            val co = o * CORNER_F
            c.drawRoundRect(-o, -o, o, o, co, co, h.ringPaint)
        }
        c.restore()
    }

    /** Nothing stored and nothing recent: one dashed cell with a plus, no words at all. */
    private fun drawEmpty(c: Canvas, ai: Int) {
        val x = cx[X_CELL + 1]
        val y = cy[X_CELL + 1]
        val r = iconR
        val cr = r * CORNER_F
        emptyPaint.color = h.cTrack
        emptyPaint.alpha = (ai * 0.55f + 0.5f).toInt()
        c.drawRoundRect(x - r, y - r, x + r, y + r, cr, cr, emptyPaint)
        val ic = h.icon(R.drawable.ic_plus, false)
        val g = (r * 0.45f).toInt().coerceAtLeast(2)
        ic.setBounds((x - g).toInt(), (y - g).toInt(), (x + g).toInt(), (y + g).toInt())
        ic.alpha = (ai * 0.7f + 0.5f).toInt()
        h.drawIc(c, ic)
    }

    // ---------------------------------------------------------------- touch

    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        if (pinN == 0 && recN == 0 && !recLoading) {
            consider(X_CELL + 1, lx, ly)
            return bestHit()
        }
        for (i in X_CELL + 1 until CELLS) if (kind[i] != EMPTY) consider(i, lx, ly)
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean =
        item in 0 until CELLS && kind[item] == RECENT

    /** Long-press on a recent: drop it and stop its background process (see [RecentApps.stop]). */
    override fun onLongPress(item: Int): Boolean {
        if (item < 0 || item >= CELLS || kind[item] != RECENT) return false
        val pkg = cellPkg[item] ?: return false
        RecentApps.stop(h.context, pkg)
        var w = 0
        for (j in 0 until recN) {
            val pj = recPkg[j]
            if (pj != pkg) {
                recPkg[w] = pj
                recBmp[w] = recBmp[j]
                w++
            }
        }
        for (j in w until CELLS) {
            recPkg[j] = null
            recBmp[j] = null
        }
        recN = w
        compose()
        return false
    }

    override fun onTap(item: Int) {
        if (item < 0 || item >= CELLS) return
        when (kind[item]) {
            PINNED -> {
                val cn = cellComp[item] ?: return
                if (launch(cn)) h.collapse()
            }
            RECENT -> {
                val pkg = cellPkg[item] ?: return
                if (RecentApps.launch(h.context, pkg)) h.collapse()
            }
            else -> if (pinN == 0 && recN == 0 && !recLoading) openSettings()
        }
    }

    private fun launch(cn: ComponentName): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        h.context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** The empty-grid plus opens the app, where the grid is filled in. */
    private fun openSettings() {
        try {
            val i = Intent(h.context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            h.context.startActivity(i)
        } catch (e: Exception) {
        }
        h.collapse()
    }

    internal companion object {
        /** The lead-defined grid (see `review/SHARED_CONTRACT.md`): 4 x 4 cells, 0.17 R pitch, 0.13 R icon. */
        const val COLS = 4
        const val ROWS = 4
        const val CELLS = COLS * ROWS
        const val CELL_F = 0.17f
        const val ICON_F = 0.13f

        /** A recent app is drawn at this fraction of a pinned icon, at this alpha (no text marks it). */
        const val RECENT_SCALE = 0.8f
        const val RECENT_ALPHA = 0.6f

        /** Corner radius of the rounded square, as a fraction of the icon side (20 %). */
        const val CORNER_F = 0.4f

        /** The corner cell reserved for the host's close X (the grid starts at the next cell). */
        const val X_CELL = 0

        /** What a cell holds. */
        const val EMPTY = 0
        const val PINNED = 1
        const val RECENT = 2

        /** One item per cell; the host hit-tests and pages by local id. */
        const val ITEMS = CELLS
    }
}
