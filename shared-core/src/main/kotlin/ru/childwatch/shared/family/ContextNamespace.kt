package ru.childwatch.shared.family

import java.nio.charset.StandardCharsets

object ContextNamespace {
    fun build(context: ActiveContext, ownerScope: String, feature: String): String {
        return listOf(
            "v${context.version}",
            ownerScope,
            context.familyId,
            context.selfMemberId,
            context.selfDeviceId,
            context.focusedMemberId,
            context.targetDeviceId,
            feature
        ).joinToString("/") { encodeSegment(it) }
    }

    private fun encodeSegment(raw: String?): String {
        val value = raw.normalizedOrNull() ?: "_"
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val safe = unsigned in 'a'.code..'z'.code ||
                    unsigned in 'A'.code..'Z'.code ||
                    unsigned in '0'.code..'9'.code ||
                    unsigned == '-'.code || unsigned == '_'.code || unsigned == '.'.code
                if (safe) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
