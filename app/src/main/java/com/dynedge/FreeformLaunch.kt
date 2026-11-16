package com.dynedge

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.widget.Toast

/** Opens an app in a floating (freeform) window; falls back to a normal launch when freeform is off. */
internal object FreeformLaunch {

    private var hinted = false

    fun supported(ctx: Context): Boolean = try {
        ctx.packageManager.hasSystemFeature("android.software.freeform_window_management") ||
            Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1
    } catch (e: Exception) {
        false
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return go(ctx, i)
    }

    fun launch(ctx: Context, cn: ComponentName): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
        return go(ctx, i)
    }

    private fun go(ctx: Context, intent: Intent): Boolean {
        val free = supported(ctx)
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (free) {
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                val dm = ctx.resources.displayMetrics
                val w = (dm.widthPixels * 0.8f).toInt()
                val hh = (dm.heightPixels * 0.6f).toInt()
                val l = (dm.widthPixels - w) / 2
                val t = (dm.heightPixels - hh) / 2
                val o = ActivityOptions.makeBasic()
                o.setLaunchBounds(Rect(l, t, l + w, t + hh))
                try {
                    ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(o, 5)
                } catch (e: Throwable) {
                }
                ctx.startActivity(intent, o.toBundle())
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ctx.startActivity(intent)
                if (!hinted) {
                    hinted = true
                    Toast.makeText(ctx, "Turn on freeform windows in Developer options for floating apps",
                        Toast.LENGTH_LONG).show()
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
