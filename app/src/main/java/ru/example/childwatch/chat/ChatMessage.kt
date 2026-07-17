package ru.example.childwatch.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: String,
    val text: String,
    val sender: String,
    val authorDeviceId: String? = null,
    val authorDisplayName: String? = null,
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

    fun getFormattedTime(): String {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun getSenderName(): String {
        val explicitName = authorDisplayName?.trim().orEmpty()
        if (explicitName.isNotEmpty()) {
            return explicitName
        }
        return when (sender) {
            "child" -> "Ребенок"
            "parent" -> "Родитель"
            else -> "Неизвестно"
        }
    }

    fun isFromChild(): Boolean = sender == "child"

    fun isFromParent(): Boolean = sender == "parent"

    fun isOutgoing(currentRole: String, ownDeviceId: String?): Boolean {
        if (sender != currentRole) {
            return false
        }

        val normalizedOwnId = ownDeviceId?.trim().orEmpty()
        val normalizedAuthorId = authorDeviceId?.trim().orEmpty()

        if (normalizedOwnId.isBlank()) {
            return sender == "child"
        }

        if (normalizedAuthorId.isBlank()) {
            return sender == "child"
        }

        return normalizedOwnId == normalizedAuthorId
    }

    fun isIncoming(currentRole: String, ownDeviceId: String?): Boolean {
        return !isOutgoing(currentRole, ownDeviceId)
    }

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
                authorDeviceId = json.optString("authorDeviceId", json.optString("senderDeviceId", null))
                    .takeIf { it.isNotBlank() },
                authorDisplayName = json.optString("authorDisplayName", json.optString("senderDisplayName", null))
                    .takeIf { it.isNotBlank() },
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
