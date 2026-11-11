package com.dynedge

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process

/** Recently used apps for the APPS ribbon (UsageStatsManager; needs the Usage access app-op). */
internal object RecentApps {

    class Item(val pkg: String, val icon: Bitmap)

    private const val WINDOW_MS = 2L * 60L * 60L * 1000L

    @Suppress("DEPRECATION")
    fun hasAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /** Newest first, at most [max], launchable only, without the launcher, this app and [exclude]. Blocking: call off the UI thread. */
    fun query(ctx: Context, exclude: Set<String>, iconPx: Int, max: Int): List<Item> {
        val out = ArrayList<Item>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return out
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - WINDOW_MS, now) ?: return out
            val last = HashMap<String, Long>()
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            if (last.isEmpty()) return out
            val pm = ctx.packageManager
            val skip = HashSet<String>(exclude)
            skip.add(ctx.packageName)
            skip.add("com.android.systemui")
            skip.add("android")
            try {
                val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                home?.activityInfo?.packageName?.let { skip.add(it) }
            } catch (x: Exception) {
            }
            val sorted = last.entries.sortedByDescending { it.value }
            for (en in sorted) {
                val pkg = en.key
                if (pkg in skip) continue
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                try {
                    out.add(Item(pkg, IconTheme.icon(ctx, pkg, iconPx)))
                } catch (x: Exception) {
                    continue
                }
                if (out.size >= max) break
            }
        } catch (e: Exception) {
            // no usage access (SecurityException) or a vendor failure: an empty ribbon is the graceful state
        }
        return out
    }

    fun launch(ctx: Context, pkg: String): Boolean = try {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            false
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(i)
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Android only allows killing background processes (KILL_BACKGROUND_PROCESSES); a real force-stop is not
     * available to a normal app. An app with a foreground service or in the foreground is not affected.
     */
    fun stop(ctx: Context, pkg: String) {
        try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        } catch (e: Exception) {
        }
    }
}
