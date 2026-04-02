package ru.example.parentwatch.session

import java.util.UUID

data class ChildActiveSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val ownChildDeviceId: String,
    val linkedParentDeviceId: String,
    val updatedAt: Long = System.currentTimeMillis()
)
