package ru.example.parentwatch.database.dao
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.parentwatch.database.entity.ChatTranscriptionV2Entity
@Dao interface ChatTranscriptionV2Dao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: ChatTranscriptionV2Entity): Long
    @Update suspend fun update(row: ChatTranscriptionV2Entity): Int
    @Query("SELECT * FROM chat_transcriptions_v2 WHERE scope_key = :scope AND draft_id = :id")
    suspend fun get(scope: String, id: String): ChatTranscriptionV2Entity?
    @Query("SELECT * FROM chat_transcriptions_v2 WHERE scope_key = :scope AND draft_id = :id")
    fun observe(scope: String, id: String): Flow<ChatTranscriptionV2Entity?>
    @Query("UPDATE chat_transcriptions_v2 SET edited_text = :text, is_edited = 1 WHERE scope_key = :scope AND draft_id = :id AND state = 'SUCCEEDED' AND (error_code IS NULL OR error_code != 'TRANSCRIPTION_ACCESS_DENIED')")
    suspend fun edit(scope: String, id: String, text: String): Int
    @Query("UPDATE chat_transcriptions_v2 SET job_id = :jobId, state = :state, text = :text, error_code = :error, created_at = :createdAt, updated_at = :updatedAt, completed_at = :completedAt WHERE scope_key = :scope AND draft_id = :id")
    suspend fun updateJob(scope: String, id: String, jobId: String, state: String, text: String?, error: String?, createdAt: Long, updatedAt: Long, completedAt: Long?): Int
    @Query("UPDATE chat_transcriptions_v2 SET error_code = :error WHERE scope_key = :scope AND draft_id = :id")
    suspend fun setError(scope: String, id: String, error: String): Int
    @Query("UPDATE chat_transcriptions_v2 SET state = :state, error_code = NULL WHERE scope_key = :scope AND draft_id = :id")
    suspend fun setState(scope: String, id: String, state: String): Int
    @Query("UPDATE chat_transcriptions_v2 SET text = NULL, edited_text = NULL, is_edited = 0, state = 'FAILED', error_code = 'TRANSCRIPTION_ACCESS_DENIED' WHERE scope_key = :scope AND draft_id = :id")
    suspend fun deny(scope: String, id: String): Int
    @Query("UPDATE chat_transcriptions_v2 SET text_message_id = :messageId WHERE scope_key = :scope AND draft_id = :id AND state = 'SUCCEEDED' AND (text_message_id IS NULL OR text_message_id = :messageId) AND (error_code IS NULL OR error_code != 'TRANSCRIPTION_ACCESS_DENIED')")
    suspend fun markTextEnqueued(scope: String, id: String, messageId: String): Int
}
