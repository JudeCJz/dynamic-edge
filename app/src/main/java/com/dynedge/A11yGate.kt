package com.dynedge

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Switches the EdgeService entry in Settings.Secure enabled_accessibility_services on and off (needs
 * WRITE_SECURE_SETTINGS, granted once over adb). Only our own entry is ever touched: every other service
 * and its order are kept exactly as they were.
 */
object A11yGate {
    private const val TAG = "DynEdgeGate"

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun flat(c: Context): String = ComponentName(c.packageName, EdgeService::class.java.name).flattenToString()

    /** Whether our entry is in the enabled list. */
    fun isEnabled(c: Context): Boolean {
        val list = try {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            null
        } ?: return false
        val me = flat(c)
        return list.split(':').any { same(it, me) }
    }

    /** Puts our entry in the wanted state. Returns true when the setting was changed. */
    fun set(c: Context, on: Boolean): Boolean {
        if (!canWrite(c)) return false
        return try {
            val cr = c.contentResolver
            val old = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val new = edit(old, flat(c), on)
            if (new == old) {
                if (on) ensureMasterOn(c)
                return false
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, new)
            // Banking apps also read accessibility_enabled directly: when removing ours empties the list, the
            // master flag goes to 0 too (enabling sets it back to 1).
            if (on) ensureMasterOn(c)
            else if (new.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "secure settings write refused", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "secure settings write failed", e)
            false
        }
    }

    private fun ensureMasterOn(c: Context) {
        val cr = c.contentResolver
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** The colon separated [list] with [flat] present or absent, every other entry kept in order. */
    fun edit(list: String, flat: String, on: Boolean): String {
        val items = list.split(':').filter { it.isNotEmpty() }
        val kept = items.filterNot { same(it, flat) }
        return if (on) (if (kept.size < items.size) items else kept + flat).joinToString(":")
        else kept.joinToString(":")
    }

    /** Same component ("pkg/.Cls" equals "pkg/pkg.Cls"), ignoring case. */
    private fun same(a: String, b: String): Boolean = full(a).equals(full(b), ignoreCase = true)

    private fun full(s: String): String {
        val pkg = s.substringBefore('/')
        val cls = s.substringAfter('/', "")
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else s
    }
}
