package ru.example.parentwatch.chat

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
    val isRead: Boolean = false
) {
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
}
