package ru.example.childwatch.profile

import android.content.Context
import ru.childwatch.shared.family.FamilyPresenceState
import ru.childwatch.shared.family.FamilyRole
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.network.LinkedChildLink
import ru.example.childwatch.network.NetworkClient

data class ParentLinkedChildOption(
    val deviceId: String,
    val displayName: String,
    val source: String,
    val markerIconId: Int = ContactIcons.CHILD,
    val familyId: String? = null,
    val memberId: String? = null,
    val role: FamilyRole = FamilyRole.CHILD,
    val deviceDisplayName: String? = null,
    val lastSeenAt: Long? = null,
    val presence: FamilyPresenceState = FamilyPresenceState.UNKNOWN,
    val avatarKey: String? = null,
    val deviceCount: Int = 1
)

class ParentLinkedChildOptionsProvider(context: Context) {

    private val appContext = context.applicationContext
    private val database by lazy { ChildWatchDatabase.getInstance(appContext) }
    private val networkClient by lazy { NetworkClient(appContext) }
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(appContext) }
    private val familyDirectoryRepository by lazy { ParentFamilyDirectoryRepository(appContext) }

    suspend fun getOptions(): List<ParentLinkedChildOption> {
        val result = linkedMapOf<String, ParentLinkedChildOption>()

        val localChildren = database.childDao().getAll()
        val localByDevice = localChildren.associateBy { it.deviceId.trim() }

        runCatching { familyDirectoryRepository.load() }
            .getOrNull()
            ?.directory
            ?.targetPeople()
            ?.forEach { person ->
                val primaryDevice = person.primaryDevice() ?: return@forEach
                val local = localByDevice[primaryDevice.deviceId]
                result[primaryDevice.deviceId] = ParentLinkedChildOption(
                    deviceId = primaryDevice.deviceId,
                    displayName = person.member.displayName,
                    source = "family",
                    markerIconId = local?.iconId?.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD,
                    familyId = person.member.familyId,
                    memberId = person.member.id,
                    role = person.member.role,
                    deviceDisplayName = primaryDevice.displayName,
                    lastSeenAt = person.activeDevices.mapNotNull { it.lastSeenAt }.maxOrNull(),
                    presence = person.presence(),
                    avatarKey = person.member.avatarKey ?: local?.avatarUrl,
                    deviceCount = person.activeDevices.size.coerceAtLeast(1)
                )
            }

        localChildren.forEach { child ->
            val deviceId = child.deviceId.trim()
            if (deviceId.isBlank()) return@forEach
            result.putIfAbsent(
                deviceId,
                ParentLinkedChildOption(
                    deviceId = deviceId,
                    displayName = child.name.trim().ifBlank { "Ребёнок" },
                    source = "local",
                    markerIconId = child.iconId.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD,
                    memberId = ru.childwatch.shared.family.StableContextIds.memberId(deviceId),
                    role = when (child.role) {
                        ru.example.childwatch.contacts.ContactRoles.PARENT -> FamilyRole.PARENT
                        ru.example.childwatch.contacts.ContactRoles.RELATIVE -> FamilyRole.GUARDIAN
                        else -> FamilyRole.CHILD
                    },
                    deviceDisplayName = child.alias,
                    lastSeenAt = child.lastSeenAt,
                    avatarKey = child.avatarUrl
                )
            )
        }

        val parentDeviceId = effectiveContextResolver.resolveOwnParentId().trim()
        val serverUrl = effectiveContextResolver.resolveServerUrl().trim()
        if (parentDeviceId.isBlank() || serverUrl.isBlank()) {
            return result.values.sortedBy { it.displayName.lowercase() }
        }

        val response = runCatching { networkClient.getLinkedChildren(parentDeviceId) }.getOrNull()
        val links = response?.body()?.children.orEmpty()
        links.forEach { link ->
            val merged = buildOption(link) ?: return@forEach
            val existing = result[merged.deviceId]
            result[merged.deviceId] = if (existing == null) {
                merged
            } else {
                existing.copy(
                    displayName = if (existing.displayName == existing.deviceId || existing.displayName.isBlank()) {
                        merged.displayName
                    } else {
                        existing.displayName
                    },
                    source = if (existing.source == "family") existing.source else "linked",
                    markerIconId = merged.markerIconId
                )
            }
        }

        return result.values.sortedBy { it.displayName.lowercase() }
    }

    suspend fun syncLocalChildren(options: List<ParentLinkedChildOption>) {
        val normalized = options
            .filter { it.deviceId.isNotBlank() }
            .distinctBy { it.deviceId }

        normalized.forEach { option ->
            val existing = database.childDao().getByDeviceId(option.deviceId)
            val normalizedIconId = option.markerIconId.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD
            if (existing == null) {
                database.childDao().insert(
                    Child(
                        deviceId = option.deviceId,
                        name = option.displayName.ifBlank { option.deviceId },
                        iconId = normalizedIconId
                    )
                )
            } else {
                val updated = existing.copy(
                    name = option.displayName.ifBlank { existing.name },
                    iconId = normalizedIconId,
                    avatarUrl = option.avatarKey ?: existing.avatarUrl,
                    lastSeenAt = option.lastSeenAt ?: existing.lastSeenAt,
                    updatedAt = System.currentTimeMillis()
                )
                if (updated != existing) {
                    database.childDao().update(updated)
                }
            }
        }
    }

    private fun buildOption(link: LinkedChildLink): ParentLinkedChildOption? {
        val deviceId = link.childDeviceId.trim()
        if (deviceId.isBlank()) return null
        val displayName = link.childDisplayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: link.displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: link.childDeviceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: deviceId
        val markerIconId = link.childMarkerIconId?.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD
        return ParentLinkedChildOption(
            deviceId = deviceId,
            displayName = displayName,
            source = "linked",
            markerIconId = markerIconId
        )
    }
}
