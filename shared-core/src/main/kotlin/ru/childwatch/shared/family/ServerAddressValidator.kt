package ru.childwatch.shared.family

import java.net.URI

/** A server setting must identify an HTTP origin, not just contain a scheme. */
object ServerAddressValidator {
    fun isValid(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        if (uri.port == 0 || uri.port > 65535) return false
        return true
    }
}
