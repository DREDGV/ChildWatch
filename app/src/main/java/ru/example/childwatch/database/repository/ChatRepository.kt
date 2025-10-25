package ru.example.childwatch.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.database.dao.ChatMessageDao
import ru.example.childwatch.database.entity.ChatMessageEntity

/**
 * Repository для работы с сообщениями чата
 *
 * Предоставляет единую точку доступа к данным чата,
 * конвертируя между Entity и domain моделями.
 */
class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    /**
     * Получить сообщение по ID
     */
    suspend fun getMessageById(id: Long): ChatMessage? {
        return chatMessageDao.getById(id)?.toChatMessage()
    }

    /**
     * Получить сообщение по messageId
     */
    suspend fun getMessageByMessageId(messageId: String): ChatMessage? {
        return chatMessageDao.getByMessageId(messageId)?.toChatMessage()
    }

    /**
     * Получить сообщения для ребенка (с пагинацией)
     */
    suspend fun getMessagesForChild(childId: Long, limit: Int = 50, offset: Int = 0): List<ChatMessage> {
        return chatMessageDao.getMessagesForChild(childId, limit, offset)
            .map { it.toChatMessage() }
    }

    /**
     * Получить сообщения для ребенка (Flow)
     */
    fun getMessagesForChildFlow(childId: Long): Flow<List<ChatMessage>> {
        return chatMessageDao.getMessagesForChildFlow(childId)
            .map { list -> list.map { it.toChatMessage() } }
    }

    /**
     * Получить непрочитанные сообщения
     */
    suspend fun getUnreadMessages(childId: Long): List<ChatMessage> {
        return chatMessageDao.getUnreadMessages(childId)
            .map { it.toChatMessage() }
    }

    /**
     * Получить количество непрочитанных сообщений
     */
    suspend fun getUnreadCount(childId: Long): Int {
        return chatMessageDao.getUnreadCount(childId)
    }

    /**
     * Получить количество непрочитанных сообщений (Flow)
     */
    fun getUnreadCountFlow(childId: Long): Flow<Int> {
        return chatMessageDao.getUnreadCountFlow(childId)
    }

    /**
     * Добавить сообщение
     */
    suspend fun insertMessage(message: ChatMessage, childId: Long): Long {
        val entity = ChatMessageEntity.fromChatMessage(message, childId)
        return chatMessageDao.insert(entity)
    }

    /**
     * Добавить несколько сообщений
     */
    suspend fun insertMessages(messages: List<ChatMessage>, childId: Long): List<Long> {
        val entities = messages.map { ChatMessageEntity.fromChatMessage(it, childId) }
        return chatMessageDao.insertAll(entities)
    }

    /**
     * Обновить сообщение
     */
    suspend fun updateMessage(message: ChatMessage, childId: Long) {
        val entity = ChatMessageEntity.fromChatMessage(message, childId)
        chatMessageDao.update(entity)
    }

    /**
     * Пометить сообщение как прочитанное
     */
    suspend fun markAsRead(messageId: Long) {
        chatMessageDao.markAsRead(messageId)
    }

    /**
     * Пометить все сообщения как прочитанные
     */
    suspend fun markAllAsRead(childId: Long) {
        chatMessageDao.markAllAsRead(childId)
    }

    /**
     * Обновить статус сообщения
     */
    suspend fun updateMessageStatus(messageId: String, status: String) {
        chatMessageDao.updateStatus(messageId, status)
    }

    /**
     * Получить сообщения от отправителя
     */
    suspend fun getMessagesBySender(childId: Long, sender: String, limit: Int = 50): List<ChatMessage> {
        return chatMessageDao.getMessagesBySender(childId, sender, limit)
            .map { it.toChatMessage() }
    }

    /**
     * Получить последнее сообщение
     */
    suspend fun getLatestMessage(childId: Long): ChatMessage? {
        return chatMessageDao.getLatestMessage(childId)?.toChatMessage()
    }

    /**
     * Получить последнее сообщение (Flow)
     */
    fun getLatestMessageFlow(childId: Long): Flow<ChatMessage?> {
        return chatMessageDao.getLatestMessageFlow(childId)
            .map { it?.toChatMessage() }
    }

    /**
     * Поиск сообщений по тексту
     */
    suspend fun searchMessages(childId: Long, query: String, limit: Int = 50): List<ChatMessage> {
        return chatMessageDao.searchMessages(childId, query, limit)
            .map { it.toChatMessage() }
    }

    /**
     * Получить сообщения в диапазоне времени
     */
    suspend fun getMessagesInTimeRange(childId: Long, startTime: Long, endTime: Long): List<ChatMessage> {
        return chatMessageDao.getMessagesInTimeRange(childId, startTime, endTime)
            .map { it.toChatMessage() }
    }

    /**
     * Удалить сообщения для ребенка
     */
    suspend fun deleteMessagesForChild(childId: Long) {
        chatMessageDao.deleteMessagesForChild(childId)
    }

    /**
     * Получить общее количество сообщений
     */
    suspend fun getMessageCount(childId: Long): Int {
        return chatMessageDao.getMessageCount(childId)
    }

    /**
     * Удалить все сообщения
     */
    suspend fun deleteAllMessages() {
        chatMessageDao.deleteAll()
    }
}
