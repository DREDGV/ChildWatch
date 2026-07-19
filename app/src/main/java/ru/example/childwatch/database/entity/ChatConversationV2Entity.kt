package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local projection of a server conversation.
 *
 * The v2 suffix is intentional: the legacy [ChatMessageEntity] table remains
 * available while the conversation based chat is rolled out incrementally.
 */
@Entity(
    tableName = "chat_conversations_v2",
    indices = [
        Index(value = ["server_conversation_id"], unique = true),
        Index(value = ["family_id"]),
        Index(value = ["type"]),
        Index(value = ["legacy_child_id"], unique = true),
        Index(value = ["last_message_at"])
    ]
)
data class ChatConversationV2Entity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "server_conversation_id")
    val serverConversationId: String? = null,

    @ColumnInfo(name = "family_id")
    val familyId: String? = null,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "legacy_child_id")
    val legacyChildId: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: Long? = null,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,

    @ColumnInfo(name = "last_sequence")
    val lastSequence: Long = 0,

    @ColumnInfo(name = "last_read_sequence")
    val lastReadSequence: Long = 0,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    @ColumnInfo(name = "last_read_at")
    val lastReadAt: Long? = null,

    @ColumnInfo(name = "muted_until")
    val mutedUntil: Long? = null,

    @ColumnInfo(name = "muted")
    val muted: Boolean = false,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "sync_state")
    val syncState: String = SYNC_STATE_LOCAL_ONLY
) {
    companion object {
        const val TYPE_FAMILY = "FAMILY"
        const val TYPE_DIRECT = "DIRECT"

        const val SYNC_STATE_LOCAL_ONLY = "LOCAL_ONLY"
        const val SYNC_STATE_MIGRATED = "MIGRATED"
        const val SYNC_STATE_SYNCED = "SYNCED"
    }
}
