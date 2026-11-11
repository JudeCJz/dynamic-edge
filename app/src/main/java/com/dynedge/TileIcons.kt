package com.dynedge

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * The phone's real SystemUI quick-tile glyphs. For a tile id (native "wifi", "bluetooth", "torch", "dnd", "rotate",
 * "vibrate", "battery_saver" or "sys:<spec>") an ordered list of drawable names is tried in com.android.systemui
 * (vivo qs_* / vivo_qs_* names first, generic AOSP names after), with the on / off variant for the tile state.
 *
 * Bitmaps are white glyphs on transparent (recolour with a colour filter: white when off, black when on, like the
 * rest of the QUICK window) and are cached per id + state + size. Build them off the draw path ([get] does a
 * resource lookup on a cache miss only).
 */
object TileIcons {

    private const val SYSUI = "com.android.systemui"

    private val cache = HashMap<String, Bitmap?>()
    private var sysRes: Resources? = null
    private var sysResTried = false

    /**
     * White glyph for the tile in the given state, [px] square. Never null: the phone's SystemUI glyph when one
     * resolves, otherwise our own Lucide drawable.
     */
    fun get(ctx: Context, id: String, on: Boolean, px: Int): Bitmap {
        system(ctx, id, on, px)?.let { return it }
        return lucide(ctx, id, if (TileState.spec(id) == "wifi") wifiLevel(ctx, on) >= WIFI_DISCONNECTED else on, px)
    }

    private const val WIFI_OFF = -1
    private const val WIFI_DISCONNECTED = 0

    /**
     * Wi-Fi glyph state: [WIFI_OFF] when Wi-Fi is disabled, [WIFI_DISCONNECTED] when it is enabled but not
     * connected, else 1..4 (the current signal level). The enabled flag is read from WifiManager directly so a
     * stale tile state can never pick the "disconnected" glyph while Wi-Fi is on.
     */
    @Suppress("DEPRECATION")
    private fun wifiLevel(ctx: Context, tileOn: Boolean): Int {
        val app = ctx.applicationContext ?: ctx
        val st = TileState.read(app, "wifi")
        val enabled = if (st == QsDriver.UNKNOWN) tileOn else st == QsDriver.ON
        if (!enabled) return WIFI_OFF
        return try {
            // "connected" comes from ConnectivityManager: without location permission WifiInfo.networkId is
            // always -1, which made a connected phone show the disconnected glyph. RSSI is not redacted.
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var linked = false
            for (n in cm.allNetworks) {
                val nc = cm.getNetworkCapabilities(n) ?: continue
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { linked = true; break }
            }
            if (!linked) return WIFI_DISCONNECTED
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = wm.connectionInfo?.rssi ?: -127
            if (rssi <= -127) 4 else wm.calculateSignalLevel(rssi).coerceIn(1, 4)
        } catch (e: Throwable) {
            4 // enabled and unreadable: a full-signal glyph
        }
    }

    /** Only the real SystemUI glyph, or null when nothing resolves (callers then draw their Lucide icon). */
    fun system(ctx: Context, id: String, on: Boolean, px: Int): Bitmap? {
        if (QsCatalog.isCustom(id)) return null
        val wifi = TileState.spec(id) == "wifi"
        val level = if (wifi) wifiLevel(ctx, on) else 0
        val key = if (wifi) "$id|w$level|$px" else "$id|$on|$px"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val b = try {
            resolve(ctx.applicationContext ?: ctx, id, on, px, level)
        } catch (e: Throwable) {
            null
        }
        synchronized(cache) { cache[key] = b }
        return b
    }

    /** True when this id has any chance of a SystemUI glyph. */
    fun supported(id: String): Boolean = !QsCatalog.isCustom(id) && names(id, true).isNotEmpty()

    private fun lucide(ctx: Context, id: String, on: Boolean, px: Int): Bitmap {
        val res = when (id) {
            "wifi" -> if (on) R.drawable.ic_wifi else R.drawable.ic_wifi_off
            "bluetooth" -> if (on) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
            "torch" -> if (on) R.drawable.ic_flashlight else R.drawable.ic_flashlight_off
            "rotate" -> if (on) R.drawable.ic_rotate else R.drawable.ic_rotate_lock
            "vibrate" -> if (on) R.drawable.ic_vibrate else R.drawable.ic_bell
            else -> Tiles.staticIcon(id)
        }
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        try {
            val d = ctx.getDrawable(res)?.mutate()
            if (d != null) {
                d.setTint(Color.WHITE)
                d.setBounds(0, 0, px, px)
                d.draw(Canvas(b))
            }
        } catch (e: Exception) {
        }
        return b
    }

