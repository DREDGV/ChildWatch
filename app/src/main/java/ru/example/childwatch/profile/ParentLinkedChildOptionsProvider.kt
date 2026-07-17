package ru.example.childwatch.profile

import android.content.Context
import ru.example.childwatch.contacts.ContactIcons
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.network.LinkedChildLink
import ru.example.childwatch.network.NetworkClient

data class ParentLinkedChildOption(
    val deviceId: String,
    val displayName: String,
    val source: String,
    val markerIconId: Int = ContactIcons.CHILD
)

class ParentLinkedChildOptionsProvider(context: Context) {

    private val appContext = context.applicationContext
    private val database by lazy { ChildWatchDatabase.getInstance(appContext) }
    private val networkClient by lazy { NetworkClient(appContext) }
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(appContext) }

    suspend fun getOptions(): List<ParentLinkedChildOption> {
        val result = linkedMapOf<String, ParentLinkedChildOption>()

        database.childDao().getAll().forEach { child ->
            val deviceId = child.deviceId.trim()
            if (deviceId.isBlank()) return@forEach
            result[deviceId] = ParentLinkedChildOption(
                deviceId = deviceId,
                displayName = child.name.trim().ifBlank { deviceId },
                source = "local",
                markerIconId = child.iconId.takeIf(ContactIcons::isKnown) ?: ContactIcons.CHILD
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
                    displayName = if (existing.displayName == existing.deviceId) {
                        merged.displayName
                    } else {
                        existing.displayName
                    },
                    source = "linked",
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
