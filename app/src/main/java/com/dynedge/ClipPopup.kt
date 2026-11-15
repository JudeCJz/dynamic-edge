package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The small floating clipboard popup the edge opens instead of ClipboardActivity.
 *
 * A second TYPE_ACCESSIBILITY_OVERLAY window owned by [EdgeService], built only when shown and removed (all views
 * dropped, ClipStore listener removed) when closed: zero idle cost. Normally FLAG_NOT_FOCUSABLE, so the app below
 * keeps its focused text field and "paste" can fill it. The window becomes focusable only (a) for one focus change
 * right after opening when the system clipboard changed (Android only lets a focused window read it), and (b) while
 * the search field is open (keyboard); search moves the popup to the top so the keyboard does not cover it.
 *
 * Closes on: outside tap (WATCH_OUTSIDE_TOUCH -> ACTION_OUTSIDE), back (key while focusable; otherwise the app below
 * changes window, see [onForeignWindow]), the close button, copy / paste (copy: when Prefs.clipClose), screen off.
 */
internal class ClipPopup(
    private val svc: AccessibilityService,
    /** True when the system clipboard changed since the last capture (EdgeView keeps the listener). */
    private val needsCapture: () -> Boolean,
    private val onCaptured: () -> Unit,
    /** Paste found no field: the text is on the clipboard, show the edge's "Copied" pill. */
    private val onCopiedOnly: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val ctx: Context = svc
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val store = ClipStore.get(svc)
    private val dm = svc.resources.displayMetrics
    private fun dp(v: Float): Int = (v * dm.density + 0.5f).toInt()

    private val accent = Skin.accentUi(Prefs.accent(svc))
    private val closeAfterCopy = Prefs.clipClose(svc)

    private var root: Root? = null
    private var lp: WindowManager.LayoutParams? = null
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var scroll: ScrollView
    private var query = ""
    private var searching = false
    private var canPaste = false
    private var capturing = false
    private var openedAt = 0L
    private var closing = false
    private var imePkg: String? = null
    /** Height cap of the popup (60 % of the screen at the bottom, less while searching at the top). */
    private var maxH = Int.MAX_VALUE

    private val storeListener: () -> Unit = { rebuildList() }
    private val captureTimeout = Runnable { endCapture() }

    val isShown: Boolean get() = root != null

    // ---------------------------------------------------------------- window

    fun show() {
        if (root != null) return
        canPaste = ClipPaste.hasEditable(svc) // before our window can take focus
        imePkg = try {
            Settings.Secure.getString(svc.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        } catch (e: Exception) {
            null
        }
        val r = build()
        val p = WindowManager.LayoutParams(
            popupWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.title = "DynamicEdgeClip"
        p.fitInsetsTypes = 0
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        placeBottom(p)
        try {
            wm.addView(r, p)
        } catch (e: Exception) {
            return
        }
        root = r
        lp = p
        openedAt = SystemClock.uptimeMillis()
        store.addListener(storeListener)
        rebuildList()
        r.alpha = 0f
        r.scaleX = 0.94f
        r.scaleY = 0.94f
        r.pivotX = p.width.toFloat()
        r.post {
            r.pivotY = r.height.toFloat()
            r.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start()
        }
        if (needsCapture()) beginCapture()
    }

    fun close() {
        val r = root ?: return
        if (closing) return
        closing = true
        r.animate().cancel()
        r.animate().alpha(0f).setDuration(110L).withEndAction { removeNow() }.start()
        // never leave it waiting for an animation that does not run (screen off)
        main.postDelayed({ removeNow() }, 200L)
    }

    /** Removes the window at once; safe to call repeatedly. */
    fun removeNow() {
        val r = root ?: return
        root = null
        lp = null
        closing = false
        main.removeCallbacks(captureTimeout)
        main.removeCallbacksAndMessages(null)
        capturing = false
        searching = false
        store.removeListener(storeListener)
        try {
            r.animate().cancel()
            wm.removeViewImmediate(r)
        } catch (e: Exception) {
        }
        onClosed()
    }

    /** Another app's window came to the front (back gesture, app switch): the popup goes away. */
    fun onForeignWindow(pkg: CharSequence?) {
        if (root == null || pkg == null) return
        if (SystemClock.uptimeMillis() - openedAt < 500L) return
        val p = pkg.toString()
        if (p == ctx.packageName || p == imePkg) return
        close()
    }

    private fun popupWidth(): Int = minOf(dp(300f), dm.widthPixels - dp(24f))

    private fun sideGravity(): Int = if (Prefs.sideLeft(ctx)) Gravity.LEFT else Gravity.RIGHT

    private fun placeBottom(p: WindowManager.LayoutParams) {
        p.gravity = Gravity.BOTTOM or sideGravity()
        // above the bean wherever it sits (side, float gap, position along the bottom edge), kept on screen
        p.x = (EdgeView.placeXFor(ctx) + dp(12f)).coerceAtMost(dm.widthPixels - popupWidth() - dp(8f)).coerceAtLeast(dp(12f))
        p.y = EdgeView.placeYFor(ctx) + dp(Prefs.idleDp(ctx) + 18f)
        maxH = (dm.heightPixels * 0.60f).toInt() - p.y / 2
    }

    private fun placeTop(p: WindowManager.LayoutParams) {
        p.gravity = Gravity.TOP or sideGravity()
        p.x = dp(12f)
        p.y = dp(56f)
        maxH = (dm.heightPixels * 0.42f).toInt()
    }

    private fun setFocusable(on: Boolean) {
        val r = root ?: return
        val p = lp ?: return
        val has = (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (has == on) return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            wm.updateViewLayout(r, p)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- clipboard capture (one focus change)

    private fun beginCapture() {
        capturing = true
        setFocusable(true)
        main.removeCallbacks(captureTimeout)
        main.postDelayed(captureTimeout, 350L)
    }

    private fun onFocus(has: Boolean) {
        if (!has || !capturing) return
        try {
            ClipPaste.capture(ctx)
            onCaptured()
        } catch (e: Throwable) {
        }
        endCapture()
    }

    private fun endCapture() {
        main.removeCallbacks(captureTimeout)
        if (!capturing) return
        capturing = false
        if (!searching) setFocusable(false)
    }

    // ---------------------------------------------------------------- search

    private fun openSearch() {
        val p = lp ?: return
        if (searching) {
            closeSearch()
            return
        }
        searching = true
        search.visibility = View.VISIBLE
        placeTop(p)
        setFocusable(true)
        try {
            root?.let { wm.updateViewLayout(it, p) }
        } catch (e: Exception) {
        }
        search.requestFocus()
        search.postDelayed({
            if (searching) {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
        openedAt = SystemClock.uptimeMillis() // the keyboard's own window events are not a "leave"
    }

    private fun closeSearch() {
        val p = lp ?: return
        searching = false
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        search.visibility = View.GONE
        placeBottom(p)
        setFocusable(false)
        try {
            root?.let { wm.updateViewLayout(it, p) }
        } catch (e: Exception) {
        }
        openedAt = SystemClock.uptimeMillis()
    }

    // ---------------------------------------------------------------- actions

    private fun copy(c: ClipStore.Clip, row: Row) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clip", c.text))
        } catch (e: Exception) {
            return
        }
        // our own copy must not trigger a capture next time
        onCaptured()
        row.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        row.check.visibility = View.VISIBLE
        row.check.scaleX = 0f
        row.check.scaleY = 0f
        row.check.animate().scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(OvershootInterpolator(2.2f)).start()
        row.text.setTextColor(accent)
        if (closeAfterCopy) {
            main.postDelayed({ close() }, 420L)
        } else {
            main.postDelayed({
                if (root != null) {
                    row.check.animate().scaleX(0f).scaleY(0f).setDuration(140L)
                        .withEndAction { row.check.visibility = View.GONE }.start()
                    row.text.setTextColor(Skin.TEXT)
                }
            }, 900L)
        }
    }

    private fun paste(c: ClipStore.Clip) {
        val text = c.text
        removeNow() // the field's window gets its focus back before the paste
        main.postDelayed({
            if (!ClipPaste.paste(svc, text)) onCopiedOnly()
        }, 140L)
    }

    // ---------------------------------------------------------------- views

    private inner class Root(c: Context) : LinearLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxH else minOf(size, maxH)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                close()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (searching) closeSearch() else close()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            onFocus(hasWindowFocus)
        }
    }

    private class Row(val view: View, val text: TextView, val check: ImageView)

    private fun surface(): GradientDrawable {
        // the edge's own colours: its radial stops from the bottom-right corner, lifted a little for legibility
        val pct = if (Prefs.gradOn(ctx)) Prefs.prefs(ctx).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100) else 0
        val s = EdgePaint.stops(Prefs.base(ctx), Prefs.grad1(ctx), Prefs.grad2(ctx), pct, Prefs.accent(ctx))
        val lift = 0xFF1C1C1E.toInt()
        return GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(
                EdgePaint.mix(s[0], lift, 0.30f),
                EdgePaint.mix(s[1], lift, 0.35f),
                EdgePaint.mix(s[3], lift, 0.45f)
            )
            setGradientCenter(1f, 1f)
            gradientRadius = dp(300f).toFloat()
            cornerRadius = dp(20f).toFloat()
            setStroke(dp(1f).coerceAtLeast(1), 0x1AFFFFFF)
        }
    }

    private fun iconBtn(res: Int, desc: String, color: Int, w: Float = 40f, click: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(color)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            isClickable = true
            background = Skin.ripple(ctx)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(44f))
            val pad = dp((w - 18f) / 2f)
            setPadding(pad, dp(13f), pad, dp(13f))
        }

    private fun build(): Root {
        val r = Root(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = surface()
            clipToOutline = true
            elevation = dp(8f).toFloat()
            setPadding(0, dp(4f), 0, dp(8f))
            isFocusableInTouchMode = true
        }
        // header: title, search, close
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(4f), 0)
        }
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_clipboard)
            setColorFilter(accent)
        }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(10f) })
        head.addView(TextView(ctx).apply {
            text = "Clipboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconBtn(R.drawable.ic_search, "Search", Skin.TEXT_2, 44f) { openSearch() })
        head.addView(iconBtn(R.drawable.ic_x, "Close", Skin.TEXT_2, 44f) { close() })
        r.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        search = EditText(ctx).apply {
            hint = "Search"
            setHintTextColor(Skin.TEXT_3)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            background = null
            setPadding(dp(16f), dp(8f), dp(16f), dp(10f))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    rebuildList()
                }
            })
        }
        r.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun rebuildList() {
        if (root == null) return
        list.removeAllViews()
        val all = store.list() // pinned first, then newest first
        val shown = if (query.isEmpty()) all else all.filter { it.text.contains(query, ignoreCase = true) }
        if (shown.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = if (query.isEmpty()) "Nothing copied yet" else "No match"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Skin.TEXT_3)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(18f), dp(16f), dp(22f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        for (c in shown.take(MAX_ROWS)) list.addView(row(c))
    }

    private fun row(c: ClipStore.Clip): View {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(16f), 0, dp(2f), 0)
            isClickable = true
            background = Skin.ripple(ctx)
        }
        val t = TextView(ctx).apply {
            text = c.text.take(400).replace('\n', ' ').replace('\t', ' ').trim()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Skin.TEXT)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8f), dp(6f), dp(8f))
        }
        v.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(accent)
            visibility = View.GONE
        }
        v.addView(check, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(8f) })
        val holder = Row(v, t, check)
        if (canPaste) v.addView(iconBtn(R.drawable.ic_clipboard_paste, "Paste", Skin.TEXT_2, 38f) { paste(c) })
        v.addView(iconBtn(R.drawable.ic_pin, if (c.pinned) "Unpin" else "Pin", if (c.pinned) accent else Skin.TEXT_3, 38f) {
            store.pin(c.id, !c.pinned)
        })
        v.addView(iconBtn(R.drawable.ic_trash, "Delete", Skin.TEXT_3, 38f) { store.delete(c.id) })
        v.setOnClickListener { copy(c, holder) }
        return v
    }

    private companion object {
        const val MAX_ROWS = 60
    }
}
