package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A grid of equal cells ([cols] equal columns, rows of [cellH] px) whose cells can be dragged to a new slot.
 * The dragged cell follows the finger (lifted: scale, alpha, elevation, haptic) and the other cells glide to
 * show where it will land. The last [fixedTail] children (the Add cell) never move and never take a drop.
 * Release calls [onOrder] with the movable cells in their new order.
 */
class DragGrid(ctx: Context, private val cols: Int, private val cellH: Int) : ViewGroup(ctx) {
    var fixedTail = 0
    var onOrder: (List<View>) -> Unit = {}

    private val order = ArrayList<View>()
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val loc = IntArray(2)
    private var drag: View? = null
    private var grabX = 0f
    private var grabY = 0f

    fun add(v: View) {
        addView(v)
        order.add(v)
    }

    private val cw: Float get() = (width - paddingLeft - paddingRight) / cols.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = (order.size + cols - 1) / cols
        val c = ((w - paddingLeft - paddingRight) / cols).coerceAtLeast(1)
        for (v in order) {
            v.measure(MeasureSpec.makeMeasureSpec(c, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, rows * cellH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = placeAll()

    private fun slotX(k: Int): Int = (paddingLeft + (k % cols) * cw).toInt()
    private fun slotY(k: Int): Int = paddingTop + (k / cols) * cellH

    private fun placeAll() {
        val c = cw
        for ((k, v) in order.withIndex()) {
            val x = slotX(k)
            val x1 = (paddingLeft + (k % cols + 1) * c).toInt()
            val y = slotY(k)
            v.layout(x, y, x1, y + cellH)
        }
    }

    /** Lets [target] lift [cell]: at once on touch ([immediate], a grip) or after a long press. */
    fun bind(target: View, cell: View, immediate: Boolean) {
        var downX = 0f
        var downY = 0f
        var lifted = false
        var lastX = 0f
        var lastY = 0f
        val lift = Runnable {
            lifted = true
            begin(cell, lastX, lastY)
        }
        target.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (drag != null) return@setOnTouchListener false
                    downX = ev.rawX
                    downY = ev.rawY
                    lastX = downX
                    lastY = downY
                    lifted = false
                    if (immediate) {
                        lifted = true
                        begin(cell, downX, downY)
                    } else {
                        v.postDelayed(lift, 320L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    if (lifted) move(ev.rawX, ev.rawY)
                    else if (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop) v.removeCallbacks(lift)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(lift)
                    if (lifted) end() else if (ev.actionMasked == MotionEvent.ACTION_UP &&
                        abs(ev.rawX - downX) <= slop && abs(ev.rawY - downY) <= slop) v.performClick()
                    lifted = false
                    true
                }
                else -> true
            }
        }
    }

    private fun begin(cell: View, rx: Float, ry: Float) {
        if (drag != null) return
        drag = cell
        parent?.requestDisallowInterceptTouchEvent(true)
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        getLocationOnScreen(loc)
        grabX = rx - loc[0] - cell.left
        grabY = ry - loc[1] - cell.top
        cell.animate().cancel()
        cell.translationZ = 8f * d
        cell.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.9f).setDuration(120L).start()
    }

    private fun move(rx: Float, ry: Float) {
        val v = drag ?: return
        getLocationOnScreen(loc)
        val fx = rx - loc[0]
        val fy = ry - loc[1]
        v.translationX = fx - grabX - v.left
        v.translationY = fy - grabY - v.top
        val movable = order.size - fixedTail
        val c = cw
        val col = ((fx - paddingLeft) / c).toInt().coerceIn(0, cols - 1)
        val row = ((fy - paddingTop) / cellH).toInt().coerceAtLeast(0)
        val to = (row * cols + col).coerceIn(0, movable - 1)
        val from = order.indexOf(v)
        if (to == from || from < 0) return
        // remember where every other cell is drawn now, re-slot, then glide them from there
        val ox = FloatArray(order.size)
        val oy = FloatArray(order.size)
        for ((k, o) in order.withIndex()) {
            ox[k] = o.left + o.translationX
            oy[k] = o.top + o.translationY
        }
        val old = ArrayList(order)
        order.removeAt(from)
        order.add(to, v)
        placeAll()
        for ((k, o) in old.withIndex()) {
            if (o === v) continue
            o.animate().cancel()
            o.translationX = ox[k] - o.left
            o.translationY = oy[k] - o.top
            o.animate().translationX(0f).translationY(0f).setDuration(160L).start()
        }
        v.translationX = fx - grabX - v.left
        v.translationY = fy - grabY - v.top
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun end() {
        val v = drag ?: return
        drag = null
        parent?.requestDisallowInterceptTouchEvent(false)
        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(160L)
            .withEndAction {
                v.translationZ = 0f
                onOrder(order.subList(0, order.size - fixedTail).toList())
            }.start()
    }
}

/**
 * Live sketch of the APPS window at true scale (capped to fit): the quarter circle with the corner at the
 * view's bottom-right, the user's icons placed and turned by [AppRing], and the recent-apps ribbon as grey
 * squares. [reload] re-reads the prefs.
 */
class RingPreview(ctx: Context) : View(ctx) {
    var accent = Skin.TEXT
    private val sq = PixelMode.on(ctx)
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ip = Paint() // app icons: filtering off while pixelated (see reload)
    private val out = AppRing.Out(Prefs.MAX_APPS)
    private val rect = RectF()
    private var icons: List<Bitmap> = emptyList()
    private var ribbon = true
    private var iconDp = Prefs.APPS_ICON_DEF
    private var gapDp = Prefs.APPS_GAP_DEF
    private var openDp = Prefs.OPEN_DEF

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        reload()
    }

    fun reload() {
        val c = context
        iconDp = Prefs.appsIconDp(c)
        gapDp = Prefs.appsGapDp(c)
        ribbon = Prefs.flag(c, Prefs.KEY_APPS_RECENTS)
        openDp = Prefs.openDp(c)
        val pix = IconTheme.pixelated(c)
        ip.isFilterBitmap = !pix
        ip.isAntiAlias = !pix
        val list = ArrayList<Bitmap>()
        val px = (Prefs.APPS_ICON_MAX * d).toInt()
        for (cn: ComponentName in Prefs.apps(c)) {
            try {
                c.packageManager.getActivityInfo(cn, 0)
                list.add(IconTheme.icon(c, cn, px))
            } catch (e: Exception) {
            }
        }
        icons = list
        requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val avail = MeasureSpec.getSize(w)
        val side = min((openDp * d).toInt(), (avail * 0.8f).toInt())
        setMeasuredDimension(avail, side + (16 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val radPx = openDp * d
        val side = (height - 16 * d)
        val s = side / radPx
        // centred horizontally: the quarter's square sits in the middle of the row
        val ox = (width + side) / 2f
        val oy = side + 8 * d
        c.save()
        c.translate(ox, oy)
        c.scale(s, s)
        p.style = Paint.Style.FILL
        p.color = 0xFF000000.toInt()
        rect.set(-radPx, -radPx, radPx, radPx)
        c.drawArc(rect, 180f, 90f, true, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d / s
        p.color = Skin.alpha(accent, 0.6f)
        c.drawArc(rect, 180f, 90f, false, p)
        p.style = Paint.Style.FILL
        if (ribbon) {
            val rr = AppRing.ribbonR(radPx, d)
            val ir = AppRing.RIB_ICON_DP * d
            p.color = Skin.SURFACE_HI
            for (i in 0 until 5) {
                val a = 45f + (i - 2f) * 13f
                drawAt(c, rr, a) { c.drawPill(sq, -ir, -ir, ir, ir, ir * 0.3f, p) }
            }
        }
        val n = icons.size
        AppRing.compute(out, n, radPx, iconDp * d / 2f, gapDp * d, ribbon, d)
        AppRing.scale(out, Prefs.ringPct(context, Prefs.KEY_APPS_RING_IN), Prefs.ringPct(context, Prefs.KEY_APPS_RING_OUT), gapDp * d, d)
        val ir = out.itemR
        for (k in 0 until out.n) {
            val b = icons[k]
            drawAt(c, min(out.r[k], radPx - 6 * d - ir), out.ang[k]) {
                rect.set(-ir, -ir, ir, ir)
                c.drawBitmap(b, null, rect, ip)
            }
        }
        c.restore()
    }

    private inline fun drawAt(c: Canvas, r: Float, aDeg: Float, body: () -> Unit) {
        val a = Math.toRadians(aDeg.toDouble())
        c.save()
        c.translate(-r * cos(a).toFloat(), -r * sin(a).toFloat())
        c.rotate(aDeg - 90f)
        body()
        c.restore()
    }
}

/** A 48 x 56 dp icon button with an optional accent dot at the icon's top-right (something needs attention). */
class DotIcon(ctx: Context, res: Int, desc: String) : View(ctx) {
    var tint = Skin.TEXT_2
        set(v) { field = v; invalidate() }
    var dotColor = Skin.TEXT
        set(v) { field = v; invalidate() }
    var dot = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val sq = PixelMode.on(ctx)
    private val icon: Drawable = PixelMode.wrapIcon(ctx, ctx.getDrawable(res)!!.mutate())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = !sq }

    init {
        isClickable = true
        contentDescription = desc
        background = Skin.ripple(ctx)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((48 * d).toInt(), (56 * d).toInt())

    override fun onDraw(c: Canvas) {
        val s = (24 * d).toInt()
        val l = (width - s) / 2
        val t = (height - s) / 2
        icon.setBounds(l, t, l + s, t + s)
        icon.setTint(tint)
        icon.draw(c)
        if (dot) {
            p.color = dotColor
            c.drawDisc(sq, l + s - 1 * d, t + 2 * d, if (sq) 3 * d else 4 * d, p)
        }
    }
}
