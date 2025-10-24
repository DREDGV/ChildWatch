package ru.example.childwatch.chat

import android.content.Context
import android.util.Log
import ru.example.childwatch.utils.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager for chat messages storage and retrieval
 */
class ChatManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ChatManager"
        private const val MESSAGES_KEY = "chat_messages"
        private const val MAX_MESSAGES = 100 // Максимум сообщений в истории
    }
    
    private val securePreferences = SecurePreferences(context, "chat_prefs")
    
    /**
     * Save a message to storage
     */
    fun saveMessage(message: ChatMessage) {
        try {
            val messages = getAllMessages()
                .filterNot { it.id == message.id }
                .toMutableList()

            messages.add(message)
            val limited = messages
                .sortedBy { it.timestamp }
                .takeLast(MAX_MESSAGES)

            persistMessages(limited)
            Log.d(TAG, "Message saved: ${message.text}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving message", e)
        }
    }
    
    /**
     * Get all messages from storage
     */
    fun getAllMessages(): List<ChatMessage> {
        try {
            val messagesJson = securePreferences.getString(MESSAGES_KEY, "[]")
            val jsonArray = JSONArray(messagesJson)
            val messages = mutableListOf<ChatMessage>()
            
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                messages.add(ChatMessage.fromJson(jsonObject))
            }
            
            Log.d(TAG, "Loaded ${messages.size} messages")
            return messages.sortedBy { it.timestamp }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading messages", e)
            return emptyList()
        }
    }
    
    /**
     * Clear all messages
     */
    fun clearAllMessages() {
        try {
            securePreferences.remove(MESSAGES_KEY)
            Log.d(TAG, "All messages cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing messages", e)
        }
    }
    
    /**
     * Mark message as read
     */
    fun markAsRead(messageId: String) {
        try {
            updateMessageStatus(messageId, ChatMessage.MessageStatus.READ)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as read", e)
        }
    }
    
    /**
     * Get unread messages count (from child)
     */
    fun getUnreadCount(): Int {
        return getAllMessages().count { !it.isRead && it.isFromChild() }
    }

    /**
     * Mark all messages as read
     */
    fun markAllAsRead() {
        try {
            val messages = getAllMessages()
                .map { it.withStatus(ChatMessage.MessageStatus.READ) }
            persistMessages(messages)
            Log.d(TAG, "All messages marked as read")

        } catch (e: Exception) {
            Log.e(TAG, "Error marking all messages as read", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // В данном случае ничего не нужно очищать
        Log.d(TAG, "ChatManager cleanup completed")
    }

    fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        try {
            val updated = getAllMessages().map { msg ->
                if (msg.id == messageId) msg.withStatus(status) else msg
            }
            persistMessages(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating message status", e)
        }
    }

    private fun persistMessages(messages: List<ChatMessage>) {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            val jsonObject = JSONObject().apply {
                put("id", msg.id)
                put("text", msg.text)
                put("sender", msg.sender)
                put("timestamp", msg.timestamp)
                put("isRead", msg.isRead)
                put("status", msg.status.name)
            }
            jsonArray.put(jsonObject)
        }

        securePreferences.putString(MESSAGES_KEY, jsonArray.toString())
    }
}
