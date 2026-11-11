package com.dynedge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min

/**
 * APPS window: up to [Prefs.MAX_APPS] shortcut apps evenly spaced on concentric rings around the corner
 * ([AppRing]: symmetric about the 45 degree diagonal, outer rings hold more), each icon turned so its "up"
 * points outward along its radius. Optional recent-apps ribbon on the outer edge (same rotation). "+" opens
 * settings when no app is set. Options: Prefs.KEY_APPS_ICON / KEY_APPS_GAP / KEY_APPS_RECENTS.
 */
internal class WinApps(h: EdgeView) : Win(h, "apps", ITEMS) {

    private val apps = AppShortcuts(h.context, h.dp(Prefs.APPS_ICON_MAX).toInt())
    private var appCount = 0
    private val appBmp = arrayOfNulls<Bitmap>(N_APPS)
    private val appPaint = Array(N_APPS) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val appMatrix = Matrix()
    private val plusRect = Rect()
    private val ring = AppRing.Out(N_APPS)
    private val rot = FloatArray(ITEMS)
    private var iconR = 0f
    private var ribbonOn = true

    // recent-apps ribbon (thin arc row near the outer edge); empty without usage access
    private val ribPkg = arrayOfNulls<String>(RIB_MAX)
    private val ribBmp = arrayOfNulls<Bitmap>(RIB_MAX)
    private val ribPaint = Array(RIB_MAX) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val ribPath = Path()
    private var ribN = 0
    private var ribGen = 0
    private var ribR = 0f
    private val ribOval = RectF()

    override fun layout(hw: Float, brad: Float) {
        val ctx = h.context
        val want = Prefs.flag(ctx, Prefs.KEY_APPS_RECENTS)
        if (want != ribbonOn) {
            ribbonOn = want
            if (started) {
                if (want) loadRibbon() else clearRibbon()
            }
        }
        val n = if (started) min(apps.count, N_APPS) else Prefs.apps(ctx).size.coerceAtMost(N_APPS)
        AppRing.compute(ring, n, rad, dp(Prefs.appsIconDp(ctx) / 2f), dp(Prefs.appsGapDp(ctx)), ribbonOn, h.dp(1f))
        iconR = ring.itemR
        for (k in 0 until N_APPS) {
            if (k < ring.n) {
                place(k, ring.r[k], ring.ang[k], iconR)
                rot[k] = ring.rot[k]
            } else {
                place(k, 0.6f * rad, 45f, iconR)
                rot[k] = -45f
            }
        }
        place(PLUS, 0.60f * rad, 45f, dp(22f))
        val ph = dp(9f).toInt()
        plusRect.set((cx[PLUS] - ph).toInt(), (cy[PLUS] - ph).toInt(), (cx[PLUS] + ph).toInt(), (cy[PLUS] + ph).toInt())
        for (i in 0 until N_APPS) appBmp[i] = null
        ribR = AppRing.ribbonR(rad, h.dp(1f))
        arcPath(ribPath, ribR, 12f, 78f)
        placeRibbon()
        if (started) refresh()
    }

    private fun placeRibbon() {
        for (i in 0 until ribN) {
            // symmetric about the 45 degree diagonal
            val a = 45f + (i - (ribN - 1) / 2f) * RIB_STEP
            place(RIB0 + i, ribR, a, dp(AppRing.RIB_ICON_DP))
            rot[RIB0 + i] = a - 90f
        }
    }

    override fun start() {
        apps.load()
        ribbonOn = Prefs.flag(h.context, Prefs.KEY_APPS_RECENTS)
        // the ring depends on the number of apps that actually loaded
        layout(0f, 0f)
        if (ribbonOn) loadRibbon() else clearRibbon()
    }

    override fun onPrefs() {
        if (started) apps.load()
    }

    private fun loadRibbon() {
        val gen = ++ribGen
        val app = h.context.applicationContext ?: h.context
        if (!RecentApps.hasAccess(app)) {
            setRibbon(emptyList())
            return
        }
        val pinned = HashSet<String>()
        for (cn in Prefs.apps(app)) pinned.add(cn.packageName)
        val px = dp(28f).toInt().coerceAtLeast(16)
        val t = Thread({
            val res = RecentApps.query(app, pinned, px, RIB_MAX)
            h.post { if (gen == ribGen && started && ribbonOn) setRibbon(res) }
        }, "edge-recent")
        t.isDaemon = true
        t.start()
    }

    private fun clearRibbon() {
        ribGen++
        setRibbon(emptyList())
    }

    private fun setRibbon(list: List<RecentApps.Item>) {
        val r = dp(AppRing.RIB_ICON_DP)
        ribN = min(list.size, RIB_MAX)
        for (i in 0 until RIB_MAX) {
            if (i < ribN) {
                val b = list[i].icon
                ribPkg[i] = list[i].pkg
                ribBmp[i] = b
                val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                appMatrix.setScale(2f * r / b.width, 2f * r / b.height)
                appMatrix.postTranslate(-r, -r)
                sh.setLocalMatrix(appMatrix)
                ribPaint[i].shader = sh
            } else {
                ribPkg[i] = null
                ribBmp[i] = null
                ribPaint[i].shader = null
            }
        }
        placeRibbon()
        dirty = true
        h.invalidate()
    }

