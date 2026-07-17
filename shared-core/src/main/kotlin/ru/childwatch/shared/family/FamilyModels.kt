package ru.childwatch.shared.family

enum class FamilyRole { PARENT, CHILD, GUARDIAN }

enum class FamilyFeature {
    CHAT,
    LOCATION,
    LOCATION_HISTORY,
    AUDIO_LISTENING,
    REMOTE_PHOTO,
    APP_USAGE,
    SEND_ATTENTION_SIGNAL,
    RECEIVE_ATTENTION_SIGNAL
}

enum class ContextSource {
    CANONICAL,
    ACTIVE_SESSION,
    SECURE_SETTINGS,
    LEGACY_MIGRATION
}

data class Family(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class FamilyMember(
    val id: String,
    val familyId: String,
    val displayName: String,
    val role: FamilyRole,
    val avatarKey: String? = null,
    val isActive: Boolean = true
)

data class FamilyDevice(
    val id: String,
    val familyId: String,
    val memberId: String,
    val deviceId: String,
    val displayName: String,
    val platform: String = "android",
    val lastSeenAt: Long? = null,
    val isActive: Boolean = true
)

data class FamilyPermission(
    val familyId: String,
    val actorMemberId: String,
    val targetMemberId: String,
    val feature: FamilyFeature,
    val allowed: Boolean
)

data class ActiveContext(
    val version: Int = CURRENT_VERSION,
    val familyId: String? = null,
    val selfMemberId: String? = null,
    val selfDeviceId: String,
    val focusedMemberId: String? = null,
    val targetDeviceId: String? = null,
    val serverUrl: String,
    val source: ContextSource,
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(selfDeviceId.isNotBlank()) { "selfDeviceId must not be blank" }
        require(serverUrl.isNotBlank()) { "serverUrl must not be blank" }
        require(targetDeviceId == null || targetDeviceId != selfDeviceId) {
            "selfDeviceId cannot be used as targetDeviceId"
        }
    }

    fun withSelection(
        focusedMemberId: String?,
        targetDeviceId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): ActiveContext {
        val target = targetDeviceId.normalizedOrNull()
            ?.takeUnless { it == selfDeviceId }
        val focused = if (target == null) {
            null
        } else {
            focusedMemberId.normalizedOrNull() ?: StableContextIds.memberId(target)
        }

        return copy(
            focusedMemberId = focused,
            targetDeviceId = target,
            source = ContextSource.CANONICAL,
            updatedAt = updatedAt
        )
    }

    fun storageNamespace(ownerScope: String, feature: String): String {
        return ContextNamespace.build(this, ownerScope, feature)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
