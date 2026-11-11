package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * Quick tiles: the native QuickToggles, battery saver, caffeine, global actions and the phone's own QS tiles.
 *
 * Routing ([perform]): silent direct toggles where Android allows (DirectToggles / QuickToggles), everything else
 * by clicking the real tile in the QS panel ([QsDriver]); a direct toggle that did not take falls back to the QS
 * click, a QS click that fails falls back to the tile's settings screen.
 *
 * Optimistic state: a tap shows the target state at once (pending), further taps on that tile are ignored until the
 * real state reaches the target or [PENDING_MS] pass. Listeners only between start() and stop().
 */
class Tiles(private val ctx: Context, private val onChange: () -> Unit) {

    var service: AccessibilityService? = null
    /** Called when a tile opened an Activity / dialog asynchronously (the caller collapses the panel). */
    var onLaunch: (() -> Unit)? = null

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val resolver = app.contentResolver
    private val changed: () -> Unit = { settlePending(); onChange() }
    private val qt = QuickToggles(ctx, changed)

    private var caffeineOn = false
    private var started = false

    private val pendTarget = HashMap<String, Boolean>()
    private val pendUntil = HashMap<String, Long>()
    private val pendCheck = Runnable { expirePending() }

    private val timeoutUri: Uri = Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT)

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            refresh()
            changed()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            changed()
        }
    }

    fun start() {
        if (started) return
        started = true
        qt.start()
        refresh()
        try {
            resolver.registerContentObserver(timeoutUri, false, observer)
            for (u in globalUris()) resolver.registerContentObserver(u, false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(TileState.NIGHT), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(TileState.DIM), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor("ui_night_mode"), false, observer)
            @Suppress("DEPRECATION")
            resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE), false, observer)
        } catch (e: Exception) {
        }
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.nfc.action.ADAPTER_STATE_CHANGED")
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, f)
        } catch (e: Exception) {
        }
    }

    fun stop() {
        if (!started) return
        started = false
        qt.stop()
        try {
            resolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
        }
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        main.removeCallbacks(pendCheck)
        pendTarget.clear()
        pendUntil.clear()
    }

    private fun globalUris(): List<Uri> {
        val out = ArrayList<Uri>(5)
        out.add(Settings.Global.getUriFor(TileState.LOW_POWER))
        out.add(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON))
        out.add(Settings.Global.getUriFor("mobile_data"))
        val sub = TileState.defaultDataSub()
        if (sub >= 0) out.add(Settings.Global.getUriFor("mobile_data$sub"))
        return out
    }

    // ---------------------------------------------------------------- state

    fun isToggle(id: String): Boolean {
        if (id in TOGGLES || id in pendTarget) return true
        if (!QsCatalog.isQs(id) || QsCatalog.isAction(id)) return false
        return TileState.readable(id) || QsDriver.state(id) != QsDriver.UNKNOWN
    }

    /** Shown state: the pending target while a tap settles, else the real state. */
    fun isOn(id: String): Boolean {
        pendTarget[id]?.let { return it }
        return realOn(id)
    }

    /** True while a tap on this tile is settling (draw it with a subtle pending look). */
    fun isPending(id: String): Boolean = id in pendTarget

    /** Real state, or null when it is unknown. */
    private fun realState(id: String): Boolean? {
        val t = toggleId(id)
        if (t != null) {
            if (t == ToggleId.WIFI) {
                val s = TileState.read(app, id)
                if (s != QsDriver.UNKNOWN) return s == QsDriver.ON
            }
            return qt.isOn(t)
        }
        if (id == "battery_saver") return TileState.read(app, id) == QsDriver.ON
        if (id == "caffeine") return caffeineOn
        if (QsCatalog.isQs(id)) {
            val s = if (TileState.readable(id)) TileState.read(app, id) else QsDriver.UNKNOWN
            val v = if (s != QsDriver.UNKNOWN) s else QsDriver.state(id)
            return if (v == QsDriver.UNKNOWN) null else v == QsDriver.ON
        }
        return null
    }

    private fun realOn(id: String): Boolean = realState(id) ?: false

    fun isAvailable(id: String): Boolean {
        val t = toggleId(id)
        if (t != null) return qt.isAvailable(t)
        if (QsCatalog.isQs(id)) return true
        return id in ALL
    }

    fun iconRes(id: String): Int {
        val t = toggleId(id)
        if (t != null) {
            if (id in pendTarget) return pendIcon(t, pendTarget[id] == true)
            return qt.iconRes(t)
        }
        if (id == RINGER) {
            return when (TileState.ringerMode(app)) {
                AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_bell_off
                else -> R.drawable.ic_bell
            }
        }
        return staticIcon(id)
    }

    private fun pendIcon(t: ToggleId, on: Boolean): Int = when (t) {
        ToggleId.WIFI -> if (on) R.drawable.ic_wifi else R.drawable.ic_wifi_off
        ToggleId.BLUETOOTH -> if (on) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
        ToggleId.TORCH -> if (on) R.drawable.ic_flashlight else R.drawable.ic_flashlight_off
        ToggleId.DND -> R.drawable.ic_moon
        ToggleId.ROTATE -> if (on) R.drawable.ic_rotate else R.drawable.ic_rotate_lock
        ToggleId.VIBRATE -> if (on) R.drawable.ic_vibrate else R.drawable.ic_bell
    }

    // ---------------------------------------------------------------- pending

    /** Marks [id] pending with the flipped state when its state is known and it is an on/off tile. */
    private fun markPending(id: String) {
        if (id == RINGER || id == "caffeine") return
        if (QsCatalog.isAction(id)) return
        val cur = realState(id) ?: return
        pendTarget[id] = !cur
        pendUntil[id] = SystemClock.uptimeMillis() + PENDING_MS
        main.removeCallbacks(pendCheck)
        main.postDelayed(pendCheck, PENDING_MS + 20L)
    }

    private fun clearPending(id: String) {
        pendTarget.remove(id)
        pendUntil.remove(id)
    }

    /** Drops pending tiles whose real state reached the target. */
    private fun settlePending() {
        if (pendTarget.isEmpty()) return
        val it = pendTarget.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (realState(e.key) == e.value) {
                pendUntil.remove(e.key)
                it.remove()
            }
        }
    }

    private fun expirePending() {
        val now = SystemClock.uptimeMillis()
        var next = Long.MAX_VALUE
        val it = pendUntil.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value <= now) {
                pendTarget.remove(e.key)
                it.remove()
            } else if (e.value < next) next = e.value
        }
        if (next != Long.MAX_VALUE) main.postDelayed(pendCheck, next - now + 20L)
        if (started) onChange()
    }

    // ---------------------------------------------------------------- actions

    /** True => the caller collapses the panel first (activity launch or global action). */
    fun perform(id: String): Boolean {
        if (id in pendTarget) return false // the last tap on this tile is still settling
        val t = toggleId(id)
        if (t == ToggleId.WIFI) {
            markPending(id)
            return direct(id)
        }
        if (t != null) {
            markPending(id)
            val launched = qt.toggle(t)
            if (launched) clearPending(id)
            return launched
        }
        when (id) {
            "assist" -> return AssistControl.launch(app)
            "battery_saver" -> {
                markPending(id)
                return direct(id)
            }
            "caffeine" -> return toggleCaffeine()
        }
        if (QsCatalog.isQs(id)) {
            if (!QsCatalog.isCustom(id) && DirectToggles.isDirect(id)) {
                markPending(id)
                return direct(id)
            }
            if (QsDriver.busy) {
                tick()
                return false
            }
            markPending(id)
            return viaQs(id)
        }
        val action = globalAction(id)
        val s = service
        if (action != 0 && s != null) {
            s.performGlobalAction(action)
            return true
        }
        return false
    }

    /** Silent direct toggle; when it does not take, the real QS tile is clicked. */
    private fun direct(id: String): Boolean {
        val launched = DirectToggles.toggle(app, id) {
            // the edge's own ids (battery_saver, rotate...) press the phone's tile of the same spec
            val qsId = if (QsCatalog.isQs(id)) id else QsCatalog.SYS + TileState.spec(id)
            // viaQs is true only when it had to open the settings screen right away
            if (viaQs(qsId)) onLaunch?.invoke()
            if (qsId != id) {
                clearPending(id)
                refreshSoon()
            }
        }
        if (launched) clearPending(id)
        refreshSoon()
        return launched
    }

    /**
     * Clicks the real QS tile (async). Returns true only when it launched the settings screen synchronously
     * (no service / driver busy); the QS click itself keeps the panel open so the state change is visible.
     */
    private fun viaQs(id: String): Boolean {
        val ok = QsDriver.run(app, service, id) { r ->
            when (r) {
                QsDriver.LEFT_OPEN -> {
                    clearPending(id)
                    onLaunch?.invoke()
                }
                QsDriver.FAILED -> {
                    clearPending(id)
                    settingsFallback(id)
                }
            }
            refreshSoon()
        }
        if (ok) return false
        clearPending(id)
        return DirectToggles.openSettings(app, id)
    }

    private fun settingsFallback(id: String) {
        clearPending(id)
        if (DirectToggles.openSettings(app, id)) onLaunch?.invoke()
        if (started) onChange()
    }

    /** Re-reads the tiles now, then 0.5 s and 1.5 s later (a toggle settles asynchronously). */
    private fun refreshSoon() {
        if (!started) return
        changed()
        main.postDelayed({ if (started) changed() }, 500L)
        main.postDelayed({ if (started) changed() }, 1500L)
    }

    private fun tick() {
        try {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (e: Exception) {
        }
    }

    fun openSettings(id: String): Boolean {
        val t = toggleId(id)
        if (t != null) return qt.openSettings(t)
        if (QsCatalog.isQs(id)) return DirectToggles.openSettings(app, id)
        return when (id) {
            "battery_saver" -> launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "caffeine" -> launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            else -> false
        }
    }

    // ---------------------------------------------------------------- caffeine

    private fun toggleCaffeine(): Boolean {
        if (!Settings.System.canWrite(app)) {
            return launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + app.packageName)))
        }
        val p = Prefs.prefs(app)
        try {
            if (caffeineOn) {
                val prev = p.getInt(PREV_TIMEOUT, DEFAULT_TIMEOUT)
                Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, prev)
                p.edit().remove(PREV_TIMEOUT).apply()
            } else {
                val cur = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT)
                p.edit().putInt(PREV_TIMEOUT, cur).apply()
                Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, CAFFEINE_MS)
            }
        } catch (e: Exception) {
            return false
        }
        if (refresh()) onChange()
        return false
    }

    private fun refresh(): Boolean {
        val c = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT) == CAFFEINE_MS
        } catch (e: Exception) {
            false
        }
        val ch = c != caffeineOn
        caffeineOn = c
        return ch
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val PREV_TIMEOUT = "caffeine_prev"
        private const val DEFAULT_TIMEOUT = 60000
        private const val CAFFEINE_MS = 30 * 60 * 1000
        private const val PENDING_MS = 3000L
        private const val RINGER = "sys:ringer"

        val ALL: List<String> = listOf(
            "wifi", "bluetooth", "torch", "dnd", "rotate", "vibrate", "assist",
            "battery_saver", "caffeine", "screenshot", "lock",
            "notifications", "quick_settings", "recents", "power_menu", "clipboard"
        )

        private val TOGGLES = setOf(
            "wifi", "bluetooth", "torch", "dnd", "rotate", "vibrate", "battery_saver", "caffeine"
        )

        private fun toggleId(id: String): ToggleId? = when (id) {
            "wifi" -> ToggleId.WIFI
            "bluetooth" -> ToggleId.BLUETOOTH
            "torch" -> ToggleId.TORCH
            "dnd" -> ToggleId.DND
            "rotate" -> ToggleId.ROTATE
            "vibrate" -> ToggleId.VIBRATE
            else -> null
        }

        private fun globalAction(id: String): Int = when (id) {
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> 0
        }

        fun label(id: String): String = if (QsCatalog.isQs(id)) QsCatalog.label(id) else when (id) {
            "wifi" -> "Wi-Fi"
            "bluetooth" -> "Bluetooth"
            "torch" -> "Torch"
            "dnd" -> "DND"
            "rotate" -> "Rotate"
            "vibrate" -> "Vibrate"
            "assist" -> "Assistant"
            "battery_saver" -> "Saver"
            "caffeine" -> "Caffeine"
            "screenshot" -> "Shot"
            "lock" -> "Lock"
            "notifications" -> "Alerts"
            "quick_settings" -> "Settings"
            "recents" -> "Recents"
            "power_menu" -> "Power"
            "clipboard" -> "Clipboard"
            else -> id
        }

        fun staticIcon(id: String): Int = if (QsCatalog.isQs(id)) {
            if (QsCatalog.isCustom(id)) R.drawable.ic_tile else QsCatalog.iconRes(id)
        } else when (id) {
            "wifi" -> R.drawable.ic_wifi
            "bluetooth" -> R.drawable.ic_bluetooth
            "torch" -> R.drawable.ic_flashlight
            "dnd" -> R.drawable.ic_moon
            "rotate" -> R.drawable.ic_rotate
            "vibrate" -> R.drawable.ic_vibrate
            "assist" -> R.drawable.ic_mic
            "battery_saver" -> R.drawable.ic_battery_saver
            "caffeine" -> R.drawable.ic_coffee
            "screenshot" -> R.drawable.ic_screenshot
            "lock" -> R.drawable.ic_lock
            "notifications" -> R.drawable.ic_panel_top
            "quick_settings" -> R.drawable.ic_sliders
            "recents" -> R.drawable.ic_layers
            "power_menu" -> R.drawable.ic_power
            "clipboard" -> R.drawable.ic_clipboard
            else -> R.drawable.ic_tile
        }

        fun needsCollapseDelay(id: String): Boolean = id == "screenshot"
    }
}
