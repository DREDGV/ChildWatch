package ru.example.parentwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Сообщение чата с привязкой к ребенку
 * Поддерживает историю до 1000+ сообщений
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
        Index(value = ["child_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["message_id"], unique = true)
    ]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "child_id")
    val childId: Long,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "sender_type")
    val senderType: String, // "parent" or "child"

    @ColumnInfo(name = "sender_name")
    val senderName: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "status")
    val status: String, // "sending", "sent", "delivered", "read", "failed"

    @ColumnInfo(name = "is_from_parent")
    val isFromParent: Boolean
)

