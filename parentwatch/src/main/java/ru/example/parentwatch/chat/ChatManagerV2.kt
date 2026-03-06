package ru.example.parentwatch.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.repository.ChatRepository
import ru.example.parentwatch.database.repository.ChildRepository

/**
 * РќРѕРІС‹Р№ ChatManager РЅР° РѕСЃРЅРѕРІРµ Room Database
 *
 * Р—Р°РјРµРЅСЏРµС‚ СЃС‚Р°СЂС‹Р№ ChatManager, РёСЃРїРѕР»СЊР·РѕРІР°РІС€РёР№ SharedPreferences.
 * Р Р°Р±РѕС‚Р°РµС‚ С‡РµСЂРµР· ChatRepository Рё РїРѕРґРґРµСЂР¶РёРІР°РµС‚ СЂРµР°РєС‚РёРІРЅС‹Рµ РѕР±РЅРѕРІР»РµРЅРёСЏ.
 */
class ChatManagerV2(context: Context, private val deviceId: String) {

    companion object {
        private const val TAG = "ChatManagerV2"
        private const val INCOMING_SENDER = "parent"
    }

    private val database = ParentWatchDatabase.getInstance(context)
    private val childRepository = ChildRepository(database.childDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())

    private var childId: Long? = null

    /**
     * РРЅРёС†РёР°Р»РёР·Р°С†РёСЏ - РїРѕР»СѓС‡РµРЅРёРµ РёР»Рё СЃРѕР·РґР°РЅРёРµ РїСЂРѕС„РёР»СЏ СЂРµР±РµРЅРєР°
     */
    suspend fun initialize() {
        try {
            val child = childRepository.getOrCreateChild(deviceId, "Р РµР±РµРЅРѕРє")
            childId = child.id
            Log.d(TAG, "ChatManagerV2 РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ РґР»СЏ СѓСЃС‚СЂРѕР№СЃС‚РІР° $deviceId, childId=$childId")
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РёРЅРёС†РёР°Р»РёР·Р°С†РёРё ChatManagerV2", e)
            throw e
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ ID СЂРµР±РµРЅРєР°
     */
    fun getChildId(): Long {
        return childId ?: throw IllegalStateException("ChatManagerV2 РЅРµ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ. Р’С‹Р·РѕРІРёС‚Рµ initialize() СЃРЅР°С‡Р°Р»Р°.")
    }

    /**
     * РЎРѕС…СЂР°РЅРёС‚СЊ СЃРѕРѕР±С‰РµРЅРёРµ
     */
    suspend fun saveMessage(message: ChatMessage) {
        try {
            val id = getChildId()
            chatRepository.insertMessage(message, id)
            Log.d(TAG, "РЎРѕРѕР±С‰РµРЅРёРµ СЃРѕС…СЂР°РЅРµРЅРѕ: ${message.text}")
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° СЃРѕС…СЂР°РЅРµРЅРёСЏ СЃРѕРѕР±С‰РµРЅРёСЏ", e)
            throw e
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ РІСЃРµ СЃРѕРѕР±С‰РµРЅРёСЏ (РѕР±С‹С‡РЅС‹Р№ СЃРїРёСЃРѕРє)
     */
    suspend fun getAllMessages(): List<ChatMessage> {
        return try {
            val id = getChildId()
            val messages = chatRepository.getMessagesForChild(id, limit = 200, offset = 0)
            Log.d(TAG, "Р—Р°РіСЂСѓР¶РµРЅРѕ ${messages.size} СЃРѕРѕР±С‰РµРЅРёР№")
            messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° Р·Р°РіСЂСѓР·РєРё СЃРѕРѕР±С‰РµРЅРёР№", e)
            emptyList()
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ СЃРѕРѕР±С‰РµРЅРёСЏ РєР°Рє Flow (СЂРµР°РєС‚РёРІРЅРѕРµ РѕР±РЅРѕРІР»РµРЅРёРµ)
     */
    fun getAllMessagesFlow(): Flow<List<ChatMessage>> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 РЅРµ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ")
        return chatRepository.getMessagesForChildFlow(id)
            .map { it.sortedBy { msg -> msg.timestamp } }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ РєРѕР»РёС‡РµСЃС‚РІРѕ РЅРµРїСЂРѕС‡РёС‚Р°РЅРЅС‹С… СЃРѕРѕР±С‰РµРЅРёР№
     */
    suspend fun getUnreadCount(): Int {
        return try {
            val id = getChildId()
            chatRepository.getUnreadCountBySender(id, INCOMING_SENDER)
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РїРѕР»СѓС‡РµРЅРёСЏ РєРѕР»РёС‡РµСЃС‚РІР° РЅРµРїСЂРѕС‡РёС‚Р°РЅРЅС‹С…", e)
            0
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ РєРѕР»РёС‡РµСЃС‚РІРѕ РЅРµРїСЂРѕС‡РёС‚Р°РЅРЅС‹С… СЃРѕРѕР±С‰РµРЅРёР№ (Flow)
     */
    fun getUnreadCountFlow(): Flow<Int> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 РЅРµ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ")
        return chatRepository.getUnreadCountFlowBySender(id, INCOMING_SENDER)
    }

    /**
     * РџРѕРјРµС‚РёС‚СЊ СЃРѕРѕР±С‰РµРЅРёРµ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅРѕРµ
     */
    suspend fun markAsRead(messageId: String) {
        try {
            val id = getChildId()
            val message = chatRepository.getMessageByMessageId(messageId)
            if (message != null) {
                // РџРѕР»СѓС‡Р°РµРј РІРЅСѓС‚СЂРµРЅРЅРёР№ ID РёР· Р‘Р” РґР»СЏ РїРѕРјРµС‚РєРё
                val entity = database.chatMessageDao().getByMessageId(messageId)
                entity?.let {
                    chatRepository.markAsRead(it.id)
                    Log.d(TAG, "РЎРѕРѕР±С‰РµРЅРёРµ РїРѕРјРµС‡РµРЅРѕ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅРѕРµ: $messageId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РїРѕРјРµС‚РєРё СЃРѕРѕР±С‰РµРЅРёСЏ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅРѕРіРѕ", e)
        }
    }

    /**
     * РџРѕРјРµС‚РёС‚СЊ РІСЃРµ СЃРѕРѕР±С‰РµРЅРёСЏ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅС‹Рµ
     */
    suspend fun markAllAsRead() {
        try {
            val id = getChildId()
            chatRepository.markAllAsRead(id)
            Log.d(TAG, "Р’СЃРµ СЃРѕРѕР±С‰РµРЅРёСЏ РїРѕРјРµС‡РµРЅС‹ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅС‹Рµ")
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РїРѕРјРµС‚РєРё РІСЃРµС… СЃРѕРѕР±С‰РµРЅРёР№ РєР°Рє РїСЂРѕС‡РёС‚Р°РЅРЅС‹С…", e)
        }
    }

    /**
     * РћР±РЅРѕРІРёС‚СЊ СЃС‚Р°С‚СѓСЃ СЃРѕРѕР±С‰РµРЅРёСЏ
     */
    suspend fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        try {
            chatRepository.updateMessageStatus(messageId, status.name.lowercase())
            Log.d(TAG, "РЎС‚Р°С‚СѓСЃ СЃРѕРѕР±С‰РµРЅРёСЏ РѕР±РЅРѕРІР»РµРЅ: $messageId -> $status")
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РѕР±РЅРѕРІР»РµРЅРёСЏ СЃС‚Р°С‚СѓСЃР° СЃРѕРѕР±С‰РµРЅРёСЏ", e)
        }
    }

    /**
     * РћС‡РёСЃС‚РёС‚СЊ РІСЃРµ СЃРѕРѕР±С‰РµРЅРёСЏ
     */
    suspend fun clearAllMessages() {
        try {
            val id = getChildId()
            chatRepository.deleteMessagesForChild(id)
            Log.d(TAG, "Р’СЃРµ СЃРѕРѕР±С‰РµРЅРёСЏ СѓРґР°Р»РµРЅС‹")
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РѕС‡РёСЃС‚РєРё СЃРѕРѕР±С‰РµРЅРёР№", e)
        }
    }

    /**
     * РџРѕРёСЃРє СЃРѕРѕР±С‰РµРЅРёР№
     */
    suspend fun searchMessages(query: String, limit: Int = 50): List<ChatMessage> {
        return try {
            val id = getChildId()
            chatRepository.searchMessages(id, query, limit)
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РїРѕРёСЃРєР° СЃРѕРѕР±С‰РµРЅРёР№", e)
            emptyList()
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ РїРѕСЃР»РµРґРЅРµРµ СЃРѕРѕР±С‰РµРЅРёРµ
     */
    suspend fun getLatestMessage(): ChatMessage? {
        return try {
            val id = getChildId()
            chatRepository.getLatestMessage(id)
        } catch (e: Exception) {
            Log.e(TAG, "РћС€РёР±РєР° РїРѕР»СѓС‡РµРЅРёСЏ РїРѕСЃР»РµРґРЅРµРіРѕ СЃРѕРѕР±С‰РµРЅРёСЏ", e)
            null
        }
    }

    /**
     * РџРѕР»СѓС‡РёС‚СЊ РїРѕСЃР»РµРґРЅРµРµ СЃРѕРѕР±С‰РµРЅРёРµ (Flow)
     */
    fun getLatestMessageFlow(): Flow<ChatMessage?> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 РЅРµ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ")
        return chatRepository.getLatestMessageFlow(id)
    }

    /**
     * РћС‡РёСЃС‚РєР° СЂРµСЃСѓСЂСЃРѕРІ
     */
    fun cleanup() {
        Log.d(TAG, "ChatManagerV2 cleanup completed")
    }
}

