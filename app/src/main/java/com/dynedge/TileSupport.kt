package com.dynedge

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.provider.Settings

/**
 * Does a quick tile work by itself when tapped on the edge, or would it only open a settings screen?
 *
 * Used by the Quick layout editor only (red markers); the overlay never asks. Cheap: a few Settings reads and
 * system-service lookups, results are not cached (the editor calls it once per catalog build).
 *
 * OK: a direct toggle with everything it needs, or a QS-click tile whose spec is in this phone's
 * sysui_qs_tiles / was seen in the live panel, with the accessibility service on and no recorded QsDriver failure.
 */
internal object TileSupport {

    /** What a tile needs; [intent] opens the screen that grants it (null when only adb can). */
    enum class Need(val label: String) {
        DND("DND access"),
        WRITE_SETTINGS("Modify system settings"),
        SECURE("secure settings (adb grant)"),
        ACCESSIBILITY("the Dyn Edge service on"),
        USAGE("usage access")
    }

    sealed class Support {
        object Ok : Support()
        class OpensSettings(val reason: String) : Support()
        class MissingHardware(val what: String) : Support()
        class NeedsPermission(val which: Need) : Support()

        val ok: Boolean get() = this is Ok

        /** One short line for the editor. */
        fun reason(): String = when (this) {
            is Ok -> ""
            is OpensSettings -> "Opens settings: $reason"
            is MissingHardware -> "This phone has no $what"
            is NeedsPermission -> "Needs ${which.label}" + if (which == Need.SECURE) "" else " - tap to grant"
        }
    }

    fun support(ctx: Context, id: String): Support {
        val app = ctx.applicationContext ?: ctx
        return try {
            decide(app, id)
        } catch (e: Exception) {
            Support.Ok
        }
    }

    /**
     * Can this tile work on this phone at all? False = missing hardware, not in this phone's quick settings, failed in
     * the panel last time, or only adb can grant what it needs. A tile that only lacks a grantable permission
     * (DND access, write settings, the Dyn Edge service) is still usable. Unknown ids are usable.
     * Used by the Quick editor's tray, and meant for the overlay to skip such tiles. Cheap: no I/O beyond a few
     * Settings reads; hardware lookups are cached.
     */
    fun usable(ctx: Context, id: String): Boolean {
        if (ShortcutStore.isId(id)) return true
        return when (val s = support(ctx, id)) {
            is Support.Ok -> true
            is Support.MissingHardware -> false
            is Support.OpensSettings -> false
            is Support.NeedsPermission -> s.which != Need.SECURE
        }
    }

    /** The screen that fixes [need], or null when it cannot be granted on the phone (adb only). */
    fun intent(ctx: Context, need: Need): Intent? = when (need) {
        Need.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        Need.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
        Need.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Need.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Need.SECURE -> null
    }

