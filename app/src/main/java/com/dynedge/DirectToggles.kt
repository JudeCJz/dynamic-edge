package com.dynedge

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Silent tile toggles through public / settings APIs (no shade). Each direct toggle reads its state back; when it
 * did not take, the caller's fallback runs (Tiles clicks the real QS tile through [QsDriver]). Tiles without a
 * direct route are never handled here. Hotspot, mobile data and airplane mode have NO direct route on purpose:
 * they are only ever changed by a QS click when the user taps that tile.
 */
internal object DirectToggles {

    private val main = Handler(Looper.getMainLooper())

    /** Specs (TileState.spec) toggled here. bt / flashlight / dnd / rotation are native QuickToggles. */
    private val DIRECT = setOf("wifi", "location", "dark", "night", "reduce_brightness", "battery", "ringer")

    fun isDirect(id: String): Boolean = TileState.spec(id) in DIRECT

    /**
     * Toggles tile [id] silently. [fallback] runs (once, on the main thread) when the change did not take.
     * Returns true when an Activity was launched (the caller collapses the panel).
     */
    fun toggle(ctx: Context, id: String, fallback: () -> Unit): Boolean {
        val app = ctx.applicationContext ?: ctx
        return when (TileState.spec(id)) {
            "wifi" -> wifi(app, fallback)
            "location" -> location(app, fallback)
            "dark" -> dark(app, fallback)
            "night" -> secureFlag(app, TileState.NIGHT, fallback)
            "reduce_brightness" -> secureFlag(app, TileState.DIM, fallback)
            "battery" -> battery(app, fallback)
            "ringer" -> ringer(app, fallback)
            else -> {
                fallback()
                false
            }
        }
    }

