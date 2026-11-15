package com.dynedge

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper

/** Recent notifications, fed by MediaListener. */
object NotifFeed {
    data class Item(
        val key: String, val pkg: String, val title: String, val text: String, val time: Long,
        val contentIntent: PendingIntent?, val clearable: Boolean
    )

    const val MAX = 8

    private val lock = Any()
    private var items: List<Item> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val icons = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 8
    }

    @Volatile
    var onChange: (() -> Unit)? = null

    @Volatile
    internal var listener: MediaListener? = null

    private val notifier = Runnable { onChange?.invoke() }

    /** Newest first, max 8. */
    fun recent(): List<Item> = synchronized(lock) { items }

    /** Called by MediaListener with the full, already filtered and sorted list. */
    internal fun publish(list: List<Item>) {
        val l = list.take(MAX)
        synchronized(lock) {
            if (l == items) return
            items = l
        }
        main.removeCallbacks(notifier)
        main.post(notifier)
    }

    fun dismiss(key: String) {
        // Optimistic: drop it from the feed now; MediaListener republishes the real list when the removal lands.
        var changed = false
        synchronized(lock) {
            val l = items.filter { it.key != key }
            if (l.size != items.size) {
                items = l
                changed = true
            }
        }
        if (changed) {
            main.removeCallbacks(notifier)
            main.post(notifier)
        }
        try {
            listener?.cancelNotification(key)
        } catch (e: Exception) {
        }
    }

    private val labels = HashMap<String, String>()

    /** Cached app name for a package (falls back to the package's last segment). */
    fun appLabel(ctx: Context, pkg: String): String {
        synchronized(labels) { labels[pkg]?.let { return it } }
        val l = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
        synchronized(labels) { labels[pkg] = l }
        return l
    }

    private val iconIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "edge-icons").apply { isDaemon = true }
    }
    private val loading = HashSet<String>()

    /**
     * Cached icon or null. A miss loads on a background thread (never on the UI thread) and calls [onChange]
     * on the main thread when the icon is ready.
     */
    fun appIcon(ctx: Context, pkg: String, px: Int): Bitmap? {
        val k = "$pkg@$px@" + IconTheme.source(ctx)
        synchronized(icons) {
            icons[k]?.let { return it }
            if (!loading.add(k)) return null
        }
        val app = ctx.applicationContext ?: ctx
        iconIo.execute {
            var ok = false
            try {
                val b = IconTheme.icon(app, pkg, px.coerceAtLeast(1))
                synchronized(icons) { icons[k] = b }
                ok = true
            } catch (e: Exception) {
            }
            synchronized(icons) { loading.remove(k) }
            if (ok) {
                main.removeCallbacks(notifier)
                main.post(notifier)
            }
        }
        return null
    }
}
