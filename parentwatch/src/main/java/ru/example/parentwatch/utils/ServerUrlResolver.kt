package ru.example.parentwatch.utils

import android.content.Context

object ServerUrlResolver {

    fun getServerUrl(context: Context): String? {
        val primary = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            .getString("server_url", null)
            ?.trim()
        if (!primary.isNullOrBlank()) {
            return normalizeServerUrl(primary)
        }

        val legacy = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            .getString("server_url", null)
            ?.trim()
        return legacy?.takeIf { it.isNotBlank() }?.let { normalizeServerUrl(it) }
    }

    fun normalizeServerUrl(raw: String): String {
        val trimmed = extractUrlCandidate(raw)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        val looksLikeLocalOrIp = trimmed.startsWith("localhost", ignoreCase = true) ||
            trimmed.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$"))

        return if (looksLikeLocalOrIp) {
            "http://$trimmed"
        } else {
            "https://$trimmed"
        }
    }

    private fun extractUrlCandidate(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value

        // Some legacy builds saved multiple URLs in one field separated by new lines.
        // Prefer the last valid-looking URL token (usually the most recently entered one).
        val regex = Regex("""https?://[^\s,;]+|(?:localhost|\d{1,3}(?:\.\d{1,3}){3}|[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?::\d+)?""")
        val matched = regex.findAll(value).map { it.value.trim() }.toList()
        return matched.lastOrNull().orEmpty().ifBlank { value.lineSequence().lastOrNull()?.trim().orEmpty() }
    }
}
