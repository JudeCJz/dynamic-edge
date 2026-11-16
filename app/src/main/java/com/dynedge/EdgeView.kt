package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Region
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.SparseArray
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


/**
 * The Dyn Edge host view: shape, springs, paging, section layers and touch dispatch.
 * The view fills its window and everything is anchored to the bottom-right corner.
 *
 * The open state is a true quarter circle of radius R centred on the corner. Each window (WinQuick, WinMedia,
 * WinClipboard, WinApps, WinTimer, WinNotifications, WinDevice) lays out, draws and hit-tests its own
 * content in polar coordinates around the corner; the list and order come from WindowsConfig. Windows are
 * rotated around the corner when paging and cached as RenderNode layers (re-recorded only when dirty).
 * To add a window: subclass Win, register it in make(), add its id to WindowsConfig.
 * When closed, the bean can morph into a live activity (LiveBus): call, timer, progress, charging, track change.
 */
class EdgeView(ctx: Context) : View(ctx) {

    interface Host {
        fun onSettled(expanded: Boolean)
        /** The overlay starts (true) or stops (false) animating / being open: vote for 90 Hz meanwhile. */
        fun onFast(on: Boolean) {}
        fun onRegionChanged(region: Region)
        /** Makes the overlay window focusable (true) or not focusable (false); used for a short clipboard capture. */
        fun setWindowFocusable(on: Boolean)
        /** Shows the small floating clipboard popup (ClipPopup); the edge never launches ClipboardActivity. */
        fun openClipPopup()
        /** Runs an AccessibilityService global action (long-press actions). */
        fun performAction(action: Int): Boolean = false
    }

    var host: Host? = null

    // ---------------------------------------------------------------- metrics
    // read live so a density / font-scale change is picked up (see onDensityChanged)
    private val density: Float get() = resources.displayMetrics.density
    internal fun dp(v: Float) = v * density
    internal fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var idle = dp(Prefs.idleDp(ctx))
    internal var rad = dp(Prefs.openDp(ctx)) // R, radius of the open quarter circle
        private set
    private var tipPx = 0f
    private var flarePx = dp(Prefs.flareDp(ctx)) // edge fillet radius (open size)

    /** Side of the invisible idle grab square (Prefs.KEY_GRAB), dp; touchable while closed, never drawn. */
    private var grabDp = Prefs.grabDp(ctx)
    val grabPx: Int get() = ceil(dp(grabDp)).toInt()

    val idleSizePx: Int get() = ceil(idle).toInt()
    val maxOpenWidthPx: Int get() = ceil(rad + flarePx).toInt()
    val maxOpenHeightPx: Int get() = ceil(rad + flarePx).toInt()

    // ---------------------------------------------------------------- state
    private val wS = Spring(idle, 420f, 0.74f)
    private val hS = Spring(idle, 300f, 0.78f)
    private val pageS = PageAnim()
    private val press = Spring(1f, 700f, 0.75f)

    private var page = 0
    private var lastPageId = "quick"
    private var openPicked = false
    private var userPaged = false
    private var pageStart = 0f
    private var dragShift = 0f

    private var progress = 0f
    private var contentAlpha = 0f
    private var collapsing = true
    private var expandedTarget = false
    private var settledExpanded = false
    private var regionExpanded = false
    private var regionClosed = idle
    private var hidden = false
    internal var sourcesStarted = false
        private set

    val isExpanded: Boolean get() = expandedTarget

    /** The accessibility service: needed by global-action tiles and by clipboard paste. */
    internal var service: AccessibilityService? = null
        private set

    fun setService(s: AccessibilityService?) {
        service = s
        (winMap["quick"] as? WinQuick)?.setService(s)
    }

    // ---------------------------------------------------------------- windows and sources
    private val winMap = HashMap<String, Win>()
    private var order = ArrayList<Win>()      // enabled windows in display order
    private var nWin = 1
    private var shownWin: Win? = null
    internal val arcs = Arcs(ctx) { onArcsChanged() }
    private var arcsOn = false

    private fun make(id: String): Win? = when (id) {
        "quick" -> WinQuick(this)
        "media" -> WinMedia(this)
        "clipboard" -> WinClipboard(this)
        "apps" -> WinApps(this)
        "timer" -> WinTimer(this)
        "notifications" -> WinNotifications(this)
        "device" -> WinDevice(this)
        "screenshots" -> WinScreenshots(this)
        else -> null
    }

    private fun indexOfId(id: String): Int {
        for (i in order.indices) if (order[i].id == id) return i
        return -1
    }

    private val mediaWin: WinMedia? get() = order.firstOrNull { it is WinMedia } as? WinMedia

    private fun startWin(w: Win) {
        if (w.started) return
        w.started = true
        w.start()
    }

    private fun stopWin(w: Win) {
        if (!w.started) return
        w.started = false
        w.stop()
    }

    private fun syncArcs() {
        val need = sourcesStarted && order.any { it is WinQuick && it.started }
        if (need == arcsOn) return
        arcsOn = need
        if (need) arcs.start() else arcs.stop()
    }

    private fun onArcsChanged() {
        if (!sourcesStarted) return
        for (w in order) w.onArcs()
        invalidate()
    }

    /** The visible, settled window gets onShown(true); everything else false. */
    private fun syncShown() {
        val want: Win? = if (expandedTarget && settledExpanded && !hidden && screenOn) order.getOrNull(page) else null
        if (want === shownWin) return
        shownWin?.onShown(false)
        shownWin = want
        want?.onShown(true)
    }

    /**
     * Windows that need live sources: MEDIA always (its playing state picks the opening window), every other
     * window only when it is the current page, the page being moved to, or the neighbour of either, so a swipe
     * always lands on a window that is already running.
     */
    private fun wantStart(w: Win): Boolean {
        if (w is WinMedia) return true
        val c = Math.round(pageS.value).coerceIn(0, max(nWin - 1, 0))
        return w.index >= min(page, c) - 1 && w.index <= max(page, c) + 1
    }

    private fun syncSources() {
        if (sourcesStarted) {
            for (w in order) if (wantStart(w)) startWin(w) else stopWin(w)
        }
        syncArcs()
    }

    /** Re-reads WindowsConfig: keeps the current window if still enabled, else the nearest surviving one. */
    internal fun reloadWindows() {
        val ids = WindowsConfig.enabled(context)
        val oldOrder = order
        val oldPage = page
        val next = ArrayList<Win>(ids.size)
        for (id in ids) {
            var w = winMap[id]
            if (w == null) {
                w = make(id)
                if (w == null) {
                    android.util.Log.w("DynEdge", "unknown window id: $id")
                    continue
                }
                winMap[id] = w
            }
            next.add(w)
        }
        if (next.isEmpty()) next.add(winMap.getOrPut("quick") { WinQuick(this) })
        for (w in oldOrder) {
            if (w !in next) {
                if (shownWin === w) {
                    w.onShown(false)
                    shownWin = null
                }
                stopWin(w)
                w.node?.discardDisplayList()
                w.node = null
                w.dirty = true
            }
        }
        order = next
        nWin = next.size
        for (i in next.indices) next[i].index = i
        (winMap["quick"] as? WinQuick)?.setService(service)
        val cur = oldOrder.getOrNull(oldPage)
        var wi = if (cur != null) indexOfId(cur.id) else -1
        if (wi < 0 && cur != null) {
            // the current window was disabled: nearest surviving neighbour in the old order
            var d = 1
            while (wi < 0 && d <= oldOrder.size) {
                val lo = oldOrder.getOrNull(oldPage - d)
                val hi = oldOrder.getOrNull(oldPage + d)
                if (lo != null) wi = indexOfId(lo.id)
                if (wi < 0 && hi != null) wi = indexOfId(hi.id)
                d++
            }
        }
        if (wi < 0) wi = indexOfId(lastPageId)
        if (wi < 0) wi = indexOfId("quick")
        if (wi < 0) wi = 0
        page = wi
        lastPageId = order[page].id
        pageS.snapTo(page.toFloat())
        layoutAll()
        syncSources()
        syncShown()
        invalidate()
    }

    internal fun relayout() {
        layoutAll()
        invalidate()
    }

    /** Animates to the window with this id; false if it is not enabled. */
    internal fun jumpTo(id: String): Boolean {
        val i = indexOfId(id)
        if (i < 0) return false
        userPaged = true
        goToPage(i, 0f)
        return true
    }
    private val path = Path()
    private val regionPath = Path()
    /** The idle touch area in the bean's own shape, unmirrored (drawn dashed while the Touch area slider moves). */
    private val touchPath = Path()
    private val arcRect = RectF()
    private val earRect = RectF()
    private val region = Region()
    private val clipRegion = Region()

    // Looks (from Prefs, see applyLooksNow). Shaders are immutable at radius GR; the shape path is drawn in
    // gradient-unit space under a canvas scale (never mutate a shader's local matrix per frame).
    internal var accent = C_RED
    private var gradientPct = 35
    private var glowOn = true
    private var hapticsOn = true
    private var stiffScale = 1f
    private var openDamp = 0.8f
    private var idleId = "glyph"
    private var baseColor = Color.BLACK

    // Ink: everything drawn on the body reads these (white on a dark body, black on a light one). Set by applyInk().
    /** Primary content colour on the body. */
    internal var ink = Color.WHITE
        private set
    /** Content colour on an [ink]-coloured surface (the flipped ink). */
    internal var inkOn = Color.BLACK
        private set
    /** Placeholder disc fill (unfilled controls), a little toward the ink. */
    internal var cFill = DEF_FILL
        private set
    /** Slider tracks. */
    internal var cTrack = DEF_TRACK
        private set
    /** Dim secondary text. */
    internal var cGrey = DEF_GREY
        private set
    internal var lightBody = false
        private set

    // Placement: floating island, distance along the bottom edge, left / right side (see applyPlacement)
    private var floatOn = false
    /** The edge is on the left side: onDraw mirrors the canvas, touch coordinates are mirrored back. */
    internal var mirror = false
        private set
    /** Window offset from the side edge / the bottom edge in px (the service applies it as LayoutParams x / y). */
    internal var placeX = 0
        private set
    internal var placeY = 0
        private set
    private var geomVer = 0
    private var lastGeomVer = -1
    // resolved shape parameters for the current flare (see resolveGeom)
    private var gFlB = 0f
    private var gFlS = 0f
    private var gRhoB = 0f
    private var gRhoS = 0f
    private var gTip = 0f
    /** Floating corner roundness 0..1 of the largest tip (0.4 r), or -1 = the fixed FLOAT_TIP_DP. */
    private var floatCornerFrac = -1f
    private val mirrorMat = Matrix()
    private val pathMeasure = PathMeasure()

    // Long-press hide: the service's hide (IME, keyguard, pause) and the user's timed hide are separate
    private var svcHidden = false
    private var userHide = false
    private val unhideRunnable = Runnable {
        userHide = false
        applyHidden()
        // spring back: pop in from a smaller bean, the press spring settles it
        press.value = 0.55f
        press.velocity = 0f
        press.target = 1f
        requestFrames()
    }

