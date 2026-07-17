package ru.childwatch.shared.family

import java.nio.charset.StandardCharsets
import java.util.UUID

data class ActiveContextCandidate(
    val familyId: String? = null,
    val selfMemberId: String? = null,
    val selfDeviceId: String? = null,
    val focusedMemberId: String? = null,
    val targetDeviceId: String? = null,
    val serverUrl: String? = null,
    val source: ContextSource,
    val updatedAt: Long = 0L
) {
    companion object {
        fun from(context: ActiveContext): ActiveContextCandidate = ActiveContextCandidate(
            familyId = context.familyId,
            selfMemberId = context.selfMemberId,
            selfDeviceId = context.selfDeviceId,
            focusedMemberId = context.focusedMemberId,
            targetDeviceId = context.targetDeviceId,
            serverUrl = context.serverUrl,
            source = context.source,
            updatedAt = context.updatedAt
        )
    }
}

class ActiveContextResolver(
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun resolve(candidatesInPriorityOrder: List<ActiveContextCandidate>): ActiveContext? {
        val selfDeviceId = candidatesInPriorityOrder.firstNotBlank { it.selfDeviceId }
            ?: return null
        val serverUrl = candidatesInPriorityOrder.firstNotBlank { it.serverUrl }
            ?: return null

        val selection = candidatesInPriorityOrder.firstNotNullOfOrNull { candidate ->
            val target = candidate.targetDeviceId.normalizedOrNull()
                ?.takeUnless { it == selfDeviceId }
                ?: return@firstNotNullOfOrNull null
            Selection(
                targetDeviceId = target,
                focusedMemberId = candidate.focusedMemberId.normalizedOrNull()
                    ?: StableContextIds.memberId(target)
            )
        }

        val familyId = candidatesInPriorityOrder.firstNotBlank { it.familyId }
            ?: StableContextIds.familyId(serverUrl, listOfNotNull(selfDeviceId, selection?.targetDeviceId))
        val selfMemberId = candidatesInPriorityOrder.firstNotBlank { it.selfMemberId }
            ?: StableContextIds.memberId(selfDeviceId)
        val contributingSource = candidatesInPriorityOrder.firstOrNull { it.hasMeaningfulValue() }
            ?.source
            ?: ContextSource.LEGACY_MIGRATION
        val updatedAt = candidatesInPriorityOrder.firstOrNull { it.hasMeaningfulValue() && it.updatedAt > 0L }
            ?.updatedAt
            ?: clock()

        return ActiveContext(
            familyId = familyId,
            selfMemberId = selfMemberId,
            selfDeviceId = selfDeviceId,
            focusedMemberId = selection?.focusedMemberId,
            targetDeviceId = selection?.targetDeviceId,
            serverUrl = serverUrl,
            source = contributingSource,
            updatedAt = updatedAt
        )
    }

    private fun ActiveContextCandidate.hasMeaningfulValue(): Boolean {
        return listOf(
            familyId,
            selfMemberId,
            selfDeviceId,
            focusedMemberId,
            targetDeviceId,
            serverUrl
        ).any { it.normalizedOrNull() != null }
    }

    private inline fun List<ActiveContextCandidate>.firstNotBlank(
        selector: (ActiveContextCandidate) -> String?
    ): String? = firstNotNullOfOrNull { selector(it).normalizedOrNull() }

    private data class Selection(
        val focusedMemberId: String,
        val targetDeviceId: String
    )
}

object StableContextIds {
    fun memberId(deviceId: String): String {
        return stableUuid("member|${deviceId.trim()}")
    }

    fun familyId(serverUrl: String, deviceIds: Collection<String>): String {
        val members = deviceIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        return stableUuid("family|${serverUrl.trim().lowercase()}|${members.joinToString("|")}")
    }

    private fun stableUuid(value: String): String {
        return UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
