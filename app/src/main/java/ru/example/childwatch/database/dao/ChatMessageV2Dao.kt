package ru.example.childwatch.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ChatMessageV2Entity

@Dao
interface ChatMessageV2Dao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: ChatMessageV2Entity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(messages: List<ChatMessageV2Entity>): List<Long>

    @Update
    suspend fun update(message: ChatMessageV2Entity): Int

    @Query("SELECT * FROM chat_messages_v2 WHERE message_id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): ChatMessageV2Entity?

    @Query("SELECT * FROM chat_messages_v2 WHERE client_message_id = :clientMessageId LIMIT 1")
    suspend fun getByClientMessageId(clientMessageId: String): ChatMessageV2Entity?

    @Query("SELECT * FROM chat_messages_v2 WHERE server_message_id = :serverMessageId LIMIT 1")
    suspend fun getByServerMessageId(serverMessageId: String): ChatMessageV2Entity?

    @Query(
        "SELECT * FROM chat_messages_v2 WHERE conversation_id = :conversationId " +
            "ORDER BY sent_at DESC, local_id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getPageNewestFirst(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<ChatMessageV2Entity>

    @Query(
        "SELECT * FROM chat_messages_v2 WHERE conversation_id = :conversationId " +
            "AND (sent_at < :beforeSentAt OR (sent_at = :beforeSentAt AND local_id < :beforeLocalId)) " +
            "ORDER BY sent_at DESC, local_id DESC LIMIT :limit"
    )
    suspend fun getPageBefore(
        conversationId: String,
        beforeSentAt: Long,
        beforeLocalId: Long,
        limit: Int
    ): List<ChatMessageV2Entity>

    @Query(
        "SELECT * FROM chat_messages_v2 WHERE conversation_id = :conversationId " +
            "AND server_sequence IS NOT NULL AND server_sequence < :beforeSequence " +
            "ORDER BY server_sequence DESC LIMIT :limit"
    )
    suspend fun getPageBeforeSequence(
        conversationId: String,
        beforeSequence: Long,
        limit: Int
    ): List<ChatMessageV2Entity>

    @Query(
        "SELECT * FROM chat_messages_v2 WHERE conversation_id = :conversationId " +
            "ORDER BY sent_at DESC, local_id DESC LIMIT :limit"
    )
    fun observeLatest(
        conversationId: String,
        limit: Int
    ): Flow<List<ChatMessageV2Entity>>

    @Query(
        "SELECT * FROM chat_messages_v2 WHERE conversation_id = :conversationId " +
            "AND text LIKE '%' || :query || '%' ORDER BY sent_at DESC, local_id DESC LIMIT :limit"
    )
    suspend fun search(conversationId: String, query: String, limit: Int): List<ChatMessageV2Entity>

    @Query("SELECT COUNT(*) FROM chat_messages_v2 WHERE conversation_id = :conversationId AND is_read = 0")
    fun observeUnreadCount(conversationId: String): Flow<Int>

    @Query(
        "UPDATE chat_messages_v2 SET status = :status, delivery_state = :deliveryState, " +
            "failure_code = :failureCode, is_read = :isRead, delivered_at = :deliveredAt, " +
            "read_at = :readAt, sync_state = :syncState " +
            "WHERE message_id = :messageId"
    )
    suspend fun updateReceiptState(
        messageId: String,
        status: String,
        deliveryState: String,
        failureCode: String?,
        isRead: Boolean,
        deliveredAt: Long?,
        readAt: Long?,
        syncState: String
    ): Int

    @Query(
        "UPDATE chat_messages_v2 SET is_read = 1, status = 'read', delivery_state = 'READ', " +
            "read_at = :readAt " +
            "WHERE conversation_id = :conversationId AND is_read = 0 AND sent_at <= :throughSentAt"
    )
    suspend fun markReadThrough(conversationId: String, throughSentAt: Long, readAt: Long): Int

    @Query(
        "UPDATE chat_messages_v2 SET conversation_id = :targetConversationId " +
            "WHERE conversation_id = :sourceConversationId"
    )
    suspend fun moveToConversation(sourceConversationId: String, targetConversationId: String): Int

    @Query(
        "UPDATE chat_messages_v2 SET is_read = 1, status = 'read', delivery_state = 'READ', " +
            "read_at = :readAt WHERE conversation_id = :conversationId " +
            "AND server_sequence IS NOT NULL AND server_sequence <= :throughSequence " +
            "AND (:localMemberId IS NULL OR sender_member_id IS NULL OR sender_member_id != :localMemberId)"
    )
    suspend fun markIncomingReadThroughSequence(
        conversationId: String,
        throughSequence: Long,
        localMemberId: String?,
        readAt: Long
    ): Int
}
