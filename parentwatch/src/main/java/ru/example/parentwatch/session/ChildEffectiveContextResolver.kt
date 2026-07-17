package ru.example.parentwatch.session

import android.content.Context

class ChildEffectiveContextResolver(context: Context) {

    private val store = ChildActiveSessionStore(context)
    private val provider = ChildEffectiveContextProvider.get(context)

    fun getActiveSession(): ChildActiveSession? = store.getActiveSession()

    fun resolveEffectiveContext(): ChildEffectiveContext? {
        val active = provider.current() ?: return store.resolveEffectiveContext()
        return ChildEffectiveContext(
            serverUrl = active.serverUrl,
            ownChildDeviceId = active.selfDeviceId,
            linkedParentDeviceId = active.targetDeviceId.orEmpty(),
            activeSessionId = store.getActiveSessionId(),
            source = when (active.source) {
                ru.childwatch.shared.family.ContextSource.CANONICAL -> ChildEffectiveContext.Source.ACTIVE_SESSION
                ru.childwatch.shared.family.ContextSource.ACTIVE_SESSION -> ChildEffectiveContext.Source.ACTIVE_SESSION
                ru.childwatch.shared.family.ContextSource.SECURE_SETTINGS -> ChildEffectiveContext.Source.CURRENT_SESSION
                ru.childwatch.shared.family.ContextSource.LEGACY_MIGRATION -> ChildEffectiveContext.Source.LEGACY_PREFS
            },
            updatedAt = active.updatedAt
        )
    }

    fun resolveServerUrl(): String = provider.current()?.serverUrl ?: store.resolveCurrentServerUrl()

    fun resolveChildDeviceId(): String = provider.current()?.selfDeviceId ?: store.resolveCurrentChildId()

    fun resolveParentDeviceId(): String = provider.current()?.targetDeviceId.orEmpty()
        .ifBlank { store.resolveCurrentParentId() }
}
