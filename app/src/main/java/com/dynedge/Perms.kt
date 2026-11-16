package com.dynedge

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** One permission or grant the widget needs, with the screen that fixes it. */
class Perm(val label: String, val ok: () -> Boolean, val intent: () -> Intent)

/** Permission checks for the settings app. */
object Perms {

    fun list(a: Activity): List<Perm> = listOf(
        Perm("Overlay service", { overlayEnabled(a) }, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        Perm("Media access", { mediaListenerEnabled(a) }, { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        Perm("Do Not Disturb access", { dndGranted(a) }, { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        Perm("Modify system settings", { Settings.System.canWrite(a) },
            { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}")) }),
        Perm("Assistant corner swipe off", { !assistSwipeOn(a) }, { Intent(Settings.ACTION_SETTINGS) }),
        Perm("Microphone", { granted(a, "android.permission.RECORD_AUDIO") }, { appDetails(a) }),
        Perm("Usage access", { RecentApps.hasAccess(a) }, { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }),
        Perm("Secure settings", { granted(a, "android.permission.WRITE_SECURE_SETTINGS") }, { appDetails(a) })
    )

    fun missing(a: Activity): List<Perm> = list(a).filter { p -> !(try { p.ok() } catch (e: Exception) { false }) }

    fun granted(a: Activity, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun appDetails(a: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))

    fun open(a: Activity, intent: () -> Intent) {
        try {
            a.startActivity(intent())
        } catch (e: Exception) {
            // no screen for it on this device
        }
    }

    fun overlayEnabled(a: Context): Boolean {
        val enabled = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(a, EdgeService::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }

    fun mediaListenerEnabled(a: Context): Boolean {
        val enabled = Settings.Secure.getString(a.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(a.packageName)
    }

    fun dndGranted(a: Context): Boolean {
        val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    private fun assistSwipeOn(a: Context): Boolean =
        Settings.Secure.getInt(a.contentResolver, "navigation_google_assist", 0) != 0
}
