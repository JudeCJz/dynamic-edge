package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Catalog of the phone's own quick settings tiles that Dynamic Edge does not implement natively.
 *
 * Ids: "sys:<spec>" for SystemUI tile specs (Settings.Secure "sysui_qs_tiles", the SystemUI stock list and specs
 * the QS driver has seen in the live panel), and "cus:<pkg>/<TileService class>" for third-party TileServices.
 * [specOf] maps any tile id to the spec SystemUI uses for it; on Android 16+ panels that spec is the start of the
 * tile container's content description ("wifi, 2x1, row 1 column 1"), which [QsDriver] matches exactly. The label
 * keys are for older panels (vivo): English guesses searched in the tile labels.
 */
internal object QsCatalog {
    const val SYS = "sys:"
    const val CUS = "cus:"

    class Entry(
        val id: String,
        val label: String,
        val keys: Array<String>,
        val comp: ComponentName?,
        /** Service icon is a dedicated (monochrome) tile glyph: tint it. False = app icon, draw as is. */
        val tinted: Boolean,
        /** Lower-case fragments looked for inside the tile's view resource id. */
        val tokens: Array<String> = emptyArray(),
        /** Spec names this tile can have in SystemUI (first = canonical); empty = none. */
        val specs: Array<String> = emptyArray()
    )

    private class Known(val label: String, val icon: Int, vararg val keys: String)

    private val KNOWN: Map<String, Known> = mapOf(
        "cell" to Known("Mobile data", R.drawable.ic_cell, "mobile data", "cellular", "mobile network", "no sim", "sim", "data"),
        "airplane" to Known("Airplane", R.drawable.ic_plane, "airplane mode", "aeroplane mode", "airplane", "flight", "aeroplane"),
        "hotspot" to Known("Hotspot", R.drawable.ic_hotspot, "hotspot", "tethering"),
        "location" to Known("Location", R.drawable.ic_map_pin, "location", "gps"),
        "night" to Known("Night", R.drawable.ic_moon_star, "eye protection", "night light", "night shield", "night"),
        "dark" to Known("Dark", R.drawable.ic_contrast, "dark theme", "dark mode", "dark"),
        "ringer" to Known("Ringer", R.drawable.ic_bell, "ringer", "sound", "ring"),
        "silent_mode" to Known("Silent", R.drawable.ic_volume_off, "silent", "mute"),
        "screen_record" to Known("Record", R.drawable.ic_screen_record, "screen record", "record"),
        "screenrecord" to Known("Record", R.drawable.ic_screen_record, "screen record", "record"),
        "calculator" to Known("Calc", R.drawable.ic_calculator, "calculator"),
        "split_screen" to Known("Split", R.drawable.ic_columns, "split"),
        "scanner" to Known("Scan", R.drawable.ic_scan, "scan"),
        "qr_code_scanner" to Known("QR scan", R.drawable.ic_qr, "qr", "scan"),
        "game_mode" to Known("Game", R.drawable.ic_gamepad, "game"),
        "monster_mode" to Known("Monster", R.drawable.ic_gamepad, "monster"),
        "upnp_server" to Known("Server", R.drawable.ic_share, "upnp", "dlna", "media server"),
        "vivo_share" to Known("Share", R.drawable.ic_share, "share"),
        "controls" to Known("Controls", R.drawable.ic_controls, "device controls", "home controls", "controls"),
        "smart_short" to Known("Shortcut", R.drawable.ic_toggle, "shortcut", "smart"),
        "inversion" to Known("Invert", R.drawable.ic_contrast, "invert", "inversion"),
        "color_correction" to Known("Colour", R.drawable.ic_palette, "colour correction", "color correction"),
        "reduce_brightness" to Known("Dim", R.drawable.ic_sun_dim, "extra dim", "reduce brightness", "dim"),
        "mictoggle" to Known("Mic", R.drawable.ic_mic, "microphone", "mic"),
        "cameratoggle" to Known("Camera", R.drawable.ic_camera, "camera"),
        "dataSaver" to Known("Data saver", R.drawable.ic_data_saver, "data saver"),
        "saver" to Known("Data saver", R.drawable.ic_data_saver, "data saver"),
        "nfc" to Known("NFC", R.drawable.ic_nfc, "nfc"),
        "cast" to Known("Cast", R.drawable.ic_cast, "cast", "screen cast"),
        "dream" to Known("Dream", R.drawable.ic_moon, "screen saver", "dream"),
        "work" to Known("Work", R.drawable.ic_layers, "work"),
        "glyphs" to Known("Glyphs", R.drawable.ic_glyphs, "glyphs", "glyph"),
        "glyphs_torch" to Known("Glyph torch", R.drawable.ic_glyphs, "glyph torch"),
        "alarm" to Known("Alarm", R.drawable.ic_alarm, "alarm"),
        "wallet" to Known("Wallet", R.drawable.ic_tile, "wallet"),
        "battery" to Known("Saver", R.drawable.ic_battery_saver, "battery saver", "power saving", "battery"),
        "bt" to Known("Bluetooth", R.drawable.ic_bluetooth, "bluetooth"),
        "flashlight" to Known("Torch", R.drawable.ic_flashlight, "flashlight", "torch"),
        "rotation" to Known("Rotate", R.drawable.ic_rotate, "auto-rotate", "auto rotate", "rotate"),
        "dnd" to Known("DND", R.drawable.ic_moon, "do not disturb", "dnd")
    )

