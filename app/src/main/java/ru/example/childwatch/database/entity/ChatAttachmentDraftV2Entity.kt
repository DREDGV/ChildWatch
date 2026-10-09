package ru.example.childwatch.database.entity

import androidx.room.*

@Entity(tableName = "chat_attachment_drafts_v2", foreignKeys = [ForeignKey(
    entity = ChatConversationV2Entity::class, parentColumns = ["conversation_id"],
    childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["conversation_id"])])
data class ChatAttachmentDraftV2Entity(
    @PrimaryKey @ColumnInfo(name = "client_message_id") val clientMessageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "family_id") val familyId: String,
    @ColumnInfo(name = "actor_member_id") val actorMemberId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "attachment_type") val attachmentType: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "attachment_json") val attachmentJson: String? = null,
    @ColumnInfo(name = "state") val state: String = "DRAFT",
    @ColumnInfo(name = "progress_bytes") val progressBytes: Long = 0,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "caption") val caption: String = "",
    @ColumnInfo(name = "sender_name") val senderName: String = "",
    @ColumnInfo(name = "sender_role") val senderRole: String = "",
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