    override fun stop() {
        ribGen++
        ribN = 0
        for (i in 0 until RIB_MAX) {
            ribPkg[i] = null
            ribBmp[i] = null
            ribPaint[i].shader = null
        }
        apps.release()
        for (i in 0 until N_APPS) {
            appBmp[i] = null
            appPaint[i].shader = null
        }
        appCount = 0
    }

    private fun refresh() {
        dirty = true
        appCount = min(min(apps.count, N_APPS), ring.n)
        val r = iconR
        for (i in 0 until N_APPS) {
            val b = if (i < appCount) apps.icon(i) else null
            if (b !== appBmp[i]) {
                appBmp[i] = b
                if (b == null) {
                    appPaint[i].shader = null
                } else {
                    val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    appMatrix.setScale(2f * r / b.width, 2f * r / b.height)
                    appMatrix.postTranslate(-r, -r)
                    s.setLocalMatrix(appMatrix)
                    appPaint[i].shader = s
                }
            }
        }
    }

    private fun drawRibbon(c: Canvas, a: Float) {
        if (ribN == 0) return
        val ai = (255f * a + 0.5f).toInt()
        val ap = h.arcPaint
        ap.strokeWidth = dp(1.5f)
        ap.color = EdgeView.C_TRACK
        ap.alpha = ai
        ribOval.set(-ribR, -ribR, ribR, ribR)
        c.drawArc(ribOval, 180f + 12f, 66f, false, ap)
        val r = dp(AppRing.RIB_ICON_DP)
        val rr = r + dp(3f)
        for (k in 0 until ribN) {
            val i = RIB0 + k
            val ps = isPressed(i)
            c.save()
            c.translate(cx[i], cy[i])
            c.rotate(rot[i])
            if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
            val p = ribPaint[k]
            p.alpha = ai
            if (p.shader != null) c.drawRect(-r, -r, r, r, p)
            if (ps) {
                h.ringPaint.alpha = ai
                c.drawRoundRect(-rr, -rr, rr, rr, rr * 0.3f, rr * 0.3f, h.ringPaint)
            }
            c.restore()
        }
    }

    override fun draw(c: Canvas, a: Float) {
        drawRibbon(c, a)
        if (appCount == 0) {
            val x = cx[PLUS]
            val y = cy[PLUS]
            val ps = isPressed(PLUS)
            val ai = (255f * a + 0.5f).toInt()
            if (ps) {
                c.save()
                c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
                h.ringPaint.alpha = ai
                c.drawCircle(x, y, ir[PLUS], h.ringPaint)
            }
            val lp = h.linePaint
            lp.color = EdgeView.C_GREY
            lp.alpha = ai
            lp.strokeWidth = dp(2f)
            val hh = dp(9f)
            c.drawLine(x - hh, y, x + hh, y, lp)
            c.drawLine(x, y - hh, x, y + hh, lp)
            if (ps) c.restore()
            return
        }
        val ai = (255f * a + 0.5f).toInt()
        val r = iconR
        val rr = r + dp(3f)
        for (k in 0 until appCount) {
            val ps = isPressed(k)
            c.save()
            c.translate(cx[k], cy[k])
            // the icon's up axis lies along the radius, pointing away from the corner
            c.rotate(rot[k])
            if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
            val p = appPaint[k]
            if (p.shader != null) {
                // the bitmap already carries its rounded corners: draw it full size, no disc
                p.alpha = ai
                c.drawRect(-r, -r, r, r, p)
            }
            if (ps) {
                h.ringPaint.alpha = ai
                c.drawRoundRect(-rr, -rr, rr, rr, rr * 0.3f, rr * 0.3f, h.ringPaint)
            }
            c.restore()
        }
    }

    override fun hit(lx: Float, ly: Float): Int {
        beginHit()
        if (appCount == 0) consider(PLUS, lx, ly) else for (k in 0 until appCount) consider(k, lx, ly)
        for (k in 0 until ribN) consider(RIB0 + k, lx, ly)
        return bestHit()
    }

    override fun longPressable(item: Int): Boolean = item >= RIB0 && item < RIB0 + ribN

    /** Long-press on a ribbon app: stop it (background processes only, see [RecentApps.stop]) and drop it. */
    override fun onLongPress(item: Int): Boolean {
        val k = item - RIB0
        if (k < 0 || k >= ribN) return false
        val pkg = ribPkg[k] ?: return false
        RecentApps.stop(h.context, pkg)
        val keep = ArrayList<RecentApps.Item>(ribN)
        for (j in 0 until ribN) {
            val pj = ribPkg[j]
            val bj = ribBmp[j]
            if (j != k && pj != null && bj != null) keep.add(RecentApps.Item(pj, bj))
        }
        setRibbon(keep)
        return false
    }

    override fun onTap(item: Int) {
        if (item >= RIB0) {
            val pkg = ribPkg.getOrNull(item - RIB0) ?: return
            if (RecentApps.launch(h.context, pkg)) h.collapse()
            return
        }
        if (item == PLUS) {
            try {
                val i = Intent(h.context, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                h.context.startActivity(i)
            } catch (e: Exception) {
            }
            h.collapse()
        } else if (apps.launch(item)) {
            h.collapse()
        }
    }

    private companion object {
        const val N_APPS = Prefs.MAX_APPS
        const val PLUS = N_APPS
        const val RIB0 = N_APPS + 1
        const val RIB_MAX = 5
        const val ITEMS = RIB0 + RIB_MAX
        const val RIB_STEP = 13f
    }
}
