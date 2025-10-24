package ru.example.childwatch.chat

import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class representing a chat message
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: String, // "child" or "parent"
    val timestamp: Long,
    val isRead: Boolean = false,
    val status: MessageStatus = MessageStatus.SENT
) {
    enum class MessageStatus {
        SENDING,
        SENT,
        DELIVERED,
        READ,
        FAILED
    }

    fun statusToServerValue(): String = when (status) {
        MessageStatus.SENDING -> "sending"
        MessageStatus.SENT -> "sent"
        MessageStatus.DELIVERED -> "delivered"
        MessageStatus.READ -> "read"
        MessageStatus.FAILED -> "failed"
    }

    /**
     * Get formatted timestamp
     */
    fun getFormattedTime(): String {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Get sender display name
     */
    fun getSenderName(): String {
        return when (sender) {
            "child" -> "Ребенок"
            "parent" -> "Родитель"
            else -> "Неизвестно"
        }
    }
    
    /**
     * Check if message is from child
     */
    fun isFromChild(): Boolean = sender == "child"
    
    /**
     * Check if message is from parent
     */
    fun isFromParent(): Boolean = sender == "parent"

    companion object {
        fun statusFromServer(value: String?): MessageStatus {
            return when (value?.lowercase(Locale.US)) {
                "sending" -> MessageStatus.SENDING
                "sent" -> MessageStatus.SENT
                "delivered" -> MessageStatus.DELIVERED
                "read" -> MessageStatus.READ
                "failed" -> MessageStatus.FAILED
                else -> MessageStatus.SENT
            }
        }

        fun fromJson(json: org.json.JSONObject): ChatMessage {
            val status = statusFromServer(json.optString("status", null))

            return ChatMessage(
                id = json.getString("id"),
                text = json.getString("text"),
                sender = json.getString("sender"),
                timestamp = json.getLong("timestamp"),
                isRead = json.optBoolean("isRead", status == MessageStatus.READ),
                status = status
            )
        }
    }
}

fun ChatMessage.withStatus(newStatus: ChatMessage.MessageStatus): ChatMessage {
    return if (status == newStatus && (newStatus != ChatMessage.MessageStatus.READ || isRead)) {
        this
    } else {
        copy(
            status = newStatus,
            isRead = if (newStatus == ChatMessage.MessageStatus.READ) true else isRead
        )
    }
}
