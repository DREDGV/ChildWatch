package ru.childwatch.shared.family

data class ContextDiagnosticSnapshot(
    val configured: Boolean,
    val version: Int?,
    val serverHost: String?,
    val familyId: String?,
    val selfMemberId: String?,
    val selfDeviceId: String?,
    val focusedMemberId: String?,
    val targetDeviceId: String?,
    val source: ContextSource?,
    val updatedAt: Long?
)

object ContextDiagnostics {
    fun snapshot(context: ActiveContext?): ContextDiagnosticSnapshot {
        return ContextDiagnosticSnapshot(
            configured = context != null,
            version = context?.version,
            serverHost = context?.serverUrl?.let(::extractHost),
            familyId = mask(context?.familyId),
            selfMemberId = mask(context?.selfMemberId),
            selfDeviceId = mask(context?.selfDeviceId),
            focusedMemberId = mask(context?.focusedMemberId),
            targetDeviceId = mask(context?.targetDeviceId),
            source = context?.source,
            updatedAt = context?.updatedAt
        )
    }

    fun mask(raw: String?): String? {
        val value = raw.normalizedOrNull() ?: return null
        return when {
            value.length <= 4 -> "****"
            value.length <= 10 -> "${value.take(2)}***${value.takeLast(2)}"
            else -> "${value.take(4)}...${value.takeLast(4)}"
        }
    }

    private fun extractHost(serverUrl: String): String {
        return runCatching { java.net.URI(serverUrl).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "invalid-host"
    }
}