    /**
     * Pixel mode hook (the lead wires it later). When non-null, onDraw hands the canvas and the whole drawing to
     * it, so it can render the drawing into a low-resolution buffer and scale it up. No-op while null.
     */
    internal var pixelHook: ((Canvas, (Canvas) -> Unit) -> Unit)? = null
    private val drawAllFn: (Canvas) -> Unit = { drawAll(it) }
    // pixel mode: the whole overlay is drawn into a low-res bitmap and upscaled without filtering
    private val pixelLayer = PixelLayer()
    private var pixelCell = 3f
    private val pixelFn: (Canvas, (Canvas) -> Unit) -> Unit = { c, block -> pixelLayer.draw(c, width, height, pixelCell, block) }
    private var grad1 = 0
    private var grad2 = 0
    private var swipeCommitDeg = 24f
    private var swipeFlingDps = 330f
    private var swipeMinFlingDeg = 6f
    private var pageFollowDeg = 48f   // finger degrees about the corner per page while dragging (bigger = calmer)
    private var pageSlop = 0f
    // the swipe sensitivity is read live, independent of the service's look reload
    private val sensListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_SWIPE_SENS) applySwipeSens()
    }
    private var backdropOn = false
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8E8EC") }
    /** Shows the invisible idle grab square on the overlay, so the touch area is visible while adjusting it. */
    private var touchShow = false
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var fillId = "flat"
    private var gradOn = true
    private var gradientActive = true
    private var vizId = "spectrum"
    private var vizOn = true
    private var liveOn = true
    private var beatOn = true
    private var musicPlaying = false
    private var beat = 0f      // smoothed overall energy (drives the bean bounce and the glow)
    private var bassS = 0f     // smoothed bass: fill core size / brightness / pattern pulse
    private var midS = 0f      // smoothed mids: slow drift of the fill core
    private var highS = 0f     // smoothed highs: rim sparkle
    private var driftPhase = 0f
    private var driftX = 0f
    private var driftY = 0f
    private var beatAmount = 0.5f
    private var kickEnv = 0f    // beat swell envelope 0..1, decays smoothly
    private var opacityPct = 100
    private var lastRimW = -1f
    private val pulseS = Spring(1f, 150f, 0.78f)  // soft + well damped: fluid swell, no snap
    private val looks = Looks()
    private val layerPaint = Paint()
    private var patExtent = 0f
    private val exclRect = Rect()
    private val exclList = ArrayList<Rect>(1).apply { add(exclRect) }
    private var exclSet = false
    private var exclL = -1
    private var exclT = -1
    private var shapeShader: Shader = buildShapeShader()
    private var glowShader: Shader = buildGlowShader()
    private var coreShader: Shader = buildCoreShader()

    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t + 0.5f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t + 0.5f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t + 0.5f).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun buildShapeShader(): Shader {
        if (pixelHook != null) {
            return PixelBands.radial(GR, EdgePaint.stops(baseColor, grad1, grad2, gradientPct, accent), EdgePaint.POSITIONS)
        }
        return EdgePaint.shapeShader(baseColor, grad1, grad2, gradientPct, accent, GR)
    }

    private fun buildGlowShader(): Shader {
        if (pixelHook != null) {
            val rgb = accent and 0xFFFFFF
            return PixelBands.radial(
                GR, intArrayOf((0x88 shl 24) or rgb, (0x30 shl 24) or rgb, rgb), floatArrayOf(0f, 0.38f, 0.85f)
            )
        }
        return EdgePaint.glowShader(accent, 1f, GR)
    }
    private fun buildCoreShader(): Shader {
        val rgb = mixColor(accent or (0xFF shl 24), Color.WHITE, 0.12f) and 0xFFFFFF
        val cols = intArrayOf((0xC0 shl 24) or rgb, (0x58 shl 24) or rgb, (0x16 shl 24) or rgb, rgb)
        val pos = floatArrayOf(0f, 0.4f, 0.75f, 1f)
        if (pixelHook != null) return PixelBands.radial(GR, cols, pos)
        return android.graphics.RadialGradient(0f, 0f, GR, cols, pos, Shader.TileMode.CLAMP)
    }
    private val unitPath = Path()
    private var gradScale = 1f
    private val gradMatrix = Matrix()
    // ---- blur (Glass.kt): the real cross-window blur behind the body; with it on, Opacity fades only the body
    // (paint alpha) instead of the whole view, because a view layer would hide the blur
    private val backBlur = GlassBlur(this)
    private var blurPx = 0
    private val blurLive: Boolean get() = blurPx > 0 && backBlur.active && !hidden

    private fun applyBlur(ctx: Context) {
        blurPx = (dp(Prefs.blur(ctx) * 0.5f)).toInt()
        if (blurPx > 0) {
            if (viewAttached) backBlur.ensure(ctx)
        } else {
            backBlur.release()
        }
        applyOpacity(Prefs.opacityPct(ctx))
    }

    private fun updateBlur() {
        val dw = drawW; val dh = drawH
        val r = min(dw, dh)
        val tip = tipFor(r)
        val w = width; val h = height
        val top = (h - dh).toInt()
        if (mirror) backBlur.update(0, top, dw.toInt(), h, 0f, r, 0f, tip, blurPx)
        else backBlur.update((w - dw).toInt(), top, w, h, r, 0f, tip, 0f, blurPx)
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = shapeShader
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = glowShader
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = coreShader
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.BUTT
    }
    internal val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = C_RED
    }
    internal val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    internal val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rimPath = Path()
    private var glow = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val liveTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val whiteIcons = SparseArray<Drawable>()
    private val blackIcons = SparseArray<Drawable>()
    private var resourcesReady = false
    private var lpX = 0f
    private var lpY = 0f
    private val sb = StringBuilder(8)

    internal val fillP: Paint get() = fill
    internal val ovalScratch = RectF()
    private val xOval = RectF()
    private var xCx = 0f
    private var xCy = 0f
    private var xRad = 0f // radius of the page-bar circle around the X glyph centre
    private var xHit = 0f
    private var lastFl = -1f
    private var drawW = 0f
    private var drawH = 0f
    private var beanScale = 1f
    private var downPx = 0f
    private var downPy = 0f
    private var downAng = 0f
    private var dAng = 0f
    private var dAngBase = Float.NaN

    // ---------------------------------------------------------------- touch state
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var vt: VelocityTracker? = null
    private var gestureActive = false
    private var startMode = MODE_IDLE
    private var dragMode = 0 // 0 none, 1 expanding from idle, 2 shrinking (close), 3 paging
    private var downRawX = 0f
    private var downRawY = 0f
    private var baseW = 0f
    private var baseH = 0f
    private var moved = false
    /** Pressed item: local id inside [pressedWin], or ID_X (pressedWin == null) for the close button. */
    internal var pressed = -1
        private set
    internal var pressedWin: Win? = null
        private set
    private var hitWin: Win? = null
    private var captureWin: Win? = null
    private var longFired = false
    private var lastX = 0f
    private var lastY = 0f

    private fun setPressed(w: Win?, item: Int) {
        if (pressed == item && pressedWin === w) return
        pressedWin?.dirty = true
        w?.dirty = true
        pressed = item
        pressedWin = w
    }

    private fun clearPressed() = setPressed(null, -1)

    private val longPressRunnable = Runnable { onLongPress() }

    // ---------------------------------------------------------------- live activities
    private var liveCur: LiveActivity? = null
    private var liveSec: LiveActivity? = null
    private var ringPulse = false // the own timer is ringing: the bean pulse spring is shown even with Beat off
    private var liveTitleRaw: String? = null
    private var liveTitleShown: String = ""
    private var liveTitleOff = 0f
    private var liveTitleLen = 0f
    private var liveCallLen = 0f
    private var liveTitleKey: String? = null
    private var liveBase = 0L
    private var liveTitleCall = false
    private var liveTotalMs = 0L
    private val liveTitlePath = Path()
    private val liveTitlePathCall = Path()
    private val liveOval = RectF()
    private val liveIconRect = Rect()
    private val liveAnsRect = Rect()
    private val liveDecRect = Rect()
    private var liveAnsX = 0f
    private var liveAnsY = 0f
    private var liveDecX = 0f
    private var liveDecY = 0f
    private var liveDotR = 0f
    private var lastLiveTrack: String? = null
    private val transientRunnables = HashMap<String, Runnable>()

    private val liveSize: Float get() = idle * LIVE_SCALE

    // ---- chips (live chip text, idle info), double-tap back, fade when idle: see EdgeExtras
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val chipFm = Paint.FontMetrics()
    private val chipFitLive = ChipFit()
    private val chipFitCur = ChipFit()
    private val chipFitPrev = ChipFit()
    private var doubleTapBack = true
    private var infoOn = false
    private var infoShowing = false
    private var infoCur = ""
    private var infoPrev = ""
    private var infoT0 = 0L
    private var infoSource: IdleInfo? = null
    private var fadeOn = false
    private var fadeFrom = 1f
    private var fadeTo = 1f
    private var fadeT0 = 0L
    private var tapPending = false
    private var tapPx = 0f
    private var tapPy = 0f

    private val infoRunnable = object : Runnable {
        override fun run() {
            if (!infoWanted()) {
                infoShowing = false
                invalidateBean()
                return
            }
            showNextInfo()
            postDelayed(this, EdgeExtras.INFO_PERIOD_MS)
        }
    }
    private val fadeRunnable = Runnable {
        if (fadeOn && fadeIdleNow()) {
            fadeFrom = fadeLevel()
            fadeTo = EdgeExtras.FADE_LEVEL
            fadeT0 = SystemClock.uptimeMillis()
            invalidateBean()
        } else {
            fadeArm()
        }
    }
    private val tapRunnable = Runnable {
        tapPending = false
        if (!expandedTarget && !gestureActive) onBeanTap(tapPx, tapPy)
    }

    private fun infoWanted(): Boolean =
        infoOn && viewAttached && screenOn && !hidden && !svcHidden && !liveWanted && !musicPlaying

    private fun showNextInfo() {
        val src = infoSource ?: IdleInfo(context).also { infoSource = it }
        val t = src.next()
        if (infoShowing) {
            infoPrev = infoCur
        } else {
            infoPrev = ""
        }
        infoCur = t
        infoT0 = SystemClock.uptimeMillis()
        infoShowing = true
        invalidateBean()
    }

    private fun syncInfo() {
        removeCallbacks(infoRunnable)
        if (infoWanted()) {
            if (!infoShowing) {
                infoSource?.reset()
                showNextInfo()
            }
            postDelayed(infoRunnable, EdgeExtras.INFO_PERIOD_MS)
        } else if (infoShowing) {
            infoShowing = false
            invalidateBean()
        }
    }

    private fun fadeIdleNow(): Boolean =
        !liveWanted && !expandedTarget && !gestureActive && dragMode == 0 && progress < 0.01f

    private fun fadeLevel(): Float {
        if (!fadeOn) return 1f
        val t = ((SystemClock.uptimeMillis() - fadeT0).toFloat() / EdgeExtras.FADE_MS).coerceIn(0f, 1f)
        return fadeFrom + (fadeTo - fadeFrom) * t
    }

    /** Back to full alpha at once (touch, live activity, panel). */
    private fun fadeWake() {
        removeCallbacks(fadeRunnable)
        if (fadeTo != 1f || fadeFrom != 1f) {
            fadeFrom = 1f
            fadeTo = 1f
            invalidateBean()
        }
    }

    /** Restarts the 5 s countdown to the faded state. */
    private fun fadeArm() {
        removeCallbacks(fadeRunnable)
        if (fadeOn && viewAttached && screenOn) postDelayed(fadeRunnable, EdgeExtras.FADE_DELAY_MS)
    }

    private fun readExtraPrefs() {
        val p = Prefs.prefs(context)
        doubleTapBack = p.getBoolean(EdgeExtras.KEY_DOUBLE_TAP_BACK, true)
        infoOn = p.getBoolean(EdgeExtras.KEY_IDLE_INFO, false)
        fadeOn = p.getBoolean(EdgeExtras.KEY_FADE_IDLE, false)
        chipFitLive.reset(); chipFitCur.reset(); chipFitPrev.reset()
        chipPaint.typeface = Typeface.create(liveTitlePaint.typeface, Typeface.BOLD)
        if (!doubleTapBack) {
            removeCallbacks(tapRunnable)
            tapPending = false
        }
        if (fadeOn) fadeArm() else fadeWake()
        syncInfo()
    }

    /** Draws a centred chip at ([cx],[cy]); text shrinks until it fits [maxW]. */
    private fun drawChip(c: Canvas, fit: ChipFit, s: String, cx: Float, cy: Float, base: Float, maxW: Float, alpha: Int) {
        if (s.isEmpty() || alpha <= 0) return
        chipPaint.textSize = fit.fit(chipPaint, s, base, maxW)
        chipPaint.color = ink
        chipPaint.alpha = alpha
        chipPaint.getFontMetrics(chipFm)
        text(c, s, cx, cy - (chipFm.ascent + chipFm.descent) / 2f, chipPaint)
    }

    /** The first tap of a possible double tap on the closed bean. */
    private fun beanTapGate(x: Float, y: Float) {
        if (tapPending) {
            removeCallbacks(tapRunnable)
            tapPending = false
            switchToPreviousApp()
            return
        }
        if (!doubleTapBack || expandedTarget || tapActsNow(x, y)) {
            onBeanTap(x, y)
            return
        }
        tapPending = true
        tapPx = x
        tapPy = y
        postDelayed(tapRunnable, min(ViewConfiguration.getDoubleTapTimeout().toLong(), EdgeExtras.DOUBLE_TAP_MAX_MS))
    }

    /** True when the live activity has its own tap action that must run on the first tap. */
    private fun tapActsNow(x: Float, y: Float): Boolean {
        val a = liveCur
        if (!liveWanted || a == null) return false
        if (LiveTest.owns(a.key) || a.key == TimerControl.KEY_DONE) return true
        if (liveDotHit(x, y) != 0) return true
        return (a.kind == LiveActivity.Kind.CALL || a.tapOpens) && a.contentIntent != null
    }

    private fun switchToPreviousApp() {
        val ctx = context.applicationContext
        Thread {
            val pkg = try { EdgeExtras.previousApp(ctx) } catch (e: Exception) { null }
            post {
                if (pkg != null && RecentApps.launch(context, pkg)) {
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    val h = host
                    if (h != null) {
                        h.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        postDelayed({ h.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 150L)
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
        }.start()
    }

    private val liveWanted: Boolean get() = liveOn && liveCur != null && screenOn && !hidden

    /** Larger "call" size of the closed bean while a call is ringing (about 2.7x idle, always inside the open panel). */
    private val callSize: Float get() = min(max(liveSize * 1.15f, min(max(idle * 2.7f, dp(132f)), rad * 0.8f)), rad * 0.9f)

    /** A call that is ringing (it has an answer action): the closed bean shows the big answer / decline view. */
    private val ringing: Boolean
        get() {
            val a = liveCur
            return a != null && a.kind == LiveActivity.Kind.CALL && a.answer != null && liveWanted
        }

    /**
     * The closed edge only grows for a ringing call or a timer that has gone off. Every other live activity (track
     * change, charging, download, running timer, ongoing call, copied) is drawn compact inside the idle size, so
     * the edge never pops bigger for a moment and never takes more touch space than usual.
     */
    private val liveBig: Boolean
        get() = liveWanted && (ringing || liveCur?.key == TimerControl.KEY_DONE)

    private fun closedSize(): Float = if (!liveWanted) idle else if (ringing) callSize else if (liveBig) liveSize else idle

    private val callNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val callPathName = Path()
    private var callNameRaw: String? = null
    private var callNameShown = ""
    private var callNameOff = 0f
    private var callNameLen = 0f
    private var callBtnR = 0f
    private var callHitR = 0f
    private var callAnsX = 0f
    private var callAnsY = 0f
    private var callDecX = 0f
    private var callDecY = 0f
    private val callAnsRect = Rect()
    private val callDecRect = Rect()

    private val liveTickRunnable = object : Runnable {
        override fun run() {
            val a = liveCur
            if (!liveWanted || expandedTarget || a == null) return
            if (a.key == TimerControl.KEY_DONE) {
                // the own timer is ringing: the bean pulses until it is stopped (tap the bean) or the alarm ends
                pulseS.value = 1.12f
                pulseS.velocity = 0f
                updateViz()
                invalidateBean()
                postDelayed(this, 900L)
                return
            }
            if (a.kind == LiveActivity.Kind.TIMER && a.countDown && a.chronoBase != 0L &&
                a.chronoBase - System.currentTimeMillis() <= 0L
            ) {
                if (a.key == TimerControl.KEY) {
                    // our own timer reached zero: rings (alarm + pulsing bean); the alarm receiver may also do this
                    TimerControl.finish(context)
                    if (TimerControl.remainingMs(context) > 0L) TimerControl.restore(context) // clocks disagreed: re-anchor
                } else {
                    LiveBus.remove(a.key) // a linked clock-app countdown that ran out: never show a stale 0:00
                }
                updateViz()
                return
            }
            if (ringing) {
                invalidateBean() // the pulsing ring of the ringing call view
                postDelayed(this, 16L)
                return
            }
            if (a.chronoBase != 0L) rebuildLiveTitle()
            invalidateBean()
            val next = if (a.progress == -2f) 40L else if (a.chronoBase != 0L) chronoDelay(a) else 0L
            if (next > 0L) postDelayed(this, next)
        }
    }
    // ---------------------------------------------------------------- frame loop
    private var framePosted = false
    private var lastFrameNanos = 0L
    private val frameCb = Choreographer.FrameCallback { t -> doFrame(t) }

    // ---------------------------------------------------------------- idle designs / visualizer
    var holdOpen = false // set by the service while the settings screen previews the open size
    private var screenOn = false
    private var viewAttached = false
    private var mediaWatch: IdleMediaWatch? = null
    private var idlePlaying = false
    private val visualizer = IdleVisualizer(ctx)
    private var vizRunning = false
    private var vizPosted = false
    private var lastVizNanos = 0L
    private var beanPx = 0
    private var looksPosted = false
    private val vizCb = Choreographer.FrameCallback { t -> vizFrame(t) }
    private val looksRunnable = Runnable {
        looksPosted = false
        applyLooksNow()
    }

    // ================================================================ public API

    fun expand() = startExpand(false, 0f, 0f)

    fun collapse() = startCollapse(false, 0f, 0f)

    private fun cancelCapture() {
        val cw = captureWin ?: return
        captureWin = null
        cw.dragEnd(true)
    }

    fun collapseImmediately() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCb)
            framePosted = false
        }
        lastFrameNanos = 0L
        cancelItemLongPress()
        removeCallbacks(liveTickRunnable)
        releaseTracker()
        gestureActive = false
        dragMode = 0
        cancelCapture()
        clearPressed()
        expandedTarget = false
        collapsing = true
        settledExpanded = false
        syncShown()
        openPicked = false
        val cs = closedSize()
        wS.snapTo(cs)
        hS.snapTo(cs)
        pageS.snapTo(page.toFloat())
        press.snapTo(1f)
        progress = 0f
        contentAlpha = 0f
        stopSources()
        regionExpanded = false
        regionClosed = cs
        publishRegion()
        host?.onSettled(false)
        updateViz()
        syncLiveTick()
        invalidate()
    }

    /**
     * Applies new idle size / open radius (dp) from the settings app. Keeps the current state: an open
     * widget stays open at the new size (springs snapped), a closed one stays closed.
     */
    fun applySizes(idleDp: Float, openDp: Float) {
        if (gestureActive || dragMode != 0) {
            cancelItemLongPress()
            releaseTracker()
            gestureActive = false
            dragMode = 0
            cancelCapture()
            clearPressed()
        }
        idle = dp(idleDp.coerceIn(32f, 80f))
        grabDp = Prefs.grabDp(context)
        lastDw = -1f
        rad = dp(openDp.coerceIn(170f, 300f))
        layoutAll()
        val cs = closedSize()
        if (expandedTarget) {
            wS.snapTo(rad)
            hS.snapTo(rad)
        } else {
            wS.snapTo(cs)
            hS.snapTo(cs)
            regionClosed = cs
        }
        wS.target = if (expandedTarget) rad else cs
        hS.target = wS.target
        updateDerived(0f)
        publishRegion()
        updateViz()
        invalidate()
    }

    /** Re-reads the edge flare radius (Prefs.KEY_FLARE); the service then resizes the window. */
    fun applyFlare() {
        flarePx = dp(Prefs.flareDp(context))
        lastDw = -1f
        layoutAll()
        publishRegion()
        invalidate()
    }

    /**
     * Re-reads the placement prefs (float, gap, position along the bottom edge, side). The service then moves the
     * window ([placeX] / [placeY], gravity) and re-reads the tip radius of the matching display corner.
     */
    fun applyPlacement() {
        val c = context
        floatOn = Prefs.floatOn(c)
        floatCornerFrac = Prefs.floatCorner(c).let { if (it < 0) -1f else it / 100f }
        mirror = Prefs.sideLeft(c)
        Geo.mirror = mirror
        placeX = placeXFor(c)
        placeY = placeYFor(c)
        geomVer++
        lastDw = -1f
        exclL = -1 // forces updateExclusion to recompute (or clear) the gesture exclusion for the new side
        layoutAll()
        publishRegion()
        invalidate()
    }

    /** Re-reads the looks and feel prefs (coalesced to one pass per frame). */
    fun applyLooks() {
        if (looksPosted) return
        looksPosted = true
        postOnAnimation(looksRunnable)
    }

    private var winPrefsPosted = false
    private val winPrefsRunnable = Runnable {
        winPrefsPosted = false
        for (w in order) w.onPrefs()
        layoutAll()
        invalidate()
    }

    /** A per-window option (Prefs.WIN_PREFIX keys), the app list or the icon style changed: re-lay the windows out. */
    fun reloadWinPrefs() {
        if (winPrefsPosted) return
        winPrefsPosted = true
        postOnAnimation(winPrefsRunnable)
    }

    /** Re-reads the QUICK layout (QuickConfig.KEY changed). */
    fun reloadQuick() {
        (winMap["quick"] as? WinQuick)?.reload()
    }

    fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        syncIdleServices()
        onLiveChanged()
        if (fadeOn) fadeArm()
        syncShown()
    }

    fun setTipRadius(px: Float) {
        tipPx = px
        publishRegion()
        invalidate()
    }

    fun setHidden(h: Boolean) {
        svcHidden = h
        applyHidden()
        syncInfo()
    }

    /** Long-press on the closed bean: it fades out for [Prefs.hideSeconds], then springs back. */
    private fun hideForAwhile() {
        removeCallbacks(unhideRunnable)
        userHide = true
        applyHidden()
        postDelayed(unhideRunnable, Prefs.hideSeconds(context) * 1000L)
    }

    /** Runs the user's long-press action ([Prefs.longAction]) for the closed bean. */
    private fun runLongAction(act: String) {
        val c = context
        when (act) {
            Prefs.LA_HIDE -> hideForAwhile()
            Prefs.LA_SETTINGS -> try {
                c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
            Prefs.LA_NOTIFICATIONS -> host?.performAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            Prefs.LA_QUICK_SETTINGS -> host?.performAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            Prefs.LA_RECENTS -> host?.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            Prefs.LA_LOCK -> host?.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            Prefs.LA_SCREENSHOT -> host?.performAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            Prefs.LA_POWER -> host?.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            Prefs.LA_TORCH -> toggleTorchOnce()
            Prefs.LA_ASSISTANT -> AssistControl.launch(c)
            Prefs.LA_TILE -> runLongTile(Prefs.longTile(c))
        }
    }

    private var longTiles: Tiles? = null

    /** Runs one Quick tile from the long press (same routing as tapping it in the QUICK window). */
    private fun runLongTile(id: String) {
        if (id.isEmpty()) return
        val t = longTiles ?: Tiles(context) {}.also { longTiles = it }
        t.service = service
        if (Tiles.needsCollapseDelay(id)) postDelayed({ t.perform(id) }, 350L) else t.perform(id)
    }

    /** Flips the torch the way the QUICK torch tile does (CameraManager.setTorchMode on the flash camera). */
    private fun toggleTorchOnce() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        var id: String? = null
        try {
            for (cid in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(cid)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    id = cid
                    break
                }
                if (id == null) id = cid
            }
        } catch (e: Exception) {
            return
        }
        val torch = id ?: return
        // the callback reports the current state once on registration: flip it, then unregister
        val cb = object : CameraManager.TorchCallback() {
            private var done = false
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (done || cameraId != torch) return
                done = true
                try {
                    cm.setTorchMode(torch, !enabled)
                } catch (e: Exception) {
                }
                try {
                    cm.unregisterTorchCallback(this)
                } catch (e: Exception) {
                }
            }
        }
        try {
            cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
        }
    }

    private fun applyHidden() {
        val h = svcHidden || userHide
        if (hidden == h) return
        hidden = h
        // never let an older fade finish after this one: cancel it, then fade (or snap when not attached yet)
        animate().cancel()
        if (h) collapseImmediately()
        val target = if (h) 0f else 1f
        if (isAttachedToWindow) animate().alpha(target).setDuration(150L).start() else alpha = target
        publishRegion()
        updateViz()
        onLiveChanged()
    }

    fun currentRegion(): Region = region

    /** Stops listeners and callbacks; call before removing the view for good. */
    fun release() {
        backBlur.release()
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCb)
            framePosted = false
        }
        stopVizLoop()
        cancelItemLongPress()
        removeCallbacks(liveTickRunnable)
        removeCallbacks(captureTimeout)
        removeCallbacks(unhideRunnable)
        captureWanted = false
        for (r in transientRunnables.values) removeCallbacks(r)
        transientRunnables.clear()
        releaseTracker()
        expandedTarget = false
        settledExpanded = false
        syncShown()
        stopSources()
        // final release: every window stops and its RenderNode is dropped, not just the enabled ones
        for (w in winMap.values) {
            stopWin(w)
            w.node?.discardDisplayList()
            w.node = null
            w.dirty = true
        }
        syncArcs()
        removeCallbacks(looksRunnable)
        screenOn = false
        syncIdleServices()
        mediaWatch?.stop()
        mediaWatch = null
        visualizer.stop()
        setClipListening(false)
        LiveBus.onChange = null
        service = null
        (winMap["quick"] as? WinQuick)?.setService(null)
    }

    /** Density / font scale changed: rebuild every density-dependent value and window. */
    fun onDensityChanged() {
        collapseImmediately()
        shownWin = null
        for (w in winMap.values) {
            stopWin(w)
            w.node?.discardDisplayList()
            w.node = null
        }
        winMap.clear()
        order = ArrayList()
        nWin = 0
        rimPaint.strokeWidth = sw(dp(1f))
        ringPaint.strokeWidth = sw(dp(1.5f))
        liveTitlePaint.textSize = sp(11f)
        applySizes(Prefs.idleDp(context), Prefs.openDp(context))
        applyFlare()
        reloadWindows()
    }

    // ================================================================ view plumbing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        publishRegion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewAttached = true
        if (blurPx > 0 && backBlur.ensure(context)) applyOpacity(opacityPct)
        applySwipeSens()
        try {
            Prefs.prefs(context).registerOnSharedPreferenceChangeListener(sensListener)
        } catch (e: Exception) {
        }
        LiveBus.onChange = { onLiveChanged() }
        LiveTest.view = this
        setClipListening(true)
        readExtraPrefs()
        syncIdleServices()
        onLiveChanged()
    }

    /** Every source and loop stops with the window; onAttachedToWindow restarts only what is wanted. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pixelLayer.release()
        viewAttached = false
        try {
            Prefs.prefs(context).unregisterOnSharedPreferenceChangeListener(sensListener)
        } catch (e: Exception) {
        }
        removeCallbacks(longPressRunnable)
        removeCallbacks(captureTimeout)
        removeCallbacks(infoRunnable)
        removeCallbacks(fadeRunnable)
        removeCallbacks(tapRunnable)
        tapPending = false
        infoShowing = false
        captureWanted = false
        stopVizLoop()
        collapseImmediately() // frame loop, springs, window sources, layers (display lists die with the window)
        syncIdleServices()    // media watch + power receiver are released while detached
        setClipListening(false)
        LiveBus.onChange = null
        if (LiveTest.view === this) LiveTest.view = null
    }

    /** Live test hooks. */
    internal fun testRefreshIdle() = syncIdleServices()
    internal fun testHide() = hideForAwhile()
    internal fun testUnhide() {
        removeCallbacks(unhideRunnable)
        unhideRunnable.run()
    }

    private fun stopVizLoop() {
        if (vizPosted) {
            Choreographer.getInstance().removeFrameCallback(vizCb)
            vizPosted = false
        }
        if (vizRunning) {
            vizRunning = false
            visualizer.stop()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && captureWanted) {
            captureWanted = false
            removeCallbacks(captureTimeout)
            try {
                ClipPaste.capture(context)
                clipDirty = false
            } catch (e: Throwable) {
            } finally {
                host?.setWindowFocusable(false)
            }
        }
    }

    // ---------------------------------------------------------------- clipboard capture / helpers for windows
    private var captureWanted = false
    private var captureLast = 0L
    // The clipboard is only read (which needs a short window focus) when the system says it changed.
    private var clipDirty = true
    private var clipListening = false
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { clipDirty = true }

    private fun setClipListening(on: Boolean) {
        if (on == clipListening) return
        try {
            val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (on) {
                cm?.addPrimaryClipChangedListener(clipListener)
                clipDirty = true
            } else {
                cm?.removePrimaryClipChangedListener(clipListener)
            }
            clipListening = on
        } catch (e: Exception) {
        }
    }
    private val captureTimeout = Runnable {
        if (captureWanted) {
            captureWanted = false
            host?.setWindowFocusable(false)
        }
    }

    /** Asks the service to make the window focusable for one focus change so the clipboard can be read. */
    internal fun requestCapture() {
        val now = SystemClock.uptimeMillis()
        if (!clipDirty || captureWanted || now - captureLast < 1500L) return
        captureLast = now
        captureWanted = true
        host?.setWindowFocusable(true)
        removeCallbacks(captureTimeout)
        postDelayed(captureTimeout, 300L)
    }

    internal fun showCopied() {
        putTransient(
            LiveActivity(
                "copied", LiveActivity.Kind.PROGRESS, "Copied", -1f, 0L, false,
                R.drawable.ic_clipboard, false, null, null, null
            ), 1800L
        )
    }

    /** Opens the floating clipboard popup (callers collapse the edge). */
    internal fun openClipboard() {
        host?.openClipPopup()
    }

    /** For ClipPopup: the system clipboard changed since the last capture. */
    internal fun clipNeedsCapture(): Boolean = clipDirty

    /** For ClipPopup: the clipboard was just read (or written by us). */
    internal fun clipCaptured() {
        clipDirty = false
    }
    // ================================================================ looks

    /**
     * Swipe sensitivity 0..100 (default 35). 0: a long dead zone, the page follows the finger at a low ratio and a
     * release needs a wide angle or a hard fling. 100: short dead zone, high ratio, small angle or a light flick.
     */
    private fun applySwipeSens() {
        val s = Prefs.swipeSens(context) / 100f
        pageSlop = touchSlop * (2f + 2.5f * (1f - s))
        pageFollowDeg = 30f + 40f * (1f - s)
        swipeCommitDeg = pageFollowDeg * (0.62f - 0.32f * s)
        swipeFlingDps = 160f + 260f * (1f - s)
        swipeMinFlingDeg = pageFollowDeg * 0.15f
    }

    private fun applyLooksNow() {
        val c = context
        applyBlur(c)
        if (PixelMode.on(c)) {
            pixelCell = PixelMode.cellPx(c)
            snapCell = PixelLayer.cellInt(pixelCell).toFloat()
            pixelLayer.face = PixelMode.typeface(c)
            liveTitlePaint.typeface = PixelMode.typeface(c)
            pixelHook = pixelFn
        } else {
            snapCell = 0f
            pixelHook = null
            pixelLayer.release()
            liveTitlePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        looks.cell = snapCell
        hapticsOn = Prefs.haptics(c)
        vizOn = Prefs.visualizer(c)
        liveOn = Prefs.live(c)
        beatOn = Prefs.beat(c)
        val newAmount = Prefs.beatAmount(c) / 100f
        val amountChanged = newAmount != beatAmount
        beatAmount = newAmount
        // The look is black and white plus the user's accent colour. Every value is read fresh here, so
        // changing any setting updates the live overlay immediately.
        accent = Prefs.accent(c)
        gradientPct = Prefs.prefs(c).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100)
        glowOn = Prefs.glow(c)
        stiffScale = Prefs.stiffnessScale(c)
        openDamp = Prefs.openDamping(c)
        idleId = Prefs.idleStyle(c)
        fillId = Prefs.fill(c)
        vizId = Prefs.vizStyle(c)
        baseColor = Prefs.base(c)
        applyInk()
        grad1 = Prefs.grad1(c)
        grad2 = Prefs.grad2(c)
        gradOn = Prefs.gradOn(c)
        rimPaint.strokeWidth = sw(dp(1f))
        ringPaint.strokeWidth = sw(dp(1.5f))
        applyOpacity(Prefs.opacityPct(c))
        applySwipeSens()
        touchShow = Prefs.touchShow(c)
        touchPaint.strokeWidth = sw(dp(1.5f))
        touchPaint.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
        looks.accent = accent
        shapeShader = buildShapeShader()
        glowShader = buildGlowShader()
        coreShader = buildCoreShader()
        // The gradient is painted under the shape; the fill pattern is an optional overlay on top of it.
        // Gradient OFF means off: the shape is the pure base colour plus its pattern.
        // a custom body colour is drawn flat: the warm accent core of the default gradient would stain it
        gradientActive = gradOn && gradientPct > 0 && (baseColor and 0xFFFFFF) == 0
        shapePaint.shader = if (gradientActive) shapeShader else null
        shapePaint.color = baseColor
        glowPaint.shader = glowShader
        corePaint.shader = coreShader
        ringPaint.color = accent
        press.stiffness = 700f * stiffScale
        for (w in order) w.dirty = true
        syncIdleServices()
        readExtraPrefs()
        onLiveChanged()
        if (amountChanged && beatOn && !hidden) {
            // live feedback for the Bounce slider: kick the bean to the new amplitude, the spring settles it
            pulseS.value = 1f + beatAmount * 0.12f
            pulseS.velocity = 0f
            updateViz()
        }
        invalidate()
    }

    /** The settings screen is previewing (idle / open): the overlay itself draws a light backdrop behind the shape. */
    fun setBackdrop(on: Boolean) {
        removeCallbacks(previewWatch)
        if (on) {
            pvStamp = Prefs.prefs(context).getLong(Prefs.KEY_PREVIEW_TS, 0L)
            pvSeenAt = SystemClock.uptimeMillis()
            postDelayed(previewWatch, 1000L)
        }
        if (backdropOn == on) return
        backdropOn = on
        invalidate()
    }

    private var pvStamp = 0L
    private var pvSeenAt = 0L

    /** A preview nobody refreshes for 4 s (the settings app died or was left) is dropped: backdrop gone, edge closed. */
    private val previewWatch = object : Runnable {
        override fun run() {
            if (!backdropOn) return
            val p = Prefs.prefs(context)
            val ts = p.getLong(Prefs.KEY_PREVIEW_TS, 0L)
            val now = SystemClock.uptimeMillis()
            if (ts != pvStamp) {
                pvStamp = ts
                pvSeenAt = now
            }
            if (p.getString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE) == Prefs.PREVIEW_NONE) {
                setBackdrop(false)
            } else if (now - pvSeenAt >= 4000L) {
                p.edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
                holdOpen = false
                setBackdrop(false)
                collapse()
            } else {
                postDelayed(this, 1000L)
            }
        }
    }

    internal fun haptic(kind: Int) {
        if (hapticsOn) performHapticFeedback(kind)
    }

    // ================================================================ idle services

    private fun invalidateBean() {
        if (mirror) invalidate(0, height - beanPx, beanPx, height)
        else invalidate(width - beanPx, height - beanPx, width, height)
    }

    private fun syncIdleServices() {
        // The light media watch serves the visualizer, the beat reaction and the track-change live activity.
        if (viewAttached && screenOn && (vizOn || liveOn || beatOn)) {
            var w = mediaWatch
            if (w == null) {
                w = IdleMediaWatch(context) { pl ->
                    musicPlaying = pl || LiveTest.demoMusic
                    idlePlaying = musicPlaying && vizOn
                    updateViz()
                    syncInfo()
                    invalidateBean()
                }
                w.onTrack = { t -> onTrackChanged(t) }
                mediaWatch = w
            }
            w.start()
            musicPlaying = w.playing || LiveTest.demoMusic
            idlePlaying = musicPlaying && vizOn
        } else {
            mediaWatch?.stop()
            musicPlaying = LiveTest.demoMusic && viewAttached && screenOn
            idlePlaying = musicPlaying && vizOn
        }
        updateViz()
        invalidateBean()
    }

    /** Overlay opacity: one hardware layer with one alpha (never per paint); no layer at 100%. */
    private fun applyOpacity(pct: Int) {
        opacityPct = pct
        if (blurPx > 0 && backBlur.active) {
            // blur on: no view layer; the body paint carries the opacity so the blur shows through it
            if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
            invalidate()
            return
        }
        // the layer is created once and only its paint alpha changes afterwards (re-creating it per slider step
        // flashed the edge); it is dropped only when the opacity is full and no slider is moving
        if (pct >= 100) {
            removeCallbacks(dropLayer)
            if (layerType != LAYER_TYPE_NONE) {
                layerPaint.alpha = 255
                setLayerPaint(layerPaint)
                postDelayed(dropLayer, 800L)
            }
        } else {
            removeCallbacks(dropLayer)
            layerPaint.alpha = (255f * pct / 100f + 0.5f).toInt()
            if (layerType == LAYER_TYPE_HARDWARE) setLayerPaint(layerPaint)
            else setLayerType(LAYER_TYPE_HARDWARE, layerPaint)
        }
        invalidate()
    }

    private val dropLayer = Runnable {
        if (opacityPct >= 100 && layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
    }

    /** Opacity slider fast path: only the layer alpha changes, nothing is rebuilt. */
    fun setOpacityPct(pct: Int) {
        if (pct == opacityPct) return
        applyOpacity(pct)
    }

    /**
     * Visualizer capture and the 30 Hz frame loop run only while something plays, the screen is on and the
     * visualizer or the beat reaction is enabled. After playback stops the loop keeps running just long
     * enough for the beat values and the bounce spring to settle, then stops completely.
     */
    private fun updateViz() {
        val want = viewAttached && screenOn && !hidden && musicPlaying && (vizOn || beatOn)
        if (want != vizRunning) {
            vizRunning = want
            if (want) {
                visualizer.start()
                lastVizNanos = 0L
            } else {
                visualizer.stop()
            }
        }
        if (viewAttached && (vizRunning || beatActive()) && !vizPosted) {
            vizPosted = true
            Choreographer.getInstance().postFrameCallback(vizCb)
        }
    }

    private fun beatActive(): Boolean =
        beat > 0.004f || bassS > 0.004f || midS > 0.004f || highS > 0.004f || abs(pulseS.value - 1f) > 0.002f

    private fun settleBeat() {
        beat = 0f; bassS = 0f; midS = 0f; highS = 0f
        driftX = 0f; driftY = 0f
        pulseS.snapTo(1f)
        lastVizNanos = 0L
    }

    /** Fade-out only, after playback stops (live levels are already smoothed once in IdleVisualizer). */
    private fun sm(v: Float, t: Float, dt: Float): Float {
        val tau = if (t > v) 0.012f else 0.1f
        return v + (t - v) * (dt / (tau + dt))
    }

    private fun vizFrame(t: Long) {
        vizPosted = false
        val live = vizRunning
        if (!live && !beatActive()) {
            settleBeat()
            invalidateBean()
            if (expandedTarget || dragMode != 0) invalidate()
            return
        }
        // every vsync: one tick feeds the bean, the open rim and the beat from the same levels at the same time
        val dt = if (lastVizNanos == 0L) 0.016f else ((t - lastVizNanos) * 1e-9f).coerceIn(0.002f, 0.05f)
        lastVizNanos = t
        var lb = 0f
        var l1 = 0f
        var l2 = 0f
        var lh = 0f
        var kick = 0f
        if (live) {
            visualizer.tick(dt, t / 1_000_000L)
            val lv = visualizer.levels
            lb = lv[0].coerceIn(0f, 1f)
            l1 = lv[1].coerceIn(0f, 1f)
            l2 = lv[2].coerceIn(0f, 1f)
            lh = lv[3].coerceIn(0f, 1f)
            kick = visualizer.kick
        }
        if (beatOn) {
            // levels are already smoothed (instant attack, ~100 ms release): use them directly, no second layer
            val en = (0.5f * lb + 0.3f * l1 + 0.2f * l2).coerceIn(0f, 1f)
            if (live) {
                beat = en; bassS = lb; midS = (l1 + l2) * 0.5f; highS = lh
            } else {
                // playback just stopped: the meter is no longer ticking, so fade the stored values out
                beat = sm(beat, 0f, dt); bassS = sm(bassS, 0f, dt); midS = sm(midS, 0f, dt); highS = sm(highS, 0f, dt)
            }
            driftPhase += dt
            driftX = sin(driftPhase * 1.3f) * midS * dp(6f)
            driftY = cos(driftPhase * 1.0f) * midS * dp(6f)
        } else {
            beat = 0f; bassS = 0f; midS = 0f; highS = 0f; driftX = 0f; driftY = 0f
        }
        // Bounce: breathing base from the energy plus a swell envelope raised on the bass onset frame (the onset
        // is detected on the raw signal inside IdleVisualizer and delayed with the levels). A soft, well-damped
        // spring follows, so the bean rises and settles like liquid.
        val drive = (beat * 1.8f).coerceAtMost(1f)
        kickEnv *= exp(-dt / 0.22f)
        if (beatOn && kick > 0f) kickEnv = max(kickEnv, kick)
        val swell = if (expandedTarget) 0.015f * drive + 0.02f * kickEnv else 0.05f * drive + 0.22f * kickEnv
        pulseS.target = 1f + beatAmount * swell
        pulseS.step(dt)
        pulseS.value = pulseS.value.coerceIn(0.97f, 1.3f)
        if (expandedTarget || dragMode != 0) invalidate() else invalidateBean()
        vizPosted = true
        Choreographer.getInstance().postFrameCallback(vizCb)
    }

    private fun drawIdle(c: Canvas, ia: Float) {
        c.save()
        c.translate(width.toFloat(), height.toFloat())
        val ps = beanScale
        c.scale(ps, ps)
        // while the visualizer draws on the bean, no idle design is drawn - only the visualizer
        if (vizOn && idlePlaying) {
            looks.drawViz(c, vizId, idle, false, visualizer.levels, ia, density)
        } else if (infoShowing) {
            val now = SystemClock.uptimeMillis()
            val p = ((now - infoT0).toFloat() / EdgeExtras.INFO_FADE_MS).coerceIn(0f, 1f)
            // the glyph fades out only while the first chip fades in; chip to chip is a plain cross-fade
            if (infoPrev.isEmpty() && p < 1f) looks.drawIdle(c, idleId, idle, ia * (1f - p), if (beatOn) beat else 0f)
            val cen = 0.4244f * idle
            val base = sp(14f)
            drawChip(c, chipFitCur, infoCur, -cen, -cen, base, cen * 1.3f, (255f * ia * p + 0.5f).toInt())
            if (infoPrev.isNotEmpty() && p < 1f) {
                drawChip(c, chipFitPrev, infoPrev, -cen, -cen, base, cen * 1.3f, (255f * ia * (1f - p) + 0.5f).toInt())
            }
            if (p < 1f) invalidateBean()
        } else {
            looks.drawIdle(c, idleId, idle, ia, if (beatOn) beat else 0f)
        }
        c.restore()
    }

    /** Visualizer on the open rim while music plays (rim styles only; the others fall back to spectrum). */
    private fun drawOpenViz(c: Canvas, a: Float) {
        val sz = min(drawW, drawH)
        if (sz < rad * 0.6f) return
        c.save()
        c.translate(width.toFloat(), height.toFloat())
        looks.drawViz(c, vizId, sz, true, visualizer.levels, a, density)
        c.restore()
    }

    // ================================================================ live activities

    private fun onTrackChanged(title: String?) {
        if (!liveOn || expandedTarget || !screenOn || hidden || title.isNullOrBlank()) return
        if (title == lastLiveTrack) return
        lastLiveTrack = title
        putTransient(
            LiveActivity(
                "media", LiveActivity.Kind.MEDIA, title.take(40), -1f, 0L, false,
                R.drawable.ic_music, false, null, null, null
            ), 3000L
        )
    }

    /** Posts a transient activity and removes it after [ms]. */
    private fun putTransient(a: LiveActivity, ms: Long) {
        val old = transientRunnables.remove(a.key)
        if (old != null) removeCallbacks(old)
        val key = a.key
        val r = Runnable {
            transientRunnables.remove(key)
            LiveBus.remove(key)
        }
        transientRunnables[key] = r
        postDelayed(r, ms)
        LiveBus.put(a)
    }

    private fun scheduleTransient(a: LiveActivity?) {
        if (a == null || a.ongoing || transientRunnables.containsKey(a.key)) return
        val key = a.key
        val ms = when (a.kind) {
            LiveActivity.Kind.MEDIA -> 3000L
            LiveActivity.Kind.CHARGING -> 3500L
            else -> if (a.key == TimerControl.KEY_DONE) 2500L else 4000L
        }
        val r = Runnable {
            transientRunnables.remove(key)
            LiveBus.remove(key)
        }
        transientRunnables[key] = r
        postDelayed(r, ms)
    }

    private fun onLiveChanged() {
        val top = if (liveOn) LiveBus.top() else null
        val sec = if (liveOn) LiveBus.second() else null
        liveCur = top
        liveSec = sec
        ringPulse = top?.key == TimerControl.KEY_DONE
        (winMap["timer"] as? WinTimer)?.onLive()
        scheduleTransient(top)
        scheduleTransient(sec)
        if (top?.key != liveTitleKey || top?.chronoBase != liveBase) {
            liveTitleKey = top?.key
            liveBase = top?.chronoBase ?: 0L
            liveTitleRaw = null
            liveTotalMs = 0L
            if (top != null && top.countDown && top.chronoBase != 0L) {
                liveTotalMs = if (top.totalMs > 0L) top.totalMs else max(1000L, top.chronoBase - System.currentTimeMillis())
            }
        }
        rebuildLiveTitle()
        syncClosedSize()
        updateViz()
        syncLiveTick()
        if (fadeOn) {
            if (liveWanted) fadeWake() else if (fadeTo == 1f) fadeArm()
        }
        syncInfo()
        invalidateBean()
    }

    /** Morphs the closed bean between idle and live size. */
    private fun syncClosedSize() {
        if (expandedTarget || gestureActive || dragMode != 0 || hidden) return
        val tgt = closedSize()
        if (wS.target == tgt && hS.target == tgt) return
        if (tgt > regionClosed) {
            regionClosed = tgt
            publishRegion()
        }
        collapsing = true
        wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
        hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
        wS.target = tgt
        hS.target = tgt
        requestFrames()
    }

    /** Delay to the next change of the shown whole second (1 Hz, aligned to the boundary, no drift). */
    private fun chronoDelay(a: LiveActivity): Long {
        val now = System.currentTimeMillis()
        val ms = if (a.countDown) (a.chronoBase - now) % 1000L + 8L else 1000L - ((now - a.chronoBase) % 1000L) + 8L
        return ms.coerceIn(16L, 1008L)
    }

    private fun syncLiveTick() {
        removeCallbacks(liveTickRunnable)
        val a = liveCur ?: return
        if (!liveWanted || expandedTarget) return
        if (a.progress == -2f || a.chronoBase != 0L || a.key == TimerControl.KEY_DONE || ringing) postDelayed(liveTickRunnable, 16L)
    }

    private fun fmtChrono(ms: Long): String {
        val t = if (ms < 0L) 0L else ms / 1000L
        sb.setLength(0)
        val h = t / 3600L
        val m = (t % 3600L) / 60L
        val s = t % 60L
        if (h > 0L) {
            sb.append(h).append(':')
            if (m < 10L) sb.append('0')
        }
        sb.append(m).append(':')
        if (s < 10L) sb.append('0')
        sb.append(s)
        return sb.toString()
    }

    private fun fmtShort(ms: Long): String {
        val t = if (ms < 0L) 0L else ms / 1000L
        sb.setLength(0)
        if (t >= 60L) sb.append((t + 59L) / 60L).append('m') else sb.append(t).append('s')
        return sb.toString()
    }

    private fun rebuildCallName(a: LiveActivity) {
        callNamePaint.typeface = liveTitlePaint.typeface
        if (a.title == callNameRaw && callNameLen > 0f) return
        callNameRaw = a.title
        callNameShown = TextUtils.ellipsize(a.title, callNamePaint, callNameLen, TextUtils.TruncateAt.END).toString()
        callNameOff = (callNameLen - callNamePaint.measureText(callNameShown)) / 2f
    }

    private fun rebuildLiveTitle() {
        val a = liveCur ?: return
        if (ringing) rebuildCallName(a)
        val call = a.kind == LiveActivity.Kind.CALL
        val avail = if (call) liveCallLen else liveTitleLen
        val raw = if (a.chronoBase != 0L) {
            val now = System.currentTimeMillis()
            val ms = if (a.countDown) a.chronoBase - now else now - a.chronoBase
            val full = fmtChrono(ms)
            // small bean: minutes only (or seconds) instead of an ellipsis
            if (liveTitlePaint.measureText(full) > avail) fmtShort(ms) else full
        } else {
            a.title
        }
        if (raw == liveTitleRaw && call == liveTitleCall) return
        liveTitleRaw = raw
        liveTitleCall = call
        liveTitleShown = TextUtils.ellipsize(raw, liveTitlePaint, avail, TextUtils.TruncateAt.END).toString()
        liveTitleOff = (avail - liveTitlePaint.measureText(liveTitleShown)) / 2f
    }

    /** The big ringing view: name on the arc, pulsing ring, answer (green) and decline (red) discs. */
    private fun drawCall(c: Canvas, a: LiveActivity) {
        val s = callSize
        val w = min(wS.value, hS.value)
        val f = ((w - idle) / max(s - idle, 1f)).coerceIn(0f, 1f)
        val over = ((s * 1.35f - max(wS.value, hS.value)) / (s * 0.35f)).coerceIn(0f, 1f)
        val la = f * f * (3f - 2f * f) * over
        if (la <= 0.01f) return
        val ai = (255f * la + 0.5f).toInt()
        val phase = (SystemClock.uptimeMillis() % 1300L) / 1300f
        c.save()
        c.translate(width.toFloat(), height.toFloat())
        val ps = press.value * (w / s).coerceIn(0.5f, 1.15f)
        c.scale(ps, ps)
        // pulsing ring along the rim: same 7 dp track and inset as every live activity
        val r = sr(s - dp(if (floatOn) 9f else 7f))
        liveOval.set(-r, -r, r, r)
        arcPaint.strokeWidth = sw(dp(7f))
        arcPaint.color = C_GREEN
        arcPaint.alpha = (255f * (0.25f + 0.45f * (1f - phase)) * la + 0.5f).toInt()
        c.drawArc(liveOval, 180f + SL_A0, SL_A1 - SL_A0, false, arcPaint)
        // caller name on the arc
        callNamePaint.color = ink
        callNamePaint.alpha = ai
        if (callNameShown.isNotEmpty()) arcText(c, callNameShown, callPathName, callNameOff, callNamePaint)
        if (a.answer != null) {
            // expanding halo, then the green disc
            arcPaint.strokeWidth = sw(dp(2f))
            arcPaint.color = C_GREEN
            arcPaint.alpha = (255f * 0.5f * (1f - phase) * la + 0.5f).toInt()
            c.drawCircle(callAnsX, callAnsY, callBtnR + dp(10f) * phase, arcPaint)
            fill.color = C_GREEN
            fill.alpha = ai
            c.drawCircle(callAnsX, callAnsY, callBtnR, fill)
            val ic = icon(R.drawable.ic_phone, false)
            ic.bounds = callAnsRect
            ic.setTint(Color.WHITE)
            ic.alpha = ai
            drawIc(c, ic)
            ic.setTint(ink)
        }
        if (a.decline != null) {
            fill.color = C_DECLINE
            fill.alpha = ai
            c.drawCircle(callDecX, callDecY, callBtnR, fill)
            val ic = icon(R.drawable.ic_phone_off, false)
            ic.bounds = callDecRect
            ic.setTint(Color.WHITE)
            ic.alpha = ai
            drawIc(c, ic)
            ic.setTint(ink)
        }
        c.restore()
    }

    private fun drawLive(c: Canvas) {
        val a = liveCur ?: return
        if (ringing) {
            drawCall(c, a)
            return
        }
        val s = liveSize
        val w = min(wS.value, hS.value)
        val big = liveBig
        // compact (idle-sized) activities show at once: the canvas scale below shrinks the live layout into the bean
        val f = if (big) ((w - idle) / max(s - idle, 1f)).coerceIn(0f, 1f) else 1f
        // fade only with the morph from idle and while a bigger (opening / collapsing) panel covers the live
        // size; the live size itself is not "opening", so it must never dim the content
        val over = ((s * 1.35f - max(wS.value, hS.value)) / (s * 0.35f)).coerceIn(0f, 1f)
        val la = f * f * (3f - 2f * f) * over
        if (la <= 0.01f) return
        val ai = (255f * la + 0.5f).toInt()
        val call = a.kind == LiveActivity.Kind.CALL
        val tint = if (call) C_GREEN else accent
        c.save()
        c.translate(width.toFloat(), height.toFloat())
        val ps = press.value * (w / s).coerceIn(0.5f, 1.15f)
        c.scale(ps, ps)
        // progress arc along the curved rim; floating, the rim ends are rounded, so the arc stops short of them
        // (plus half the stroke and its round cap) instead of running out past the body
        // one thick track for every live activity (timer, download, charging, call...): 7 dp, inset so it sits
        // inside the rim
        val r = sr(s - dp(if (floatOn) 9f else 7f))
        val trim = if (floatOn) Math.toDegrees(((max(gRhoB, gRhoS) + dp(7f)) / max(r, 1f)).toDouble()).toFloat() else 0f
        val A0 = SL_A0 + trim
        val A1 = SL_A1 - trim
        liveOval.set(-r, -r, r, r)
        arcPaint.strokeWidth = sw(dp(7f))
        arcPaint.color = ink
        arcPaint.alpha = (255f * 0.24f * la + 0.5f).toInt()
        // a call has no progress: no empty track on the rim, just the hang-up button
        if (!call) c.drawArc(liveOval, 180f + A0, A1 - A0, false, arcPaint)
        arcPaint.color = tint
        arcPaint.alpha = ai
        if (call) {
            // nothing on the rim
        } else if (a.progress == -2f) {
            val head = ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (A1 - A0 + 24f)
            val st = max(0f, head - 24f)
            val en = min(A1 - A0, head)
            if (en > st) c.drawArc(liveOval, 180f + A0 + st, en - st, false, arcPaint)
        } else {
            val fr = when {
                a.progress >= 0f -> a.progress
                a.chronoBase != 0L && a.countDown && liveTotalMs > 0L ->
                    (a.chronoBase - System.currentTimeMillis()).toFloat() / liveTotalMs
                else -> -1f
            }
            if (fr > 0.002f) c.drawArc(liveOval, 180f + A0, (A1 - A0) * fr.coerceAtMost(1f), false, arcPaint)
        }
        // icon at the centroid (an ongoing call shows its hang-up button there instead)
        if (a.chip.isNotEmpty() && !(call && a.decline != null)) {
            // chip text where the icon goes, sized to read at idle size once the layout is scaled down
            val cen = 0.4244f * s
            val base = sp(14f) * (if (big) 1f else LIVE_SCALE)
            drawChip(c, chipFitLive, a.chip, -cen, -cen, base, cen * 1.3f, ai)
        } else if (a.iconRes != 0 && !(call && a.decline != null)) {
            val ic = icon(a.iconRes, false)
            ic.bounds = liveIconRect
            ic.alpha = ai
            drawIc(c, ic)
        }
        // short title along the rim
        liveTitlePaint.color = ink
        liveTitlePaint.alpha = ai
        if (big && liveTitleShown.isNotEmpty()) {
            arcText(c, liveTitleShown, if (call) liveTitlePathCall else liveTitlePath, liveTitleOff, liveTitlePaint)
        }
        if (call) {
            if (a.answer != null) {
                fill.color = C_GREEN
                fill.alpha = ai
                c.drawCircle(liveAnsX, liveAnsY, liveDotR, fill)
                val ic = icon(R.drawable.ic_phone, true)
                ic.bounds = liveAnsRect
                ic.alpha = ai
                drawIc(c, ic)
            }
            if (a.decline != null) {
                fill.color = C_DECLINE
                fill.alpha = ai
                c.drawCircle(liveDecX, liveDecY, liveDotR, fill)
                val ic = icon(R.drawable.ic_phone_off, true)
                ic.bounds = liveDecRect
                ic.alpha = ai
                drawIc(c, ic)
            }
        }
        if (liveSec != null && !call) {
            val ang = Math.toRadians(if (floatOn) (A1 + 3f).toDouble() else 88.0)
            fill.color = ink
            fill.alpha = (255f * 0.7f * la + 0.5f).toInt()
            c.drawCircle(-r * cos(ang).toFloat(), -r * sin(ang).toFloat(), dp(2.2f), fill)
        }
        c.restore()
    }

    /** 1 = answer dot, 2 = decline dot, 0 = none (view coordinates). */
    private fun liveDotHit(x: Float, y: Float): Int {
        val a = liveCur ?: return 0
        if (a.kind != LiveActivity.Kind.CALL) return 0
        val px = x - width
        val py = y - height
        val big = ringing
        val hr = if (big) callHitR else dp(18f)
        val ax = if (big) callAnsX else liveAnsX
        val ay = if (big) callAnsY else liveAnsY
        val bx = if (big) callDecX else liveDecX
        val by = if (big) callDecY else liveDecY
        if (a.answer != null) {
            val dx = px - ax
            val dy = py - ay
            if (dx * dx + dy * dy <= hr * hr) return 1
        }
        if (a.decline != null) {
            val dx = px - bx
            val dy = py - by
            if (dx * dx + dy * dy <= hr * hr) return 2
        }
        return 0
    }

    private fun sendPi(pi: PendingIntent?) {
        if (pi == null) return
        try {
            pi.send()
        } catch (e: Exception) {
        }
    }

    private fun onBeanTap(x: Float, y: Float) {
        val a = liveCur
        if (liveWanted && a != null) {
            if (LiveTest.owns(a.key)) {
                // a live-test entry: the pulsing bean or a call dot just ends the test
                if (a.key == TimerControl.KEY_DONE || liveDotHit(x, y) != 0) {
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    LiveTest.endKey(a.key)
                    return
                }
            } else if (a.key == TimerControl.KEY_DONE) {
                // tapping the pulsing bean silences the timer alarm
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                TimerControl.stopAlarm(context)
                return
            }
            when (liveDotHit(x, y)) {
                1 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.answer); return }
                2 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.decline); return }
            }
            if ((a.kind == LiveActivity.Kind.CALL || a.tapOpens) && a.contentIntent != null) {
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                sendPi(a.contentIntent)
                return
            }
            if (a.kind == LiveActivity.Kind.MEDIA && contentAlpha < 0.2f) {
                val mi = indexOfId("media")
                if (mi >= 0) {
                    openPicked = true
                    userPaged = true
                    page = mi
                    lastPageId = "media"
                    pageS.snapTo(mi.toFloat())
                    syncSources()
                }
            }
        }
        startExpand(false, 0f, 0f)
    }

    // ================================================================ geometry

    /** The rounding of the corner between both bezels for a shape of radius [r]. */
    private fun tipFor(r: Float): Float =
        if (floatOn && floatCornerFrac >= 0f) floatCornerFrac * 0.4f * r else min(gTip, 0.4f * r)

    /** Edge fillet radius for a shape of the given size: scaled with the idle size, at most 40% of it. */
    private fun flareFor(size: Float, p: Float): Float {
        if (flarePx <= 0.5f || floatOn) return 0f
        val fIdle = min(flarePx * idle / dp(48f), 0.4f * idle)
        val f = fIdle + (flarePx - fIdle) * p
        return min(f, 0.4f * size)
    }

    /**
     * Resolves the shape parameters for the current flare [fl] (see [flareFor]):
     * - attached to the corner: flare ears on both screen edges, the tip follows the display corner;
     * - position > 0 (not floating): the shape leaves the side bezel, so no side ear, a rounded top-of-wall corner;
     * - floating: no ears at all, the whole island is rounded (both arc ends and the corner tip).
     */
    private fun resolveGeom(fl: Float) {
        val rd = dp(ROUND_DP)
        if (floatOn) {
            gFlB = 0f
            gFlS = 0f
            gRhoB = rd
            gRhoS = rd
            gTip = dp(FLOAT_TIP_DP)
        } else if (placeX > 0) {
            gFlB = fl
            gFlS = 0f
            gRhoB = 0f
            gRhoS = rd
            gTip = 0f
        } else {
            gFlB = fl
            gFlS = fl
            gRhoB = 0f
            gRhoS = 0f
            gTip = tipPx
        }
    }

    /**
     * ONE geometry for fill, region, hit test and pattern clip: the quarter-ellipse bean centred on the corner,
     * its two arc ends either meeting the screen edges through exact concave fillets ("ears", gFl*), or rounded
     * (gRho*) when that edge is not attached, plus the corner tip. Parameters come from [resolveGeom].
     */
    private fun buildShape(p: Path, ox: Float, oy: Float, w: Float, h: Float) {
        p.rewind()
        val right = ox + w
        val bottom = oy + h
        val r0 = min(w, h)
        val fB = if (gFlB > 0.5f) gFlB else 0f
        val fS = if (gFlS > 0.5f) gFlS else 0f
        val startX: Float
        val aB: Float
        if (fB > 0f) {
            val s0 = sqrt(r0 * r0 + 2f * r0 * fB)
            val th = Math.toDegrees(atan2(fB.toDouble(), s0.toDouble())).toFloat()
            val c1x = right - s0
            val c1y = bottom - fB
            p.moveTo(c1x, bottom)
            earRect.set(c1x - fB, c1y - fB, c1x + fB, c1y + fB)
            p.arcTo(earRect, 90f, th - 90f, false)
            aB = 180f + th
            startX = c1x
        } else if (gRhoB > 0.5f) {
            val rb = min(gRhoB, 0.3f * r0)
            val sB = sqrt((r0 - rb) * (r0 - rb) - rb * rb)
            val al = Math.toDegrees(atan2(rb.toDouble(), sB.toDouble())).toFloat()
            val cxx = right - sB
            val cyy = bottom - rb
            p.moveTo(cxx, bottom)
            earRect.set(cxx - rb, cyy - rb, cxx + rb, cyy + rb)
            p.arcTo(earRect, 90f, 90f + al, false)
            aB = 180f + al
            startX = cxx
        } else {
            p.moveTo(right - r0, bottom)
            aB = 180f
            startX = right - r0
        }
        arcRect.set(right - r0, bottom - r0, right + r0, bottom + r0)
        if (fS > 0f) {
            val s0 = sqrt(r0 * r0 + 2f * r0 * fS)
            val th = Math.toDegrees(atan2(fS.toDouble(), s0.toDouble())).toFloat()
            p.arcTo(arcRect, aB, 270f - th - aB, false)
            val c2x = right - fS
            val c2y = bottom - s0
            earRect.set(c2x - fS, c2y - fS, c2x + fS, c2y + fS)
            p.arcTo(earRect, 90f - th, th - 90f, false)
        } else if (gRhoS > 0.5f) {
            val rs = min(gRhoS, 0.3f * r0)
            val sS = sqrt((r0 - rs) * (r0 - rs) - rs * rs)
            val be = Math.toDegrees(atan2(rs.toDouble(), sS.toDouble())).toFloat()
            p.arcTo(arcRect, aB, 270f - be - aB, false)
            val c2x = right - rs
            val c2y = bottom - sS
            earRect.set(c2x - rs, c2y - rs, c2x + rs, c2y + rs)
            p.arcTo(earRect, 270f - be, 90f + be, false)
        } else {
            p.arcTo(arcRect, aB, 270f - aB, false)
        }
        val t = tipFor(r0)
        if (t > 0.5f) {
            p.lineTo(right, bottom - t)
            arcRect.set(right - 2f * t, bottom - 2f * t, right, bottom)
            p.arcTo(arcRect, 0f, 90f, false)
        } else {
            p.lineTo(right, bottom)
        }
        p.lineTo(startX, bottom)
        p.close()
    }

    private val fitMatrix = Matrix()

    /**
     * The same outline for a non-square shape: built as a square of the smaller side (flares and tip included),
     * then stretched about the corner to w x h. Fill, flares, pattern clip and region all come from this one
     * path per frame, so they can never drift apart while the width and height springs lead and lag.
     */
    private fun buildShapeFit(p: Path, right: Float, bottom: Float, w: Float, h: Float) {
        val m = min(w, h)
        if (abs(w - h) < 0.5f) {
            buildShape(p, right - w, bottom - h, w, h)
            return
        }
        buildShape(p, right - m, bottom - m, m, m)
        fitMatrix.setScale(w / m, h / m, right, bottom)
        p.transform(fitMatrix)
    }

    private fun layoutAll() {
        // generous node bounds; the node clip lives in the node's own (unrotated) space, so rotating a page can never cut it
        nodeS = ceil(rad).toInt() + ceil(dp(8f)).toInt()
        beanPx = ceil(max(idle * LIVE_SCALE, callSize)).toInt() + ceil(dp(4f)).toInt()
        for (w in order) w.dirty = true
        val hw = min(dp(24.5f), 0.12f * rad - dp(1.5f)).coerceAtLeast(dp(12f))
        val brad = min(dp(20f), hw - dp(2f))

        // close X: at the idle bean's centroid (quarter disc centroid = 4/(3 pi) * size).
        // Visual 34 dp, 48 dp hit target.
        val xc = min(0.4244f * idle, 0.16f * rad)
        xCx = -xc
        xCy = -xc
        xRad = (xc - dp(3.5f)).coerceIn(dp(10f), dp(18f))
        // the X follows the rounded corner (Corner slider, display corner): pushed out along the diagonal until the
        // whole button sits inside the corner fillet with a small margin
        val tip = when {
            floatOn -> if (floatCornerFrac >= 0f) floatCornerFrac * 0.4f * rad else min(dp(FLOAT_TIP_DP), 0.4f * rad)
            placeX > 0 -> 0f
            else -> min(tipPx, 0.4f * rad)
        }
        val need = xRad + dp(4f)
        val fit = if (tip > need) tip - (tip - need) / 1.4142f else tip
        if (fit > xc) {
            xCx = -fit
            xCy = -fit
        }
        xHit = dp(24f) // 48 dp hit area
        patExtent = rad * 1.2f + flarePx + dp(30f)

        for (w in order) {
            w.layout(hw, brad)
        }
        layoutLive()
        layoutCall()
        liveTitleRaw = null
        callNameRaw = null
        rebuildLiveTitle()
    }

    private var nodeS = 0

    private fun layoutLive() {
        val s = liveSize
        val cen = 0.4244f * s
        val ih = dp(12f).toInt()
        liveIconRect.set((-cen).toInt() - ih, (-cen).toInt() - ih, (-cen).toInt() + ih, (-cen).toInt() + ih)
        val tr = s - dp(17f)
        Geo.textArcPath(liveTitlePath, xOval, tr, LV_A0, LV_A1)
        liveTitleLen = tr * Math.toRadians((LV_A1 - LV_A0).toDouble()).toFloat()
        // call title sits between the two dots
        val call0 = 28f
        Geo.textArcPath(liveTitlePathCall, xOval, tr, call0, LV_A1 - (call0 - LV_A0))
        liveCallLen = tr * Math.toRadians((LV_A1 - LV_A0 - 2f * (call0 - LV_A0)).toDouble()).toFloat()
        // ongoing call on the closed bean: one red hang-up button where the activity icon sits (the centroid),
        // the same place and size as every other live icon, never on the rim track
        liveDotR = dp(15f)
        val dr = s - dp(16f)
        val aA = Math.toRadians(75.0)
        liveDecX = -cen
        liveDecY = -cen
        liveAnsX = -dr * cos(aA).toFloat()
        liveAnsY = -dr * sin(aA).toFloat()
        val gh = dp(8f).toInt()
        liveAnsRect.set(liveAnsX.toInt() - gh, liveAnsY.toInt() - gh, liveAnsX.toInt() + gh, liveAnsY.toInt() + gh)
        liveDecRect.set(liveDecX.toInt() - gh, liveDecY.toInt() - gh, liveDecX.toInt() + gh, liveDecY.toInt() + gh)
    }

    /** Geometry of the ringing view: decline near the bottom edge, answer near the side edge (mirrored by the canvas). */
    private fun layoutCall() {
        val s = callSize
        val tr = s - dp(17f)
        Geo.textArcPath(callPathName, xOval, tr, LV_A0 + 4f, LV_A1 - 4f)
        callNameLen = tr * Math.toRadians((LV_A1 - LV_A0 - 8f).toDouble()).toFloat()
        callBtnR = dp(25f)
        callHitR = dp(28f)
        val br = min(s * 0.62f, s - dp(56f)).coerceAtLeast(callBtnR * 2f)
        val dA = Math.toRadians(24.0)
        val aA = Math.toRadians(66.0)
        callDecX = -br * cos(dA).toFloat()
        callDecY = -br * sin(dA).toFloat()
        callAnsX = -br * cos(aA).toFloat()
        callAnsY = -br * sin(aA).toFloat()
        val gh = dp(11f).toInt()
        callAnsRect.set(callAnsX.toInt() - gh, callAnsY.toInt() - gh, callAnsX.toInt() + gh, callAnsY.toInt() + gh)
        callDecRect.set(callDecX.toInt() - gh, callDecY.toInt() - gh, callDecX.toInt() + gh, callDecY.toInt() + gh)
    }

    private fun ensureResources() {
        if (resourcesReady) return
        resourcesReady = true
        val all = intArrayOf(
            R.drawable.ic_play, R.drawable.ic_pause, R.drawable.ic_skip_back, R.drawable.ic_skip_forward,
            R.drawable.ic_music, R.drawable.ic_clipboard, R.drawable.ic_phone, R.drawable.ic_phone_off,
            R.drawable.ic_bell, R.drawable.ic_bolt
        )
        for (r in all) {
            icon(r, false)
            icon(r, true)
        }
    }

    /** [black] = false: the icon in the ink colour; true: in the flipped ink (for ink-coloured surfaces). Cached per ink. */
    internal fun icon(res: Int, black: Boolean): Drawable {
        val cache = if (black) blackIcons else whiteIcons
        var d = cache.get(res)
        if (d == null) {
            d = context.getDrawable(res)!!.mutate()
            d.setTint(if (black) inkOn else ink)
            cache.put(res, d)
        }
        return d
    }

    /** Draws an icon / drawable un-mirrored (when the edge is on the left the canvas is mirrored). */
    internal fun drawIc(c: Canvas, d: Drawable) {
        if (!mirror) {
            d.draw(c)
            return
        }
        val b = d.bounds
        c.save()
        c.scale(-1f, 1f, b.exactCenterX(), b.exactCenterY())
        d.draw(c)
        c.restore()
    }

    /** Cancels the left-side canvas mirror in a local frame whose origin is the centre of the item being drawn. */
    internal fun unflip(c: Canvas) {
        if (mirror) c.scale(-1f, 1f)
    }

    /** Pixel mode: the whole-device-pixel cell the overlay grid uses, else 0. */
    @JvmField internal var snapCell = 0f

    /** A stroke width snapped to whole cells in pixel mode (at least one cell), else unchanged. */
    internal fun sw(v: Float): Float = if (snapCell > 0f) max(snapCell, Math.round(v / snapCell) * snapCell) else v

    /** A radius / length snapped to whole cells in pixel mode, else unchanged. */
    internal fun sr(v: Float): Float = if (snapCell > 0f) Math.round(v / snapCell) * snapCell else v

    /** The pixel font in pixel mode, else [d] (text paints use it so measuring matches what is replayed). */
    internal fun face(d: Typeface): Typeface = PixelMode.typeface(context) ?: d

    /** Straight text that stays upright and readable on the left side (positions stay mirrored). */
    internal fun text(c: Canvas, s: String, x: Float, y: Float, paint: Paint) {
        if (!mirror) {
            if (pixelHook != null && pixelLayer.recText(s, x, y, paint)) return
            c.drawText(s, x, y, paint)
            return
        }
        val al = paint.textAlign
        if (al == Paint.Align.LEFT) paint.textAlign = Paint.Align.RIGHT
        else if (al == Paint.Align.RIGHT) paint.textAlign = Paint.Align.LEFT
        c.save()
        c.scale(-1f, 1f)
        if (pixelHook == null || !pixelLayer.recText(s, -x, y, paint)) c.drawText(s, -x, y, paint)
        c.restore()
        paint.textAlign = al
    }

    /**
     * Text along an arc built with [Win.textArcPath] / Geo.textArcPath. [off] is the offset from the arc's start
     * in the normal (right side) layout; on the left side the path is reversed, so the offset is mirrored too and
     * the text block keeps its mirrored position while still reading left to right.
     */
    internal fun arcText(c: Canvas, s: String, path: Path, off: Float, paint: Paint) {
        if (!mirror) {
            if (pixelHook != null && pixelLayer.recPath(s, path, off, paint)) return
            c.drawTextOnPath(s, path, off, 0f, paint)
            return
        }
        pathMeasure.setPath(path, false)
        val o = pathMeasure.length - off - paint.measureText(s)
        c.save()
        c.scale(-1f, 1f)
        if (pixelHook == null || !pixelLayer.recPath(s, path, o, paint)) c.drawTextOnPath(s, path, o, 0f, paint)
        c.restore()
    }

    /** Recomputes the ink colours from the base colour and re-tints every cached icon. */
    private fun applyInk() {
        val b = baseColor
        val lum = (0.299f * Color.red(b) + 0.587f * Color.green(b) + 0.114f * Color.blue(b)) / 255f
        val light = lum > 0.5f
        lightBody = light
        val i = if (light) Color.BLACK else Color.WHITE
        val o = if (light) Color.WHITE else Color.BLACK
        if ((b and 0xFFFFFF) == 0) {
            cFill = DEF_FILL
            cTrack = DEF_TRACK
            cGrey = DEF_GREY
        } else {
            cFill = mixColor(b or (0xFF shl 24), i, 0.10f)
            cTrack = mixColor(b or (0xFF shl 24), i, 0.17f)
            cGrey = mixColor(b or (0xFF shl 24), i, 0.55f)
        }
        looks.ink = i
        if (i == ink && o == inkOn) return
        ink = i
        inkOn = o
        for (k in 0 until whiteIcons.size()) whiteIcons.valueAt(k).setTint(i)
        for (k in 0 until blackIcons.size()) blackIcons.valueAt(k).setTint(o)
    }

    internal fun warm(res: Int) {
        if (res == 0) return
        icon(res, false)
        icon(res, true)
    }

    private var cscale = 0f
    private var coff = 0f
    private var pwLo = 0
    private var pwHiW = 0f
    private var lastDw = -1f
    private var lastDh = -1f
    private var lastW = -1
    private var lastH = -1
    private var lastTip = -1f

    // ================================================================ sources

    private fun ensureSources() {
        if (sourcesStarted) return
        sourcesStarted = true
        syncSources()
    }

    private val startSourcesRunnable = Runnable {
        if (expandedTarget || dragMode != 0) {
            ensureSources()
            val mw = mediaWin
            if (!userPaged && mw != null && mw.playing && page != mw.index && !gestureActive) goToPage(mw.index, 0f)
            invalidate()
        }
    }

    private fun discardLayers() {
        for (w in order) {
            w.node?.discardDisplayList()
            w.dirty = true
        }
    }

    private fun stopSources() {
        discardLayers()
        removeCallbacks(startSourcesRunnable)
        if (!sourcesStarted) return
        syncShown()
        sourcesStarted = false
        for (w in order) stopWin(w)
        syncArcs()
    }

    // ================================================================ region

    private val grabPath = Path()
    private val grabRegion = Region()

    /** True while the panel is open or opening (EdgeService closes it when another app comes to the front). */
    val isOpen: Boolean get() = expandedTarget && !holdOpen

    private fun publishRegion() {
        val ww = width
        val hh = height
        var w = 0f
        var h = 0f
        var fl = 0f
        var ex = 0f
        if (hidden) {
            region.setEmpty()
        } else {
            if (ww <= 0 || hh <= 0) return
            if (regionExpanded) {
                w = rad; h = rad
                fl = flareFor(rad, 1f)
            } else {
                // a few dp of slop up / left so a press starts inside the overlay, not on the system gesture strip
                w = regionClosed + dp(6f); h = w
                fl = flareFor(regionClosed, 0f)
            }
            resolveGeom(fl)
            lastDw = -1f // the shared shape parameters were just overwritten: the draw path rebuilds
            buildShape(regionPath, ww - w, hh - h, w, h)
            if (mirror) {
                mirrorMat.setScale(-1f, 1f, ww / 2f, 0f)
                regionPath.transform(mirrorMat)
            }
            clipRegion.set(0, 0, ww, hh)
            region.setPath(regionPath, clipRegion)
            if (!regionExpanded) {
                // invisible grab square at the bottom-right corner (never smaller than the bean + slop): a swipe
                // starting just above / left of the bean comes here, not to the launcher underneath
                // shaped like the bean (a quarter circle around the corner), never a square: the square's far
                // corner blocked taps on things next to the edge
                // the bean's own outline grown to the grab size (same corner, same rounding), so the touch area
                // has the edge's real shape
                ex = max(w, grabPx.toFloat())
                grabPath.rewind()
                buildShape(grabPath, ww - ex, hh - ex, ex, ex)
                touchPath.set(grabPath)
                if (mirror) grabPath.transform(mirrorMat)
                grabRegion.setPath(grabPath, clipRegion)
                region.op(grabRegion, Region.Op.UNION)
            }
        }
        host?.onRegionChanged(region)
        if (ex > w) updateExclusion(ex, ex, fl) else updateExclusion(w, h, fl)
    }

    /**
     * System back-gesture exclusion on the right edge for the bean / open shape only: a strip as wide as the
     * gesture zone, as tall as the shape (flare included; closed: the grab square), capped at 200 dp from the
     * bottom (bean first).
     * The bottom home / recents swipe cannot be excluded by apps.
     */
    private fun updateExclusion(w: Float, h: Float, fl: Float) {
        // only while the window touches the side bezel: a floating / moved edge is not in the gesture zone
        val want = !hidden && width > 0 && height > 0 && w > 0f && placeX == 0
        if (want) {
            val strip = min(ceil(w + fl).toInt(), ceil(dp(48f)).toInt())
            val hh = min(ceil(h + fl).toInt(), ceil(dp(200f)).toInt())
            val l = if (mirror) 0 else max(0, width - strip)
            val t = max(0, height - hh)
            if (exclSet && l == exclL && t == exclT) return
            exclL = l
            exclT = t
            if (mirror) exclRect.set(0, t, strip, height) else exclRect.set(l, t, width, height)
            systemGestureExclusionRects = exclList
            exclSet = true
        } else if (exclSet) {
            systemGestureExclusionRects = emptyList()
            exclSet = false
            exclL = -1
        }
    }

    // ================================================================ paging

    /** Chooses the window to open on: media if playing, else the last used one. */
    private fun pickPage() {
        openPicked = true
        if (contentAlpha > 0.2f) return // mid-collapse: keep what is on screen
        userPaged = false
        val mw = mediaWin
        val li = indexOfId(lastPageId)
        page = if (sourcesStarted && mw != null && mw.playing) mw.index else if (li >= 0) li else 0
        pageS.snapTo(page.toFloat())
        syncSources()
    }

    private fun goToPage(p: Int, vel: Float) {
        page = p.coerceIn(0, nWin - 1)
        lastPageId = order[page].id
        collapsing = false
        settledExpanded = false
        syncShown()
        // The page glides: it eases from where the finger left it to the new page, so the switch
        // reads as one continuous movement. Monotonic - it never overshoots and never springs back.
        pageS.speedScale = stiffScale
        pageS.glideTo(page.toFloat(), vel)
        syncSources()
        requestFrames()
    }

    // ================================================================ animation

    private fun startExpand(setVel: Boolean, vw: Float, vh: Float) {
        if (hidden) return
        ensureResources()
        val was = expandedTarget
        expandedTarget = true
        removeCallbacks(liveTickRunnable)
        updateViz()
        collapsing = false
        settledExpanded = false
        syncShown()
        if (!openPicked) pickPage()
        // Binder-heavy listener setup is kept off the first animation frames.
        removeCallbacks(startSourcesRunnable)
        if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
        regionExpanded = true
        publishRegion()
        wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
        hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
        pageS.syncTarget(page.toFloat())
        wS.target = rad
        hS.target = rad
        if (setVel) {
            wS.velocity = vw
            hS.velocity = vh
        }
        press.target = 1f
        if (!was) haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        requestFrames()
    }

    private fun startCollapse(setVel: Boolean, vw: Float, vh: Float) {
        cancelItemLongPress()
        clearPressed()
        expandedTarget = false
        settledExpanded = false
        syncShown()
        updateViz()
        collapsing = true
        openPicked = false
        val cs = closedSize()
        wS.stiffness = 520f * stiffScale; wS.dampingRatio = 0.92f
        hS.stiffness = 600f * stiffScale; hS.dampingRatio = 0.92f
        wS.target = cs
        hS.target = cs
        if (setVel) {
            wS.velocity = vw
            hS.velocity = vh
        }
        press.target = 1f
        requestFrames()
    }

    private var fastVote = false

    private fun setFast(on: Boolean) {
        if (on == fastVote) return
        fastVote = on
        host?.onFast(on)
    }

    private fun requestFrames() {
        if (framePosted) return
        framePosted = true
        setFast(true)
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        val dt = if (lastFrameNanos == 0L) {
            1f / 120f
        } else {
            // the springs are closed-form (exact for any dt), so a stalled frame catches up in one step
            ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0f, 0.25f)
        }
        lastFrameNanos = frameTimeNanos
        wS.step(dt)
        hS.step(dt)
        pageS.step(dt * 1000f)
        press.step(dt)
        updateDerived(dt)
        invalidate()
        if (allSettled()) {
            wS.snapTo(wS.target)
            hS.snapTo(hS.target)
            pageS.snapTo(pageS.target)
            press.snapTo(press.target)
            updateDerived(0f)
            lastFrameNanos = 0L
            if (!gestureActive) finishSettle()
        } else {
            requestFrames()
        }
    }

    private fun allSettled(): Boolean =
        wS.isSettled(0.3f, 15f) && hS.isSettled(0.3f, 15f) &&
            pageS.settled && press.isSettled(0.002f, 0.1f)

    private fun finishSettle() {
        if (expandedTarget) {
            regionExpanded = true
            settledExpanded = true
            publishRegion()
            syncSources() // drop windows that are no longer next to the settled page
            host?.onSettled(true)
            syncShown()
        } else {
            regionExpanded = false
            settledExpanded = false
            setFast(false)
            syncShown()
            stopSources()
            regionClosed = closedSize()
            publishRegion()
            host?.onSettled(false)
            updateViz()
            syncLiveTick()
            syncClosedSize()
            invalidateBean()
        }
    }

    private fun updateDerived(dt: Float) {
        val span = max(rad - idle, 1f)
        val p = min((wS.value - idle) / span, (hS.value - idle) / span).coerceIn(0f, 1f)
        progress = p
        val t = ((p - 0.55f) / 0.40f).coerceIn(0f, 1f)
        val sm = t * t * (3f - 2f * t)
        contentAlpha = if (collapsing) {
            min(sm, max(0f, contentAlpha - dt / 0.09f))
        } else {
            sm
        }
    }

    // ================================================================ draw

    private fun buildLivePath() {
        val pulse = if (beatOn || ringPulse) pulseS.value else 1f
        val ps = press.value * pulse
        beanScale = ps
        var dw = wS.value * ps
        var dh = hS.value * ps
        val pressGlow = ((1f - press.value) / (1f - PRESS_SCALE)).coerceIn(0f, 1f)
        glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, beat * 0.35f))
        val fl = flareFor(min(dw, dh), progress)
        // Never let the shape (with its flares and spring overshoot) reach the window bounds: no cut edge.
        if (width > 0) dw = min(dw, width - fl - dp(2f))
        if (height > 0) dh = min(dh, height - fl - dp(2f))
        drawW = dw
        drawH = dh
        // content transform shared by drawing and touch (one snapshot per frame)
        cscale = (min(wS.value, hS.value) / rad).coerceIn(0f, 1f)
        coff = (1f - contentAlpha) * dp(16f)
        // Static shape: keep the Path objects untouched so HWUI reuses their cached geometry.
        val rw = rimPaint.strokeWidth
        if (dw == lastDw && dh == lastDh && width == lastW && height == lastH && tipPx == lastTip && fl == lastFl && rw == lastRimW &&
            geomVer == lastGeomVer
        ) return
        lastDw = dw; lastDh = dh; lastW = width; lastH = height; lastTip = tipPx; lastFl = fl; lastRimW = rw
        lastGeomVer = geomVer
        val rt = width.toFloat()
        val bt = height.toFloat()
        resolveGeom(fl)
        buildShapeFit(path, rt, bt, dw, dh)
        val r = max(dw, dh) * 1.15f / GR
        gradScale = r
        gradMatrix.setTranslate(-width.toFloat(), -height.toFloat())
        gradMatrix.postScale(1f / r, 1f / r)
        path.transform(gradMatrix, unitPath)
    }

    override fun onDraw(c: Canvas) {
        val hook = pixelHook
        if (hook != null) hook(c, drawAllFn) else drawAll(c)
    }

    /** All drawing, in the logical right-corner frame; on the left side the canvas is mirrored once, here. */
    private fun drawAll(c: Canvas) {
        buildLivePath()
        if (blurLive) {
            updateBlur()
            backBlur.draw(c)
        }
        if (mirror) {
            c.save()
            c.translate(width.toFloat(), 0f)
            c.scale(-1f, 1f)
        }
        // fade when idle: the whole closed bean under one alpha layer (only while it is not fully opaque)
        var fadeLayer = false
        if (fadeOn && fadeIdleNow()) {
            val fl = fadeLevel()
            if (fl < 0.999f) {
                c.saveLayerAlpha(
                    (width - beanPx).toFloat(), (height - beanPx).toFloat(), width.toFloat(), height.toFloat(),
                    (255f * fl + 0.5f).toInt()
                )
                fadeLayer = true
                if (SystemClock.uptimeMillis() - fadeT0 < EdgeExtras.FADE_MS + 20L) invalidateBean()
            }
        }
        // preview backdrop: a quarter disc that fits inside the window, so it never ends in a cut edge
        if (backdropOn) c.drawCircle(width.toFloat(), height.toFloat(), min(width, height) - 1f, backdropPaint)
        buildLivePath()
        val g = glow
        drawShapeFill(c, g)
        if (touchShow && !expandedTarget) {
            // the invisible idle touch area, dashed in the accent colour in the bean's own shape (a settings aid)
            touchPaint.color = (accent and 0xFFFFFF) or (0x88 shl 24)
            c.drawPath(touchPath, touchPaint)
        }
        val ia = 1f - progress * 3f
        if (liveWanted && !expandedTarget) {
            drawLive(c)
        } else if (ia > 0.01f && !liveWanted) {
            drawIdle(c, ia)
        }
        val a = contentAlpha
        if (a > 0.004f && vizOn && musicPlaying && vizRunning) drawOpenViz(c, a)
        if (a > 0.004f && resourcesReady) drawContent(c, a)
        if (fadeLayer) c.restore()
        if (mirror) c.restore()
    }

    /**
     * The shape fill: base gradient (or pure black), the bass-driven core overlay (radius, brightness, accent
     * mix, drift), then the optional pattern clipped to the shape. Works the same open and closed.
     */
    private fun drawShapeFill(c: Canvas, g: Float) {
        c.save()
        c.translate(width.toFloat(), height.toFloat())
        c.scale(gradScale, gradScale)
        if (blurLive) {
            shapePaint.alpha = (255f * min(opacityPct, 85) / 100f + 0.5f).toInt()
            c.drawPath(unitPath, shapePaint)
            shapePaint.alpha = 255
        } else {
            c.drawPath(unitPath, shapePaint)
        }
        if (glowOn && g > 0.01f) {
            glowPaint.alpha = (255f * g + 0.5f).toInt()
            c.drawPath(unitPath, glowPaint)
        }
        c.restore()
        val b = if (beatOn) bassS else 0f
        if (gradientActive && b > 0.01f) {
            // The core is a radial fade that ends fully transparent inside the shape, so it needs no clip.
            val sz = min(drawW, drawH)
            val coreR = min(sz * 0.8f * (1f + 0.35f * b), sz * 1.02f)
            val k = coreR / GR
            corePaint.alpha = (255f * (0.12f + 0.38f * b).coerceAtMost(0.5f) + 0.5f).toInt()
            c.save()
            c.translate(width + driftX, height + driftY)
            c.scale(k, k)
            c.drawCircle(0f, 0f, GR, corePaint)
            c.restore()
        }
        if (fillId == "dots") {
            c.save()
            c.clipPath(path)
            c.translate(width.toFloat(), height.toFloat())
            looks.drawPattern(c, fillId, patExtent, density, 1.5f, b)
            c.restore()
        }
    }

    /**
     * Crossfade weights: only the two pages around the (clamped) position are visible and their weights sum to 1.
     * A settled page has weight exactly 1 and every other page exactly 0.
     */
    private fun computePageWeights() {
        val last = max(nWin - 1, 0)
        val p = pageS.value.coerceIn(0f, last.toFloat())
        var lo = p.toInt()
        if (lo >= last) lo = max(last - 1, 0)
        var f = p - lo
        if (f < 0.002f) f = 0f else if (f > 0.998f) f = 1f
        pwLo = lo
        // The crossfade finishes early in the glide: the incoming page is fully opaque while it is still
        // rotating into place. The panel therefore never dips half-transparent, and the rotation of the
        // incoming page stays visible for the rest of the glide - that is what makes the swap read as one
        // continuous movement instead of a switch.
        val k = (f / 0.45f).coerceIn(0f, 1f)
        pwHiW = k * k * (3f - 2f * k)
    }

    private fun pageWeight(i: Int): Float = if (i == pwLo) 1f - pwHiW else if (i == pwLo + 1) pwHiW else 0f

    private fun drawContent(c: Canvas, a: Float) {
        if (!c.isHardwareAccelerated) {
            drawContentDirect(c, a)
            return
        }
        val off = coff
        // content grows with the melting shape, so no clip is needed
        val cs = cscale
        val pos = pageS.value
        val s = nodeS
        val sf = s.toFloat()
        val w = width
        val h = height
        computePageWeights()
        for (i in 0 until nWin) {
            val win = order[i]
            val d = i - pos
            val fa = pageWeight(i)
            if (fa <= 0.004f) continue
            var n = win.node
            if (n == null) {
                n = RenderNode("edgeWin" + win.id)
                n.setUseCompositingLayer(true, null)
                n.setClipToBounds(true)
                win.node = n
            }
            n.setPosition(w - s, h - s, w, h)
            // Re-record when the content changed (dirty is set by every content input) or the display list is
            // gone: a node skipped while invisible can lose its list on this device (verified black-window bug).
            if (win.dirty || !n.hasDisplayList()) {
                val rc = n.beginRecording(s, s)
                try {
                    rc.translate(sf, sf)
                    drawWin(rc, win, 1f)
                } finally {
                    n.endRecording()
                }
                win.dirty = false
            }
            n.setPivotX(sf)
            n.setPivotY(sf)
            n.setRotationZ(d * PAGE_DEG)
            n.setAlpha(a * fa)
            n.setScaleX(cs)
            n.setScaleY(cs)
            n.setTranslationX(off)
            n.setTranslationY(off)
            c.drawRenderNode(n)
        }
        c.save()
        c.translate(w + off, h + off)
        c.scale(cs, cs)
        drawPageBars(c, a)
        drawClose(c, a)
        c.restore()
    }

    /** Software path: the same transform contract as the RenderNode path (translate, scale, rotate per page). */
    private fun drawContentDirect(c: Canvas, a: Float) {
        c.save()
        c.clipPath(path)
        c.translate(width + coff, height + coff)
        c.scale(cscale, cscale)
        val pos = pageS.value
        computePageWeights()
        for (i in 0 until nWin) {
            val fa = pageWeight(i)
            if (fa <= 0.004f) continue
            c.save()
            c.rotate((i - pos) * PAGE_DEG)
            drawWin(c, order[i], a * fa)
            c.restore()
        }
        drawPageBars(c, a)
        drawClose(c, a)
        c.restore()
    }

    private fun drawWin(c: Canvas, w: Win, a: Float) {
        w.draw(c, a)
    }

    private fun drawPageBars(c: Canvas, a: Float) {
        // Evenly spaced curved bars on a circle concentric with the X glyph centre, opening toward the panel.
        // The accent highlight is one arc whose ends are interpolated from the continuous page position.
        val n = nWin
        if (n < 2) return
        val rd = xRad
        val ai = (255f * a + 0.5f).toInt()
        val total = (n * 34f).coerceIn(90f, 250f)
        val gap = BAR_GAP + 2f
        val seg = (total - (n - 1) * gap) / n
        val a0 = 225f - total / 2f
        val step = seg + gap
        val cap = Math.toDegrees((dp(1.5f) / rd).toDouble()).toFloat().coerceAtMost(seg / 3f)
        xOval.set(xCx - rd, xCy - rd, xCx + rd, xCy + rd)
        arcPaint.strokeWidth = sw(dp(3f))
        arcPaint.color = cGrey
        arcPaint.alpha = (255f * 0.6f * a + 0.5f).toInt()
        for (i in 0 until n) {
            c.drawArc(xOval, a0 + i * step + cap, seg - 2f * cap, false, arcPaint)
        }
        val pos = pageS.value.coerceIn(0f, (n - 1).toFloat())
        val i0 = min(pos.toInt(), n - 2)
        val f = pos - i0
        val bulge = sin(Math.PI * f).toFloat() * 3f
        val st = a0 + (i0 + f) * step - bulge + cap
        val en = a0 + (i0 + f) * step + seg + bulge - cap
        arcPaint.color = accent
        arcPaint.alpha = ai
        c.drawArc(xOval, st, en - st, false, arcPaint)
    }

    /**
     * Plain X glyph (Lucide x, 2 dp stroke, ~60% white); no disc. The hit area is [xHit].
     */
    private fun drawClose(c: Canvas, a: Float) {
        val cx = xCx
        val cy = xCy
        val sc = 0.6f + 0.4f * a
        val px = pressed == ID_X && pressedWin == null
        c.save()
        val s2 = sc * (if (px) PRESS_SCALE else 1f)
        c.scale(s2, s2, cx, cy)
        linePaint.color = ink
        linePaint.alpha = (255f * (if (px) 1f else 0.6f) * a + 0.5f).toInt()
        linePaint.strokeWidth = sw(dp(2f))
        val h = dp(5.5f)
        c.drawLine(cx - h, cy - h, cx + h, cy + h, linePaint)
        c.drawLine(cx - h, cy + h, cx + h, cy - h, linePaint)
        c.restore()
    }

    // ================================================================ touch

    /** Feeds the tracker raw (screen) coordinates through a pooled copy; the framework's event is never touched. */
    private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
        val t = vt ?: return
        val m = MotionEvent.obtain(ev)
        try {
            m.setLocation(rx, ry)
            t.addMovement(m)
        } finally {
            m.recycle()
        }
    }

    private fun releaseTracker() {
        vt?.recycle()
        vt = null
    }

    private fun cancelItemLongPress() {
        removeCallbacks(longPressRunnable)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_OUTSIDE) {
            val close = expandedTarget && !gestureActive && !holdOpen && outsideFarEnough(ev)
            if (close) startCollapse(false, 0f, 0f)
            return close
        }
        if (hidden) return false
        when (action) {
            MotionEvent.ACTION_DOWN -> return handleDown(ev)
            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return false
                handleMove(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return false
                handleUp(ev, false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!gestureActive) return false
                handleUp(ev, true)
            }
        }
        return true
    }

    /** Local x / raw x in the logical (right-corner) frame: mirrored back when the edge is on the left. */
    private fun lx(ev: MotionEvent): Float = if (mirror) width - ev.x else ev.x
    private fun rawLx(ev: MotionEvent): Float = if (mirror) -ev.rawX else ev.rawX

    private fun handleDown(ev: MotionEvent): Boolean {
        val x = lx(ev)
        val y = ev.y
        val rx = rawLx(ev)
        val ry = ev.rawY
        ensureResources()
        buildLivePath()
        if (fadeOn) fadeWake()
        // the published touch region is the single hit shape (it already includes the flares, the bean slop and,
        // while closed, the invisible grab square - a DOWN there is handled exactly like one on the bean)
        if (!region.contains(ev.x.toInt(), y.toInt())) return false
        downPx = x - width
        downPy = y - height
        lastX = x
        lastY = y
        downAng = Geo.angle(downPx, downPy)
        dAng = 0f

        releaseTracker()
        vt = VelocityTracker.obtain()
        gestureActive = true
        downRawX = rx
        downRawY = ry
        moved = false
        dragMode = 0
        captureWin = null
        clearPressed()
        longFired = false
        addMove(ev, rx, ry)

        if (!expandedTarget) {
            startMode = MODE_IDLE
            updateViz()
            removeCallbacks(liveTickRunnable)
            // Pay the listener-registration cost during the finger-down, before the expand animation.
            ensureSources()
            press.target = PRESS_SCALE
            // a long press on the closed bean (idle or live) hides it for a while (see onLongPress)
            postDelayed(longPressRunnable, longPressMs)
            requestFrames()
        } else {
            startMode = MODE_EXPANDED
            baseW = wS.value
            baseH = hS.value
            pageStart = pageS.value
            if (contentAlpha > 0.3f) {
                val item = hitItem(x, y)
                if (item == ID_X) {
                    setPressed(null, ID_X)
                    invalidate()
                } else if (item >= 0) {
                    val w = hitWin
                    if (w != null && w.usable(item)) {
                        setPressed(w, item)
                        if (w.longPressable(item)) {
                            val ms = w.holdMs(item)
                            postDelayed(longPressRunnable, if (ms > 0L) ms else longPressMs)
                        }
                        invalidate()
                    }
                } else {
                    // a DOWN on a slider / dial / seek bar claims the whole gesture
                    val w = settledWin(x, y)
                    if (w != null && w.capture(lpX, lpY)) {
                        captureWin = w
                        moved = true
                        w.dragTo(lpX, lpY)
                        invalidate()
                    }
                }
            }
        }
        return true
    }

    /** The window under the finger when a page is settled (sets lpX/lpY to its local frame), else null. */
    private fun settledWin(x: Float, y: Float): Win? {
        if (nWin == 0) return null
        val pos = pageS.value
        val s = Math.round(pos).coerceIn(0, nWin - 1)
        if (abs(pos - s) > 0.2f) return null
        toLocal(x, y, s - pos)
        return order[s]
    }

    /** Hit test: ID_X, a window-local item (hitWin set), or -1. */
    private fun hitItem(x: Float, y: Float): Int {
        hitWin = null
        val cs = if (cscale < 0.2f) 0.2f else cscale
        val px = (x - width - coff) / cs
        val py = (y - height - coff) / cs
        if ((px - xCx) * (px - xCx) + (py - xCy) * (py - xCy) <= xHit * xHit) return ID_X
        val w = settledWin(x, y) ?: return -1
        val i = w.hit(lpX, lpY)
        if (i >= 0) hitWin = w
        return i
    }

    /** Converts a view point to the local (un-rotated) polar frame of a window page d pages away. */
    private fun toLocal(x: Float, y: Float, d: Float) {
        val sc = if (cscale < 0.2f) 0.2f else cscale
        val px = (x - width - coff) / sc
        val py = (y - height - coff) / sc
        val ang = Math.toRadians((-d * PAGE_DEG).toDouble())
        val cs = cos(ang).toFloat()
        val sn = sin(ang).toFloat()
        lpX = px * cs - py * sn
        lpY = px * sn + py * cs
    }

    private fun handleMove(ev: MotionEvent) {
        val rx = rawLx(ev)
        val ry = ev.rawY
        addMove(ev, rx, ry)
        lastX = lx(ev)
        lastY = ev.y
        val cw = captureWin
        if (cw != null) {
            moved = true
            toLocal(lx(ev), ev.y, cw.index - pageS.value)
            cw.dragTo(lpX, lpY)
            return
        }
        val dx = rx - downRawX
        val dy = ry - downRawY
        if (dragMode == 0) {
            if (sqrt(dx * dx + dy * dy) > touchSlop) {
                moved = true
                cancelItemLongPress()
                if (pressed >= 0) {
                    clearPressed()
                    invalidate()
                }
                if (startMode == MODE_IDLE) {
                    // intentional up / left motion only: never while moving away from the screen corner
                    if (max(-dx, -dy) > touchSlop && -dx > -0.5f * touchSlop && -dy > -0.5f * touchSlop) beginDrag(1)
                }
            }
            if (startMode != MODE_IDLE && sqrt(dx * dx + dy * dy) > pageSlop) {
                // any movement on the open panel is a page swipe (angular about the corner); closing is
                // decided on release and only for a clearly radial inward fling. Less sensitive = larger slop.
                beginDrag(3)
            }
        }
        if (dragMode != 0) applyDrag(dx, dy)
    }

    private fun beginDrag(mode: Int) {
        dragMode = mode
        ensureResources()
        collapsing = false
        settledExpanded = false
        syncShown()
        removeCallbacks(liveTickRunnable)
        if (mode == 1 && !openPicked) pickPage()
        removeCallbacks(startSourcesRunnable)
        if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
        regionExpanded = true
        publishRegion()
        updateViz()
        when (mode) {
            1 -> {
                baseW = max(idle, wS.value)
                baseH = max(idle, hS.value)
            }
            2 -> {
                baseW = wS.value
                baseH = hS.value
            }
            else -> {
                pageStart = pageS.value
                dAngBase = Float.NaN
                dragShift = max(0.9f * rad, 1f)
                // a finger on the panel takes over from any glide in flight
                pageS.followTo(pageS.value)
            }
        }
        press.target = 1f
        requestFrames()
    }

    private fun rubber(v: Float, maxV: Float): Float {
        if (v <= idle) return idle
        if (v <= maxV) return v
        return maxV + min((v - maxV) * 0.3f, dp(18f))
    }

    private fun setSizeDirect(w: Float, h: Float) {
        wS.value = w; wS.target = w; wS.velocity = 0f
        hS.value = h; hS.target = h; hS.velocity = 0f
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            1 -> setSizeDirect(rubber(baseW - dx, rad), rubber(baseH - dy, rad))
            2 -> {
                val hh = rubber(baseH - dy, baseH)
                val fr = (hh - idle) / max(baseH - idle, 1f)
                setSizeDirect(idle + (baseW - idle) * fr, hh)
            }
            3 -> {
                val cpx = downPx + dx
                val cpy = downPy + dy
                if (cpx * cpx + cpy * cpy > dp(18f) * dp(18f)) {
                    // the dead zone is not part of the swipe: the page starts moving from zero, no jump
                    val ang = Geo.angle(cpx, cpy) - downAng
                    if (dAngBase.isNaN()) dAngBase = ang
                    dAng = ang - dAngBase
                }
                val last = (nWin - 1).toFloat()
                var pos = pageStart - dAng / pageFollowDeg
                if (pos < 0f) pos *= 0.3f
                else if (pos > last) pos = last + (pos - last) * 0.3f
                pageS.dragTo(pos)
            }
        }
        updateDerived(0f)
        invalidate()
    }

    private fun finishDrag(vx: Float, vy: Float) {
        val mode = dragMode
        val outward = if (mode == 2) -vy else -(vx + vy) * 0.5f
        val thr = dp(500f)
        val doExpand = when {
            outward > thr -> true
            outward < -thr -> false
            mode == 1 -> progress > 0.33f
            else -> progress > 0.67f
        }
        dragMode = 0
        val vh = -vy
        val vw = if (mode == 2) -vy * (baseW - idle) / max(baseH - idle, 1f) else -vx
        if (doExpand) startExpand(true, vw, vh) else startCollapse(true, vw, vh)
    }

    /** True unless the outside touch landed within 24 dp of the shape edge (likely a missed swipe). */
    private fun outsideFarEnough(ev: MotionEvent): Boolean {
        val px = lx(ev) - width
        val py = ev.y - height
        val d = sqrt(px * px + py * py)
        return d - rad >= dp(24f)
    }

    /** Page change from the finger's angular travel about the corner (deg) and angular velocity (deg/s). */
    private fun finishPage(angle: Float, omega: Float) {
        val base = Math.round(pageStart).coerceIn(0, nWin - 1)
        var target = base
        // decreasing angle (toward the bottom edge) = next window
        if (angle <= -swipeCommitDeg || (omega <= -swipeFlingDps && angle <= -swipeMinFlingDeg)) target = base + 1
        else if (angle >= swipeCommitDeg || (omega >= swipeFlingDps && angle >= swipeMinFlingDeg)) target = base - 1
        target = target.coerceIn(0, nWin - 1)
        if (target != base) userPaged = true
        dragMode = 0
        goToPage(target, -omega / pageFollowDeg)
    }

    private fun handleUp(ev: MotionEvent, cancel: Boolean) {
        val rx = rawLx(ev)
        val ry = ev.rawY
        val x = lx(ev)
        val y = ev.y
        cancelItemLongPress()
        var vx = 0f
        var vy = 0f
        val t = vt
        if (t != null) {
            if (!cancel) addMove(ev, rx, ry)
            t.computeCurrentVelocity(1000)
            vx = t.xVelocity
            vy = t.yVelocity
        }
        releaseTracker()
        val item = pressed
        val pw = pressedWin
        clearPressed()
        gestureActive = false
        val quick = !moved && (ev.eventTime - ev.downTime) < TAP_MS

        val cw = captureWin
        if (cw != null) {
            captureWin = null
            cw.dragEnd(cancel)
        } else if (dragMode == 3) {
            var omega = 0f
            var closeIt = false
            val cpx = x - width
            val cpy = y - height
            val r = sqrt(cpx * cpx + cpy * cpy)
            if (!cancel && r > dp(20f)) {
                val ux = cpx / r
                val uy = cpy / r
                val vr = vx * ux + vy * uy
                val vt = vx * (-uy) + vy * ux
                omega = Math.toDegrees((vt / r).toDouble()).toFloat()
                val inward = -vr
                val downR = sqrt(downPx * downPx + downPy * downPy)
                closeIt = inward >= dp(800f) && inward >= 2.5f * abs(vt) && downR - r >= dp(40f) && abs(dAng) < 8f
            }
            if (closeIt) {
                dragMode = 0
                startCollapse(false, 0f, 0f)
            } else {
                finishPage(if (cancel) 0f else dAng, omega)
            }
        } else if (dragMode != 0) {
            finishDrag(if (cancel) 0f else vx, if (cancel) 0f else vy)
        } else if (startMode == MODE_IDLE) {
            press.target = 1f
            if (!cancel && quick) beanTapGate(x, y)
        } else if (!cancel && !longFired && !moved && item >= 0 && hitItem(x, y) == item && hitWin === pw) {
            if (item == ID_X) {
                startCollapse(false, 0f, 0f)
            } else {
                pw?.onTap(item)
            }
        }
        updateViz()
        syncClosedSize()
        syncLiveTick()
        if (fadeOn) fadeArm()
        invalidate()
        requestFrames()
    }

    private fun onLongPress() {
        if (!gestureActive) return
        if (startMode == MODE_IDLE) {
            if (expandedTarget || dragMode != 0 || moved) return
            longFired = true
            val act = Prefs.longAction(context)
            if (act == Prefs.LA_NONE) return
            haptic(HapticFeedbackConstants.LONG_PRESS)
            runLongAction(act)
            return
        }
        val item = pressed
        val w = pressedWin
        if (w != null && w.longPressable(item)) {
            clearPressed()
            longFired = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
            val launched = w.onLongPress(item)
            invalidate()
            if (launched) {
                startCollapse(false, 0f, 0f)
            }
        }
    }

    // ================================================================ init

    init {
        floatOn = Prefs.floatOn(ctx)
        floatCornerFrac = Prefs.floatCorner(ctx).let { if (it < 0) -1f else it / 100f }
        mirror = Prefs.sideLeft(ctx)
        Geo.mirror = mirror
        placeX = placeXFor(ctx)
        placeY = placeYFor(ctx)
        applyLooksNow()
        reloadWindows()
    }

    internal companion object {
        /** Corner rounding of the floating island (dp). */
        const val ROUND_DP = 12f
        const val FLOAT_TIP_DP = 16f

        /**
         * Inset in px of the floating island from BOTH the bottom and the side edge: the float gap, clamped so the
         * open panel stays on screen on both axes. Not floating: 0 (the edge sits in the corner). edgePos is ignored.
         */
        fun floatInset(ctx: Context): Int {
            if (!Prefs.floatOn(ctx)) return 0
            val d = ctx.resources.displayMetrics.density
            val base = Prefs.floatGapDp(ctx) * d
            val b = try {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
            } catch (e: Exception) {
                null
            }
            val dm = ctx.resources.displayMetrics
            val sw = (b?.width() ?: dm.widthPixels).toFloat()
            val sh = (b?.height() ?: dm.heightPixels).toFloat()
            val radPx = Prefs.openDp(ctx).coerceIn(170f, 300f) * d
            val maxI = max(0f, min(sw, sh) - radPx - 4f * d)
            return min(base, maxI).toInt()
        }

        /** Distance in px of the window's side edge from the screen's side edge (LayoutParams.x). */
        fun placeXFor(ctx: Context): Int = floatInset(ctx)

        /** Distance in px of the window's bottom edge from the screen's bottom edge (LayoutParams.y). */
        fun placeYFor(ctx: Context): Int = floatInset(ctx)

        const val PRESS_SCALE = 0.92f
        const val TAP_MS = 300L
        const val MODE_IDLE = 0
        const val MODE_EXPANDED = 1
        const val ID_X = 1000
        const val PAGE_DEG = 70f
        const val SL_A0 = 8f
        const val SL_A1 = 82f
        const val LV_A0 = 16f
        const val LV_A1 = 74f
        const val LIVE_SCALE = 1.8f
        val C_RED = Color.parseColor("#FF4B3E")
        val C_BAR = Color.parseColor("#5A5A5E")
        val C_CREAM = Color.parseColor("#F2EDE4")
        val C_GREEN = Color.parseColor("#30D158")
        val C_DECLINE = Color.parseColor("#FF453A")
        const val BAR_GAP = 4f
        const val GR = 100f

        // The overlay's content colours. Black and white plus the user's accent.
        private val DEF_FILL = Color.parseColor("#1C1C1E")
        private val DEF_TRACK = Color.parseColor("#2C2C2E")
        private val DEF_GREY = Color.parseColor("#8E8E93")

        /** The overlay's placeholder fill (unfilled controls). */
        val C_FILL = DEF_FILL

        /** The overlay's track colour (slider tracks). */
        val C_TRACK = DEF_TRACK

        /** The overlay's dim colour (secondary text). */
        val C_GREY = DEF_GREY

        /** The overlay's ink colour (primary content). */
        val C_FG = Color.WHITE

        /** The overlay's ink on a coloured surface. */
        val C_ONFG = Color.BLACK
    }
}
