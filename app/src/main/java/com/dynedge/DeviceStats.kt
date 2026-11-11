package com.dynedge

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs

/** Battery, RAM and storage numbers. Receiver and 2 s timer exist only between start() and stop(). */
class DeviceStats(ctx: Context, private val onChange: () -> Unit) {

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val mem = ActivityManager.MemoryInfo()
    private var started = false

    var batteryPct = 0; private set
    var charging = false; private set
    var tempC = 0f; private set
    var ramUsedFrac = 0f; private set
    var ramTotalGb = 0f; private set
    var storageUsedFrac = 0f; private set
    var storageTotalGb = 0f; private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && readBattery(intent)) onChange()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            if (readRam()) onChange()
            main.postDelayed(this, 2000L)
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            val sticky = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) readBattery(sticky)
        } catch (e: Exception) {
        }
        readRam()
        readStorage()
        main.postDelayed(tick, 2000L)
        onChange()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(tick)
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
    }

    private fun readBattery(i: Intent): Boolean {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100f / scale).toInt().coerceIn(0, 100) else batteryPct
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val changed = pct != batteryPct || ch != charging || t != tempC
        batteryPct = pct; charging = ch; tempC = t
        return changed
    }

    private fun readRam(): Boolean {
        val m = am ?: return false
        return try {
            m.getMemoryInfo(mem)
            val total = mem.totalMem.coerceAtLeast(1L)
            val used = ((total - mem.availMem).toFloat() / total).coerceIn(0f, 1f)
            val gb = total / 1073741824f
            val changed = Math.abs(used - ramUsedFrac) > 0.004f || gb != ramTotalGb
            ramUsedFrac = used; ramTotalGb = gb
            changed
        } catch (e: Exception) {
            false
        }
    }

    private fun readStorage() {
        try {
            val s = StatFs(app.dataDir.path)
            val total = s.totalBytes.coerceAtLeast(1L)
            storageUsedFrac = ((total - s.availableBytes).toFloat() / total).coerceIn(0f, 1f)
            storageTotalGb = total / 1073741824f
        } catch (e: Exception) {
        }
    }
}
