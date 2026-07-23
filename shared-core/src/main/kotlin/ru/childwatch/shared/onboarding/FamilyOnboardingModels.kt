package ru.childwatch.shared.onboarding

import java.net.URI

enum class FamilyInvitationMode {
    NEW_MEMBER,
    EXISTING_MEMBER
}

enum class FamilyAppKind {
    PARENT_MONITOR,
    CHILD_DEVICE
}

/** Prevents a child identity from accidentally entering the adult app and vice versa. */
object FamilyOnboardingRolePolicy {
    fun accepts(appKind: FamilyAppKind, role: String?): Boolean {
        val normalizedRole = role?.trim()?.uppercase().orEmpty()
        return when (appKind) {
            FamilyAppKind.PARENT_MONITOR -> normalizedRole in setOf("PARENT", "GUARDIAN")
            FamilyAppKind.CHILD_DEVICE -> normalizedRole == "CHILD"
        }
    }
}

enum class FamilyOnboardingEntryDecision {
    KEEP_COMPLETED,
    COMPLETE_FROM_SERVER,
    PRESERVE_LEGACY_LINK,
    OPEN_JOIN_WIZARD
}

/** Deterministic first-run policy; protects upgraded installations from being mistaken for new ones. */
object FamilyOnboardingEntryPolicy {
    fun decide(
        localCompleted: Boolean,
        hasServerMembership: Boolean,
        hasLegacyParentLink: Boolean
    ): FamilyOnboardingEntryDecision = when {
        localCompleted -> FamilyOnboardingEntryDecision.KEEP_COMPLETED
        hasServerMembership -> FamilyOnboardingEntryDecision.COMPLETE_FROM_SERVER
        hasLegacyParentLink -> FamilyOnboardingEntryDecision.PRESERVE_LEGACY_LINK
        else -> FamilyOnboardingEntryDecision.OPEN_JOIN_WIZARD
    }
}

data class FamilyBootstrapRequest(
    val familyName: String,
    val displayName: String,
    val role: String = "PARENT",
    val avatarKey: String? = null
)

data class FamilyProfileConfirmationRequest(
    val displayName: String,
    val avatarKey: String? = null
)

data class FamilyInvitationCreateRequest(
    val familyId: String,
    val mode: String,
    val targetMemberId: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val avatarKey: String? = null,
    val ttlMs: Long = 15 * 60 * 1000L
)

data class FamilyInvitationAcceptRequest(
    val deviceName: String? = null,
    val clientKind: String
)

data class OnboardingFamilyData(
    val id: String,
    val name: String
)

data class OnboardingMemberData(
    val id: String? = null,
    val familyId: String? = null,
    val displayName: String,
    val role: String,
    val avatarKey: String? = null
)

data class OnboardingBindingData(
    val id: String? = null,
    val familyId: String,
    val memberId: String,
    val deviceId: String,
    val displayName: String? = null,
    val platform: String? = null,
    val memberBindingSource: String? = null
)

data class FamilyOnboardingResultResponse(
    val success: Boolean,
    val family: OnboardingFamilyData,
    val member: OnboardingMemberData,
    val binding: OnboardingBindingData
)

data class FamilyInvitationData(
    val id: String,
    val family: OnboardingFamilyData,
    val mode: String,
    val member: OnboardingMemberData,
    val invitedBy: String,
    val createdAt: Long = 0L,
    val expiresAt: Long,
    val isExpired: Boolean = false,
    val isConsumed: Boolean = false,
    val isRevoked: Boolean = false,
    val token: String? = null,
    val invitationUri: String? = null
)

data class FamilyInvitationResponse(
    val success: Boolean,
    val invitation: FamilyInvitationData
)

data class FamilyInvitationsResponse(
    val success: Boolean,
    val invitations: List<FamilyInvitationData> = emptyList()
)

data class FamilyOnboardingSimpleResponse(
    val success: Boolean
)

data class FamilyLegacyMigrationDeviceData(
    val id: String,
    val familyId: String,
    val memberId: String,
    val deviceId: String,
    val displayName: String? = null,
    val platform: String? = null,
    val lastSeenAt: Long? = null,
    val memberBindingSource: String? = null
)

data class FamilyLegacyMigrationCandidateData(
    val member: OnboardingMemberData,
    val devices: List<FamilyLegacyMigrationDeviceData> = emptyList()
)

data class FamilyLegacyMigrationCandidatesResponse(
    val success: Boolean,
    val familyId: String,
    val candidates: List<FamilyLegacyMigrationCandidateData> = emptyList()
)

data class FamilyLegacyProfileConfirmRequest(
    val displayName: String,
    val role: String,
    val avatarKey: String? = null
)

data class FamilyLegacyProfileConfirmResponse(
    val success: Boolean,
    val family: OnboardingFamilyData,
    val member: OnboardingMemberData,
    val devices: List<FamilyLegacyMigrationDeviceData> = emptyList()
)

data class FamilyDeviceTransferRequest(
    val targetMemberId: String,
    val confirmed: Boolean
)

/** Versioned QR payload parser. Raw device identifiers are intentionally rejected. */
object FamilyInvitationTokenParser {
    private val tokenPattern = Regex("^[a-fA-F0-9]{64}$")

    fun parse(rawValue: String?): String? {
        val raw = rawValue?.trim().orEmpty()
        if (tokenPattern.matches(raw)) return raw.lowercase()
        if (!raw.startsWith("childwatch://family/join", ignoreCase = true)) return null
        return runCatching {
            URI(raw).rawQuery
                ?.split('&')
                ?.asSequence()
                ?.mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
                }
                ?.firstOrNull { it.first == "token" }
                ?.second
                ?.takeIf(tokenPattern::matches)
                ?.lowercase()
        }.getOrNull()
    }
}