    private fun resources(ctx: Context): Resources? {
        if (!sysResTried) {
            sysResTried = true
            sysRes = try {
                ctx.packageManager.getResourcesForApplication(SYSUI)
            } catch (e: Exception) {
                null
            }
        }
        return sysRes
    }

    private fun resolve(ctx: Context, id: String, on: Boolean, px: Int, wifiLevel: Int): Bitmap? {
        val res = resources(ctx) ?: return null
        val list = if (TileState.spec(id) == "wifi") wifiNames(wifiLevel) else names(id, on)
        for (name in list) {
            val rid = res.getIdentifier(name, "drawable", SYSUI)
            if (rid == 0) continue
            val b = render(ctx, res, rid, px) ?: continue
            return b
        }
        return null
    }

    /** White glyph, or null when the drawable cannot be drawn or is a filled disc / background (not a glyph). */
    private fun render(ctx: Context, res: Resources, rid: Int, px: Int): Bitmap? {
        val d: Drawable = try {
            res.getDrawable(rid, ctx.theme)
        } catch (e: Exception) {
            try {
                res.getDrawable(rid, null)
            } catch (e2: Exception) {
                return null
            }
        } ?: return null
        val m = d.constantState?.newDrawable(res)?.mutate() ?: d.mutate()
        m.setTint(Color.WHITE)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        m.setBounds(0, 0, px, px)
        m.draw(Canvas(b))
        // a glyph is sparse; a mostly opaque square / disc is a tile background
        var opaque = 0
        val step = (px / 12).coerceAtLeast(1)
        var total = 0
        var y = step / 2
        while (y < px) {
            var x = step / 2
            while (x < px) {
                total++
                if ((b.getPixel(x, y) ushr 24) > 128) opaque++
                x += step
            }
            y += step
        }
        if (total == 0 || opaque == 0 || opaque * 100 > total * 72) return null
        return b
    }

    /** Wi-Fi candidates: off glyph, the disconnected glyph (enabled, no link) or the connected signal glyph. */
    private fun wifiNames(level: Int): List<String> = when {
        level == WIFI_OFF -> listOf("vivo_qs_wifi_off", "qs_wifi_icon_off", "ic_signal_wifi_off", "ic_qs_wifi_off")
        level == WIFI_DISCONNECTED -> listOf("ic_qs_wifi_disconnected", "vivo_qs_wifi_disconnected", "qs_wifi_icon_off")
        else -> listOf(
            "vivo_qs_wifi_signal_$level", "vivo_qs_wifi_$level", "vivo_ic_qs_wifi_$level", "vivo_qs_wifi_signal_4", "ic_qs_wifi_4",
            "ic_qs_wifi_full_4", "ic_wifi_signal_4", "ic_signal_wifi_4_bar"
        )
    }

