package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas

/** Loads the user's shortcut apps as ready-to-draw bitmaps. Call load() on open, release() on close. */
class AppShortcuts(private val ctx: Context, private val iconPx: Int) {

    private var comps: List<ComponentName> = emptyList()
    private var icons: List<Bitmap> = emptyList()
    private var labels: List<String> = emptyList()

    val count: Int get() = comps.size

    fun load() {
        val pm = ctx.packageManager
        val c = ArrayList<ComponentName>()
        val b = ArrayList<Bitmap>()
        val l = ArrayList<String>()
        for (cn in Prefs.apps(ctx)) {
            if (c.size >= Prefs.MAX_APPS) break
            try {
                val info = pm.getActivityInfo(cn, 0)
                val bmp = IconTheme.icon(ctx, cn, iconPx)
                c.add(cn)
                b.add(bmp)
                l.add(info.loadLabel(pm).toString())
            } catch (e: Exception) {
                // app removed or icon failed: skip
            }
        }
        comps = c
        icons = b
        labels = l
    }

    fun release() {
        comps = emptyList()
        icons = emptyList()
        labels = emptyList()
    }

    fun icon(i: Int): Bitmap? = icons.getOrNull(i)

    fun label(i: Int): String = labels.getOrNull(i) ?: ""

    fun launch(i: Int): Boolean {
        val c = comps.getOrNull(i) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(c)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
