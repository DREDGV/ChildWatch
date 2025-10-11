package ru.example.parentwatch.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple secure preferences wrapper
 * Uses SharedPreferences with MODE_PRIVATE for basic security
 */
class SecurePreferences(context: Context, name: String) {

    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
