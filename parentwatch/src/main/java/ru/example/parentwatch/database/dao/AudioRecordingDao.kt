package ru.example.parentwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.parentwatch.database.entity.AudioRecording

/**
 * DAO for AudioRecording entity
 *
 * Provides database access methods for managing audio recordings.
 */
@Dao
interface AudioRecordingDao {

    /**
     * Insert a new recording
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: AudioRecording): Long

    /**
     * Insert multiple recordings
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recordings: List<AudioRecording>): List<Long>

    /**
     * Update an existing recording
     */
    @Update
    suspend fun update(recording: AudioRecording)

    /**
     * Delete a recording
     */
    @Delete
    suspend fun delete(recording: AudioRecording)

    /**
     * Get recording by ID
     */
    @Query("SELECT * FROM audio_recordings WHERE id = :id")
    suspend fun getById(id: Long): AudioRecording?

    /**
     * Get recording by recording ID
     */
    @Query("SELECT * FROM audio_recordings WHERE recording_id = :recordingId")
    suspend fun getByRecordingId(recordingId: String): AudioRecording?

    /**
     * Get all recordings for a child (paginated)
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecordingsForChild(childId: Long, limit: Int = 50, offset: Int = 0): List<AudioRecording>

    /**
     * Get all recordings for a child as Flow (reactive, latest 100)
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 100")
    fun getRecordingsForChildFlow(childId: Long): Flow<List<AudioRecording>>

    /**
     * Get downloaded recordings for a child
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId AND is_downloaded = 1 ORDER BY timestamp DESC")
    suspend fun getDownloadedRecordings(childId: Long): List<AudioRecording>

    /**
     * Get unplayed recordings for a child
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId AND is_played = 0 ORDER BY timestamp DESC")
    suspend fun getUnplayedRecordings(childId: Long): List<AudioRecording>

    /**
     * Get unplayed recordings count
     */
    @Query("SELECT COUNT(*) FROM audio_recordings WHERE child_id = :childId AND is_played = 0")
    suspend fun getUnplayedCount(childId: Long): Int

    /**
     * Get unplayed recordings count as Flow (reactive)
     */
    @Query("SELECT COUNT(*) FROM audio_recordings WHERE child_id = :childId AND is_played = 0")
    fun getUnplayedCountFlow(childId: Long): Flow<Int>

    /**
     * Mark recording as played
     */
    @Query("UPDATE audio_recordings SET is_played = 1 WHERE id = :recordingId")
    suspend fun markAsPlayed(recordingId: Long)

    /**
     * Mark recording as downloaded
     */
    @Query("UPDATE audio_recordings SET is_downloaded = 1 WHERE id = :recordingId")
    suspend fun markAsDownloaded(recordingId: Long)

    /**
     * Get latest recording for a child
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecording(childId: Long): AudioRecording?

    /**
     * Get latest recording as Flow (reactive)
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecordingFlow(childId: Long): Flow<AudioRecording?>

    /**
     * Get recordings by volume mode
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId AND volume_mode = :volumeMode ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordingsByVolumeMode(childId: Long, volumeMode: String, limit: Int = 50): List<AudioRecording>

    /**
     * Get recordings in time range
     */
    @Query("SELECT * FROM audio_recordings WHERE child_id = :childId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getRecordingsInTimeRange(childId: Long, startTime: Long, endTime: Long): List<AudioRecording>

    /**
     * Delete recordings for a child
     */
    @Query("DELETE FROM audio_recordings WHERE child_id = :childId")
    suspend fun deleteRecordingsForChild(childId: Long)

    /**
     * Delete old recordings (older than timestamp)
     */
    @Query("DELETE FROM audio_recordings WHERE timestamp < :timestamp")
    suspend fun deleteOldRecordings(timestamp: Long)

    /**
     * Delete all recordings
     */
    @Query("DELETE FROM audio_recordings")
    suspend fun deleteAll()

    /**
     * Get total recording count for a child
     */
    @Query("SELECT COUNT(*) FROM audio_recordings WHERE child_id = :childId")
    suspend fun getRecordingCount(childId: Long): Int

    /**
     * Get total duration of recordings for a child
     */
    @Query("SELECT SUM(duration) FROM audio_recordings WHERE child_id = :childId")
    suspend fun getTotalDuration(childId: Long): Long?

    /**
     * Get total file size of recordings for a child
     */
    @Query("SELECT SUM(file_size) FROM audio_recordings WHERE child_id = :childId")
    suspend fun getTotalFileSize(childId: Long): Long?
}

