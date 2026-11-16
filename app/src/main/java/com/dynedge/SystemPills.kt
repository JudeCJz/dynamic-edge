package com.dynedge

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Charging details (time to full / watts, the 80% pill) and short system pills (headphones, VPN, hotspot,
 * low battery). Driven by EdgeService: [start] after the overlay is up, [stop] on teardown. Observes only.
 */
class SystemPills(private val ctx: Context, private val screenOff: () -> Boolean) {

    companion object {
        private const val TAG = "DynEdgePills"
        const val KEY_CHARGE_DETAIL = "live_charge_detail"
        const val KEY_CHARGE80 = "live_charge80"
        const val KEY_HEADPHONES = "pill_headphones"
        const val KEY_VPN = "pill_vpn"
        const val KEY_HOTSPOT = "pill_hotspot"
        const val KEY_BATTERY = "pill_battery"
        private const val PILL_MS = 6000L
        private const val TICK_MS = 3000L
        private const val ACTION_AP = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val AP_ENABLED = 13
        private const val AP_DISABLED = 11
    }

    private val main = Handler(Looper.getMainLooper())
    private val pillRemovers = HashMap<String, Runnable>()
    private var started = false
    private var startedAt = 0L

    // battery cache
    private var level = -1
    private var voltageMv = 0
    private var plugged = false
    private var fired80 = false
    private var fired15 = false
    private var fired5 = false

    // charging ticker
    private var chargeShown = false
    private var chipIndex = 0

    // other state
    private var lastAp = -1
    private val vpnNets = HashSet<Network>()
    private var vpnCb: ConnectivityManager.NetworkCallback? = null
    private var btDevice: BluetoothDevice? = null
    private var wiredIn = false

