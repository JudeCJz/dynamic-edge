package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class EdgeService : AccessibilityService(), EdgeView.Host {

    companion object {
        private const val TAG = "DynEdge"

        /** True while the accessibility service is bound (the settings screen shows a status row otherwise). */
        @Volatile
        var connected = false
            private set
    }

    private var wm: WindowManager? = null
    private var edge: EdgeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var touch: TouchRegion? = null
    private var sizesPosted = false
    private var imeVisible = false
    private var screenOff = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    closeClipPopup()
                    edge?.collapseImmediately()
                    edge?.setScreenOn(false)
                    updateHidden()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    edge?.setScreenOn(true)
                    updateHidden()
                }
                Intent.ACTION_USER_PRESENT -> updateHidden()
                Intent.ACTION_POWER_CONNECTED -> postCharging()
                Intent.ACTION_POWER_DISCONNECTED -> {
                    mainHandler.removeCallbacks(chargingRemove)
                    LiveBus.remove("charging")
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chargingRemove = Runnable { LiveBus.remove("charging") }

    private fun postCharging() {
        if (!Prefs.live(this) || screenOff) return
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lv >= 0 && sc > 0) (lv * 100 / sc) else 0
        LiveBus.put(
            LiveActivity(
                "charging", LiveActivity.Kind.CHARGING, "$pct%", pct / 100f, 0L, false,
                R.drawable.ic_bolt, false, null, null, null
            )
        )
        mainHandler.removeCallbacks(chargingRemove)
        mainHandler.postDelayed(chargingRemove, 3500L)
    }

    private var prefListenerRegistered = false
    // strong reference: SharedPreferences only holds listeners weakly
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_IDLE_DP, Prefs.KEY_OPEN_DP, Prefs.KEY_GRAB -> scheduleSizes()
            Prefs.KEY_PREVIEW -> applyPreview()
            Prefs.KEY_ACCENT, Prefs.KEY_GRADIENT, Prefs.KEY_GLOW,
            Prefs.KEY_SPEED, Prefs.KEY_BOUNCE, Prefs.KEY_HAPTICS, Prefs.KEY_IDLE_STYLE,
            Prefs.KEY_VISUALIZER, Prefs.KEY_LIVE, Prefs.KEY_BEAT, Prefs.KEY_FILL, Prefs.KEY_VIZ_STYLE,
            Prefs.KEY_BEAT_AMOUNT, Prefs.KEY_OPACITY, Prefs.KEY_BASE, Prefs.KEY_SWIPE_SENS,
            Prefs.KEY_GRAD_ON, Prefs.KEY_GRAD1, Prefs.KEY_GRAD2, Prefs.KEY_TOUCH_SHOW,
            // pixel mode changes typefaces and stroke grids baked into the windows: rebuild the overlay window
            Prefs.KEY_PIXEL_MODE -> if (edge != null) { detachWindow(); attach() }
            Prefs.KEY_PIXEL_SIZE, Prefs.KEY_BLUR -> edge?.applyLooks()
            Prefs.KEY_FLOAT, Prefs.KEY_FLOAT_GAP, Prefs.KEY_POS, Prefs.KEY_SIDE_LEFT -> applyPlacement()
            QuickConfig.KEY, ShortcutStore.KEY -> edge?.reloadQuick()
            WindowsConfig.KEY -> edge?.reloadWindows()
            Prefs.KEY_FLARE -> applyFlare()
            Prefs.KEY_PAUSE_ON, Prefs.KEY_PAUSE_APPS -> pauseSet = PauseGate.watchSet(this)
            Prefs.KEY_APPS, IconTheme.KEY, Prefs.KEY_ICON_CORNER, Prefs.KEY_ICON_PIXEL -> edge?.reloadWinPrefs()
            else -> if (key != null && key.startsWith(Prefs.WIN_PREFIX)) edge?.reloadWinPrefs()
        }
    }

    private val sizesRunnable = Runnable {
        sizesPosted = false
        applySizesFromPrefs()
    }

    /** Coalesces a stream of size changes (slider drag) to one relayout per frame. */
    private fun scheduleSizes() {
        val view = edge ?: return
        if (sizesPosted) return
        sizesPosted = true
        view.postOnAnimation(sizesRunnable)
    }

    private fun applyPreview() {
        val view = edge ?: return
        view.setBackdrop(Prefs.preview(this) != Prefs.PREVIEW_NONE)
        when (Prefs.preview(this)) {
            Prefs.PREVIEW_OPEN -> {
                view.holdOpen = true
                view.expand()
            }
            else -> {
                view.holdOpen = false
                view.collapse()
            }
        }
    }

    private fun applySizesFromPrefs() {
        val view = edge ?: return
        val manager = wm ?: return
        val params = lp ?: return
        view.applySizes(Prefs.idleDp(this), Prefs.openDp(this))
        view.applyPlacement() // the position clamp depends on the open size
        resizeWindow(view, manager, params)
    }

    /** Float / gap / position / side changed: the view re-reads them, then the window moves (gravity, x, y). */
    private fun applyPlacement() {
        val view = edge ?: return
        val manager = wm ?: return
        val params = lp ?: return
        view.applyPlacement()
        view.setTipRadius(readTipRadius())
        resizeWindow(view, manager, params)
    }

    /** Copies the view's placement into the layout params; true if anything changed. */
    private fun syncPlacement(view: EdgeView, params: WindowManager.LayoutParams): Boolean {
        val g = Gravity.BOTTOM or (if (Prefs.sideLeft(this)) Gravity.LEFT else Gravity.RIGHT)
        if (params.gravity == g && params.x == view.placeX && params.y == view.placeY) return false
        params.gravity = g
        params.x = view.placeX
        params.y = view.placeY
        return true
    }

    private fun applyFlare() {
        val view = edge ?: return
        val manager = wm ?: return
        val params = lp ?: return
        view.applyFlare()
        view.applyPlacement()
        resizeWindow(view, manager, params)
    }

    /** The window is always the large size; it only changes when the sizes change in prefs. */
    private fun resizeWindow(view: EdgeView, manager: WindowManager, params: WindowManager.LayoutParams) {
        val pad = (36f * density).toInt()
        // never smaller than the idle grab square, so the whole grab area is reachable
        val w = kotlin.math.max(view.maxOpenWidthPx + pad, view.grabPx)
        val h = kotlin.math.max(view.maxOpenHeightPx + pad, view.grabPx)
        val moved = syncPlacement(view, params)
        if (params.width == w && params.height == h && !moved) return
        params.width = w
        params.height = h
        pushLayout(view, manager, params)
    }

    private var layoutFails = 0
    private val layoutRetry = Runnable {
        val v = edge
        val m = wm
        val p = lp
        if (v != null && m != null && p != null) pushLayout(v, m, p)
    }

    /** updateViewLayout with logging; a failure is retried a few times, then the window is rebuilt from scratch. */
    private fun pushLayout(view: EdgeView, manager: WindowManager, params: WindowManager.LayoutParams) {
        try {
            manager.updateViewLayout(view, params)
            layoutFails = 0
        } catch (e: IllegalArgumentException) {
            onLayoutFailure(e)
        } catch (e: IllegalStateException) {
            onLayoutFailure(e)
        } catch (e: WindowManager.BadTokenException) {
            onLayoutFailure(e)
        }
    }

    private fun onLayoutFailure(e: Exception) {
        layoutFails++
        Log.w(TAG, "updateViewLayout failed (" + layoutFails + ")", e)
        mainHandler.removeCallbacks(layoutRetry)
        if (layoutFails <= 3) {
            mainHandler.postDelayed(layoutRetry, 500L)
        } else {
            layoutFails = 0
            Log.e(TAG, "window update keeps failing: rebuilding the overlay window")
            detachWindow()
            scheduleRetry()
        }
    }

    private val density: Float get() = resources.displayMetrics.density

    private var attached = false
    private var attachTries = 0
    private val retryDelays = longArrayOf(300L, 1000L, 3000L, 5000L)
    private val attachRunnable = Runnable { attach() }

    // ------------------------------------------------------------ pause in sensitive apps

    /** Packages that switch the service off (see PauseGate); empty when the feature is off. */
    private var pauseSet: Set<String> = emptySet()
    private var gating = false

    /** A paused app is coming to the front: remove ourselves from the accessibility list at once. */
    private fun gate(pkg: String) {
        if (gating) return
        gating = true
        edge?.setHidden(true)
        if (!PauseGate.pause(this, pkg)) {
            gating = false
            updateHidden()
        }
    }

    /** At connect: if a paused app is already in front, go off before building anything. */
    private fun gateAtConnect(): Boolean {
        if (pauseSet.isEmpty()) return false
        val front = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        } ?: PauseGate.frontApp(this, 30L * 60L * 1000L) ?: return false
        if (front !in pauseSet) return false
        gate(front)
        return gating
    }

    private var windowsPosted = false
    private val windowsRunnable = Runnable {
        windowsPosted = false
        updateHidden()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // one-shot look migrations (version flag in Prefs), so the overlay never draws a removed option
        Prefs.migrate(this)
        Prefs.migrateLook(this)
        // a settings preview left behind by a killed settings screen (reinstall, crash) must never stick:
        // no white backdrop, no forced-open panel, no dashed touch square after a restart
        Prefs.prefs(this).edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE)
            .putBoolean(Prefs.KEY_TOUCH_SHOW, false).apply()
        connected = true
        gating = false
        PauseGate.onServiceUp(this)
        pauseSet = PauseGate.watchSet(this)
        if (gateAtConnect()) return
        // migrate the old fill semantics before the view reads them
        Prefs.migrate(this)
        mainHandler.removeCallbacks(attachRunnable)
        attachTries = 0
        attach()
    }

    /** Idempotent: builds the view once, adds the window, and keeps retrying with backoff while the token is not valid. */
    private fun attach() {
        if (attached || !connected) return
        val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        var view = edge
        var params = lp
        if (view == null || params == null) {
            // optional system tweak: never allowed to delay or break the window itself
            try {
                AssistControl.disableCornerGesture(this)
            } catch (e: Exception) {
                Log.w(TAG, "corner gesture switch failed", e)
            }
            val v = EdgeView(this)
            v.host = this
            v.setService(this)
            v.setTipRadius(readTipRadius())
            val winPx = kotlin.math.max(v.maxOpenWidthPx + (36f * density).toInt(), v.grabPx)
            val p = WindowManager.LayoutParams(
                winPx,
                winPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.BOTTOM or (if (Prefs.sideLeft(this)) Gravity.LEFT else Gravity.RIGHT)
            p.x = v.placeX
            p.y = v.placeY
            p.fitInsetsTypes = 0
            p.fitInsetsSides = 0
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.title = "DynamicEdge"
            edge = v
            lp = p
            view = v
            params = p
        }
        try {
            if (!view.isAttachedToWindow) manager.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            // the accessibility token is not valid yet: temporary
            Log.w(TAG, "addView: bad token, retrying", e)
            scheduleRetry()
            return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "addView: illegal state, retrying", e)
            scheduleRetry()
            return
        } catch (e: RuntimeException) {
            // unexpected: report it loudly, keep trying but slowly so a real bug does not spin
            Log.e(TAG, "addView failed unexpectedly", e)
            scheduleRetry(30000L)
            return
        }
        attached = true
        attachTries = 0
        layoutFails = 0
        touch?.uninstall()
        touch = TouchRegion.install(view)
        touch?.set(view.currentRegion())

        if (!receiverRegistered) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            filter.addAction(Intent.ACTION_POWER_CONNECTED)
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
            registerReceiver(receiver, filter)
            receiverRegistered = true
        }

        screenOff = false
        view.setBackdrop(Prefs.preview(this) != Prefs.PREVIEW_NONE)
        updateHidden()
        view.setScreenOn((getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true)

        if (!prefListenerRegistered) {
            Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
            prefListenerRegistered = true
        }
        // a countdown started before a restart keeps showing on the bean
        TimerControl.restore(this)
    }

    /** Retries forever while the service stays connected; the delay grows to a steady 5 s (or [fixed]). */
    private fun scheduleRetry(fixed: Long = 0L) {
        if (!connected) return
        val d = if (fixed > 0L) fixed else retryDelays[minOf(attachTries, retryDelays.size - 1)]
        attachTries++
        mainHandler.removeCallbacks(attachRunnable)
        mainHandler.postDelayed(attachRunnable, d)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // the clipboard popup goes away when another window comes to the front (back gesture, app switch)
                clipPopup?.onForeignWindow(event.packageName)
                // an open panel left behind by HOME / an app switch would keep blocking touches: close it
                val ep = event.packageName?.toString()
                if (ep != null && ep != packageName && ep != "com.android.systemui") {
                    edge?.let { if (it.isOpen) it.collapse() }
                }
                // hot path: a set lookup on the framework's package string, nothing else
                val set = pauseSet
                if (set.isEmpty()) return
                val p = event.packageName ?: return
                val hit = if (p is String) p in set else p.toString() in set
                if (hit) gate(p.toString())
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // notificationTimeout is 0 (for the gate), so window bursts are coalesced here instead
                if (windowsPosted || gating) return
                windowsPosted = true
                mainHandler.postDelayed(windowsRunnable, 100L)
            }
        }
    }

    /** Asks the window list directly (also at connect, on screen changes); a throwing / null list keeps the last answer. */
    private fun refreshIme() {
        try {
            val w = windows ?: return
            imeVisible = w.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            Log.w(TAG, "window list unavailable", e)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        val view = edge ?: return
        val manager = wm ?: return
        val params = lp ?: return
        view.setTipRadius(readTipRadius())
        view.onDensityChanged() // density / font scale: sizes, paints and windows are rebuilt
        view.applyPlacement()   // the screen width may have changed (rotation)
        resizeWindow(view, manager, params)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ EdgeView.Host

    override fun onFast(on: Boolean) {
        val v = edge ?: return
        val m = wm ?: return
        val p = lp ?: return
        if (FrameRate.set(this, p, on)) pushLayout(v, m, p)
    }

    override fun onSettled(expanded: Boolean) {
        // The window never resizes while opening or closing.
    }

    override fun onRegionChanged(region: Region) {
        touch?.set(region)
    }

    override fun setWindowFocusable(on: Boolean) {
        val view = edge ?: return
        val params = lp ?: return
        val manager = wm ?: return
        val has = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (has == on) return
        val old = params.flags
        params.flags = if (on) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            manager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // keep the flags in step with the window, otherwise clipboard capture could leave it focusable
            Log.w(TAG, "focusable change failed", e)
            params.flags = old
            if (!on) onLayoutFailure(e)
        }
    }

    // ------------------------------------------------------------ clipboard popup (lazy second overlay window)

    private var clipPopup: ClipPopup? = null

    override fun performAction(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (e: Exception) {
        false
    }

    override fun openClipPopup() {
        if (!attached) return
        val cur = clipPopup
        if (cur != null && cur.isShown) return
        val p = ClipPopup(
            this,
            needsCapture = { edge?.clipNeedsCapture() ?: true },
            onCaptured = { edge?.clipCaptured() },
            onCopiedOnly = { edge?.showCopied() },
            onClosed = { clipPopup = null }
        )
        clipPopup = p
        p.show()
        if (!p.isShown) clipPopup = null
    }

    private fun closeClipPopup() {
        clipPopup?.removeNow()
        clipPopup = null
    }

    // ------------------------------------------------------------ helpers

    private fun updateHidden() {
        refreshIme()
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = km?.isKeyguardLocked ?: false
        if (locked || screenOff) closeClipPopup()
        edge?.setHidden(imeVisible || locked || screenOff)
    }

    private fun readTipRadius(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0f
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
        val pos = if (Prefs.sideLeft(this)) RoundedCorner.POSITION_BOTTOM_LEFT else RoundedCorner.POSITION_BOTTOM_RIGHT
        return display.getRoundedCorner(pos)?.radius?.toFloat() ?: 0f
    }

    /** Removes the overlay window and drops the view; safe to call repeatedly. */
    private fun detachWindow() {
        mainHandler.removeCallbacks(layoutRetry)
        closeClipPopup()
        attached = false
        val view = edge
        val manager = wm
        edge = null
        lp = null
        try {
            touch?.uninstall()
        } catch (e: Exception) {
            Log.w(TAG, "touch region uninstall failed", e)
        } finally {
            touch = null
        }
        if (view != null) {
            try {
                edge?.removeCallbacks(sizesRunnable)
                view.removeCallbacks(sizesRunnable)
                view.host = null
                view.setService(null)
                view.release()
            } catch (e: Exception) {
                Log.w(TAG, "view release failed", e)
            } finally {
                try {
                    if (view.isAttachedToWindow) manager?.removeViewImmediate(view)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView failed", e)
                }
            }
        }
        sizesPosted = false
    }

    /** Idempotent: every step runs even when another one fails, and all attachment state is reset. */
    private fun teardown() {
        connected = false
        mainHandler.removeCallbacks(attachRunnable)
        mainHandler.removeCallbacks(windowsRunnable)
        windowsPosted = false
        mainHandler.removeCallbacks(chargingRemove)
        try {
            if (receiverRegistered) unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "receiver unregister failed", e)
        } finally {
            receiverRegistered = false
        }
        try {
            if (prefListenerRegistered) Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            Log.w(TAG, "pref listener unregister failed", e)
        } finally {
            prefListenerRegistered = false
        }
        try {
            detachWindow()
        } finally {
            wm = null
            attachTries = 0
            layoutFails = 0
            imeVisible = false
            screenOff = false
        }
    }
}
