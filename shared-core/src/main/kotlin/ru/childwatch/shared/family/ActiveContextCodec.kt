package ru.childwatch.shared.family

import java.nio.charset.StandardCharsets
import java.util.Base64

object ActiveContextCodec {
    fun encode(context: ActiveContext): String {
        return buildList {
            add("version=${context.version}")
            addEncoded("familyId", context.familyId)
            addEncoded("selfMemberId", context.selfMemberId)
            addEncoded("selfDeviceId", context.selfDeviceId)
            addEncoded("focusedMemberId", context.focusedMemberId)
            addEncoded("targetDeviceId", context.targetDeviceId)
            addEncoded("serverUrl", context.serverUrl)
            add("source=${context.source.name}")
            add("updatedAt=${context.updatedAt}")
        }.joinToString("\n")
    }

    fun decode(raw: String): ActiveContext? {
        if (raw.isBlank()) return null
        val values = raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()

        return runCatching {
            val version = values["version"]?.toIntOrNull() ?: return null
            if (version > ActiveContext.CURRENT_VERSION) return null
            ActiveContext(
                version = version,
                familyId = values.decode("familyId"),
                selfMemberId = values.decode("selfMemberId"),
                selfDeviceId = values.decode("selfDeviceId") ?: return null,
                focusedMemberId = values.decode("focusedMemberId"),
                targetDeviceId = values.decode("targetDeviceId"),
                serverUrl = values.decode("serverUrl") ?: return null,
                source = values["source"]?.let(ContextSource::valueOf) ?: return null,
                updatedAt = values["updatedAt"]?.toLongOrNull() ?: return null
            )
        }.getOrNull()
    }

    private fun MutableList<String>.addEncoded(key: String, value: String?) {
        if (value == null) return
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        add("$key=$encoded")
    }

    private fun Map<String, String>.decode(key: String): String? {
        val encoded = this[key] ?: return null
        return String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
    }
}
