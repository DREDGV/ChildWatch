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
            val messages = getAllMessages().toMutableList()
            
            // Добавляем новое сообщение
            messages.add(message)
            
            // Ограничиваем количество сообщений
            if (messages.size > MAX_MESSAGES) {
                messages.removeAt(0) // Удаляем самое старое
            }
            
            // Сохраняем в JSON формате
            val jsonArray = JSONArray()
            messages.forEach { msg ->
                val jsonObject = JSONObject().apply {
                    put("id", msg.id)
                    put("text", msg.text)
                    put("sender", msg.sender)
                    put("timestamp", msg.timestamp)
                    put("isRead", msg.isRead)
                }
                jsonArray.put(jsonObject)
            }
            
            securePreferences.putString(MESSAGES_KEY, jsonArray.toString())
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
                val message = ChatMessage(
                    id = jsonObject.getString("id"),
                    text = jsonObject.getString("text"),
                    sender = jsonObject.getString("sender"),
                    timestamp = jsonObject.getLong("timestamp"),
                    isRead = jsonObject.getBoolean("isRead")
                )
                messages.add(message)
            }
            
            Log.d(TAG, "Loaded ${messages.size} messages")
            return messages
            
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
            val messages = getAllMessages().toMutableList()
            val messageIndex = messages.indexOfFirst { it.id == messageId }
            
            if (messageIndex != -1) {
                val message = messages[messageIndex]
                val updatedMessage = message.copy(isRead = true)
                messages[messageIndex] = updatedMessage
                
                // Сохраняем обновленный список
                val jsonArray = JSONArray()
                messages.forEach { msg ->
                    val jsonObject = JSONObject().apply {
                        put("id", msg.id)
                        put("text", msg.text)
                        put("sender", msg.sender)
                        put("timestamp", msg.timestamp)
                        put("isRead", msg.isRead)
                    }
                    jsonArray.put(jsonObject)
                }
                
                securePreferences.putString(MESSAGES_KEY, jsonArray.toString())
                Log.d(TAG, "Message marked as read: $messageId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as read", e)
        }
    }
    
    /**
     * Get unread messages count
     */
    fun getUnreadCount(): Int {
        return getAllMessages().count { !it.isRead && it.isFromParent() }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // В данном случае ничего не нужно очищать
        Log.d(TAG, "ChatManager cleanup completed")
    }
}