    /** Candidate drawable names, best first, for the tile in this state. Empty = no SystemUI glyph expected. */
    private fun names(id: String, on: Boolean): List<String> {
        val s = if (on) "on" else "off"
        val spec = if (id.startsWith(QsCatalog.SYS)) id.substring(QsCatalog.SYS.length) else id
        val out = ArrayList<String>(12)
        when (spec) {
            "wifi" -> out.addAll(wifiNames(if (on) 4 else WIFI_OFF))
            "bluetooth", "bt" -> out.addAll(listOf(
                "qs_bluetooth_icon_$s", "vivo_qs_bluetooth_$s", "ic_qs_bluetooth_$s"
            ) + (if (on) listOf("ic_qs_bluetooth_connected") else emptyList()) + listOf(
                "ic_qs_bluetooth", "ic_bluetooth_$s", "vivo_qs_bluetooth"
            ))
            "torch", "flashlight" -> out.addAll(listOf(
                "qs_flashlight_icon_$s", "vivo_qs_flashlight_standard_$s", "vivo_qs_flashlight_highlight_$s",
                "ic_qs_flashlight_$s", "ic_qs_flashlight", "ic_flashlight_$s", "vivo_qs_flashlight"
            ))
            "dnd" -> out.addAll(listOf(
                "qs_dnd_icon_$s", "vivo_qs_dnd_$s", "ic_qs_dnd_$s", "ic_qs_dnd", "ic_dnd", "vivo_qs_dnd"
            ))
            "rotate", "rotation" -> out.addAll(listOf(
                "qs_auto_rotate_icon_$s", "vivo_qs_auto_rotate_$s", "ic_qs_auto_rotate_$s", "ic_qs_auto_rotate",
                "ic_screen_rotation_$s", "vivo_qs_auto_rotate"
            ))
            "vibrate", "vibrate_mode" -> out.addAll(listOf(
                "vivo_qs_vibrate_$s", "qs_vibrate_icon_$s", "ic_qs_vibrate_$s", "ic_qs_ringer_vibrate",
                "ic_volume_ringer_vibrate", "vivo_qs_vibrate"
            ))
            "battery_saver", "battery" -> out.addAll(listOf(
                "qs_battery_saver_icon_$s", "vivo_qs_battery_saver_$s", "ic_qs_battery_saver_$s", "ic_qs_battery_saver",
                "vivo_qs_battery_saver"
            ))
            "cell" -> out.addAll(listOf(
                "qs_ic_signal_cellular", "ic_qs_signal_cellular", "ic_cellular", "ic_signal_cellular_alt",
                "ic_signal_cellular_4_bar", "ic_qs_mobile_data", "ic_mobile_data", "vivo_qs_cell_data_$s"
            ))
            "airplane" -> out.addAll(listOf(
                "qs_airplane_icon_$s", "vivo_qs_airplane_$s", "ic_qs_airplane_$s", "ic_qs_airplane",
                "ic_signal_airplane", "ic_airplanemode_$s", "vivo_qs_airplane"
            ))
            "hotspot" -> out.addAll(listOf(
                "qs_hotspot_icon_$s", "vivo_qs_hotspot_$s", "ic_qs_hotspot_$s", "ic_qs_hotspot", "ic_hotspot",
                "ic_wifi_tethering", "ic_wifi_tethering_$s", "vivo_qs_hotspot"
            ))
            "location" -> out.addAll(listOf(
                "qs_location_icon_$s", "vivo_qs_location_$s", "ic_qs_location_$s", "ic_qs_location", "ic_location",
                "ic_location_$s", "vivo_qs_location"
            ))
            "nfc" -> out.addAll(listOf(
                "vivo_qs_nfc_$s", "qs_nfc_icon_$s", "ic_qs_nfc_$s", "ic_qs_nfc", "ic_nfc", "vivo_qs_nfc"
            ))
            "dark" -> out.addAll(listOf(
                "qs_light_dark_theme_icon_$s", "vivo_qs_dark_$s", "ic_qs_ui_mode_night", "ic_qs_dark_theme"
            ))
            "night" -> out.addAll(listOf(
                "qs_nightlight_icon_$s", "vivo_qs_night_display_$s", "vivo_qs_night_mode_$s", "vivo_qs_moonlight_$s",
                "ic_qs_night_display_$s", "ic_qs_night_display"
            ))
            "silent_mode" -> out.addAll(listOf("vivo_qs_silent_mode_$s", "ic_qs_silent_mode_$s"))
            "screen_record" -> out.addAll(listOf(
                "qs_screen_record_icon_$s", "vivo_qs_screen_record_$s", "vivo_qs_screen_record", "ic_screenrecord"
            ))
            "dataSaver", "saver" -> out.addAll(listOf("qs_data_saver_icon_$s", "vivo_qs_data_saver_$s", "ic_data_saver_$s"))
            "inversion" -> out.addAll(listOf("qs_invert_colors_icon_$s", "vivo_qs_invert_colors_$s", "ic_invert_colors_$s"))
            "reduce_brightness" -> out.addAll(listOf("qs_extra_dim_icon_$s", "ic_qs_extra_dim_$s"))
            "mictoggle" -> out.addAll(listOf("qs_mic_access_$s", "ic_mic_access_$s"))
            "cameratoggle" -> out.addAll(listOf("qs_camera_access_icon_$s", "ic_camera_access_$s"))
            "cast" -> out.addAll(listOf("vivo_qs_cast_$s", "ic_qs_cast_$s", "ic_cast", "vivo_qs_cast"))
            "dream" -> out.addAll(listOf("ic_qs_screen_saver", "ic_qs_dream"))
            "game_mode", "monster_mode" -> out.addAll(listOf("vivo_qs_monster_$s"))
            "split_screen" -> out.addAll(listOf("vivo_qs_miniscreen_$s"))
            "upnp_server" -> out.addAll(listOf("vivo_qs_upnpserver"))
            "vivo_share" -> out.addAll(listOf("vivo_qs_vivoshare_$s", "vivo_qs_vivoshare"))
            "calculator" -> out.addAll(listOf("vivo_qs_calculator"))
            "smart_short" -> out.addAll(listOf("vivo_qs_smartshort"))
            "ringer", "caffeine", "assist", "screenshot", "lock", "notifications", "quick_settings", "recents",
            "power_menu", "clipboard" -> return out
            else -> {
                if (id.startsWith(QsCatalog.SYS)) {
                    out.addAll(listOf("qs_${spec}_icon_$s", "vivo_qs_${spec}_$s", "vivo_qs_$spec", "ic_qs_${spec}_$s", "ic_qs_$spec"))
                }
            }
        }
        return out
    }
}
