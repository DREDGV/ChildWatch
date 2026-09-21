package ru.example.parentwatch.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.example.parentwatch.database.entity.ChatConversationV2Entity

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

    /**
     * Conversations shown in the list.
     *
     * Archived rows are excluded: removing a chat from the list hides it for
     * this device while the messages stay intact and readable if it returns.
     */
    @Query(
        "SELECT * FROM chat_conversations_v2 WHERE is_archived = 0 " +
            "ORDER BY last_message_at DESC, updated_at DESC, conversation_id ASC"
    )
    fun observeAll(): Flow<List<ChatConversationV2Entity>>

    @Query(
        "SELECT * FROM chat_conversations_v2 WHERE is_archived = 0 " +
            "ORDER BY last_message_at DESC, updated_at DESC, conversation_id ASC"
    )
    suspend fun getAll(): List<ChatConversationV2Entity>

    /** Every row, including ones the user removed from the list. */
    @Query("SELECT * FROM chat_conversations_v2")
    suspend fun getAllIncludingArchived(): List<ChatConversationV2Entity>

    @Query("DELETE FROM chat_conversations_v2 WHERE conversation_id = :conversationId")
    suspend fun deleteById(conversationId: String): Int

    /** Renames a conversation for this device only. */
    @Query(
        "UPDATE chat_conversations_v2 SET custom_title = :title, updated_at = :updatedAt " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun updateTitle(conversationId: String, title: String?, updatedAt: Long): Int

    /** Hides a conversation from the list, or brings it back. */
    @Query(
        "UPDATE chat_conversations_v2 SET is_archived = :archived, updated_at = :updatedAt " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun setArchived(conversationId: String, archived: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE chat_conversations_v2 SET last_message_at = :lastMessageAt, " +
            "last_message_preview = :preview, updated_at = :updatedAt WHERE conversation_id = :conversationId"
    )
    suspend fun updateLastMessage(
        conversationId: String,
        lastMessageAt: Long?,
        preview: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_conversations_v2 SET unread_count = :unreadCount, last_read_at = :lastReadAt, " +
            "updated_at = :updatedAt WHERE conversation_id = :conversationId"
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
