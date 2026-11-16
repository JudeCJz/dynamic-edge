package com.dynedge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * First-run setup for people who install the APK without a computer: one sheet with every step in order,
 * a check mark on the finished ones, each row opening exactly the screen it needs. Steps that only adb can
 * grant (secure settings, the assistant corner swipe) are never listed: the app works without them.
 * The sheet refreshes itself when the activity resumes (the person comes back from a settings screen).
 */
object Setup {

    private class Step(
        val icon: Int,
        val title: String,
        val hint: String,
        val required: Boolean,
        val done: (Activity) -> Boolean,
        val go: (Activity) -> Unit
    )

    const val REQ_MIC = 41

    private var dialog: AlertDialog? = null
    private var col: LinearLayout? = null
    private var accent = 0
    /** The sheet opens by itself once per app start while the edge is still off. */
    private var autoShown = false

    private fun steps(a: Activity): List<Step> {
        val out = ArrayList<Step>()
        // Android 13+ greys out the accessibility switch of a sideloaded app until this is allowed
        if (Build.VERSION.SDK_INT >= 33 && !Perms.overlayEnabled(a)) {
            out.add(Step(R.drawable.ic_shield, "Allow restricted settings",
                "In App info tap the three-dot menu at the top, then Allow restricted settings. Skip if there is no such menu.",
                true, { false }, { Perms.open(it) { Perms.appDetails(it) } }))
        }
        out.add(Step(R.drawable.ic_layers, "Turn on Dyn Edge",
            "Accessibility > Installed apps > Dyn Edge > On. It only checks whether the keyboard is open.",
            true, { Perms.overlayEnabled(it) }, { Perms.open(it) { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) } }))
        out.add(Step(R.drawable.ic_bolt, "Keep it running",
            "Lets the edge stay on screen when the phone saves battery.",
            false, { batteryOk(it) }, { askBattery(it) }))
        out.add(Step(R.drawable.ic_music, "Music and live activities",
            "Notification access: media controls, timers, downloads and calls on the edge.",
            false, { Perms.mediaListenerEnabled(it) },
            { Perms.open(it) { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) } }))
        out.add(Step(R.drawable.ic_audio_lines, "Music visualiser",
            "Microphone permission, only to read the music level. Nothing is recorded.",
            false, { Perms.granted(it, android.Manifest.permission.RECORD_AUDIO) }, { askMic(it) }))
        out.add(Step(R.drawable.ic_sliders, "Brightness slider",
            "Modify system settings, for the brightness arc.",
            false, { Settings.System.canWrite(it) },
            { Perms.open(it) { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${it.packageName}")) } }))
        out.add(Step(R.drawable.ic_moon, "Do Not Disturb tile",
            "Do Not Disturb access, so the tile can switch it.",
            false, { Perms.dndGranted(it) },
            { Perms.open(it) { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) } }))
        out.add(Step(R.drawable.ic_layers, "Recent apps",
            "Usage access, for the recent apps row and pausing in banking apps.",
            false, { RecentApps.hasAccess(it) },
            { Perms.open(it) { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) } }))
        return out
    }

    /** True when something a friend can fix themselves is still open (adb-only items never count). */
    fun pending(a: Activity): Int = steps(a).count { s -> !(try { s.done(a) } catch (e: Exception) { false }) }

    /** Opens the sheet automatically the first time per app start while the edge is off. */
    fun maybeAuto(a: Activity, acc: Int) {
        if (autoShown || Perms.overlayEnabled(a)) return
        autoShown = true
        show(a, acc)
    }

    fun show(a: Activity, acc: Int) {
        accent = acc
        dialog?.dismiss()
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(8))
        }
        val d = AlertDialog.Builder(a).setTitle("Set up Dyn Edge")
            .setView(ScrollView(a).apply { addView(col) })
            .setNegativeButton("Later", null).create()
        fill(a, col)
        this.col = col
        d.setOnShowListener { d.flat(acc) }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    /** Call from Activity.onResume: re-checks every step while the sheet is open; closes it once all are done. */
    fun refresh(a: Activity) {
        val d = dialog ?: return
        if (!d.isShowing) return
        if (pending(a) == 0) {
            d.dismiss()
            return
        }
        col?.let { fill(a, it) }
    }

    private fun fill(a: Activity, col: LinearLayout) {
        col.removeAllViews()
        var firstOpen = true
        for (s in steps(a)) {
            val done = try { s.done(a) } catch (e: Exception) { false }
            val next = !done && firstOpen
            if (next) firstOpen = false
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = a.dp(64)
                setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                isClickable = !done
                background = if (done) null else a.rippleBg()
                alpha = if (done) 0.55f else 1f
                contentDescription = s.title + if (done) ", done" else ""
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    s.go(a)
                }
            }
            r.addView(a.icon(s.icon, accent, 22), LinearLayout.LayoutParams(a.dp(24), a.dp(24)))
            val text = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(a.txt(s.title + if (s.required) "" else "  (optional)", 15f,
                    if (next) accent else Skin.TEXT))
                addView(a.txt(s.hint, 12f, Skin.TEXT_2))
            }
            r.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = a.dp(14) })
            r.addView(a.icon(if (done) R.drawable.ic_check else R.drawable.ic_chevron_right, accent, 20),
                LinearLayout.LayoutParams(a.dp(24), a.dp(24)).apply { leftMargin = a.dp(8) })
            col.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun batteryOk(a: Activity): Boolean {
        val pm = a.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(a.packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        } catch (e: Exception) {
            Perms.open(a) { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        }
    }

    private fun askMic(a: Activity) {
        val p = android.Manifest.permission.RECORD_AUDIO
        // asked before and refused for good: only App info can grant it now
        if (Prefs.prefs(a).getBoolean("mic_asked", false) && !a.shouldShowRequestPermissionRationale(p)) {
            Perms.open(a) { Perms.appDetails(a) }
            return
        }
        Prefs.prefs(a).edit().putBoolean("mic_asked", true).apply()
        a.requestPermissions(arrayOf(p), REQ_MIC)
    }
}
