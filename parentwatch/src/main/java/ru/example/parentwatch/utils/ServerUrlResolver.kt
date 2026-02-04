package ru.example.parentwatch.utils

import android.content.Context

object ServerUrlResolver {

    fun getServerUrl(context: Context): String? {
        val primary = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            .getString("server_url", null)
            ?.trim()
        if (!primary.isNullOrBlank()) {
            return primary
        }

        val legacy = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            .getString("server_url", null)
            ?.trim()
        return legacy?.takeIf { it.isNotBlank() }
    }
}