    private fun decide(app: Context, id: String): Support {
        when (id) {
            "wifi" -> return if (app.getSystemService(Context.WIFI_SERVICE) == null) Support.MissingHardware("Wi-Fi") else Support.Ok
            "bluetooth" -> return if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
                Support.MissingHardware("Bluetooth") else Support.Ok
            "torch" -> return if (!hasFlash(app)) Support.MissingHardware("flash") else Support.Ok
            "dnd" -> return if (!dnd(app)) Support.NeedsPermission(Need.DND) else Support.Ok
            "rotate", "caffeine" -> return if (!Settings.System.canWrite(app)) Support.NeedsPermission(Need.WRITE_SETTINGS) else Support.Ok
            "vibrate", "assist", "clipboard" -> return Support.Ok
            "battery_saver" -> {
                // a phone with its own saver tile gets that tile pressed (Nothing OS ignores the Global write)
                if (inPanel(app, "battery")) return qs(app, id, "battery")
                return if (!secure(app)) Support.NeedsPermission(Need.SECURE) else Support.Ok
            }
            "screenshot", "lock", "notifications", "quick_settings", "recents", "power_menu" ->
                return if (!Perms.overlayEnabled(app)) Support.NeedsPermission(Need.ACCESSIBILITY) else Support.Ok
        }
        if (id.startsWith(QsCatalog.CUS)) return qs(app, id, QsCatalog.specOf(id) ?: return Support.OpensSettings("unknown tile"))
        if (!id.startsWith(QsCatalog.SYS)) return Support.Ok
        val spec = id.substring(QsCatalog.SYS.length)
        when (spec) {
            "nfc" -> if (!feature(app, PackageManager.FEATURE_NFC) || NfcAdapter.getDefaultAdapter(app) == null)
                return Support.MissingHardware("NFC")
            "cell" -> if (!feature(app, PackageManager.FEATURE_TELEPHONY)) return Support.MissingHardware("mobile data")
            "hotspot" -> if (!feature(app, PackageManager.FEATURE_WIFI)) return Support.MissingHardware("Wi-Fi")
            "flashlight" -> if (!hasFlash(app)) return Support.MissingHardware("flash")
            "bt" -> if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return Support.MissingHardware("Bluetooth")
        }
        if (DirectToggles.isDirect(id)) {
            when (spec) {
                "wifi" -> return Support.Ok
                // secure-settings writes: fine with the grant, else the QS tile has to be pressed
                "location", "night", "reduce_brightness", "dark" -> if (secure(app)) return Support.Ok
                "ringer" -> if (!inPanel(app, "ringer")) return Support.Ok
                "battery" -> if (!inPanel(app, "battery") && secure(app)) return Support.Ok
            }
        }
        return qs(app, id, spec)
    }

    /** A tile that is pressed in the QS panel by QsDriver. */
    private fun qs(app: Context, id: String, spec: String): Support {
        if (failed(app, id) || failed(app, QsCatalog.SYS + spec)) return Support.OpensSettings("not found in quick settings last time")
        if (!inPanel(app, spec)) return Support.OpensSettings("not in this phone's quick settings")
        if (!Perms.overlayEnabled(app)) return Support.NeedsPermission(Need.ACCESSIBILITY)
        return Support.Ok
    }

    /** [spec] is in sysui_qs_tiles or was seen in the live panel. custom(...) specs compare by component. */
    private fun inPanel(app: Context, spec: String): Boolean {
        val all = QsCatalog.allSpecs(app)
        if (spec.startsWith("custom(")) {
            val want = component(spec) ?: return false
            return all.any { it.startsWith("custom(") && component(it) == want }
        }
        return spec in all || QsCatalog.seenSpecs(app).contains(spec)
    }

    private fun component(spec: String): ComponentName? =
        ComponentName.unflattenFromString(spec.removePrefix("custom(").removeSuffix(")"))

    private var flash: Boolean? = null

    private fun hasFlash(app: Context): Boolean = flash ?: (try {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        cm != null && cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }).also { flash = it }

    private fun feature(app: Context, f: String): Boolean = app.packageManager.hasSystemFeature(f)

    private fun dnd(app: Context): Boolean =
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.isNotificationPolicyAccessGranted ?: false

    private fun secure(app: Context): Boolean =
        app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- QsDriver failures

    private const val FAIL_KEY = "qs_failed_ids"

    private fun failed(app: Context, id: String): Boolean =
        Prefs.prefs(app).getStringSet(FAIL_KEY, null)?.contains(id) == true

    /** QsDriver could not find the tile in the panel: remembered until the next success. */
    fun recordFailure(ctx: Context, id: String) = edit(ctx, id, true)

    fun recordSuccess(ctx: Context, id: String) = edit(ctx, id, false)

    private fun edit(ctx: Context, id: String, add: Boolean) {
        try {
            val p = Prefs.prefs(ctx)
            val cur = p.getStringSet(FAIL_KEY, null) ?: emptySet()
            if ((id in cur) == add) return
            val next = HashSet(cur)
            if (add) next.add(id) else next.remove(id)
            p.edit().putStringSet(FAIL_KEY, next).apply()
        } catch (e: Exception) {
        }
    }
}
