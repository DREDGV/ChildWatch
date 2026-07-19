package ru.childwatch.shared.family

/**
 * Immutable point-in-time context captured for a single feature operation.
 *
 * The family/member fields intentionally have defaults and are appended after
 * the original fields so existing positional constructor calls remain source
 * compatible while newer callers can inspect the complete active identity.
 */
data class FeatureContext @JvmOverloads constructor(
    val selfDeviceId: String,
    val targetDeviceId: String?,
    val serverUrl: String,
    val storageNamespace: String,
    val source: ContextSource,
    val updatedAt: Long,
    val familyId: String? = null,
    val selfMemberId: String? = null,
    val focusedMemberId: String? = null
)

fun ActiveContext.forFeature(ownerScope: String, feature: String): FeatureContext {
    return FeatureContext(
        selfDeviceId = selfDeviceId,
        targetDeviceId = targetDeviceId,
        serverUrl = serverUrl,
        storageNamespace = storageNamespace(ownerScope, feature),
        source = source,
        updatedAt = updatedAt,
        familyId = familyId,
        selfMemberId = selfMemberId,
        focusedMemberId = focusedMemberId
    )
}
