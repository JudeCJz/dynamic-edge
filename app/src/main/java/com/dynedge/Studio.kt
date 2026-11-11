package com.dynedge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The settings screens, built from one set of flat, captioned controls.
 *
 * [mode] picks the page:
 * - [MODE_MAIN] (MainActivity): title bar with a gear (accent dot while permissions are missing), the status
 *   banner when the overlay needs attention, and one scrolling page: Colour, Shape, Windows, Music, Motion.
 *   The Windows list: switch = on/off, row tap = that window's editor (Quick -> QuickEditorActivity), grip =
 *   drag to reorder at once (long-press on the row also lifts it).
 * - [MODE_SETTINGS] (SettingsActivity): permissions, pause in apps, saved looks, resets.
 * - a window id (WindowEditActivity): that window's editor; see [editorPanel].
 *
 * Every control writes Prefs; one SharedPreferences listener keeps the page in step, and the overlay service
 * listens to the same keys, so the edge updates live.
 *
 * Symmetry rules: every section has the same 8 dp side padding; rows put a 24 dp icon column at 8 dp, captions at
 * the same x, and the right-hand control on the same right edge; grids and thumbnail rows use equal-weight
 * columns.
 */
class Studio(private val a: Activity, private val mode: String = MODE_MAIN) {

    val root: FrameLayout = FrameLayout(a)
    /** The one page: every setting of this screen. */
    private val page = LinearLayout(a)
    private val pageScroll = ScrollView(a)
    private val banner = LinearLayout(a)
    private val bannerText = a.txt("", 13f, Skin.TEXT, true)
    private val bannerAction = a.txt("", 13f, Skin.TEXT, true)
    private var bannerClick: () -> Unit = {}
    private var gear: DotIcon? = null

    private val tabs: IntArray = when (mode) {
        MODE_MAIN -> intArrayOf(T_COLOUR, T_SHAPE, T_WINDOWS, T_MUSIC, T_MOTION)
        MODE_SETTINGS -> intArrayOf(T_MORE)
        else -> intArrayOf(T_EDIT)
    }

    private var acc = a.accentUi()
    private var bottomInset = 0
    private var undoView: View? = null
    private var picking = false
    private var resumed = false

    /** Was the last preview the open state? The flare slider previews in the state the edge is in. */
    private var previewOpen = false

    // live views kept in step by the prefs listener
    private val vizThumbs = ArrayList<EdgeThumb>()
    private val choices = ArrayList<Choice>()
    private val lookThumbs = ArrayList<EdgeThumb>()
    private var ringPreview: RingPreview? = null

    /** The synthetic beat for the animated visualizer thumbnails (no canvas needed). */
    private val beatDriver = BeatDriver {
        for (i in vizThumbs.indices) vizThumbs[i].invalidate()
    }

    private class Choice(val thumbs: List<EdgeThumb>, val current: () -> String)

    private var needRebuild = false
    private var needLook = false
    private var needWins = false
    private var needApps = false
    private var needMore = false
    private var flushPosted = false
    private val flush = Runnable { flushNow() }

    /** Closes the real overlay again after the motion replay. */
    private val closePreview = Runnable { preview(Prefs.PREVIEW_NONE) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPref(key) }

    private fun edit() = Prefs.prefs(a).edit()

