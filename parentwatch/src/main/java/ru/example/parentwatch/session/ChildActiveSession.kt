package ru.example.parentwatch.session

import java.util.UUID

data class ChildActiveSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val ownChildDeviceId: String,
    val linkedParentDeviceId: String,
    /**
     * Portable avatar preset for this profile, for example `preset:corgi`.
     *
     * Nullable with a default so sessions written by older builds keep loading;
     * when it is absent the family directory is used, then the fallback icon.
     */
    val avatarKey: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
