package com.dynedge

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Real on/off state of tiles, read from system settings and managers. Takes native ids ("wifi", "bluetooth",
 * "battery_saver", ...) and "sys:<spec>" ids. Returns [QsDriver.UNKNOWN] when it cannot be read (the caller then
 * uses the last state the QS driver saw in the panel). The torch has no getter: QuickToggles tracks it by callback.
 */
internal object TileState {

    /** Spec-like key: "sys:x" -> x; native ids are mapped to the SystemUI spec name. */
    fun spec(id: String): String {
        if (id.startsWith(QsCatalog.SYS)) return id.substring(QsCatalog.SYS.length)
        return when (id) {
            "bluetooth" -> "bt"
            "torch" -> "flashlight"
            "rotate" -> "rotation"
            "battery_saver" -> "battery"
            else -> id
        }
    }

    @Suppress("DEPRECATION")
    fun read(ctx: Context, id: String): Int {
        val app = ctx.applicationContext ?: ctx
        val cr = app.contentResolver
        return try {
            when (spec(id)) {
                "wifi", "internet" -> {
                    val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return QsDriver.UNKNOWN
                    val s = wm.wifiState
                    b(s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING)
                }
                "bt" -> {
                    val a = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        ?: return QsDriver.UNKNOWN
                    val s = a.state
                    b(s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_TURNING_ON)
                }
                "dnd" -> {
                    val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        ?: return QsDriver.UNKNOWN
                    b(nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                "rotation" -> b(Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1)
                "ringer" -> b(ringerMode(app) != AudioManager.RINGER_MODE_NORMAL)
                "location" -> {
                    val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return QsDriver.UNKNOWN
                    b(lm.isLocationEnabled)
                }
                "airplane" -> b(Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
                "cell" -> cell(app)
                "hotspot" -> hotspot(app)
                "nfc" -> {
                    val a = NfcAdapter.getDefaultAdapter(app) ?: return QsDriver.UNKNOWN
                    b(a.isEnabled)
                }
                "dark" -> {
                    val ui = android.content.res.Resources.getSystem().configuration.uiMode
                    b((ui and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                }
                "night" -> b(Settings.Secure.getInt(cr, NIGHT, 0) == 1)
                "reduce_brightness" -> b(Settings.Secure.getInt(cr, DIM, 0) == 1)
                "battery" -> {
                    val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null) b(pm.isPowerSaveMode) else b(Settings.Global.getInt(cr, LOW_POWER, 0) == 1)
                }
                else -> QsDriver.UNKNOWN
            }
        } catch (e: Throwable) {
            QsDriver.UNKNOWN
        }
    }

    /** True when [read] can produce a value for this id at all. */
    fun readable(id: String): Boolean = when (spec(id)) {
        "wifi", "internet", "bt", "dnd", "rotation", "ringer", "location", "airplane", "cell", "hotspot", "nfc",
        "dark", "night", "reduce_brightness", "battery" -> true
        else -> false
    }

    fun ringerMode(ctx: Context): Int = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
    } catch (e: Throwable) {
        AudioManager.RINGER_MODE_NORMAL
    }

    /** Settings.Global mobile_data<defaultDataSubId> when present, else mobile_data, else TelephonyManager. */
    private fun cell(app: Context): Int {
        val cr = app.contentResolver
        val sub = defaultDataSub()
        if (sub >= 0) {
            val v = Settings.Global.getInt(cr, "mobile_data$sub", -1)
            if (v >= 0) return b(v == 1)
        }
        val g = Settings.Global.getInt(cr, "mobile_data", -1)
        if (g >= 0) return b(g == 1)
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return QsDriver.UNKNOWN
        return try { b(tm.isDataEnabled) } catch (e: Throwable) { QsDriver.UNKNOWN }
    }

    fun defaultDataSub(): Int = try {
        SubscriptionManager.getDefaultDataSubscriptionId()
    } catch (e: Throwable) {
        -1
    }

    private fun hotspot(app: Context): Int {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return QsDriver.UNKNOWN
        try {
            val m = wm.javaClass.getMethod("isWifiApEnabled")
            return b(m.invoke(wm) as Boolean)
        } catch (e: Throwable) {
        }
        return try {
            // WIFI_AP_STATE_ENABLING 12, ENABLED 13
            val s = wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int
            b(s == 12 || s == 13)
        } catch (e: Throwable) {
            QsDriver.UNKNOWN
        }
    }

    const val NIGHT = "night_display_activated"
    const val DIM = "reduce_bright_colors_activated"
    const val LOW_POWER = "low_power"

    private fun b(on: Boolean): Int = if (on) QsDriver.ON else QsDriver.OFF
}
