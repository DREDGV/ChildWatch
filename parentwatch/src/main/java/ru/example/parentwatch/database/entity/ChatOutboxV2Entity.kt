package ru.example.parentwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_outbox_v2",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationV2Entity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["client_message_id"], unique = true),
        Index(value = ["message_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["state", "next_attempt_at"])
    ]
)
data class ChatOutboxV2Entity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "outbox_id") val outboxId: Long = 0,
    @ColumnInfo(name = "client_message_id") val clientMessageId: String,
    @ColumnInfo(name = "message_id") val messageId: String? = null,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "client_sent_at") val clientSentAt: Long,
    @ColumnInfo(name = "state") val state: String = STATE_PENDING,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "lease_token") val leaseToken: String? = null,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long? = null,
    @ColumnInfo(name = "priority") val priority: Int = 0
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SENDING = "SENDING"
        const val STATE_RETRY = "RETRY"
        const val STATE_SENT = "SENT"
        const val STATE_FAILED = "FAILED"
    }
}
