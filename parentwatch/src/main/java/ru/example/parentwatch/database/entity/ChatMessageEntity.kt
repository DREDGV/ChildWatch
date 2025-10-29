package ru.example.parentwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.example.parentwatch.chat.ChatMessage

/**
 * ChatMessage Entity - represents a chat message in the database
 *
 * This entity stores chat messages exchanged between parents and children.
 * Messages are linked to a specific child via foreign key relationship.
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = Child::class,
            parentColumns = ["id"],
            childColumns = ["child_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"], unique = true),
        Index(value = ["child_id"]),
        Index(value = ["sender"]),
        Index(value = ["timestamp"]),
        Index(value = ["is_read"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "child_id")
    val childId: Long,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "sender")
    val sender: String, // "child" or "parent"

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = "sent", // sending, sent, delivered, read, failed

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to ChatMessage domain model
     */
    fun toChatMessage(): ChatMessage {
        return ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = isRead,
            status = ChatMessage.statusFromServer(status)
        )
    }

    companion object {
        /**
         * Create from ChatMessage domain model
         */
        fun fromChatMessage(message: ChatMessage, childId: Long): ChatMessageEntity {
            return ChatMessageEntity(
                messageId = message.id,
                childId = childId,
                text = message.text,
                sender = message.sender,
                timestamp = message.timestamp,
                isRead = message.isRead,
                status = message.statusToServerValue()
            )
        }
    }
}

