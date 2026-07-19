package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages_v2",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationV2Entity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"], unique = true),
        Index(value = ["server_message_id"], unique = true),
        Index(value = ["client_message_id"], unique = true),
        Index(value = ["conversation_id"]),
        Index(value = ["conversation_id", "sent_at"]),
        Index(value = ["conversation_id", "server_sequence"]),
        Index(value = ["sender_member_id"]),
        Index(value = ["status"]),
        Index(value = ["is_read"])
    ]
)
data class ChatMessageV2Entity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "server_message_id")
    val serverMessageId: String? = null,

    @ColumnInfo(name = "client_message_id")
    val clientMessageId: String? = null,

    @ColumnInfo(name = "server_sequence")
    val serverSequence: Long? = null,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "legacy_row_id")
    val legacyRowId: Long? = null,

    @ColumnInfo(name = "sender_member_id")
    val senderMemberId: String? = null,

    @ColumnInfo(name = "sender_device_id")
    val senderDeviceId: String? = null,

    @ColumnInfo(name = "sender_display_name")
    val senderDisplayName: String? = null,

    @ColumnInfo(name = "sender_role")
    val senderRole: String? = null,

    @ColumnInfo(name = "legacy_sender")
    val legacySender: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "message_type")
    val messageType: String = TYPE_TEXT,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long,

    @ColumnInfo(name = "client_sent_at")
    val clientSentAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "server_created_at")
    val serverCreatedAt: Long? = null,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "delivery_state")
    val deliveryState: String,

    @ColumnInfo(name = "failure_code")
    val failureCode: String? = null,

    @ColumnInfo(name = "legacy_message_id")
    val legacyMessageId: String? = null,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "read_at")
    val readAt: Long? = null,

    @ColumnInfo(name = "edited_at")
    val editedAt: Long? = null,

    @ColumnInfo(name = "reply_to_message_id")
    val replyToMessageId: String? = null,

    @ColumnInfo(name = "sync_state")
    val syncState: String = SYNC_STATE_LOCAL_ONLY
) {
    companion object {
        const val TYPE_TEXT = "TEXT"

        const val SYNC_STATE_LOCAL_ONLY = "LOCAL_ONLY"
        const val SYNC_STATE_MIGRATED = "MIGRATED"
        const val SYNC_STATE_SYNCED = "SYNCED"
        const val SYNC_STATE_FAILED = "FAILED"
    }
}
