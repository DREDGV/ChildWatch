package ru.example.parentwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.parentwatch.database.entity.ChatMessageEntity

/**
 * DAO for ChatMessage entity
 *
 * Provides database access methods for managing chat messages with pagination support.
 */
@Dao
interface ChatMessageDao {

    /**
     * Insert a new message
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    /**
     * Insert multiple messages
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>): List<Long>

    /**
     * Update an existing message
     */
    @Update
    suspend fun update(message: ChatMessageEntity)

    /**
     * Delete a message
     */
    @Delete
    suspend fun delete(message: ChatMessageEntity)

    /**
     * Get message by ID
     */
    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessageEntity?

    /**
     * Get message by message ID
     */
    @Query("SELECT * FROM chat_messages WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): ChatMessageEntity?

    /**
     * Get all messages for a child (paginated)
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesForChild(childId: Long, limit: Int = 50, offset: Int = 0): List<ChatMessageEntity>

    /**
     * Get all messages for a child as Flow (reactive, latest 200)
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 200")
    fun getMessagesForChildFlow(childId: Long): Flow<List<ChatMessageEntity>>

    /**
     * Get unread messages for a child
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId AND is_read = 0 ORDER BY timestamp DESC")
    suspend fun getUnreadMessages(childId: Long): List<ChatMessageEntity>

    /**
     * Get unread messages count for a child
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE child_id = :childId AND is_read = 0")
    suspend fun getUnreadCount(childId: Long): Int

    /**
     * Get unread messages count as Flow (reactive)
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE child_id = :childId AND is_read = 0")
    fun getUnreadCountFlow(childId: Long): Flow<Int>

    /**
     * Mark message as read
     */
    @Query("UPDATE chat_messages SET is_read = 1, status = 'read' WHERE id = :messageId")
    suspend fun markAsRead(messageId: Long)

    /**
     * Mark all messages as read for a child
     */
    @Query("UPDATE chat_messages SET is_read = 1, status = 'read' WHERE child_id = :childId AND is_read = 0")
    suspend fun markAllAsRead(childId: Long)

    /**
     * Update message status
     */
    @Query("UPDATE chat_messages SET status = :status WHERE message_id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    /**
     * Get messages by sender for a child
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId AND sender = :sender ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesBySender(childId: Long, sender: String, limit: Int = 50): List<ChatMessageEntity>

    /**
     * Get latest message for a child
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(childId: Long): ChatMessageEntity?

    /**
     * Get latest message as Flow (reactive)
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestMessageFlow(childId: Long): Flow<ChatMessageEntity?>

    /**
     * Search messages by text
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId AND text LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchMessages(childId: Long, query: String, limit: Int = 50): List<ChatMessageEntity>

    /**
     * Delete messages for a child
     */
    @Query("DELETE FROM chat_messages WHERE child_id = :childId")
    suspend fun deleteMessagesForChild(childId: Long)

    /**
     * Delete all messages
     */
    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    /**
     * Get total message count for a child
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE child_id = :childId")
    suspend fun getMessageCount(childId: Long): Int

    /**
     * Get messages in time range
     */
    @Query("SELECT * FROM chat_messages WHERE child_id = :childId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getMessagesInTimeRange(childId: Long, startTime: Long, endTime: Long): List<ChatMessageEntity>
}

