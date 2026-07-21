package ru.example.childwatch.profile

import android.content.Context

/** Shared person selection boundary for parent-side monitoring features. */
class ParentTargetSelector(context: Context) {
    private val optionsProvider = ParentLinkedChildOptionsProvider(context.applicationContext)
    private val contextProvider = ParentEffectiveContextProvider.get(context.applicationContext)

    suspend fun load(): List<ParentLinkedChildOption> = optionsProvider.getOptions()
        .filter { it.deviceId.isNotBlank() }
        .distinctBy { option -> option.memberId?.takeIf(String::isNotBlank) ?: option.deviceId }
        .sortedBy { it.displayName.lowercase() }

    fun select(option: ParentLinkedChildOption) {
        contextProvider.updateSelection(
            focusedMemberId = option.memberId,
            targetDeviceId = option.deviceId
        )
    }
}
