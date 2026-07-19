package ru.example.childwatch.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ChatConversationV2Entity

@Dao
interface ChatConversationV2Dao {
    @Upsert
    suspend fun upsert(conversation: ChatConversationV2Entity)

    @Upsert
    suspend fun upsertAll(conversations: List<ChatConversationV2Entity>)

    @Query("SELECT * FROM chat_conversations_v2 WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getById(conversationId: String): ChatConversationV2Entity?

    @Query("SELECT * FROM chat_conversations_v2 WHERE server_conversation_id = :serverConversationId LIMIT 1")
    suspend fun getByServerId(serverConversationId: String): ChatConversationV2Entity?

    @Query("SELECT * FROM chat_conversations_v2 WHERE legacy_child_id = :childId LIMIT 1")
    suspend fun getByLegacyChildId(childId: Long): ChatConversationV2Entity?

    @Query(
        "SELECT * FROM chat_conversations_v2 " +
            "ORDER BY is_archived ASC, last_message_at DESC, updated_at DESC, conversation_id ASC"
    )
    fun observeAll(): Flow<List<ChatConversationV2Entity>>

    @Query(
        "SELECT * FROM chat_conversations_v2 " +
            "ORDER BY is_archived ASC, last_message_at DESC, updated_at DESC, conversation_id ASC"
    )
    suspend fun getAll(): List<ChatConversationV2Entity>

    @Query("DELETE FROM chat_conversations_v2 WHERE conversation_id = :conversationId")
    suspend fun deleteById(conversationId: String): Int

    @Query(
        "UPDATE chat_conversations_v2 SET " +
            "last_message_at = :lastMessageAt, last_message_preview = :preview, updated_at = :updatedAt " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun updateLastMessage(
        conversationId: String,
        lastMessageAt: Long?,
        preview: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_conversations_v2 SET unread_count = :unreadCount, " +
            "last_read_at = :lastReadAt, updated_at = :updatedAt WHERE conversation_id = :conversationId"
    )
    suspend fun updateReadState(
        conversationId: String,
        unreadCount: Int,
        lastReadAt: Long?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_conversations_v2 SET last_sequence = :lastSequence, " +
            "last_read_sequence = :lastReadSequence, unread_count = :unreadCount, " +
            "updated_at = :updatedAt WHERE conversation_id = :conversationId"
    )
    suspend fun updateSequenceState(
        conversationId: String,
        lastSequence: Long,
        lastReadSequence: Long,
        unreadCount: Int,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_conversations_v2 SET muted_until = :mutedUntil, updated_at = :updatedAt " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun setMutedUntil(conversationId: String, mutedUntil: Long?, updatedAt: Long): Int
}
