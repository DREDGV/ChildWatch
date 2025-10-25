package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AudioRecording Entity - represents an audio recording in the database
 *
 * This entity stores metadata about audio recordings captured from child devices.
 * Recordings are linked to a specific child via foreign key relationship.
 */
@Entity(
    tableName = "audio_recordings",
    foreignKeys = [
        ForeignKey(
            entity = Child::class,
            parentColumns = ["id"],
            childColumns = ["child_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recording_id"], unique = true),
        Index(value = ["child_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["created_at"])
    ]
)
data class AudioRecording(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    @ColumnInfo(name = "child_id")
    val childId: Long,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_url")
    val fileUrl: String? = null,

    @ColumnInfo(name = "duration")
    val duration: Long, // in milliseconds

    @ColumnInfo(name = "file_size")
    val fileSize: Long, // in bytes

    @ColumnInfo(name = "format")
    val format: String = "aac", // aac, mp3, wav, etc.

    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int = 44100,

    @ColumnInfo(name = "bit_rate")
    val bitRate: Int = 128000,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    @ColumnInfo(name = "is_played")
    val isPlayed: Boolean = false,

    @ColumnInfo(name = "volume_mode")
    val volumeMode: String? = null, // normal, loud, silent, etc.

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
