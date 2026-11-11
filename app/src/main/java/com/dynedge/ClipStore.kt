package com.dynedge

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Clipboard history, persisted to filesDir/clips.json. Listeners are called on the main thread. */
class ClipStore private constructor(ctx: Context) {

    data class Clip(val id: Long, val text: String, val time: Long, val pinned: Boolean)

    private val file = File(ctx.applicationContext.filesDir, "clips.json")
    private val main = Handler(Looper.getMainLooper())
    // Single worker: the initial load is queued first, so every later persist runs after it. Daemon: never pins the process.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "edge-clips").apply { isDaemon = true } }
    private val lock = Any()
    private val items = ArrayList<Clip>()
    private val listeners = ArrayList<() -> Unit>()
    private var nextId = 1L

    private val notifier = Runnable {
        val copy = synchronized(lock) { ArrayList(listeners) }
        for (l in copy) l()
    }

    init {
        io.execute { load() }
    }

    fun list(): List<Clip> = synchronized(lock) {
        val pinned = items.filter { it.pinned }.sortedByDescending { it.time }
        val rest = items.filter { !it.pinned }.sortedByDescending { it.time }
        pinned + rest
    }

    fun add(text: String): Boolean {
        var t = text.trim()
        if (t.isEmpty()) return false
        if (t.length > MAX_TEXT) t = t.substring(0, MAX_TEXT)
        synchronized(lock) {
            val newest = items.maxByOrNull { it.time }
            if (newest != null && newest.text == t) return false
            val now = maxOf(System.currentTimeMillis(), (newest?.time ?: 0L) + 1)
            val idx = items.indexOfFirst { it.text == t }
            if (idx >= 0) {
                items[idx] = items[idx].copy(time = now)
            } else {
                items.add(Clip(nextId++, t, now, false))
                val unpinned = items.filter { !it.pinned }.sortedBy { it.time }
                var extra = unpinned.size - MAX_UNPINNED
                var i = 0
                while (extra > 0 && i < unpinned.size) {
                    items.remove(unpinned[i])
                    i++
                    extra--
                }
            }
        }
        changed()
        return true
    }

    fun pin(id: Long, pinned: Boolean) {
        val did = synchronized(lock) {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0 || items[i].pinned == pinned) false else {
                items[i] = items[i].copy(pinned = pinned)
                true
            }
        }
        if (did) changed()
    }

    fun delete(id: Long) {
        val did = synchronized(lock) { items.removeAll { it.id == id } }
        if (did) changed()
    }

    fun clearUnpinned() {
        val did = synchronized(lock) { items.removeAll { !it.pinned } }
        if (did) changed()
    }

    fun addListener(l: () -> Unit) {
        synchronized(lock) { if (l !in listeners) listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(lock) { listeners.remove(l) }
    }

    private fun changed() {
        persist()
        main.removeCallbacks(notifier)
        main.post(notifier)
    }

    private fun persist() {
        io.execute {
            val json = synchronized(lock) {
                val a = JSONArray()
                for (c in items) {
                    a.put(
                        JSONObject().put("id", c.id).put("text", c.text)
                            .put("time", c.time).put("pinned", c.pinned)
                    )
                }
                a.toString()
            }
            try {
                val tmp = File(file.parentFile, "clips.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
            }
        }
    }

    /** Runs on the io thread. Merges the file into whatever was added in memory meanwhile, then notifies. */
    private fun load() {
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            synchronized(lock) {
                val loaded = ArrayList<Clip>()
                var maxId = nextId
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val text = o.optString("text", "")
                    if (text.isEmpty()) continue
                    val id = o.optLong("id", 0L)
                    loaded.add(Clip(id, text, o.optLong("time", 0L), o.optBoolean("pinned", false)))
                    if (id >= maxId) maxId = id + 1
                }
                // clips copied before the file finished loading got fresh ids; move them above the loaded range
                val early = ArrayList(items)
                items.clear()
                nextId = maxId
                for (c in loaded) if (c.id > 0L) items.add(c) else items.add(c.copy(id = nextId++))
                for (c in early) {
                    if (items.none { it.text == c.text }) items.add(c.copy(id = nextId++))
                }
            }
            main.removeCallbacks(notifier)
            main.post(notifier)
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val MAX_UNPINNED = 60
        private const val MAX_TEXT = 20000

        @Volatile
        private var instance: ClipStore? = null

        fun get(ctx: Context): ClipStore =
            instance ?: synchronized(this) {
                instance ?: ClipStore(ctx).also { instance = it }
            }
    }
}
