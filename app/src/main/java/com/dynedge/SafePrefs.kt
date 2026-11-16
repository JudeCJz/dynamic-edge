package com.dynedge

import android.content.SharedPreferences

/**
 * A SharedPreferences that never throws ClassCastException on reads: when a key holds another type than asked
 * for, the stored value is converted when that makes sense (number <-> text, int <-> float) and otherwise the
 * default is returned. Writes and listeners go straight to [base].
 */
class SafePrefs(val base: SharedPreferences) : SharedPreferences by base {

    override fun getString(key: String?, defValue: String?): String? = try {
        base.getString(key, defValue)
    } catch (e: ClassCastException) {
        when (val v = base.all[key]) {
            null -> defValue
            is Set<*> -> defValue
            else -> v.toString()
        }
    }

    override fun getInt(key: String?, defValue: Int): Int = try {
        base.getInt(key, defValue)
    } catch (e: ClassCastException) {
        when (val v = base.all[key]) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: v.trim().toFloatOrNull()?.toInt() ?: defValue
            is Boolean -> if (v) 1 else 0
            else -> defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long = try {
        base.getLong(key, defValue)
    } catch (e: ClassCastException) {
        when (val v = base.all[key]) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float = try {
        base.getFloat(key, defValue)
    } catch (e: ClassCastException) {
        when (val v = base.all[key]) {
            is Number -> v.toFloat()
            is String -> v.trim().toFloatOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = try {
        base.getBoolean(key, defValue)
    } catch (e: ClassCastException) {
        when (val v = base.all[key]) {
            is String -> when (v.trim().lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> defValue }
            is Number -> v.toInt() != 0
            else -> defValue
        }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = try {
        base.getStringSet(key, defValues)
    } catch (e: ClassCastException) {
        defValues
    }
}
