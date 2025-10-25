package ru.example.childwatch.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.repository.ChatRepository
import ru.example.childwatch.database.repository.ChildRepository

/**
 * Новый ChatManager на основе Room Database
 *
 * Заменяет старый ChatManager, использовавший SharedPreferences.
 * Работает через ChatRepository и поддерживает реактивные обновления.
 */
class ChatManagerV2(context: Context, private val deviceId: String) {

    companion object {
        private const val TAG = "ChatManagerV2"
    }

    private val database = ChildWatchDatabase.getInstance(context)
    private val childRepository = ChildRepository(database.childDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())

    private var childId: Long? = null

    /**
     * Инициализация - получение или создание профиля ребенка
     */
    suspend fun initialize() {
        try {
            val child = childRepository.getOrCreateChild(deviceId, "Ребенок")
            childId = child.id
            Log.d(TAG, "ChatManagerV2 инициализирован для устройства $deviceId, childId=$childId")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации ChatManagerV2", e)
            throw e
        }
    }

    /**
     * Получить ID ребенка
     */
    fun getChildId(): Long {
        return childId ?: throw IllegalStateException("ChatManagerV2 не инициализирован. Вызовите initialize() сначала.")
    }

    /**
     * Сохранить сообщение
     */
    suspend fun saveMessage(message: ChatMessage) {
        try {
            val id = getChildId()
            chatRepository.insertMessage(message, id)
            Log.d(TAG, "Сообщение сохранено: ${message.text}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения сообщения", e)
            throw e
        }
    }

    /**
     * Получить все сообщения (обычный список)
     */
    suspend fun getAllMessages(): List<ChatMessage> {
        return try {
            val id = getChildId()
            val messages = chatRepository.getMessagesForChild(id, limit = 200, offset = 0)
            Log.d(TAG, "Загружено ${messages.size} сообщений")
            messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки сообщений", e)
            emptyList()
        }
    }

    /**
     * Получить сообщения как Flow (реактивное обновление)
     */
    fun getAllMessagesFlow(): Flow<List<ChatMessage>> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 не инициализирован")
        return chatRepository.getMessagesForChildFlow(id)
            .map { it.sortedBy { msg -> msg.timestamp } }
    }

    /**
     * Получить количество непрочитанных сообщений
     */
    suspend fun getUnreadCount(): Int {
        return try {
            val id = getChildId()
            chatRepository.getUnreadCount(id)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения количества непрочитанных", e)
            0
        }
    }

    /**
     * Получить количество непрочитанных сообщений (Flow)
     */
    fun getUnreadCountFlow(): Flow<Int> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 не инициализирован")
        return chatRepository.getUnreadCountFlow(id)
    }

    /**
     * Пометить сообщение как прочитанное
     */
    suspend fun markAsRead(messageId: String) {
        try {
            val id = getChildId()
            val message = chatRepository.getMessageByMessageId(messageId)
            if (message != null) {
                // Получаем внутренний ID из БД для пометки
                val entity = database.chatMessageDao().getByMessageId(messageId)
                entity?.let {
                    chatRepository.markAsRead(it.id)
                    Log.d(TAG, "Сообщение помечено как прочитанное: $messageId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пометки сообщения как прочитанного", e)
        }
    }

    /**
     * Пометить все сообщения как прочитанные
     */
    suspend fun markAllAsRead() {
        try {
            val id = getChildId()
            chatRepository.markAllAsRead(id)
            Log.d(TAG, "Все сообщения помечены как прочитанные")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пометки всех сообщений как прочитанных", e)
        }
    }

    /**
     * Обновить статус сообщения
     */
    suspend fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        try {
            chatRepository.updateMessageStatus(messageId, status.name.lowercase())
            Log.d(TAG, "Статус сообщения обновлен: $messageId -> $status")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления статуса сообщения", e)
        }
    }

    /**
     * Очистить все сообщения
     */
    suspend fun clearAllMessages() {
        try {
            val id = getChildId()
            chatRepository.deleteMessagesForChild(id)
            Log.d(TAG, "Все сообщения удалены")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки сообщений", e)
        }
    }

    /**
     * Поиск сообщений
     */
    suspend fun searchMessages(query: String, limit: Int = 50): List<ChatMessage> {
        return try {
            val id = getChildId()
            chatRepository.searchMessages(id, query, limit)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка поиска сообщений", e)
            emptyList()
        }
    }

    /**
     * Получить последнее сообщение
     */
    suspend fun getLatestMessage(): ChatMessage? {
        return try {
            val id = getChildId()
            chatRepository.getLatestMessage(id)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения последнего сообщения", e)
            null
        }
    }

    /**
     * Получить последнее сообщение (Flow)
     */
    fun getLatestMessageFlow(): Flow<ChatMessage?> {
        val id = childId ?: throw IllegalStateException("ChatManagerV2 не инициализирован")
        return chatRepository.getLatestMessageFlow(id)
    }

    /**
     * Очистка ресурсов
     */
    fun cleanup() {
        Log.d(TAG, "ChatManagerV2 cleanup completed")
    }
}
