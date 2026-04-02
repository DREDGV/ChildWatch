package ru.example.parentwatch.session

import android.content.Context

class ChildEffectiveContextResolver(context: Context) {

    private val store = ChildActiveSessionStore(context)

    fun getActiveSession(): ChildActiveSession? = store.getActiveSession()

    fun resolveEffectiveContext(): ChildEffectiveContext? = store.resolveEffectiveContext()

    fun resolveServerUrl(): String = store.resolveCurrentServerUrl()

    fun resolveChildDeviceId(): String = store.resolveCurrentChildId()

    fun resolveParentDeviceId(): String = store.resolveCurrentParentId()
}
