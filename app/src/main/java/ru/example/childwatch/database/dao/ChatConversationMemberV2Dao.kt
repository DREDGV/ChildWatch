package ru.example.childwatch.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ChatConversationMemberV2Entity

@Dao
interface ChatConversationMemberV2Dao {
    @Upsert
    suspend fun upsert(member: ChatConversationMemberV2Entity)

    @Upsert
    suspend fun upsertAll(members: List<ChatConversationMemberV2Entity>)

    @Query(
        "SELECT * FROM chat_conversation_members_v2 " +
            "WHERE conversation_id = :conversationId ORDER BY role ASC, display_name COLLATE NOCASE ASC"
    )
    fun observeForConversation(conversationId: String): Flow<List<ChatConversationMemberV2Entity>>

    @Query(
        "SELECT * FROM chat_conversation_members_v2 WHERE conversation_id = :conversationId " +
            "ORDER BY role ASC, display_name COLLATE NOCASE ASC"
    )
    suspend fun getForConversation(conversationId: String): List<ChatConversationMemberV2Entity>

    @Query(
        "SELECT * FROM chat_conversation_members_v2 " +
            "WHERE conversation_id = :conversationId AND member_id = :memberId LIMIT 1"
    )
    suspend fun get(conversationId: String, memberId: String): ChatConversationMemberV2Entity?

    @Query("SELECT * FROM chat_conversation_members_v2 WHERE member_id = :memberId")
    suspend fun getConversationsForMember(memberId: String): List<ChatConversationMemberV2Entity>

    @Query(
        "UPDATE chat_conversation_members_v2 SET last_delivered_at = :deliveredAt " +
            "WHERE conversation_id = :conversationId AND member_id = :memberId"
    )
    suspend fun updateDeliveredAt(conversationId: String, memberId: String, deliveredAt: Long): Int

    @Query(
        "UPDATE chat_conversation_members_v2 SET last_read_at = :readAt " +
            "WHERE conversation_id = :conversationId AND member_id = :memberId"
    )
    suspend fun updateReadAt(conversationId: String, memberId: String, readAt: Long): Int

    @Query("DELETE FROM chat_conversation_members_v2 WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String): Int
}