    /** Long-press target: the tile's settings screen. True when an Activity was launched. */
    fun openSettings(ctx: Context, id: String): Boolean {
        val app = ctx.applicationContext ?: ctx
        if (id.startsWith(QsCatalog.CUS)) return openCustom(app, id)
        return when (TileState.spec(id)) {
            "wifi", "internet" -> launchFirst(app, Intent(Settings.ACTION_WIFI_SETTINGS))
            "bt" -> launchFirst(app, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "location" -> launchFirst(app, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "dark" -> launchFirst(app, Intent("android.settings.DARK_THEME_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "airplane" -> launchFirst(app, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "cell" -> launchFirst(app, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "nfc" -> launchFirst(app, Intent(Settings.ACTION_NFC_SETTINGS))
            "hotspot" -> launchFirst(app, *hotspotIntents())
            "battery" -> launchFirst(app, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "ringer" -> launchFirst(app, Intent(Settings.ACTION_SOUND_SETTINGS))
            else -> launchFirst(app, *fallbackIntents(TileState.spec(id)))
        }
    }

    // ---------------------------------------------------------------- wifi

    @Suppress("DEPRECATION")
    private fun wifi(app: Context, fallback: () -> Unit): Boolean {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            fallback()
            return false
        }
        val target = !wifiWanted(wm)
        val ok = try {
            wm.setWifiEnabled(target)
        } catch (e: Throwable) {
            false
        }
        if (!ok) {
            fallback()
            return false
        }
        main.postDelayed({
            if (wifiWanted(wm) != target) fallback()
        }, 1500L)
        return false
    }

    /** On or switching on. */
    @Suppress("DEPRECATION")
    private fun wifiWanted(wm: WifiManager): Boolean {
        val s = wm.wifiState
        return s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING
    }

    // ---------------------------------------------------------------- location

    private fun location(app: Context, fallback: () -> Unit): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            fallback()
            return false
        }
        val target = !lm.isLocationEnabled
        try {
            @Suppress("DEPRECATION")
            Settings.Secure.putInt(
                app.contentResolver, Settings.Secure.LOCATION_MODE,
                if (target) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            )
        } catch (e: Throwable) {
        }
        if (lm.isLocationEnabled != target) {
            try {
                val m = LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, UserHandle::class.java)
                m.invoke(lm, target, Process.myUserHandle())
            } catch (e: Throwable) {
            }
        }
        main.postDelayed({
            if (lm.isLocationEnabled != target) fallback()
        }, 800L)
        return false
    }

    // ---------------------------------------------------------------- dark mode

    private fun isDark(): Boolean {
        val ui = android.content.res.Resources.getSystem().configuration.uiMode
        return (ui and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dark(app: Context, fallback: () -> Unit): Boolean {
        val um = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (um == null) {
            fallback()
            return false
        }
        val targetDark = !isDark()
        val mode = if (targetDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        var done = false
        try {
            um.nightMode = mode
            done = um.nightMode == mode
        } catch (e: Throwable) {
        }
        if (!done) {
            try {
                // Settings.Secure ui_night_mode: 1 = no, 2 = yes
                Settings.Secure.putInt(app.contentResolver, "ui_night_mode", if (targetDark) 2 else 1)
            } catch (e: Throwable) {
            }
        }
        main.postDelayed({
            val applied = isDark() == targetDark || try { um.nightMode == mode } catch (e: Throwable) { false }
            if (!applied) fallback()
        }, 900L)
        return false
    }

    // ---------------------------------------------------------------- secure flags / battery saver / ringer

    /** Night light (night_display_activated) and extra dim (reduce_bright_colors_activated): 0 / 1. */
    private fun secureFlag(app: Context, key: String, fallback: () -> Unit): Boolean {
        val cr = app.contentResolver
        val ok = try {
            val on = Settings.Secure.getInt(cr, key, 0) == 1
            Settings.Secure.putInt(cr, key, if (on) 0 else 1)
        } catch (e: Throwable) {
            false
        }
        if (!ok) fallback()
        return false
    }

    /** Settings.Global low_power, verified with PowerManager.isPowerSaveMode after 600 ms. */
    private fun battery(app: Context, fallback: () -> Unit): Boolean {
        // Phones with their own battery saver tile: press it. Writing low_power only changes the stored value
        // on Nothing OS (Android 17) - battery saver stays off.
        if (hasQsTile(app, "battery")) {
            fallback()
            return false
        }
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val target = !(pm?.isPowerSaveMode ?: (Settings.Global.getInt(app.contentResolver, TileState.LOW_POWER, 0) == 1))
        val ok = try {
            Settings.Global.putInt(app.contentResolver, TileState.LOW_POWER, if (target) 1 else 0)
        } catch (e: Throwable) {
            false
        }
        if (!ok) {
            fallback()
            return false
        }
        main.postDelayed({
            val now = pm?.isPowerSaveMode ?: return@postDelayed
            if (now != target) fallback()
        }, 600L)
        return false
    }

    /** Ringer: normal -> vibrate -> silent -> normal. */
    /**
     * Cycles normal -> vibrate -> silent. Some phones (Nothing OS on Android 17) ignore an app's request for
     * silent (or any mode) without an error, so the mode is checked after 400 ms and the real QS ringer tile is
     * pressed through [fallback] when it did not change.
     */
    private fun ringer(app: Context, fallback: () -> Unit): Boolean {
        // The phone has its own ringer tile (e.g. Nothing OS): press it, so the edge does exactly what the
        // phone's tile does. Asking AudioManager for silent there turns on Do Not Disturb instead.
        if (hasQsTile(app, "ringer")) {
            fallback()
            return false
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return launchFirst(app, Intent(Settings.ACTION_SOUND_SETTINGS))
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try {
            am.ringerMode = next
        } catch (e: SecurityException) {
            fallback()
            return false
        }
        main.postDelayed({
            val now = try { am.ringerMode } catch (e: Throwable) { next }
            if (now != next) fallback()
        }, 400L)
        return false
    }

    /** True when the phone's quick settings list (sysui_qs_tiles) has the tile [spec]. */
    private fun hasQsTile(app: Context, spec: String): Boolean = try {
        Settings.Secure.getString(app.contentResolver, "sysui_qs_tiles").orEmpty().split(',').any { it.trim() == spec }
    } catch (e: Throwable) {
        false
    }

    // ---------------------------------------------------------------- intents

    private fun hotspotIntents(): Array<Intent> = arrayOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        Intent().setClassName("com.vivo.settings", "com.vivo.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )

    /** Tiles without a dedicated screen above: the nearest settings screen. */
    private fun fallbackIntents(spec: String): Array<Intent> = when (spec) {
        "night" -> arrayOf(Intent("android.settings.NIGHT_DISPLAY_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS))
        "reduce_brightness" -> arrayOf(Intent("android.settings.REDUCE_BRIGHT_COLORS_SETTINGS"), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        "inversion", "color_correction" -> arrayOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        "silent_mode" -> arrayOf(Intent(Settings.ACTION_SOUND_SETTINGS))
        "dataSaver", "saver" -> arrayOf(Intent(Settings.ACTION_DATA_USAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        "calculator" -> arrayOf(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
        "mictoggle", "cameratoggle" -> arrayOf(Intent("android.settings.PRIVACY_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        "cast" -> arrayOf(Intent(Settings.ACTION_CAST_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        "dream" -> arrayOf(Intent(Settings.ACTION_DREAM_SETTINGS), Intent(Settings.ACTION_DISPLAY_SETTINGS))
        "flashlight", "rotation" -> arrayOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        "dnd" -> arrayOf(Intent("android.settings.ZEN_MODE_SETTINGS"), Intent(Settings.ACTION_SOUND_SETTINGS))
        else -> arrayOf(Intent(Settings.ACTION_SETTINGS))
    }

    /** A third-party tile: its own preferences screen if it declares one, else the app. */
    private fun openCustom(app: Context, id: String): Boolean {
        val cn = ComponentName.unflattenFromString(id.removePrefix(QsCatalog.CUS)) ?: return false
        val pm = app.packageManager
        val prefs = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(cn.packageName)
        val ri = try { pm.resolveActivity(prefs, 0) } catch (e: Throwable) { null }
        if (ri != null) {
            prefs.component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
            if (launch(app, prefs)) return true
        }
        val launcher = pm.getLaunchIntentForPackage(cn.packageName) ?: return false
        return launch(app, launcher)
    }

    private fun launchFirst(app: Context, vararg intents: Intent): Boolean {
        val pm = app.packageManager
        for (i in intents) {
            val ok = try { i.resolveActivity(pm) != null } catch (e: Throwable) { false }
            if (ok && launch(app, i)) return true
        }
        return false
    }

    private fun launch(app: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(i)
        true
    } catch (e: Throwable) {
        false
    }
}