    init {
        root.setBackgroundColor(Skin.BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            bottomInset = bars.bottom
            applyBottomPad()
            WindowInsets.CONSUMED
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(buildTopBar(), a.lp(MATCH, WRAP))

        // ---- status banner (main page only, only when the overlay needs attention)
        buildBanner()
        col.addView(banner, a.lp(MATCH, WRAP).apply {
            leftMargin = a.dp(16); rightMargin = a.dp(16); topMargin = a.dp(4); bottomMargin = a.dp(4)
        })

        page.orientation = LinearLayout.VERTICAL
        pageScroll.addView(page, MATCH, WRAP)
        col.addView(pageScroll, a.lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        buildPanels()
        Prefs.prefs(a).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun buildTopBar(): View = when (mode) {
        MODE_MAIN -> {
            val bar = a.topBar("Dynamic Edge", null) as LinearLayout
            bar.setPadding(a.dp(Skin.PAD), 0, a.dp(4), 0)
            val g = DotIcon(a, R.drawable.ic_settings, "Settings").apply {
                tint = acc
                dotColor = acc
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    a.startActivity(Intent(a, SettingsActivity::class.java))
                }
            }
            gear = g
            bar.addView(g)
            bar
        }
        MODE_SETTINGS -> a.topBar("Settings") { a.finish() }
        else -> a.topBar(WindowsConfig.label(mode)) { a.finish() }
    }

    // ================================================================ lifecycle

    fun onResume() {
        acc = a.accentUi()
        refreshStatus()
        // permissions, apps and windows may have changed in another screen (the first resume follows the build)
        if (resumed) {
            rebuildPanel(T_WINDOWS)
            rebuildPanel(T_EDIT)
            rebuildPanel(T_MORE)
        }
        resumed = true
        syncBeat()
        root.postDelayed({ if (!a.isDestroyed) refreshStatus() }, 1500L)
    }

    fun onPause() {
        resumed = false
        beatDriver.stop()
        root.removeCallbacks(closePreview)
    }

    fun onDestroy() {
        Prefs.prefs(a).unregisterOnSharedPreferenceChangeListener(prefListener)
        root.removeCallbacks(flush)
        root.removeCallbacks(closePreview)
        beatDriver.stop()
    }

    // ================================================================ the beat

    /** The synthetic beat animates the music thumbnails while the main page is up. */
    private fun syncBeat() {
        if (resumed && mode == MODE_MAIN) beatDriver.start() else beatDriver.stop()
    }

    // ================================================================ prefs -> views

    private fun onPref(key: String?) {
        when (key) {
            null, Prefs.KEY_PREVIEW, Prefs.KEY_PAUSE_ON, Prefs.KEY_PAUSE_APPS, Prefs.KEY_PAUSED_BY_GATE,
            Prefs.KEY_PAUSED_PKG, Prefs.KEY_PAUSED_AT -> return
            Prefs.KEY_ACCENT -> {
                needLook = true
                if (!picking) needRebuild = true
            }
            WindowsConfig.KEY, QuickConfig.KEY -> needWins = true
            Prefs.KEY_APPS, IconTheme.KEY -> needApps = true
            "saved_looks" -> needMore = true
            else -> needLook = true
        }
        if (!flushPosted) {
            flushPosted = true
            root.postOnAnimation(flush)
        }
    }

    private fun flushNow() {
        flushPosted = false
        if (needRebuild) {
            needRebuild = false
            needLook = false
            needApps = false
            needMore = false
            rebuildAll()
        }
        if (needLook) {
            needLook = false
            syncLookViews()
        }
        if (needWins) {
            needWins = false
            rebuildPanel(T_WINDOWS)
        }
        if (needApps) {
            needApps = false
            if (mode == "apps") rebuildPanel(T_EDIT)
        }
        if (needMore) {
            needMore = false
            rebuildPanel(T_MORE)
        }
    }

    private fun syncLookViews() {
        for (t in lookThumbs) t.load(a)
        for (ch in choices) {
            val cur = ch.current()
            for (t in ch.thumbs) t.chosen = t.style == cur
        }
        ringPreview?.reload()
    }

    /** Accent changed (or a look was applied / reset): rebuild every panel in the new colours. */
    private fun rebuildAll() {
        acc = a.accentUi()
        gear?.tint = acc
        gear?.dotColor = acc
        refreshStatus()
        buildPanels()
    }

    // ================================================================ status banner

    private fun buildBanner() {
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.minimumHeight = a.dp(44)
        banner.setPadding(a.dp(16), 0, a.dp(8), 0)
        banner.isClickable = true
        banner.visibility = View.GONE
        bannerText.maxLines = 1
        bannerText.ellipsize = TextUtils.TruncateAt.END
        banner.addView(a.icon(R.drawable.ic_alert, Skin.TEXT, 18).also { it.tag = "ic" })
        banner.addView(bannerText, a.lp(0, WRAP, 1f).apply { leftMargin = a.dp(10) })
        banner.addView(bannerAction, a.lp(WRAP, WRAP).apply { rightMargin = a.dp(4) })
        banner.addView(a.icon(R.drawable.ic_chevron_right, Skin.TEXT, 18).also { it.tag = "chev" })
        banner.setOnClickListener { bannerClick() }
    }

    private fun openSettings() {
        a.startActivity(Intent(a, SettingsActivity::class.java))
    }

    private fun refreshStatus() {
        if (mode != MODE_MAIN) return
        val missing = Perms.missing(a).size
        gear?.dot = missing > 0
        val (msg, action, click) = when {
            !Perms.overlayEnabled(a) -> Triple("Overlay is off", "Turn on", {
                Perms.open(a) { Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            })
            !EdgeService.connected -> Triple("Overlay stopped", "Fix", { Perms.open(a) { Perms.appDetails(a) } })
            missing > 0 -> Triple(if (missing == 1) "1 permission needed" else "$missing permissions needed", "Fix", { openSettings() })
            else -> Triple("", "", {})
        }
        if (msg.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        val ink = Skin.onColor(acc)
        banner.background = GradientDrawable().apply {
            setColor(acc)
            cornerRadius = a.dpf(22f)
        }
        bannerText.text = msg
        bannerText.setTextColor(ink)
        bannerAction.text = action
        bannerAction.setTextColor(ink)
        for (i in 0 until banner.childCount) (banner.getChildAt(i) as? ImageView)?.setColorFilter(ink)
        banner.contentDescription = "$msg. $action"
        bannerClick = click
        banner.visibility = View.VISIBLE
    }

    // ================================================================ panels

    private fun bottomPad(): Int = maxOf(a.dp(12), a.dp((Prefs.idleDp(a) + 24f).toInt()) - bottomInset)

    private fun applyBottomPad() {
        val pad = bottomPad()
        page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, pad)
        undoView?.let {
            (it.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = pad
            it.requestLayout()
        }
    }

    private fun buildPanels() {
        vizThumbs.clear()
        choices.clear()
        lookThumbs.clear()
        ringPreview = null
        page.removeAllViews()
        for ((k, t) in tabs.withIndex()) page.addView(section(t, k == 0))
    }

    private fun rebuildPanel(t: Int) {
        val i = tabs.indexOf(t)
        if (i < 0 || page.childCount != tabs.size) return
        val y = pageScroll.scrollY
        if (t == T_EDIT) ringPreview = null
        page.removeViewAt(i)
        page.addView(section(t, i == 0), i)
        pageScroll.post { pageScroll.scrollTo(0, y) }
    }

    /** One group of the page: its settings, separated by space (no heading: every row is captioned). */
    private fun section(t: Int, first: Boolean): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(8), a.dp(if (first) 6 else 22), a.dp(8), a.dp(4))
        }
        when (t) {
            T_COLOUR -> colourPanel(box)
            T_SHAPE -> shapePanel(box)
            T_WINDOWS -> windowsPanel(box)
            T_MUSIC -> musicPanel(box)
            T_MOTION -> motionPanel(box)
            T_MORE -> morePanel(box)
            T_EDIT -> editorPanel(box)
        }
        return box
    }

    // ---------------------------------------------------------------- control factories

    private fun row(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = a.dp(52)
    }

    /** The 24 dp icon column every row shares (empty when [res] is 0, so captions still line up). */
    private fun lead(res: Int): View =
        if (res != 0) a.icon(res, acc, 20).apply { layoutParams = a.lp(a.dp(24), a.dp(24)) }
        else View(a).apply { layoutParams = a.lp(a.dp(24), a.dp(24)) }

    /** A group heading: the accent icon in the row icon column and the name in grey. */
    private fun captionLine(res: Int, caption: String): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(8), a.dp(12), a.dp(8), a.dp(4))
        addView(lead(res))
        addView(a.txt(caption, 13f, Skin.TEXT_2).apply {
            setPadding(a.dp(14), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    /** The name of a setting, next to its control: one fixed-width column so every track starts at one x. */
    private fun captionText(caption: String): TextView = a.txt(caption, 15f, Skin.TEXT).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * A slider with its caption: the icon column, the name column, then the track to the shared right edge.
     * The number still appears only while dragging.
     */
    private fun slider(
        res: Int, caption: String, min: Float, max: Float, value: Float, tick: Float?,
        start: () -> Unit = {}, stop: () -> Unit = {}, label: TextView? = null,
        save: (Float) -> Unit
    ): View {
        val s = FlatSlider(a).apply {
            this.min = min
            this.max = max
            this.tick = tick
            this.value = value
            accent = acc
            contentDescription = caption
            onStart = start
            onStop = stop
            onChange = save
        }
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(8), 0, a.dp(4), 0)
        }
        r.addView(lead(res))
        r.addView(label ?: captionText(caption), a.lp(a.dp(CAPTION_W), WRAP).apply { leftMargin = a.dp(14) })
        r.addView(s, a.lp(0, WRAP, 1f))
        return r
    }

    /** A round control with its caption under it (for rows that hold several controls). */
    private fun cell(control: View, caption: String): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(control, a.lp(WRAP, WRAP))
        addView(smallCaption(caption))
    }

