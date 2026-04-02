package ru.example.parentwatch.session

data class ChildEffectiveContext(
    val serverUrl: String,
    val ownChildDeviceId: String,
    val linkedParentDeviceId: String,
    val activeSessionId: String?,
    val source: Source,
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class Source {
        ACTIVE_SESSION,
        CURRENT_SESSION,
        LEGACY_PREFS,
        FALLBACK
    }
}
