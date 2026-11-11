package com.dynedge

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

enum class ToggleId { WIFI, BLUETOOTH, TORCH, DND, ROTATE, VIBRATE }

/**
 * State and actions for the six quick toggles. Listeners exist only between start() and stop().
 * All onChange callbacks arrive on the main thread.
 */
@Suppress("DEPRECATION", "MissingPermission")
class QuickToggles(private val ctx: Context, private val onChange: () -> Unit) {

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())

    private val wifi: WifiManager? = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val camera: CameraManager? = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val nm: NotificationManager? =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val audio: AudioManager? = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val resolver = app.contentResolver
    private val rotateUri: Uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)

    private val torchId: String? = findTorchId()

    private var wifiOn = false
    private var btOn = false
    private var torchOn = false
    private var dndOn = false
    private var rotateOn = true
    private var vibOn = false

    private var started = false

    private fun fire() = onChange()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (refreshAll()) fire()
        }
    }

    private val rotateObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (refreshAll()) fire()
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId && torchOn != enabled) {
                torchOn = enabled
                fire()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        refreshAll()

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            // Without the receiver the states still refresh on every start().
        }
        try {
            resolver.registerContentObserver(rotateUri, false, rotateObserver)
        } catch (e: Exception) {
        }
        if (camera != null && torchId != null) {
            try {
                camera.registerTorchCallback(torchCallback, main)
            } catch (e: Exception) {
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        try {
            resolver.unregisterContentObserver(rotateObserver)
        } catch (e: Exception) {
        }
        if (camera != null) {
            try {
                camera.unregisterTorchCallback(torchCallback)
            } catch (e: Exception) {
            }
        }
    }

    fun isOn(id: ToggleId): Boolean = when (id) {
        ToggleId.WIFI -> wifiOn
        ToggleId.BLUETOOTH -> btOn
        ToggleId.TORCH -> torchOn
        ToggleId.DND -> dndOn
        ToggleId.ROTATE -> rotateOn
        ToggleId.VIBRATE -> vibOn
    }

    fun isAvailable(id: ToggleId): Boolean = when (id) {
        ToggleId.WIFI -> wifi != null
        ToggleId.BLUETOOTH -> adapter != null
        ToggleId.TORCH -> torchId != null
        ToggleId.DND -> nm != null
        ToggleId.ROTATE -> true
        ToggleId.VIBRATE -> audio != null
    }

    fun iconRes(id: ToggleId): Int = when (id) {
        ToggleId.WIFI -> if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off
        ToggleId.BLUETOOTH -> if (btOn) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
        ToggleId.TORCH -> if (torchOn) R.drawable.ic_flashlight else R.drawable.ic_flashlight_off
        ToggleId.DND -> R.drawable.ic_moon
        ToggleId.ROTATE -> if (rotateOn) R.drawable.ic_rotate else R.drawable.ic_rotate_lock
        ToggleId.VIBRATE -> if (vibOn) R.drawable.ic_vibrate else R.drawable.ic_bell
    }

    /** Performs the toggle. Returns true if an Activity was launched (caller collapses). */
    fun toggle(id: ToggleId): Boolean {
        if (!isAvailable(id)) return false
        return when (id) {
            ToggleId.WIFI -> toggleWifi()
            ToggleId.BLUETOOTH -> toggleBluetooth()
            ToggleId.TORCH -> toggleTorch()
            ToggleId.DND -> toggleDnd()
            ToggleId.ROTATE -> toggleRotate()
            ToggleId.VIBRATE -> toggleVibrate()
        }
    }

    /** Long-press target. Returns true if an Activity was launched. */
    fun openSettings(id: ToggleId): Boolean = when (id) {
        ToggleId.WIFI -> launch(Intent(Settings.ACTION_WIFI_SETTINGS))
        ToggleId.BLUETOOTH -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        ToggleId.TORCH -> false
        ToggleId.DND -> launch(Intent("android.settings.ZEN_MODE_SETTINGS")) ||
            launch(Intent(Settings.ACTION_SOUND_SETTINGS))
        ToggleId.ROTATE -> launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        ToggleId.VIBRATE -> launch(Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    // ---- individual toggles -------------------------------------------------------------

    private fun toggleWifi(): Boolean {
        val wm = wifi ?: return false
        val target = !wifiOn
        val ok = try {
            wm.setWifiEnabled(target)
        } catch (e: Exception) {
            false
        }
        if (!ok) return launch(Intent(Settings.Panel.ACTION_WIFI))
        wifiOn = target
        fire()
        return false
    }

    private fun toggleBluetooth(): Boolean {
        val a = adapter ?: return false
        val target = !btOn
        val ok = try {
            if (target) a.enable() else a.disable()
        } catch (e: Exception) {
            false
        }
        if (!ok) return launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        btOn = target
        fire()
        return false
    }

    private fun toggleTorch(): Boolean {
        val cm = camera ?: return false
        val id = torchId ?: return false
        try {
            cm.setTorchMode(id, !torchOn)
        } catch (e: CameraAccessException) {
        } catch (e: IllegalArgumentException) {
        } catch (e: Exception) {
        }
        return false
    }

    private fun toggleDnd(): Boolean {
        val m = nm ?: return false
        if (!m.isNotificationPolicyAccessGranted) {
            return launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        try {
            m.setInterruptionFilter(
                if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALL
                else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        } catch (e: SecurityException) {
            return launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        if (refreshAll()) fire()
        return false
    }

    private fun toggleRotate(): Boolean {
        if (!Settings.System.canWrite(app)) {
            return launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + app.packageName))
            )
        }
        try {
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (rotateOn) 0 else 1)
        } catch (e: Exception) {
            return false
        }
        if (refreshAll()) fire()
        return false
    }

    private fun toggleVibrate(): Boolean {
        val am = audio ?: return false
        try {
            am.ringerMode = if (vibOn) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
        } catch (e: SecurityException) {
            return launch(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        if (refreshAll()) fire()
        return false
    }

    // ---- state ------------------------------------------------------------------------

    /** Re-reads every state except the torch (which has its own callback). True if anything changed. */
    private fun refreshAll(): Boolean {
        var changed = false

        val w = readWifi()
        if (w != wifiOn) { wifiOn = w; changed = true }

        val b = readBluetooth()
        if (b != btOn) { btOn = b; changed = true }

        val d = readDnd()
        if (d != dndOn) { dndOn = d; changed = true }

        val r = readRotate()
        if (r != rotateOn) { rotateOn = r; changed = true }

        val v = readVibrate()
        if (v != vibOn) { vibOn = v; changed = true }

        return changed
    }

    private fun readWifi(): Boolean {
        val wm = wifi ?: return false
        return try {
            val s = wm.wifiState
            s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING
        } catch (e: Exception) {
            false
        }
    }

    private fun readBluetooth(): Boolean {
        val a = adapter ?: return false
        return try {
            val s = a.state
            s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_TURNING_ON
        } catch (e: Exception) {
            false
        }
    }

    private fun readDnd(): Boolean {
        val m = nm ?: return false
        return try {
            m.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            false
        }
    }

    private fun readRotate(): Boolean = try {
        Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
    } catch (e: Exception) {
        true
    }

    private fun readVibrate(): Boolean {
        val am = audio ?: return false
        return try {
            am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        } catch (e: Exception) {
            false
        }
    }

    private fun findTorchId(): String? {
        val cm = camera ?: return null
        return try {
            var any: String? = null
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
                if (any == null) any = id
            }
            any
        } catch (e: Exception) {
            null
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
