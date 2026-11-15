package com.dynedge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hue / saturation / value picker drawn straight onto a Canvas - no external libraries.
 *
 * The top area is the saturation-value square (white -> hue left to right, black -> transparent top to
 * bottom), the strip underneath is the hue wheel. The crosshair shows the current selection.
 */
class HsvPickerView(ctx: Context) : View(ctx) {

    /** The selected colour (opaque). */
    var color: Int = Color.RED
        private set

    /** Fired on every change so callers can preview live. */
    var onColor: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val px = PixelMode.on(ctx)
    private val grid = if (px) PixelMode.gridPx(ctx) else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = !px }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; isAntiAlias = !px }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var hue = 0f
    private var sat = 1f
    private var bri = 1f
    private var side = 0f
    private var barTop = 0f
    private var barH = 0f
    private var onBar = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var hueShader: Shader? = null
    private var satShader: Shader? = null
    private var valShader: Shader? = null
    private var satHue = -1f

    init {
        setColorInternal(Color.RED, notify = false)
    }

    /** Set the colour without firing [onColor] (used while the user drags). */
    fun setColorSilently(c: Int) = setColorInternal(c, notify = false)

    fun setColor(c: Int) = setColorInternal(c, notify = true)

    private fun setColorInternal(c: Int, notify: Boolean) {
        color = Color.argb(255, Color.red(c), Color.green(c), Color.blue(c))
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        bri = hsv[2]
        invalidate()
        if (notify) onColor?.invoke(color)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        side = minOf(w.toFloat(), 220 * d)
        barH = 28 * d
        barTop = side + 14 * d
        setMeasuredDimension(w, (barTop + barH).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (side <= 0f) return
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))

        // saturation-value square: white -> hue, then black -> transparent over the top
        if (satShader == null || satHue != hue) {
            satHue = hue
            satShader = LinearGradient(0f, 0f, side, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            valShader = LinearGradient(0f, 0f, 0f, side, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        p.reset()
        p.isAntiAlias = !px
        p.shader = satShader
        c.drawRect(0f, 0f, side, side, p)
        p.shader = valShader
        c.drawRect(0f, 0f, side, side, p)

        // hue strip
        if (hueShader == null) {
            hueShader = LinearGradient(
                0f, 0f, side, 0f,
                intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                    Color.BLUE, Color.MAGENTA, Color.RED
                ),
                floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        p.shader = hueShader
        c.drawRect(0f, barTop, side, barTop + barH, p)
        p.shader = null

        // crosshair in the square
        val cx = sat * side
        val cy = (1f - bri) * side
        ring.strokeWidth = if (px) grid else 3 * d
        ring.color = 0x66000000
        c.drawDisc(px, cx, cy, if (px) 4 * grid else 9 * d, ring)
        ring.color = Color.WHITE
        c.drawDisc(px, cx, cy, if (px) 3 * grid else 7 * d, ring)

        // marker on the hue strip
        val hx = hue / 360f * side
        ring.strokeWidth = if (px) grid else 3 * d
        ring.color = 0x66000000
        c.drawDisc(px, hx, barTop + barH / 2f, if (px) 5 * grid else 11 * d, ring)
        ring.color = Color.WHITE
        c.drawDisc(px, hx, barTop + barH / 2f, if (px) 4 * grid else 9 * d, ring)
        p.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        c.drawDisc(px, hx, barTop + barH / 2f, if (px) 2 * grid else 6 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = e.x
                    downY = e.y
                    claimed = false
                    onBar = e.y >= barTop
                }
                // the square is a 2-D control and claims at once; the hue strip waits for clear horizontal intent
                if (!claimed) {
                    if (!onBar || (Math.abs(e.x - downX) > slop && Math.abs(e.x - downX) > Math.abs(e.y - downY))) {
                        claimed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (Math.abs(e.y - downY) > slop) {
                        return false
                    }
                }
                if (claimed || e.actionMasked == MotionEvent.ACTION_DOWN) apply(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && claimed) {
                    apply(e.x, e.y)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun apply(x: Float, y: Float) {
        if (side <= 0f) return
        if (onBar) {
            val f = (x / side).coerceIn(0f, 1f)
            hue = f * 360f
            if (hue >= 360f) hue = 359.9f
        } else {
            sat = (x / side).coerceIn(0f, 1f)
            bri = 1f - (y / side).coerceIn(0f, 1f)
        }
        color = Color.HSVToColor(floatArrayOf(hue, sat, bri))
        invalidate()
        onColor?.invoke(color)
    }
}

/** Helpers for the colour rows and the colour dialog. */
object ColorPicker {

    /** "#RRGGBB" */
    fun hex(c: Int): String = String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c))

    /**
     * Modal colour picker: the HSV square, the hue strip, a hex field and a row of presets.
     * The value is applied live through [onPicked] so the caller can preview it straight away.
     */
    fun show(
        ctx: Context, title: String, initial: Int, accent: Int,
        autoLabel: String? = null, restore: Int = initial, onDone: (() -> Unit)? = null, onPicked: (Int) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val picker = HsvPickerView(ctx).apply { setColorSilently(initial) }

        val hex = TextView(ctx).apply {
            PixelMode.typeface(ctx)?.let { typeface = it }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Skin.TEXT)
            text = hex(initial)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (44 * d).toInt()
        }
        val field = EditText(ctx).apply {
            setText(hex(initial))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Skin.TEXT)
            setHintTextColor(Skin.TEXT_3)
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hexRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (4 * d).toInt()
            setPadding(p, 0, p, 0)
        }
        hexRow.addView(field)


        // presets scroll sideways so every swatch stays a round 44 dp cell
        val presets = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (c in PRESETS) {
            val s = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke((1 * d).toInt(), 0x55000000)
                }
                isClickable = true
                contentDescription = hex(c)
                setOnClickListener { picker.setColor(c) }
            }
            presets.addView(s, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                marginEnd = (8 * d).toInt()
            })
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, (8 * d).toInt(), p, 0)
            background = android.graphics.drawable.ColorDrawable(Skin.BG)
            addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hexRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply {
                topMargin = (12 * d).toInt()
            })
            addView(View(ctx).apply { setBackgroundColor(accent) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2 * d).toInt()))
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(presets, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply {
                topMargin = (8 * d).toInt()
            })
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(body)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .apply { if (autoLabel != null) setNeutralButton(autoLabel, null) }
            .create()

        var live = initial
        fun applyField() {
            val t = field.text.toString().trim()
            val c = if (t.startsWith("#")) {
                try {
                    Color.parseColor(t)
                } catch (e: Exception) {
                    null
                }
            } else null
            if (c != null) {
                picker.setColorSilently(c)
                live = c
                hex.text = hex(c)
                onPicked(c)
            }
        }
        picker.onColor = { c ->
            live = c
            hex.text = hex(c)
            field.setText(hex(c))
            onPicked(c)
        }
        field.setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) applyField() }

        dialog.setOnDismissListener { onDone?.invoke() }
        dialog.setOnShowListener {
            dialog.window?.decorView?.let { PixelMode.applyTo(it) }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Skin.BG))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Skin.TEXT_2)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyField()
                dialog.dismiss()
            }
            // the value is applied live, so Cancel puts the original colour back
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                picker.setColorSilently(initial)
                onPicked(restore)
                dialog.dismiss()
            }
            if (autoLabel != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(accent)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    onPicked(0)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** A small, useful spread of colours. */
    val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFE5E5EA.toInt(), 0xFF9A9AA0.toInt(), 0xFF6E6E76.toInt(),
        0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt(), 0xFF000000.toInt(), 0xFFFF4B3E.toInt(),
        0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF00D4C7.toInt(), 0xFF8E8E93.toInt()
    )
}