    /** Native tiles that can also be clicked through the QS panel (fallback when the direct API did not take). */
    private val ROUTED: Map<String, Entry> = mapOf(
        "wifi" to Entry("wifi", "Wi-Fi", arrayOf("wi-fi", "wifi", "wlan", "internet"), null, false,
            arrayOf("wifi", "internet"), arrayOf("wifi", "internet")),
        "battery_saver" to Entry("battery_saver", "Saver", arrayOf("battery saver", "power saving", "power saver"), null, false,
            arrayOf("battery", "saver"), arrayOf("battery", "battery_saver")),
        "bluetooth" to Entry("bluetooth", "Bluetooth", arrayOf("bluetooth"), null, false, arrayOf("bluetooth"), arrayOf("bt")),
        "torch" to Entry("torch", "Torch", arrayOf("flashlight", "torch"), null, false, arrayOf("flashlight", "torch"), arrayOf("flashlight")),
        "dnd" to Entry("dnd", "DND", arrayOf("do not disturb", "dnd"), null, false, arrayOf("dnd"), arrayOf("dnd")),
        "rotate" to Entry("rotate", "Rotate", arrayOf("auto-rotate", "auto rotate", "rotate"), null, false, arrayOf("rotat"), arrayOf("rotation"))
    )

    private val TOKENS = mapOf(
        "cell" to arrayOf("cell", "mobile", "sim"),
        "airplane" to arrayOf("airplane", "flight"),
        "hotspot" to arrayOf("hotspot", "tether"),
        "location" to arrayOf("location", "gps"),
        "nfc" to arrayOf("nfc"),
        "night" to arrayOf("night", "eye"),
        "dark" to arrayOf("dark")
    )

    /** SystemUI specs that map to a tile we implement natively (never listed as "sys:"). */
    private val NATIVE_SPECS = setOf("wifi", "internet", "bt", "flashlight", "dnd", "rotation", "vibrate_mode", "battery")

    /** Specs that perform an action or open a dialog / app instead of flipping an on / off state. */
    private val ACTION_SPECS = setOf(
        "screenrecord", "screen_record", "qr_code_scanner", "cast", "controls", "calculator", "scanner",
        "split_screen", "wallet", "alarm", "smart_short", "upnp_server", "vivo_share", "dream", "font_scaling",
        "record_issue", "hearing_devices", "notes", "screenshot"
    )

    private var sys: List<Entry>? = null
    private var cus: List<Entry>? = null

    fun isQs(id: String): Boolean = id.startsWith(SYS) || id.startsWith(CUS)
    fun isCustom(id: String): Boolean = id.startsWith(CUS)

    /** The SystemUI spec for a tile id: "sys:x" -> x, "cus:pkg/cls" -> custom(pkg/cls), native ids by table, else null. */
    fun specOf(id: String): String? {
        if (id.startsWith(SYS)) return id.substring(SYS.length)
        if (id.startsWith(CUS)) {
            val cn = ComponentName.unflattenFromString(id.substring(CUS.length)) ?: return null
            return "custom(" + cn.flattenToShortString() + ")"
        }
        return ROUTED[id]?.specs?.firstOrNull()
    }

    /** True for tiles that do something (dialog, app, one-shot) rather than toggle a state. */
    fun isAction(id: String): Boolean = id.startsWith(SYS) && id.substring(SYS.length) in ACTION_SPECS

    fun iconRes(id: String): Int {
        if (!id.startsWith(SYS)) return 0
        return KNOWN[id.substring(SYS.length)]?.icon ?: R.drawable.ic_tile
    }

