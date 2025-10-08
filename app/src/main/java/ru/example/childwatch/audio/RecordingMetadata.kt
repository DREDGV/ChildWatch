package ru.example.childwatch.audio

data class RecordingMetadata(
    val id: String,
    val fileName: String,
    val filePath: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long
)
