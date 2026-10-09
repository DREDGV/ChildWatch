package ru.example.childwatch.database.dao
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ChatAttachmentDraftV2Entity
@Dao interface ChatAttachmentDraftV2Dao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(draft: ChatAttachmentDraftV2Entity): Long
    @Update suspend fun update(draft: ChatAttachmentDraftV2Entity): Int
    @Query("SELECT * FROM chat_attachment_drafts_v2 WHERE client_message_id = :id") suspend fun get(id: String): ChatAttachmentDraftV2Entity?
    @Query("SELECT * FROM chat_attachment_drafts_v2 WHERE conversation_id = :id ORDER BY created_at") fun observe(id: String): Flow<List<ChatAttachmentDraftV2Entity>>
    @Query("SELECT * FROM chat_attachment_drafts_v2 WHERE state IN ('QUEUED','UPLOADING','UPLOADED','RETRY') AND next_attempt_at <= :now AND server_url = :server AND family_id = :family AND actor_member_id = :actor AND device_id = :device ORDER BY created_at LIMIT 10")
    suspend fun pending(server: String, family: String, actor: String, device: String, now: Long): List<ChatAttachmentDraftV2Entity>
}
