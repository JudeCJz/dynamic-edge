package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Shortcut tiles: user-made Quick tiles that open one target (a Settings page, an app, an app's info page).
 *
 * A tile's layout id is `sc:<uuid>` ([QuickConfig] only stores that id). Its data lives in Prefs under [KEY] as
 * `{"v":1,"items":[{"id","kind","action","pkg","cls","hl","icon","app","label","target"}, ...]}`:
 * - kind: [KIND_SETTINGS] (action + optional pkg/cls component + optional hl highlight key), [KIND_APP] (pkg + cls
 *   launcher activity) or [KIND_INFO] (pkg, opens the app's details screen).
 * - icon: a name from [ShortcutIcons]; app: true = draw the target app's own icon instead (kinds app / appinfo).
 * - label: shown in the editor; target: the human name of the destination (shown when editing).
 * Removing the tile in the editor deletes its item here ([delete]).
 */
internal object ShortcutStore {
    const val KEY = "quick_shortcuts"
    const val PREFIX = "sc:"
    const val KIND_SETTINGS = "settings"
    const val KIND_APP = "app"
    const val KIND_INFO = "appinfo"

    /** AOSP Settings scrolls to and highlights this preference (key in both extras). */
    private const val HL_KEY = ":settings:fragment_args_key"
    private const val HL_ARGS = ":settings:show_fragment_args"

    data class Shortcut(
        val id: String,
        val kind: String,
        val action: String?,
        val pkg: String?,
        val cls: String?,
        val hl: String?,
        val icon: String,
        val appIcon: Boolean,
        val label: String,
        val target: String
    ) {
        /** True when the target belongs to an app (its own icon can be used). */
        val hasApp: Boolean get() = kind != KIND_SETTINGS && !pkg.isNullOrEmpty()
    }

    fun isId(id: String): Boolean = id.startsWith(PREFIX)

    fun newId(): String = PREFIX + UUID.randomUUID().toString()

    // ---------------------------------------------------------------- storage

    @Volatile private var cacheRaw: String? = null
    @Volatile private var cache: LinkedHashMap<String, Shortcut> = LinkedHashMap()

    private fun read(ctx: Context): LinkedHashMap<String, Shortcut> {
        val raw = try { Prefs.prefs(ctx).getString(KEY, null) } catch (e: Exception) { null }
        if (raw == cacheRaw) return cache
        val out = LinkedHashMap<String, Shortcut>()
        if (raw != null) {
            try {
                val a = JSONObject(raw).optJSONArray("items")
                if (a != null) for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (!isId(id)) continue
                    out[id] = Shortcut(
                        id, o.optString("kind", KIND_SETTINGS),
                        o.optString("action", "").ifEmpty { null }, o.optString("pkg", "").ifEmpty { null },
                        o.optString("cls", "").ifEmpty { null }, o.optString("hl", "").ifEmpty { null },
                        o.optString("icon", "settings"), o.optBoolean("app", false),
                        o.optString("label", ""), o.optString("target", "")
                    )
                }
            } catch (e: Exception) {
            }
        }
        cacheRaw = raw
        cache = out
        return out
    }

    private fun write(ctx: Context, m: Map<String, Shortcut>) {
        val a = JSONArray()
        for (s in m.values) {
            a.put(JSONObject().apply {
                put("id", s.id)
                put("kind", s.kind)
                s.action?.let { put("action", it) }
                s.pkg?.let { put("pkg", it) }
                s.cls?.let { put("cls", it) }
                s.hl?.let { put("hl", it) }
                put("icon", s.icon)
                put("app", s.appIcon)
                put("label", s.label)
                put("target", s.target)
            })
        }
        val raw = JSONObject().put("v", 1).put("items", a).toString()
        Prefs.prefs(ctx).edit().putString(KEY, raw).apply()
    }

    fun all(ctx: Context): List<Shortcut> = read(ctx).values.toList()

    fun get(ctx: Context, id: String): Shortcut? = read(ctx)[id]

    /** Saves (adds or replaces) one shortcut. */
    fun put(ctx: Context, s: Shortcut) {
        val m = LinkedHashMap(read(ctx))
        m[s.id] = s
        write(ctx, m)
    }

    /** Deletes one shortcut; returns it (the editor keeps it so Undo can bring it back). */
    fun delete(ctx: Context, id: String): Shortcut? {
        val m = LinkedHashMap(read(ctx))
        val old = m.remove(id) ?: return null
        write(ctx, m)
        return old
    }

    // ---------------------------------------------------------------- look

    fun label(ctx: Context, id: String): String = get(ctx, id)?.label?.ifEmpty { null } ?: "Shortcut"

    /** The Lucide icon of the tile ([R.drawable.ic_settings] when unknown). */
    fun iconRes(ctx: Context, id: String): Int = ShortcutIcons.res(get(ctx, id)?.icon)

    /** The target app's own icon when the tile uses it, else null. Builds a bitmap: call off the draw path. */
    fun appBitmap(ctx: Context, id: String, px: Int): Bitmap? {
        val s = get(ctx, id) ?: return null
        if (!s.appIcon || !s.hasApp) return null
        return appIcon(ctx, s, px)
    }

    fun appIcon(ctx: Context, s: Shortcut, px: Int): Bitmap? {
        val pkg = s.pkg ?: return null
        val cls = s.cls
        return try {
            if (s.kind == KIND_APP && cls != null) IconTheme.icon(ctx, ComponentName(pkg, cls), px)
            else IconTheme.icon(ctx, pkg, px)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- launch

    /** The intent for a shortcut (without the new-task flag), or null when it is incomplete. */
    fun intent(s: Shortcut): Intent? {
        when (s.kind) {
            KIND_APP -> {
                val pkg = s.pkg ?: return null
                val cls = s.cls ?: return null
                return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
            }
            KIND_INFO -> {
                val pkg = s.pkg ?: return null
                return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            }
            else -> {
                val action = s.action ?: return null
                val i = ShortcutCatalog.build(action, null)
                val pkg = s.pkg
                val cls = s.cls
                if (pkg != null && cls != null) i.component = ComponentName(pkg, cls)
                val hl = s.hl
                if (hl != null) {
                    i.putExtra(HL_KEY, hl)
                    i.putExtra(HL_ARGS, Bundle().apply { putString(HL_KEY, hl) })
                }
                return i
            }
        }
    }

    /** Opens the target. True when an Activity was launched (the caller collapses the panel). */
    fun launch(ctx: Context, id: String): Boolean {
        val s = get(ctx, id) ?: return false
        val i = intent(s) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Throwable) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Throwable) {
                false
            }
        }
    }

    /** Long-press target: the picker in edit mode. */
    fun edit(ctx: Context, id: String): Boolean = try {
        ctx.startActivity(ShortcutPickerActivity.intent(ctx, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Throwable) {
        false
    }
}

/** The icons a shortcut tile can use (Lucide stroke vectors), in the picker's grid order. Names are stored in prefs. */
internal object ShortcutIcons {
    val ALL: List<Pair<String, Int>> = listOf(
        "settings" to R.drawable.ic_settings, "cpu" to R.drawable.ic_cpu, "smartphone" to R.drawable.ic_smartphone,
        "code" to R.drawable.ic_code, "wrench" to R.drawable.ic_wrench, "wifi" to R.drawable.ic_wifi,
        "bluetooth" to R.drawable.ic_bluetooth, "battery" to R.drawable.ic_battery, "sun" to R.drawable.ic_sun,
        "moon" to R.drawable.ic_moon, "bell" to R.drawable.ic_bell, "lock" to R.drawable.ic_lock,
        "shield" to R.drawable.ic_shield, "globe" to R.drawable.ic_globe, "map-pin" to R.drawable.ic_map_pin,
        "hard-drive" to R.drawable.ic_hard_drive, "terminal" to R.drawable.ic_terminal, "bug" to R.drawable.ic_bug,
        "info" to R.drawable.ic_info, "hash" to R.drawable.ic_hash, "key" to R.drawable.ic_key,
        "user" to R.drawable.ic_user, "camera" to R.drawable.ic_camera, "music" to R.drawable.ic_music,
        "play" to R.drawable.ic_play, "download" to R.drawable.ic_download, "upload" to R.drawable.ic_upload,
        "folder" to R.drawable.ic_folder, "file" to R.drawable.ic_file, "star" to R.drawable.ic_star,
        "heart" to R.drawable.ic_heart, "zap" to R.drawable.ic_zap, "home" to R.drawable.ic_home,
        "search" to R.drawable.ic_search, "link" to R.drawable.ic_link, "clock" to R.drawable.ic_clock,
        "calendar" to R.drawable.ic_calendar, "mail" to R.drawable.ic_mail, "message" to R.drawable.ic_message_square,
        "gamepad" to R.drawable.ic_gamepad
    )

    /** Extra icons used by catalog entries only (not offered in the picker's icon grid, but stored names resolve). */
    private val EXTRA: Map<String, Int> = mapOf(
        "calculator" to R.drawable.ic_sc_calculator, "alarm" to R.drawable.ic_sc_alarm, "timer" to R.drawable.ic_sc_timer,
        "phone" to R.drawable.ic_sc_phone, "video" to R.drawable.ic_sc_video, "image" to R.drawable.ic_sc_image,
        "map" to R.drawable.ic_sc_map, "mic" to R.drawable.ic_sc_mic, "volume" to R.drawable.ic_sc_volume,
        "type" to R.drawable.ic_sc_type, "card" to R.drawable.ic_sc_card, "compass" to R.drawable.ic_sc_compass,
        "plane" to R.drawable.ic_sc_plane, "access" to R.drawable.ic_sc_access, "grid" to R.drawable.ic_sc_grid,
        "rotate" to R.drawable.ic_sc_rotate, "keyboard" to R.drawable.ic_sc_keyboard, "eye" to R.drawable.ic_sc_eye,
        "printer" to R.drawable.ic_sc_printer, "wallet" to R.drawable.ic_sc_wallet, "cast" to R.drawable.ic_sc_cast,
        "contrast" to R.drawable.ic_sc_contrast
    )

    fun res(name: String?): Int = ALL.firstOrNull { it.first == name }?.second ?: EXTRA[name] ?: R.drawable.ic_settings
}