    private fun smallCaption(caption: String): TextView = a.txt(caption, 11f, Skin.TEXT_2).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(a.dp(2), a.dp(3), a.dp(2), 0)
    }

    /** One full-width setting row: the icon, the name and a switch on the right. */
    private fun toggleRow(res: Int, caption: String, on: Boolean, change: (Boolean) -> Unit): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(8), 0, a.dp(4), 0)
            isClickable = true
            background = a.rippleBg()
            contentDescription = caption
        }
        r.addView(lead(res))
        r.addView(a.txt(caption, 15f, Skin.TEXT), a.lp(0, WRAP, 1f).apply { leftMargin = a.dp(14) })
        val t = ToggleSwitch(a, caption).apply {
            this.accent = acc
            checked = on
            onToggle = change
        }
        r.addView(t, a.lp(a.dp(52), a.dp(48)))
        r.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            t.checked = !t.checked
            change(t.checked)
        }
        return r
    }

    /** A switch row bound to a Boolean pref that defaults to true (the window editor options). */
    private fun flagRow(res: Int, caption: String, key: String): View =
        toggleRow(res, caption, Prefs.flag(a, key)) { v -> edit().putBoolean(key, v).apply() }

    /** A full-width row that opens something: icon, name, chevron on the shared right edge. */
    private fun linkRow(res: Int, caption: String, click: () -> Unit): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(8), 0, a.dp(4), 0)
            isClickable = true
            background = a.rippleBg()
            contentDescription = caption
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                click()
            }
        }
        r.addView(lead(res))
        r.addView(a.txt(caption, 15f, Skin.TEXT), a.lp(0, WRAP, 1f).apply { leftMargin = a.dp(14) })
        r.addView(a.icon(R.drawable.ic_chevron_right, acc, 20), a.lp(a.dp(52), a.dp(24)))
        return r
    }

    /** Icon with a short caption (only where the icon alone would be ambiguous). */
    private fun action(res: Int, label: String, desc: String, click: () -> Unit): View {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = a.dp(48)
            setPadding(a.dp(12), 0, a.dp(14), 0)
            isClickable = true
            background = a.rippleBg()
            contentDescription = desc
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                click()
            }
        }
        v.addView(a.icon(res, acc, 20))
        v.addView(a.txt(label, 13f, Skin.TEXT_2).apply { setPadding(a.dp(8), 0, 0, 0) })
        return v
    }

    private fun hscroll(content: LinearLayout): HorizontalScrollView = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, WRAP, WRAP)
    }

    /** Show the real overlay while the user works with a slider. */
    private fun preview(mode: String) {
        previewOpen = mode == Prefs.PREVIEW_OPEN
        edit().putString(Prefs.KEY_PREVIEW, mode).apply()
    }

    /**
     * Replay the open-and-close motion on the real overlay: the edge opens with the current speed and
     * closes again.
     */
    private fun replayMotion() {
        preview(Prefs.PREVIEW_OPEN)
        root.removeCallbacks(closePreview)
        root.postDelayed(closePreview, 1600L)
    }

    // ---------------------------------------------------------------- Colour

    /** The edge colour: the only colour the user picks (the rest of the look is black and white). */
    private fun colourPanel(box: LinearLayout) {
        val accent = Prefs.accent(a)
        box.addView(swatchStrip(R.drawable.ic_palette, "Edge colour", Prefs.ACCENTS, accent, { col ->
            edit().putInt(Prefs.KEY_ACCENT, col).apply()
        }) {
            pick("Edge colour", accent, Prefs.KEY_ACCENT)
        })
    }

    private fun pick(title: String, colour: Int, key: String) {
        picking = true
        ColorPicker.show(a, title, colour, acc, null, colour, {
            picking = false
            rebuildAll()
        }) { col -> edit().putInt(key, col).apply() }
    }

    private fun swatchStrip(lead: Int, desc: String, colors: IntArray, cur: Int, onPick: (Int) -> Unit, onCustom: () -> Unit): View {
        val strip = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sw = ArrayList<Swatch>()
        val curOpaque = cur or (0xFF shl 24)
        val custom = colors.none { (it or (0xFF shl 24)) == curOpaque }
        val hue = HueCell(a).apply {
            colour = curOpaque
            chosen = custom
            ring = acc
            contentDescription = "$desc, custom"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCustom()
            }
        }
        for (col in colors) {
            val s = Swatch(a, col, (col or (0xFF shl 24)) == curOpaque, acc).apply {
                isClickable = true
                contentDescription = "$desc ${ColorPicker.hex(col)}"
            }
            s.setOnClickListener {
                s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                for (o in sw) {
                    o.chosen = o === s
                    o.invalidate()
                }
                hue.chosen = false
                onPick(col)
            }
            sw.add(s)
            strip.addView(s, a.lp(0, a.dp(44), 1f))
        }
        strip.addView(hue, a.lp(0, a.dp(44), 1f))
        val block = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        block.addView(captionLine(lead, desc))
        // equal-weight cells: the swatches spread evenly across the full width
        block.addView(strip, a.lp(MATCH, WRAP))
        return block
    }

    private fun thumb(kind: Int, style: String, desc: String, chosen: Boolean, onPick: () -> Unit): EdgeThumb =
        EdgeThumb(a, kind, style).apply {
            load(a)
            ring = acc
            this.chosen = chosen
            contentDescription = desc
            background = a.rippleBg()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onPick()
            }
            lookThumbs.add(this)
        }

    // ---------------------------------------------------------------- Shape

    private fun shapePanel(box: LinearLayout) {
        box.addView(slider(R.drawable.ic_bean, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(a), Prefs.IDLE_DEF,
            start = { preview(Prefs.PREVIEW_IDLE) }, stop = { preview(Prefs.PREVIEW_NONE) }) {
            Prefs.setIdleDp(a, it)
        })
        box.addView(slider(R.drawable.ic_quarter, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(a), Prefs.OPEN_DEF,
            start = { preview(Prefs.PREVIEW_OPEN) }, stop = { preview(Prefs.PREVIEW_NONE) }) {
            Prefs.setOpenDp(a, it)
        })
        box.addView(slider(R.drawable.ic_flare, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(a), Prefs.FLARE_DEF,
            start = { preview(if (previewOpen) Prefs.PREVIEW_OPEN else Prefs.PREVIEW_IDLE) },
            stop = { preview(Prefs.PREVIEW_NONE) }) {
            edit().putFloat(Prefs.KEY_FLARE, it).apply()
        })
        // invisible on the phone, so it only changes the touch area; the dashed square is only shown while dragging
        box.addView(slider(R.drawable.ic_pointer, "Grab area", Prefs.GRAB_MIN, Prefs.GRAB_MAX, Prefs.grabDp(a), Prefs.GRAB_DEF,
            start = { edit().putBoolean(Prefs.KEY_TOUCH_SHOW, true).apply() },
            stop = { edit().putBoolean(Prefs.KEY_TOUCH_SHOW, false).apply() }) {
            Prefs.setGrabDp(a, it)
        })
        box.addView(slider(R.drawable.ic_droplet, "Opacity", Prefs.OPACITY_MIN.toFloat(), 100f, Prefs.opacityPct(a).toFloat(), 100f) {
            edit().putInt(Prefs.KEY_OPACITY, it.toInt()).apply()
        })
        box.addView(toggleRow(R.drawable.ic_sparkles, "Glow", Prefs.glow(a)) { v ->
            edit().putBoolean(Prefs.KEY_GLOW, v).apply()
        })
    }

    // ---------------------------------------------------------------- Windows

    /** The windows: switch = on / off, row = that window's editor, grip = drag to reorder. */
    private fun windowsPanel(box: LinearLayout) {
        box.addView(captionLine(R.drawable.ic_panel_top, "Windows"))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (e in WindowsConfig.load(a)) list.addView(windowRow(e, list), a.lp(MATCH, WRAP))
        box.addView(list, a.lp(MATCH, WRAP))
    }

    private fun openEditor(id: String) {
        if (id == "quick") a.startActivity(Intent(a, QuickEditorActivity::class.java))
        else a.startActivity(WindowEditActivity.intent(a, id))
    }

    /** One window row: icon, name, grip (drag at once), switch. Tap the row = edit the window. */
    private fun windowRow(e: WindowsConfig.Entry, list: LinearLayout): View {
        val label = WindowsConfig.label(e.id)
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(8), 0, a.dp(4), 0)
            isClickable = true
            background = a.rippleBg()
            contentDescription = "$label. Tap to edit"
            tag = e.id
        }
        r.addView(lead(WindowsConfig.icon(e.id)))
        r.addView(a.txt(label, 15f, Skin.TEXT), a.lp(0, WRAP, 1f).apply { leftMargin = a.dp(14) })
        val grip = a.icon(R.drawable.ic_grip, Skin.TEXT_2, 20).apply {
            contentDescription = "Drag $label"
            layoutParams = a.lp(a.dp(44), a.dp(48))
        }
        r.addView(grip)
        r.addView(ToggleSwitch(a, label).apply {
            accent = acc
            checked = e.on
            onToggle = { on -> setWindowOn(e.id, on) }
        }, a.lp(a.dp(52), a.dp(48)))
        val tap = {
            r.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            openEditor(e.id)
        }
        listDrag(grip, r, list, true, null)
        listDrag(r, r, list, false, tap)
        return r
    }

    private fun setWindowOn(id: String, on: Boolean) {
        WindowsConfig.save(a, WindowsConfig.load(a).map { if (it.id == id) it.copy(on = on) else it })
    }

    /** Persists the window order [ids]. */
    private fun saveWindowOrder(ids: List<String>) {
        val on = HashMap<String, Boolean>()
        for (e in WindowsConfig.load(a)) on[e.id] = e.on
        WindowsConfig.save(a, ids.map { WindowsConfig.Entry(it, on[it] ?: true) })
    }

    /**
     * Drag to reorder in a vertical list. [target] lifts [row] at once ([immediate], the grip) or after a long
     * press. The row follows the finger, the rows it passes glide one slot the other way; views are never
     * removed during the gesture (that would cancel it). Release animates the row into its slot and saves the
     * order; the prefs listener then rebuilds the list in that order. A short tap calls [onTap].
     */
    private fun listDrag(target: View, row: View, list: LinearLayout, immediate: Boolean, onTap: (() -> Unit)?) {
        val slop = ViewConfiguration.get(a).scaledTouchSlop
        var downY = 0f
        var downX = 0f
        var dragging = false
        var moved = false
        var from = 0
        var to = 0
        val lift = Runnable {
            dragging = true
            moved = true
            from = list.indexOfChild(row)
            to = from
            pageScroll.requestDisallowInterceptTouchEvent(true)
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            row.translationZ = a.dpf(6f)
            // opaque while lifted, so the rows it passes do not show through
            row.setBackgroundColor(Skin.SURFACE)
            row.animate().scaleX(1.02f).scaleY(1.02f).alpha(0.9f).setDuration(120L).start()
        }
        fun shift(k: Int, by: Float) {
            val v = list.getChildAt(k) ?: return
            v.animate().translationY(by).setDuration(150L).start()
        }
        fun drop(save: Boolean) {
            dragging = false
            pageScroll.requestDisallowInterceptTouchEvent(false)
            val h = row.height.toFloat()
            val ids = ArrayList<String>()
            for (k in 0 until list.childCount) (list.getChildAt(k).tag as? String)?.let { ids.add(it) }
            if (from in ids.indices && to in ids.indices) ids.add(to, ids.removeAt(from))
            row.animate().translationY((to - from) * h).scaleX(1f).scaleY(1f).alpha(1f).setDuration(140L)
                .withEndAction {
                    row.translationZ = 0f
                    row.background = a.rippleBg()
                    if (save && to != from) saveWindowOrder(ids)
                    else for (k in 0 until list.childCount) list.getChildAt(k).translationY = 0f
                }.start()
        }
        target.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    downX = ev.rawX
                    dragging = false
                    moved = false
                    if (immediate) lift.run() else v.postDelayed(lift, 320L)
                    v.isPressed = !immediate
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!dragging) {
                        if (abs(dy) > slop || abs(ev.rawX - downX) > slop) {
                            v.removeCallbacks(lift)
                            v.isPressed = false
                            moved = true
                        }
                    } else {
                        val h = row.height.coerceAtLeast(1).toFloat()
                        row.translationY = dy
                        val nt = (from + Math.round(dy / h)).coerceIn(0, list.childCount - 1)
                        if (nt != to) {
                            to = nt
                            for (k in 0 until list.childCount) {
                                if (k == from) continue
                                val by = when {
                                    k in (from + 1)..to -> -h
                                    k in to until from -> h
                                    else -> 0f
                                }
                                shift(k, by)
                            }
                            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(lift)
                    v.isPressed = false
                    if (dragging) drop(true)
                    else if (!moved) onTap?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(lift)
                    v.isPressed = false
                    if (dragging) drop(true)
                    true
                }
                else -> true
            }
        }
    }

    // ---------------------------------------------------------------- Music

    private fun musicPanel(box: LinearLayout) {
        box.addView(captionLine(R.drawable.ic_music, "Visualizer style"))
        val r = row()
        val viz = ArrayList<EdgeThumb>()
        val names = listOf("Bars", "Spectrum", "Rays", "Wave", "Dots")
        for ((k, id) in Prefs.VIZ_STYLES.withIndex()) {
            val name = names.getOrElse(k) { id }
            val t = thumb(EdgeThumb.VIZ, id, "$name visualizer", Prefs.vizStyle(a) == id) {
                edit().putString(Prefs.KEY_VIZ_STYLE, id).apply()
            }
            t.levels = beatDriver.beat.levels
            viz.add(t)
            vizThumbs.add(t)
            val c = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(t, a.lp(MATCH, a.dp(72)))
                addView(smallCaption(name), a.lp(MATCH, WRAP))
            }
            r.addView(c, a.lp(0, WRAP, 1f))
        }
        choices.add(Choice(viz) { Prefs.vizStyle(a) })
        box.addView(r)
    }

    // ---------------------------------------------------------------- Motion

    private fun motionPanel(box: LinearLayout) {
        box.addView(slider(R.drawable.ic_gauge, "Speed", 0f, 100f, Prefs.speed(a).toFloat(), 50f, stop = { replayMotion() }) {
            edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply()
        })
    }

    // ---------------------------------------------------------------- window editors

    /**
     * The editor of one window ([mode] = its id). Every control shows / hides or resizes a real element of
     * that window through a Prefs.WIN_PREFIX key; the overlay re-lays the window out live.
     */
    private fun editorPanel(box: LinearLayout) {
        when (mode) {
            "apps" -> appsEditor(box)
            "media" -> {
                box.addView(flagRow(R.drawable.ic_music, "Album art", Prefs.KEY_MEDIA_ART))
                box.addView(flagRow(R.drawable.ic_skip_forward, "Skip buttons", Prefs.KEY_MEDIA_SKIP))
                box.addView(flagRow(R.drawable.ic_type, "Title and artist", Prefs.KEY_MEDIA_TEXT))
                box.addView(flagRow(R.drawable.ic_ring, "Seek arc", Prefs.KEY_MEDIA_SEEK))
            }
            "clipboard" -> {
                box.addView(rowsSlider(Prefs.KEY_CLIP_ROWS))
                box.addView(toggleRow(R.drawable.ic_check, "Close after copy", Prefs.clipClose(a)) { v ->
                    edit().putBoolean(Prefs.KEY_CLIP_CLOSE, v).apply()
                })
                box.addView(linkRow(R.drawable.ic_clipboard, "History") {
                    a.startActivity(Intent(a, ClipboardActivity::class.java))
                })
            }
            "notifications" -> {
                box.addView(rowsSlider(Prefs.KEY_NOTIF_ROWS))
                box.addView(flagRow(R.drawable.ic_type, "Message text", Prefs.KEY_NOTIF_BODY))
                box.addView(flagRow(R.drawable.ic_grid, "App icons", Prefs.KEY_NOTIF_ICONS))
            }
            "timer" -> {
                box.addView(captionLine(R.drawable.ic_timer, "Preset chips"))
                for (i in 0 until 4) {
                    val key = Prefs.KEY_TIMER_P + i
                    val v = Prefs.timerPreset(a, i)
                    val label = captionText("$v min")
                    box.addView(slider(0, "Preset ${i + 1}", 1f, 60f, v.toFloat(), Prefs.TIMER_DEF[i].toFloat(), label = label) {
                        val m = Math.round(it).coerceIn(1, 60)
                        label.text = "$m min"
                        edit().putInt(key, m).apply()
                    })
                }
            }
            "device" -> {
                box.addView(flagRow(R.drawable.ic_battery, "Battery", Prefs.KEY_DEV_BAT))
                box.addView(flagRow(R.drawable.ic_memory, "RAM", Prefs.KEY_DEV_RAM))
                box.addView(flagRow(R.drawable.ic_hard_drive, "Storage", Prefs.KEY_DEV_DISK))
                box.addView(flagRow(R.drawable.ic_thermometer, "Temperature", Prefs.KEY_DEV_TEMP))
            }
        }
    }

    private fun rowsSlider(key: String): View =
        slider(R.drawable.ic_layers, "Rows", 1f, 4f, Prefs.rows(a, key).toFloat(), 4f) {
            edit().putInt(key, Math.round(it).coerceIn(1, 4)).apply()
        }

    /**
     * The APPS window editor: a live ring preview, the app grid (grip = drag at once, long-press also lifts,
     * x = remove, + = add), then icon size, spacing, recent apps and monochrome.
     */
    private fun appsEditor(box: LinearLayout) {
        val pm = a.packageManager
        val shown = ArrayList<ComponentName>()
        for (cn in Prefs.apps(a)) {
            try {
                pm.getActivityInfo(cn, 0)
                shown.add(cn)
            } catch (e: Exception) {
                // uninstalled: dropped on the next save
            }
        }
        val pv = RingPreview(a).apply { accent = acc }
        ringPreview = pv
        box.addView(pv, a.lp(MATCH, WRAP).apply { topMargin = a.dp(4); bottomMargin = a.dp(8) })

        val grid = DragGrid(a, 4, a.dp(88))
        for (cn in shown) {
            val (cell, grip) = appCell(cn, shown)
            grid.add(cell)
            grid.bind(grip, cell, true)
            grid.bind(cell, cell, false)
        }
        if (shown.size < Prefs.MAX_APPS) {
            grid.add(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, a.dp(8), 0, 0)
                addView(RoundIcon(a, R.drawable.ic_plus, "Add app", false).apply {
                    ink = acc
                    onClick = {
                        Prefs.setApps(a, shown)
                        a.startActivity(Intent(a, AppPickerActivity::class.java))
                    }
                }, a.lp(a.dp(48), a.dp(48)))
                addView(smallCaption("Add"), a.lp(MATCH, WRAP))
            })
            grid.fixedTail = 1
        }
        grid.onOrder = { cells ->
            val out = ArrayList<ComponentName>()
            for (c in cells) (c.tag as? ComponentName)?.let { out.add(it) }
            if (out != shown) Prefs.setApps(a, out)
        }
        box.addView(grid, a.lp(MATCH, WRAP))

        box.addView(slider(R.drawable.ic_maximize, "Icon size", Prefs.APPS_ICON_MIN, Prefs.APPS_ICON_MAX,
            Prefs.appsIconDp(a), Prefs.APPS_ICON_DEF) {
            edit().putFloat(Prefs.KEY_APPS_ICON, it).apply()
        })
        box.addView(slider(R.drawable.ic_columns, "Spacing", Prefs.APPS_GAP_MIN, Prefs.APPS_GAP_MAX,
            Prefs.appsGapDp(a), Prefs.APPS_GAP_DEF) {
            edit().putFloat(Prefs.KEY_APPS_GAP, it).apply()
        })
        box.addView(flagRow(R.drawable.ic_activity, "Recent apps", Prefs.KEY_APPS_RECENTS))
        // ON = themed / monochrome, OFF = system icons; the prefs listener rebuilds this page
        box.addView(toggleRow(R.drawable.ic_contrast, "Monochrome", IconTheme.source(a) == IconTheme.THEMED) { on ->
            IconTheme.clear()
            edit().putString(IconTheme.KEY, if (on) IconTheme.THEMED else IconTheme.SYSTEM).apply()
        })
    }

    /** One app cell: grip top-left and remove top-right (mirrored), icon in the middle, name under it. */
    private fun appCell(cn: ComponentName, shown: ArrayList<ComponentName>): Pair<View, View> {
        val label = try {
            a.packageManager.getActivityInfo(cn, 0).loadLabel(a.packageManager).toString()
        } catch (e: Exception) {
            cn.packageName
        }
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = cn
            setPadding(0, a.dp(4), 0, 0)
            contentDescription = "$label. Hold to move"
        }
        val frame = FrameLayout(a)
        val iv = ImageView(a).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            try {
                setImageBitmap(IconTheme.icon(a, cn, a.dp(44)))
            } catch (e: Exception) {
                // blank
            }
        }
        frame.addView(iv, FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.CENTER))
        val grip = FrameLayout(a).apply {
            contentDescription = "Move $label"
        }
        grip.addView(View(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Skin.SURFACE_HI)
            }
        }, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER))
        grip.addView(a.icon(R.drawable.ic_grip, Skin.TEXT, 14), FrameLayout.LayoutParams(a.dp(14), a.dp(14), Gravity.CENTER))
        frame.addView(grip, FrameLayout.LayoutParams(a.dp(28), a.dp(28), Gravity.TOP or Gravity.START))
        val badge = FrameLayout(a).apply {
            isClickable = true
            contentDescription = "Remove $label"
            background = a.rippleBg()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                shown.remove(cn)
                Prefs.setApps(a, shown)
            }
        }
        badge.addView(View(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Skin.SURFACE_HI)
            }
        }, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER))
        badge.addView(a.icon(R.drawable.ic_x, Skin.TEXT, 12), FrameLayout.LayoutParams(a.dp(12), a.dp(12), Gravity.CENTER))
        frame.addView(badge, FrameLayout.LayoutParams(a.dp(28), a.dp(28), Gravity.TOP or Gravity.END))
        // 80 dp wide frame: the 44 dp icon centred, grip and badge overlapping its top corners symmetrically
        cell.addView(frame, a.lp(a.dp(80), a.dp(52)))
        cell.addView(a.txt(label, 11f, Skin.TEXT_2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(a.dp(4), a.dp(2), a.dp(4), 0)
        }, a.lp(MATCH, WRAP))
        return Pair(cell, grip)
    }

    // ---------------------------------------------------------------- Settings page

    private fun permIcon(label: String): Int = when {
        label.startsWith("Overlay") -> R.drawable.ic_layers
        label.startsWith("Media") -> R.drawable.ic_music
        label.startsWith("Do Not") -> R.drawable.ic_moon
        label.startsWith("Modify") -> R.drawable.ic_sun
        label.startsWith("Assistant") -> R.drawable.ic_swipe
        label.startsWith("Microphone") -> R.drawable.ic_mic
        label.startsWith("Usage") -> R.drawable.ic_activity
        label.startsWith("Secure") -> R.drawable.ic_lock
        else -> R.drawable.ic_wrench
    }

    private fun permShort(label: String): String = when {
        label.startsWith("Overlay") -> "Overlay"
        label.startsWith("Media") -> "Media"
        label.startsWith("Do Not") -> "DND"
        label.startsWith("Modify") -> "System"
        label.startsWith("Assistant") -> "Assistant"
        label.startsWith("Microphone") -> "Mic"
        label.startsWith("Usage") -> "Usage"
        label.startsWith("Secure") -> "Secure"
        else -> label
    }

    private fun morePanel(box: LinearLayout) {
        // permissions: one status icon each, captioned; a dot marks what still needs allowing; tap = its screen
        val all = Perms.list(a)
        box.addView(captionLine(R.drawable.ic_shield, "Permissions"))
        var r: LinearLayout? = null
        for ((k, p) in all.withIndex()) {
            if (k % 4 == 0) {
                r = row()
                box.addView(r, a.lp(MATCH, WRAP))
            }
            val ok = try { p.ok() } catch (e: Exception) { false }
            val c = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            c.addView(PermDot(a, permIcon(p.label), ok, acc).apply {
                contentDescription = p.label + if (ok) ", allowed" else ", needed"
                setOnClickListener { Perms.open(a, p.intent) }
                setOnLongClickListener {
                    Toast.makeText(a, p.label, Toast.LENGTH_SHORT).show()
                    true
                }
            }, a.lp(MATCH, a.dp(48)))
            c.addView(smallCaption(permShort(p.label)), a.lp(MATCH, WRAP))
            r!!.addView(c, a.lp(0, WRAP, 1f))
        }
        val rest = all.size % 4
        if (rest != 0) for (k in 0 until 4 - rest) r!!.addView(View(a), a.lp(0, 1, 1f))

        pausePanel(box)

        // saved looks
        box.addView(captionLine(R.drawable.ic_save, "Saved looks"))
        val strip = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(8), 0, a.dp(8), 0)
        }
        strip.addView(cell(RoundIcon(a, R.drawable.ic_plus, "Save this look", false).apply {
            ink = acc
            onClick = {
                a.nameDialog("Save look", "Name") { name ->
                    SavedLooks.save(a, name, Prefs.snapshot(a))
                }
            }
        }, "Save"), a.lp(a.dp(68), WRAP))
        for (look in SavedLooks.all(a)) {
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2))
                isClickable = true
                isLongClickable = true
                background = a.rippleBg()
                contentDescription = "Look ${look.name}. Tap to use, long-press to delete"
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val snap = Prefs.snapshot(a)
                    SavedLooks.apply(a, look)
                    rebuildAll()
                    undoBar("Look applied") {
                        clearKeys(Prefs.LOOK_KEYS)
                        Prefs.restore(a, snap)
                        rebuildAll()
                    }
                }
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    a.confirmDialog("Delete \"${look.name}\"?", "The saved look is removed.", "Delete") {
                        SavedLooks.remove(a, look.name)
                    }
                    true
                }
            }
            cell.addView(EdgeThumb(a, EdgeThumb.LOOK, look.name).apply {
                sizeDp = 48f
                loadMap(look.values)
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            cell.addView(smallCaption(look.name).apply { maxWidth = a.dp(64) })
            strip.addView(cell, a.lp(a.dp(68), WRAP))
        }
        box.addView(hscroll(strip), a.lp(MATCH, WRAP).apply { topMargin = a.dp(4) })

        // resets: two equal halves, each centred
        val ar = row()
        ar.addView(action(R.drawable.ic_undo, "Look", "Reset look") {
            val snap = Prefs.snapshot(a, LOOK_ONLY)
            clearKeys(LOOK_ONLY)
            rebuildAll()
            undoBar("Look reset") {
                Prefs.restore(a, snap)
                rebuildAll()
            }
        }, a.lp(0, WRAP, 1f))
        ar.addView(action(R.drawable.ic_rotate, "Feel", "Reset feel") {
            val snap = Prefs.snapshot(a, FEEL_KEYS)
            clearKeys(FEEL_KEYS)
            rebuildAll()
            undoBar("Feel reset") {
                Prefs.restore(a, snap)
                rebuildAll()
            }
        }, a.lp(0, WRAP, 1f))
        box.addView(ar)
    }

    /** "Pause in apps": master switch + the row that opens the multi-select app picker. Flat, no boxes. */
    private fun pausePanel(box: LinearLayout) {
        box.addView(captionLine(R.drawable.ic_pause, "Pause in apps"))
        val on = Prefs.pauseOn(a)
        val n = Prefs.pauseApps(a).size
        val summary = when {
            !A11yGate.canWrite(a) -> "Needs secure settings"
            !RecentApps.hasAccess(a) -> "Needs usage access"
            n == 0 -> "No apps"
            n == 1 -> "1 app"
            else -> "$n apps"
        }
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(56)
            setPadding(a.dp(8), 0, a.dp(4), 0)
            isClickable = true
            background = a.rippleBg()
            contentDescription = "Choose apps to pause in, $summary"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                a.startActivity(AppPickerActivity.pauseIntent(a))
            }
        }
        val text = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (on) 1f else 0.5f
            addView(a.txt("Sensitive apps", 15f, Skin.TEXT))
            addView(a.txt(summary, 13f, Skin.TEXT_2))
        }
        r.addView(lead(R.drawable.ic_shield))
        r.addView(text, a.lp(0, WRAP, 1f).apply { leftMargin = a.dp(14) })
        r.addView(a.icon(R.drawable.ic_chevron_right, acc, 20), a.lp(WRAP, WRAP).apply { rightMargin = a.dp(8) })
        val sw = ToggleSwitch(a, "Pause in apps").apply {
            accent = acc
            checked = on
            onToggle = { v ->
                edit().putBoolean(Prefs.KEY_PAUSE_ON, v).apply()
                text.alpha = if (v) 1f else 0.5f
            }
        }
        r.addView(sw, a.lp(a.dp(52), a.dp(48)))
        box.addView(r)
    }

    private fun clearKeys(keys: Array<String>) {
        val e = edit()
        for (k in keys) e.remove(k)
        e.commit()
    }

    // ---------------------------------------------------------------- undo

    private fun undoBar(msg: String, onUndo: () -> Unit) {
        clearUndo()
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(52)
            setPadding(a.dp(Skin.PAD), 0, a.dp(8), 0)
            setBackgroundColor(Skin.SURFACE_HI)
            isClickable = true
        }
        bar.addView(a.txt(msg, 14f, Skin.TEXT), a.lp(0, WRAP, 1f))
        bar.addView(a.txt("Undo", 14f, acc, true).apply {
            gravity = Gravity.CENTER
            minimumHeight = a.dp(44)
            minimumWidth = a.dp(64)
            isClickable = true
            background = a.rippleBg()
            setOnClickListener {
                clearUndo()
                onUndo()
            }
        })
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        lp.bottomMargin = bottomPad()
        root.addView(bar, lp)
        undoView = bar
        bar.postDelayed({ if (undoView === bar) clearUndo() }, 5000L)
    }

    private fun clearUndo() {
        undoView?.let { root.removeView(it) }
        undoView = null
    }

    companion object {
        const val MODE_MAIN = ""
        const val MODE_SETTINGS = "settings"

        const val T_COLOUR = 0
        const val T_SHAPE = 1
        const val T_WINDOWS = 2
        const val T_MUSIC = 3
        const val T_MOTION = 4
        const val T_MORE = 6
        const val T_EDIT = 7

        /** Width of the caption column of slider rows (dp): every track starts at the same x. */
        private const val CAPTION_W = 104

        /** What "reset look" clears: the editable look keys only (feel, windows, apps and sizes are not touched). */
        val LOOK_ONLY = arrayOf(Prefs.KEY_ACCENT, Prefs.KEY_GLOW, Prefs.KEY_OPACITY, Prefs.KEY_VIZ_STYLE)
        val FEEL_KEYS = arrayOf(Prefs.KEY_SPEED)
    }
}

/** A permission as an icon with a status dot: accent icon and dot while it still needs allowing. */
class PermDot(ctx: android.content.Context, res: Int, private val ok: Boolean, private val accent: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val icon = ctx.getDrawable(res)!!.mutate()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isLongClickable = true
        background = Skin.ripple(ctx)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val s = (20 * d).toInt()
        val t = (height / 2f - s / 2f - 4 * d).toInt()
        val l = (cx - s / 2f).toInt()
        icon.setBounds(l, t, l + s, t + s)
        icon.setTint(if (ok) Skin.TEXT_3 else accent)
        icon.draw(c)
        p.color = if (ok) Skin.TRACK else accent
        c.drawCircle(cx, t + s + 7 * d, (if (ok) 2f else 3.5f) * d, p)
    }
}
