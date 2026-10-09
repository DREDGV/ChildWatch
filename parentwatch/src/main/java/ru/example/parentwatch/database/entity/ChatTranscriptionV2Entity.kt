package ru.example.parentwatch.database.entity

import androidx.room.*
import ru.childwatch.shared.chat.ChatTranscriptionPolicy

@Entity(tableName = "chat_transcriptions_v2", primaryKeys = ["scope_key", "draft_id"],
    foreignKeys = [ForeignKey(entity = ChatConversationV2Entity::class,
        parentColumns = ["conversation_id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversation_id"])])
data class ChatTranscriptionV2Entity(
    @ColumnInfo(name = "scope_key") val scopeKey: String,
    @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "family_id") val familyId: String,
    @ColumnInfo(name = "actor_member_id") val actorMemberId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String,
    @ColumnInfo(name = "job_id") val jobId: String? = null,
    @ColumnInfo(name = "state") val state: String = "LOCAL_PENDING",
    @ColumnInfo(name = "text") val text: String? = null,
    @ColumnInfo(name = "edited_text") val editedText: String? = null,
    @ColumnInfo(name = "is_edited") val isEdited: Boolean = false,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "text_message_id") val textMessageId: String? = null
) {
    fun editorText(): String = ChatTranscriptionPolicy.editorText(isEdited, editedText, text)
}