    /** Short label for an id (no context needed for system tiles; custom ones need [entry]). */
    fun label(id: String): String {
        if (id.startsWith(SYS)) return sysLabel(id.substring(SYS.length))
        val cn = ComponentName.unflattenFromString(id.removePrefix(CUS))
        return cn?.shortClassName?.substringAfterLast('.')?.removeSuffix("TileService")?.removeSuffix("Tile") ?: id
    }

    private fun sysLabel(spec: String): String {
        KNOWN[spec]?.let { return it.label }
        seenLabels[spec]?.let { if (it.isNotBlank()) return it }
        return humanise(spec)
    }

    /** "reduce_brightness" -> "Reduce brightness", "dataSaver" -> "Data saver". */
    private fun humanise(spec: String): String {
        val sb = StringBuilder()
        for ((i, ch) in spec.withIndex()) {
            when {
                ch == '_' || ch == '-' -> sb.append(' ')
                ch.isUpperCase() && i > 0 -> sb.append(' ').append(ch.lowercaseChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim().replaceFirstChar { it.uppercase() }
    }

    /** All specs of Settings.Secure sysui_qs_tiles in the phone's order (native ones included). */
    fun allSpecs(ctx: Context): List<String> {
        val raw = try {
            Settings.Secure.getString(ctx.contentResolver, "sysui_qs_tiles")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return splitSpecs(raw)
    }

    /** SystemUI's stock tile list (config string quick_settings_tiles_stock), or empty. */
    private fun stockSpecs(ctx: Context): List<String> = try {
        val res = ctx.packageManager.getResourcesForApplication("com.android.systemui")
        val rid = res.getIdentifier("quick_settings_tiles_stock", "string", "com.android.systemui")
        if (rid == 0) emptyList() else splitSpecs(res.getString(rid))
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * System tiles of this phone that are not native: the user's tile list first (in the phone's order), then
     * specs seen in the live panel, then SystemUI's stock tiles.
     */
    fun systems(ctx: Context, force: Boolean = false): List<Entry> {
        sys?.let { if (!force) return it }
        loadSeen(ctx)
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        val all = ArrayList<String>()
        all.addAll(allSpecs(ctx))
        all.addAll(seenLabels.keys)
        all.addAll(stockSpecs(ctx))
        for (spec in all) {
            if (spec.isEmpty() || spec in NATIVE_SPECS || spec.startsWith("custom(") || !seen.add(spec)) continue
            out.add(sysEntry(spec))
        }
        sys = out
        return out
    }

    /** Installed third-party TileServices (label from the service, then the app). */
    fun customs(ctx: Context, force: Boolean = false): List<Entry> {
        cus?.let { if (!force) return it }
        val out = ArrayList<Entry>()
        try {
            val pm = ctx.packageManager
            val list = pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE), PackageManager.GET_META_DATA)
            for (ri in list) {
                val si = ri.serviceInfo ?: continue
                if (si.packageName == ctx.packageName) continue
                if (si.permission != "android.permission.BIND_QUICK_SETTINGS_TILE") continue
                out.add(customEntry(pm, si))
            }
            out.sortBy { it.label.lowercase() }
        } catch (e: Exception) {
        }
        cus = out
        return out
    }

    /** Entry for any id (also ids no longer on the phone), or null for a native id without a QS route / malformed id. */
    fun entry(ctx: Context, id: String): Entry? {
        if (id.startsWith(SYS)) return sysEntry(id.substring(SYS.length))
        ROUTED[id]?.let { return it }
        if (!id.startsWith(CUS)) return null
        customs(ctx).firstOrNull { it.id == id }?.let { return it }
        val cn = ComponentName.unflattenFromString(id.substring(CUS.length)) ?: return null
        return try {
            val pm = ctx.packageManager
            customEntry(pm, pm.getServiceInfo(cn, 0))
        } catch (e: Exception) {
            Entry(id, label(id), emptyArray(), cn, false, emptyArray(), arrayOf("custom(" + cn.flattenToShortString() + ")"))
        }
    }

    private fun sysEntry(spec: String): Entry {
        val k = KNOWN[spec]
        val keys = ArrayList<String>()
        if (k != null) keys.addAll(k.keys)
        seenLabels[spec]?.let { if (it.isNotBlank()) keys.add(it.lowercase()) }
        keys.add(spec.replace('_', ' ').lowercase())
        keys.add(spec.lowercase())
        return Entry(SYS + spec, sysLabel(spec), keys.distinct().toTypedArray(), null, false,
            TOKENS[spec] ?: arrayOf(spec.lowercase()), arrayOf(spec))
    }

    private fun customEntry(pm: PackageManager, si: ServiceInfo): Entry {
        val cn = ComponentName(si.packageName, si.name)
        val svc = try {
            si.loadLabel(pm).toString()
        } catch (e: Exception) {
            ""
        }
        val app = try {
            si.applicationInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            ""
        }
        val label = svc.ifBlank { app }.ifBlank { si.name.substringAfterLast('.') }
        val keys = ArrayList<String>(2)
        if (svc.isNotBlank()) keys.add(svc.lowercase())
        if (app.isNotBlank() && !keys.contains(app.lowercase())) keys.add(app.lowercase())
        return Entry(CUS + cn.flattenToString(), label, keys.toTypedArray(), cn, si.icon != 0,
            emptyArray(), arrayOf("custom(" + cn.flattenToShortString() + ")"))
    }

    // ---------------------------------------------------------------- specs / labels seen in the live panel

    private const val SEEN_KEY = "qs_seen_specs"
    private val seenLabels = LinkedHashMap<String, String>()
    private var seenLoaded = false

    /** Specs the QS driver has seen in the live panel (custom tiles excluded). */
    fun seenSpecs(ctx: Context): Set<String> {
        loadSeen(ctx)
        return seenLabels.keys
    }

    private fun loadSeen(ctx: Context) {
        if (seenLoaded) return
        seenLoaded = true
        val raw = try { Prefs.prefs(ctx).getString(SEEN_KEY, null) } catch (e: Exception) { null } ?: return
        for (line in raw.split('\n')) {
            val t = line.indexOf('\t')
            if (t <= 0) continue
            seenLabels[line.substring(0, t)] = line.substring(t + 1)
        }
    }

    /**
     * Records the specs and labels the QS driver saw in the panel (Android 16+ content descriptions), so tiles
     * that are not in sysui_qs_tiles (e.g. Nothing's glyphs_torch) become choosable. Saved only when it changed.
     */
    fun remember(ctx: Context, found: Map<String, String>) {
        loadSeen(ctx)
        var changed = false
        for ((spec, label) in found) {
            if (spec.startsWith("custom(") || spec.isEmpty() || spec.contains('\n') || spec.contains('\t')) continue
            val l = label.substringBefore(',').replace('\n', ' ').replace('\t', ' ').trim()
            if (seenLabels[spec] != l) {
                seenLabels[spec] = l
                changed = true
            }
        }
        if (!changed) return
        val sb = StringBuilder()
        for ((k, v) in seenLabels) sb.append(k).append('\t').append(v).append('\n')
        try {
            Prefs.prefs(ctx).edit().putString(SEEN_KEY, sb.toString()).apply()
        } catch (e: Exception) {
        }
        sys = null
    }

    /**
     * The tile glyph as a square bitmap. Tinted glyphs are rendered white (recolour with a colour filter);
     * app icons are rendered as is. Null if the icon cannot be loaded.
     */
    fun bitmap(ctx: Context, e: Entry, px: Int): Bitmap? {
        val cn = e.comp ?: return null
        return try {
            val pm = ctx.packageManager
            val si = pm.getServiceInfo(cn, 0)
            val d: Drawable = si.loadIcon(pm).mutate()
            if (e.tinted) d.setTint(Color.WHITE)
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(b))
            b
        } catch (e2: Exception) {
            null
        }
    }

    /** Drawable for the editor (white when tinted). */
    fun drawable(ctx: Context, e: Entry): Drawable? {
        val cn = e.comp ?: return null
        return try {
            val pm = ctx.packageManager
            val d = pm.getServiceInfo(cn, 0).loadIcon(pm).mutate()
            if (e.tinted) d.setTint(Color.WHITE)
            d
        } catch (e2: Exception) {
            null
        }
    }

    fun splitSpecs(raw: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (ch in raw) {
            when {
                ch == '(' -> { depth++; sb.append(ch) }
                ch == ')' -> { if (depth > 0) depth--; sb.append(ch) }
                ch == ',' && depth == 0 -> {
                    val t = sb.toString().trim()
                    if (t.isNotEmpty()) out.add(t)
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        val t = sb.toString().trim()
        if (t.isNotEmpty()) out.add(t)
        return out
    }
}
