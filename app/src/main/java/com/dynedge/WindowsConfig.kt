package com.dynedge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven list of panel windows: order and on/off, stored as JSON in Prefs.
 *
 * There are exactly seven windows. The settings screen lists them with a switch each; a row tap opens that
 * window's editor (WindowEditActivity, or QuickEditorActivity for Quick).
 */
object WindowsConfig {
    const val KEY = "windows"

    /** The only windows that exist, in page order. */
    val ALL = listOf("quick", "media", "apps", "clipboard", "timer", "notifications", "device")

    /** Every one of them is on when the user has never chosen otherwise. */
    val DEFAULT_ON: Set<String> = ALL.toSet()

    data class Entry(val id: String, val on: Boolean)

    fun load(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val s = Prefs.prefs(ctx).getString(KEY, null)
        if (s != null) {
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id in ALL && out.none { it.id == id }) out.add(Entry(id, o.optBoolean("on", false)))
                }
            } catch (e: Exception) {
                out.clear()
            }
        }
        if (s == null || out.isEmpty()) {
            out.clear()
            for (id in ALL) out.add(Entry(id, id in DEFAULT_ON))
            return out
        }
        for (id in ALL) if (out.none { it.id == id }) out.add(Entry(id, false))
        return out
    }

    fun enabled(ctx: Context): List<String> {
        val l = load(ctx).filter { it.on }.map { it.id }
        return if (l.isEmpty()) listOf("quick") else l
    }

    fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        val seen = HashSet<String>()
        for (e in list) {
            if (e.id !in ALL || !seen.add(e.id)) continue
            a.put(JSONObject().put("id", e.id).put("on", e.on))
        }
        Prefs.prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun label(id: String): String = when (id) {
        "quick" -> "Quick"
        "media" -> "Media"
        "apps" -> "Apps"
        "clipboard" -> "Clipboard"
        "timer" -> "Timer"
        "notifications" -> "Alerts"
        "device" -> "Device"
        else -> id
    }

    fun icon(id: String): Int = when (id) {
        "quick" -> R.drawable.ic_sliders
        "media" -> R.drawable.ic_music
        "apps" -> R.drawable.ic_grid
        "clipboard" -> R.drawable.ic_clipboard
        "timer" -> R.drawable.ic_timer
        "notifications" -> R.drawable.ic_bell
        "device" -> R.drawable.ic_cpu
        else -> R.drawable.ic_sliders
    }
}
