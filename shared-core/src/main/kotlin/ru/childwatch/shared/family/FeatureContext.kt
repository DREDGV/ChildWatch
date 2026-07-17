package ru.childwatch.shared.family

data class FeatureContext(
    val selfDeviceId: String,
    val targetDeviceId: String?,
    val serverUrl: String,
    val storageNamespace: String,
    val source: ContextSource,
    val updatedAt: Long
)

fun ActiveContext.forFeature(ownerScope: String, feature: String): FeatureContext {
    return FeatureContext(
        selfDeviceId = selfDeviceId,
        targetDeviceId = targetDeviceId,
        serverUrl = serverUrl,
        storageNamespace = storageNamespace(ownerScope, feature),
        source = source,
        updatedAt = updatedAt
    )
}
