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
        require(selfDeviceId.isNotBlank())
        require(serverUrl.isNotBlank())
        require(targetDeviceId == null || targetDeviceId != selfDeviceId)
    }

    fun storageNamespace(feature: String): String {
        fun safe(value: String?): String =
            value?.trim()?.takeIf { it.isNotEmpty() } ?: "_"

        return listOf(
            safe(familyId),
            safe(selfMemberId),
            safe(focusedMemberId),
            safe(targetDeviceId),
            safe(feature)
        ).joinToString("/")
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
