package com.dynedge

import android.app.Activity
import android.graphics.Color
import android.os.Bundle

/**
 * Settings app host. The whole screen is one [Studio] page (Colour, Shape, Windows, Music, Motion; gear =
 * SettingsActivity). Framework Views only, built in code.
 */
class MainActivity : Activity() {

    private var hookMode = false
    private var studio: Studio? = null

    private fun edit() = Prefs.prefs(this).edit()

    /**
     * adb test hook, no UI: am start -n com.dynedge/.MainActivity --es set_prefs "flare=12.0;fill=grooves;rim=false"
     * [--es preview idle|open|none]. Types: true/false Boolean, integer Int, number with a dot Float, "#RRGGBB"
     * or 0xAARRGGBB Int (colour), else String; idle_dp / open_dp / flare are always stored as Float.
     */
    private fun runHook(): Boolean {
        val sp = intent.getStringExtra("set_prefs")
        val pv = intent.getStringExtra("preview")
        if (intent.getBooleanExtra("dump_prefs", false)) {
            // adb test hook: logcat -s DynEdgePrefs prints every pref as key=value (restorable through set_prefs)
            for ((k, v) in Prefs.prefs(this).all) android.util.Log.i("DynEdgePrefs", "$k=$v")
            return true
        }
        // adb test hook: --es live_test <id> toggles one Live test effect (LiveTest ids), "stop" ends them all
        intent.getStringExtra("live_test")?.let { id ->
            if (id == "stop") LiveTest.stopAll() else LiveTest.toggle(this, id)
            return true
        }
        if (sp == null && pv == null) return false
        val e = edit()
        if (sp != null) {
            for (part in sp.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim()
                when {
                    k == Prefs.KEY_IDLE_DP || k == Prefs.KEY_OPEN_DP || k == Prefs.KEY_FLARE ->
                        e.putFloat(k, v.toFloatOrNull() ?: continue)
                    v == "true" || v == "false" -> e.putBoolean(k, v == "true")
                    v.startsWith("#") -> e.putInt(k, try { Color.parseColor(v) } catch (x: Exception) { continue })
                    v.startsWith("0x") -> e.putInt(k, v.substring(2).toLongOrNull(16)?.toInt() ?: continue)
                    v.toIntOrNull() != null -> e.putInt(k, v.toInt())
                    v.contains('.') && v.toFloatOrNull() != null -> e.putFloat(k, v.toFloat())
                    else -> e.putString(k, v)
                }
            }
        }
        if (pv != null) e.putString(Prefs.KEY_PREVIEW, if (pv == "idle" || pv == "open") pv else Prefs.PREVIEW_NONE)
        e.commit()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runHook()) {
            hookMode = true
            finish()
            return
        }
        // adb hook: am start -n com.dynedge/.MainActivity --ez white_wallpaper true
        if (intent.getBooleanExtra("white_wallpaper", false)) setWhiteWallpaper()
        // adb hook: push an image to Android/data/com.dynedge/files/<name>, then
        // am start -n com.dynedge/.MainActivity --es wallpaper_file <name>
        intent.getStringExtra("wallpaper_file")?.let { name ->
            try {
                val f = java.io.File(getExternalFilesDir(null), name)
                java.io.FileInputStream(f).use {
                    android.app.WallpaperManager.getInstance(this)
                        .setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                }
            } catch (e: Exception) {
                android.util.Log.w("DynamicEdge", "wallpaper_file failed", e)
            }
        }
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        // one-off migration of the old fill semantics, before anything reads them
        Prefs.migrate(this)
        // the look is black and white plus the accent: clear anything an older build had chosen
        Prefs.migrateLook(this)

        val s = Studio(this)
        studio = s
        setContentView(s.root)
        PixelMode.apply(this)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        if (hookMode) return
        studio?.onPause()
        // a slider released off-screen must never leave the preview or the grab square stuck on
        edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).putBoolean(Prefs.KEY_TOUCH_SHOW, false).apply()
    }

    override fun onResume() {
        super.onResume()
        if (hookMode) return
        // our own settings never count as a sensitive app: bring a paused overlay back here
        PauseGate.resumeNow(this)
        // permissions, apps and the service state may have changed while we were away
        studio?.onResume()
    }

    override fun onDestroy() {
        studio?.onDestroy()
        studio = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- adb hooks

    private fun setWhiteWallpaper() {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(108, 240, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            android.app.WallpaperManager.getInstance(this)
                .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.w("DynamicEdge", "wallpaper failed", e)
        }
    }
}
