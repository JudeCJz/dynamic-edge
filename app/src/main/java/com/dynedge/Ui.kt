package com.dynedge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small shared view factories for the settings screens (the studio and the editor / picker / clipboard
 * screens). Flat: no cards, no rounded boxes, no dividers; touch targets are at least 44 dp.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The theme colour as the settings paint it (lightened if it would be unreadable). */
fun Context.accentUi(): Int = Skin.accentUi(Prefs.accent(this))

fun Context.lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.txt(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

fun Context.icon(res: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(color)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.rippleBg() = Skin.ripple(this)

/** Accent section label that separates groups (no boxes, no divider). */
fun Context.sectionLabel(label: String): TextView = txt(label, 13f, accentUi(), true).apply {
    setPadding(dp(Skin.PAD), dp(28), dp(Skin.PAD), dp(8))
    layoutParams = lp(MATCH, WRAP)
}

/** A tappable icon-only button of at least 44 dp. */
fun Context.iconButton(res: Int, desc: String, color: Int = Skin.TEXT_2, w: Int = 48, h: Int = 48, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp((h - 24) / 2), dp(12), dp((h - 24) / 2))
        isClickable = true
        background = rippleBg()
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = lp(dp(w), dp(h))
    }

/**
 * Top bar shared by every settings screen: a short title on the main screen; an accent chevron-left back
 * button and the title on the others. 56 dp, flat, no divider. The main screen shows no logo - the
 * settings page is a plain list, the logo only belongs on the launcher icon.
 */
fun Context.topBar(title: String, onBack: (() -> Unit)?): View {
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
    }
    if (onBack != null) {
        bar.setPadding(dp(4), 0, dp(Skin.PAD), 0)
        bar.addView(iconButton(R.drawable.ic_chevron_left, "Back", accentUi(), 48, 56) { onBack() })
        bar.addView(txt(title, 18f, Skin.TEXT, true), lp(0, WRAP, 1f))
    } else {
        bar.setPadding(dp(Skin.PAD), 0, dp(Skin.PAD), 0)
        bar.addView(txt(title, 20f, Skin.TEXT, true), lp(0, WRAP, 1f))
    }
    return bar
}

/** A round colour swatch. [selected] adds a ring around it. */
class Swatch(ctx: Context, var colour: Int, var chosen: Boolean, var ring: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) / 2f
        val r = half * 0.64f
        p.style = Paint.Style.FILL
        p.color = colour
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = 0x44FFFFFF
        c.drawCircle(cx, cy, r, p)
        if (chosen) {
            p.strokeWidth = 2 * d
            p.color = ring
            c.drawCircle(cx, cy, half * 0.86f, p)
        }
    }
}

// ---------------------------------------------------------------- dialogs

private fun AlertDialog.flat(accent: Int) {
    window?.setBackgroundDrawable(ColorDrawable(Skin.BG))
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Skin.TEXT_2)
}

/** Ask before doing something destructive. */
fun Context.confirmDialog(title: String, message: String, positive: String, onYes: () -> Unit) {
    val acc = accentUi()
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive, null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnShowListener {
        dialog.flat(acc)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            onYes()
        }
    }
    dialog.show()
}

/** Ask for a short name. */
fun Context.nameDialog(title: String, hint: String, onName: (String) -> Unit) {
    val acc = accentUi()
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Skin.TEXT)
        setHintTextColor(Skin.TEXT_3)
        this.hint = hint
        setSingleLine(true)
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(input, lp(MATCH, WRAP))
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnShowListener {
        dialog.flat(acc)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = input.text.toString().trim()
            if (n.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onName(n)
        }
    }
    dialog.show()
}

/** The pixel-art logo at 32 dp, scaled with nearest-neighbour (no filtering). Null if it cannot be decoded. */
private fun Context.pixelLogo(): ImageView? {
    val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
    val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.logo_pixel, opts) ?: return null
    val d = android.graphics.drawable.BitmapDrawable(resources, bmp)
    d.setFilterBitmap(false)
    return ImageView(this).apply {
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = null
    }
}
