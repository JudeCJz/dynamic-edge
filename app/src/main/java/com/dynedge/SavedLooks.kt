package com.dynedge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named snapshots of the looks settings ("custom looks").
 *
 * A look stores the values of [Prefs.LOOK_KEYS] - the accent, the edge colours, the gradient, the
 * motion and the feedback - so it can be put back later with one tap.
 */
class SavedLook(val name: String, val values: Map<String, Any?>)

object SavedLooks {

    private const val KEY = "saved_looks"

    /** Every saved look, oldest first. */
    fun all(ctx: Context): List<SavedLook> {
        val raw = Prefs.prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<SavedLook>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val vo = o.optJSONObject("values") ?: JSONObject()
                val vals = LinkedHashMap<String, Any?>()
                for (k in vo.keys()) vals[k] = decode(vo.optString(k))
                out.add(SavedLook(o.optString("name"), vals))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Save [snap] under [name]. A name that is already used is replaced. */
    fun save(ctx: Context, name: String, snap: Map<String, Any?>) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val list = ArrayList(all(ctx))
        list.removeAll { it.name.equals(clean, ignoreCase = true) }
        list.add(SavedLook(clean, LinkedHashMap(snap)))
        write(ctx, list)
    }

    /** Remove the look called [name]. */
    fun remove(ctx: Context, name: String) {
        val list = ArrayList(all(ctx))
        list.removeAll { it.name.equals(name, ignoreCase = true) }
        write(ctx, list)
    }

    /** Put a saved look back into the settings. */
    fun apply(ctx: Context, look: SavedLook) {
        Prefs.restore(ctx, look.values)
    }

    private fun write(ctx: Context, list: List<SavedLook>) {
        val arr = JSONArray()
        for (l in list) {
            val o = JSONObject()
            o.put("name", l.name)
            val vo = JSONObject()
            for ((k, v) in l.values) vo.put(k, encode(v))
            o.put("values", vo)
            arr.put(o)
        }
        Prefs.prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    // values are tagged with their type so a float never comes back as an int
    private fun encode(v: Any?): String = when (v) {
        is Boolean -> "b" + v
        is Int -> "i" + v
        is Long -> "l" + v
        is Float -> "f" + v
        is String -> "s" + v
        else -> "n"
    }

    private fun decode(s: String): Any? = when {
        s.startsWith("b") -> s.substring(1).toBoolean()
        s.startsWith("i") -> s.substring(1).toIntOrNull()
        s.startsWith("l") -> s.substring(1).toLongOrNull()
        s.startsWith("f") -> s.substring(1).toFloatOrNull()
        s.startsWith("s") -> s.substring(1)
        else -> null
    }
}
