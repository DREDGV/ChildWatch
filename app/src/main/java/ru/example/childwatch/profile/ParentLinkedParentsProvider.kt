package ru.example.childwatch.profile

import android.content.Context
import ru.example.childwatch.network.LinkedParentLink
import ru.example.childwatch.network.NetworkClient

data class ParentLinkedParentOption(
    val parentDeviceId: String,
    val displayName: String
)

class ParentLinkedParentsProvider(context: Context) {

    private val appContext = context.applicationContext
    private val networkClient by lazy { NetworkClient(appContext) }

    suspend fun getOptions(childDeviceId: String): List<ParentLinkedParentOption> {
        val normalizedChildId = childDeviceId.trim()
        if (normalizedChildId.isBlank()) return emptyList()

        val response = runCatching { networkClient.getLinkedParents(normalizedChildId) }.getOrNull()
        if (response?.isSuccessful != true) return emptyList()

        return response.body()
            ?.parents
            .orEmpty()
            .mapNotNull(::buildOption)
            .distinctBy { it.parentDeviceId }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun buildOption(link: LinkedParentLink): ParentLinkedParentOption? {
        val parentDeviceId = link.parentDeviceId.trim()
        if (parentDeviceId.isBlank()) return null

        val displayName = link.parentDisplayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: link.displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: link.parentDeviceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: parentDeviceId

        return ParentLinkedParentOption(
            parentDeviceId = parentDeviceId,
            displayName = displayName
        )
    }
}