    private fun pref(key: String, def: Boolean) = Prefs.prefs(ctx).getBoolean(key, def)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                handle(intent ?: return, isInitialStickyBroadcast)
            } catch (e: Exception) {
                Log.w(TAG, "receiver failed", e)
            }
        }
    }

    private val chargeTick = object : Runnable {
        override fun run() {
            if (!chargeShown) return
            postCharge()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val chargeRemove = Runnable { LiveBus.remove("charging") }

    fun start() {
        if (started) return
        started = true
        startedAt = SystemClock.elapsedRealtime()
        val f = IntentFilter()
        f.addAction(Intent.ACTION_BATTERY_CHANGED)
        f.addAction(Intent.ACTION_HEADSET_PLUG)
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        f.addAction(ACTION_BT_BATTERY)
        f.addAction(ACTION_AP)
        try {
            ctx.registerReceiver(receiver, f)
        } catch (e: Exception) {
            Log.w(TAG, "register failed", e)
        }
        registerVpn()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed", e)
        }
        try {
            vpnCb?.let { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "vpn callback unregister failed", e)
        }
        vpnCb = null
        vpnNets.clear()
        chargeShown = false
        main.removeCallbacksAndMessages(null)
        pillRemovers.clear()
        LiveBus.removeIf { it.startsWith("pill:") }
        LiveBus.remove("charging")
        lastAp = -1
        btDevice = null
        wiredIn = false
    }

    // ------------------------------------------------------------ charging

    /** Power plugged in: the short pop, or the long-lived detail chip when the pref is on. */
    fun chargeStart() {
        if (!Prefs.live(ctx)) return
        readSticky()
        fired80 = level >= 80
        if (screenOff()) return
        chargeShown = true
        chipIndex = 0
        main.removeCallbacks(chargeTick)
        main.removeCallbacks(chargeRemove)
        postCharge()
        if (pref(KEY_CHARGE_DETAIL, true)) {
            main.postDelayed(chargeTick, TICK_MS)
        } else {
            main.postDelayed(chargeRemove, 3500L)
        }
    }

    fun chargeStop() {
        chargeShown = false
        fired80 = false
        main.removeCallbacks(chargeTick)
        main.removeCallbacks(chargeRemove)
        LiveBus.remove("charging")
        LiveBus.remove("pill:80")
    }

    private fun postCharge() {
        if (screenOff()) return
        readSticky()
        val pct = if (level >= 0) level else 0
        val detail = pref(KEY_CHARGE_DETAIL, true)
        var chip = ""
        if (detail) {
            val chips = ArrayList<String>(2)
            timeToFull()?.let { chips.add(it) }
            watts()?.let { chips.add(it) }
            if (chips.isNotEmpty()) {
                chip = chips[chipIndex % chips.size]
                chipIndex++
            }
        }
        LiveBus.put(
            LiveActivity(
                "charging", LiveActivity.Kind.CHARGING, "$pct%", pct / 100f, 0L, false,
                R.drawable.ic_bolt, detail, null, null, null, chip = chip
            )
        )
    }

    private fun timeToFull(): String? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val ms = try { bm.computeChargeTimeRemaining() } catch (e: Exception) { -1L }
        if (ms <= 0L) return null
        val h = ms / 3600000L
        val m = (ms / 60000L) % 60L
        return if (h > 0L) "${h}h ${m}m" else "${if (m < 1L) 1L else m}m"
    }

    private fun watts(): String? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        var ua = try {
            Math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong())
        } catch (e: Exception) {
            0L
        }
        if (ua <= 0L || voltageMv <= 0) return null
        if (ua < 20000L) ua *= 1000L // some phones report milliamps
        val w = Math.round(ua / 1_000_000.0 * voltageMv / 1000.0).toInt()
        return if (w >= 1) "${w}W" else null
    }

    private fun readSticky() {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        readBattery(i)
    }

    private fun readBattery(i: Intent) {
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lv >= 0 && sc > 0) level = lv * 100 / sc
        voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, voltageMv)
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    // ------------------------------------------------------------ receiver

    private fun handle(i: Intent, initial: Boolean) {
        when (i.action) {
            Intent.ACTION_BATTERY_CHANGED -> onBattery(i, initial)
            Intent.ACTION_HEADSET_PLUG -> {
                val on = i.getIntExtra("state", 0) == 1
                val was = wiredIn
                wiredIn = on
                if (on && !was && !initial) pill("headphones", "Headphones", R.drawable.ic_headphones, "", KEY_HEADPHONES)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> onBtConnected(i)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val d = btDevice
                if (d != null && d == deviceOf(i)) btDevice = null
            }
            ACTION_BT_BATTERY -> {
                val d = deviceOf(i)
                val lv = i.getIntExtra(EXTRA_BT_BATTERY, -1)
                if (d != null && d == btDevice && lv in 0..100 && pillRemovers.containsKey("pill:headphones")) {
                    pill("headphones", "Headphones", R.drawable.ic_headphones, "$lv%", KEY_HEADPHONES)
                }
            }
            ACTION_AP -> {
                val st = i.getIntExtra("wifi_state", -1)
                val norm = when (st) {
                    AP_ENABLED -> 1
                    AP_DISABLED -> 0
                    else -> -1
                }
                if (norm >= 0 && norm != lastAp) {
                    val first = lastAp < 0
                    lastAp = norm
                    if (!first || norm == 1) {
                        pill("hotspot", if (norm == 1) "Hotspot on" else "Hotspot off", R.drawable.ic_hotspot, "", KEY_HOTSPOT)
                    }
                }
            }
        }
    }

    private fun onBattery(i: Intent, initial: Boolean) {
        readBattery(i)
        if (level < 0) return
        if (plugged) {
            fired15 = true
            fired5 = true
            if (!fired80 && level >= 80) {
                fired80 = true
                if (!initial && pref(KEY_CHARGE80, false)) pill("80", "80%", R.drawable.ic_bolt, "", KEY_CHARGE80, false)
            }
        } else {
            if (level > 15) fired15 = false
            if (level > 5) fired5 = false
            if (level <= 5 && !fired5) {
                fired5 = true
                fired15 = true
                if (!initial) pill("battery", "Battery 5%", R.drawable.ic_battery_low, "", KEY_BATTERY)
            } else if (level <= 15 && !fired15) {
                fired15 = true
                if (!initial) pill("battery", "Battery 15%", R.drawable.ic_battery_low, "", KEY_BATTERY)
            }
            if (initial) {
                if (level <= 15) fired15 = true
                if (level <= 5) fired5 = true
            }
        }
    }

    // ------------------------------------------------------------ bluetooth

    private fun deviceOf(i: Intent): BluetoothDevice? = try {
        i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
    } catch (e: Exception) {
        null
    }

    private fun isAudio(d: BluetoothDevice): Boolean = try {
        when (d.bluetoothClass?.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED -> true
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    private fun btBattery(d: BluetoothDevice): Int = try {
        (d.javaClass.getMethod("getBatteryLevel").invoke(d) as? Int) ?: -1
    } catch (e: Throwable) {
        -1
    }

    private fun onBtConnected(i: Intent) {
        val d = deviceOf(i) ?: return
        if (!isAudio(d)) return
        btDevice = d
        // the earbud reports its battery a moment after the link comes up
        main.postDelayed({
            if (!started || btDevice != d) return@postDelayed
            val lv = btBattery(d)
            pill("headphones", "Headphones", R.drawable.ic_headphones, if (lv in 0..100) "$lv%" else "", KEY_HEADPHONES)
        }, 1500L)
    }

    // ------------------------------------------------------------ vpn

    private fun registerVpn() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val added = vpnNets.add(network)
                    // callbacks for a VPN that was already up when the service started are not news
                    if (added && vpnNets.size == 1 && SystemClock.elapsedRealtime() - startedAt > 2500L) {
                        pill("vpn", "VPN on", R.drawable.ic_shield_check, "", KEY_VPN)
                    }
                }

                override fun onLost(network: Network) {
                    if (vpnNets.remove(network) && vpnNets.isEmpty()) {
                        pill("vpn", "VPN off", R.drawable.ic_shield_check, "", KEY_VPN)
                    }
                }
            }
            cm.registerNetworkCallback(req, cb)
            vpnCb = cb
        } catch (e: Exception) {
            Log.w(TAG, "vpn callback failed", e)
        }
    }

    // ------------------------------------------------------------ pill output

    private fun pill(key: String, title: String, icon: Int, chip: String, pref: String, defaultOn: Boolean = true) {
        if (!started || !Prefs.live(ctx) || screenOff()) return
        if (!pref(pref, defaultOn)) return
        val k = "pill:$key"
        // ongoing: the bean's own 4 s transient timer must not cut the 6 s pill short
        LiveBus.put(
            LiveActivity(
                k, LiveActivity.Kind.PROGRESS, title, -1f, 0L, false,
                icon, true, null, null, null, chip = chip
            )
        )
        pillRemovers.remove(k)?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pillRemovers.remove(k)
            LiveBus.remove(k)
        }
        pillRemovers[k] = r
        main.postDelayed(r, PILL_MS)
    }
}
