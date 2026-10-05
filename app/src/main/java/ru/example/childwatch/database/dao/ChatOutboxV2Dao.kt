package ru.example.childwatch.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ChatOutboxV2Entity

@Dao
interface ChatOutboxV2Dao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueIfAbsent(item: ChatOutboxV2Entity): Long

    @Query("SELECT * FROM chat_outbox_v2 WHERE client_message_id = :clientMessageId LIMIT 1")
    suspend fun getByClientMessageId(clientMessageId: String): ChatOutboxV2Entity?

    @Query(
        "SELECT * FROM chat_outbox_v2 WHERE state IN ('PENDING', 'RETRY', 'SENDING') " +
            "AND next_attempt_at <= :now AND (lease_expires_at IS NULL OR lease_expires_at <= :now) " +
            "ORDER BY priority DESC, next_attempt_at ASC, outbox_id ASC LIMIT :limit"
    )
    suspend fun getReady(now: Long, limit: Int): List<ChatOutboxV2Entity>

    @Query(
        "UPDATE chat_outbox_v2 SET state = 'SENDING', lease_token = :leaseToken, " +
            "lease_expires_at = :leaseExpiresAt, updated_at = :updatedAt " +
            "WHERE outbox_id = :outboxId AND state IN ('PENDING', 'RETRY', 'SENDING') " +
            "AND (lease_expires_at IS NULL OR lease_expires_at <= :updatedAt)"
    )
    suspend fun tryAcquire(
        outboxId: Long,
        leaseToken: String,
        leaseExpiresAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_outbox_v2 SET state = :state, attempt_count = :attemptCount, " +
            "next_attempt_at = :nextAttemptAt, last_error = :lastError, updated_at = :updatedAt, " +
            "lease_token = NULL, lease_expires_at = NULL WHERE outbox_id = :outboxId"
    )
    suspend fun scheduleNextAttempt(
        outboxId: Long,
        state: String,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE chat_outbox_v2 SET state = 'SENT', last_error = NULL, updated_at = :updatedAt, " +
            "lease_token = NULL, lease_expires_at = NULL WHERE client_message_id = :clientMessageId"
    )
    suspend fun markSent(clientMessageId: String, updatedAt: Long): Int

    @Query(
        "UPDATE chat_outbox_v2 SET conversation_id = :targetConversationId " +
            "WHERE conversation_id = :sourceConversationId"
    )
    suspend fun moveToConversation(sourceConversationId: String, targetConversationId: String): Int

    @Query(
        "UPDATE chat_outbox_v2 SET state = 'PENDING', next_attempt_at = :now, " +
            "last_error = NULL, lease_token = NULL, lease_expires_at = NULL, updated_at = :now " +
            "WHERE client_message_id = :clientMessageId AND state = 'FAILED'"
    )
    suspend fun retryFailed(clientMessageId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM chat_outbox_v2 WHERE state IN ('PENDING', 'RETRY', 'SENDING')")
    fun observePendingCount(): Flow<Int>
    @Query("SELECT * FROM chat_outbox_v2 WHERE text = :scopeKey AND state IN ('READ_PENDING','READ_SENDING') " +
        "AND next_attempt_at <= :now AND (lease_expires_at IS NULL OR lease_expires_at <= :now) " +
        "ORDER BY next_attempt_at ASC LIMIT :limit")
    suspend fun getReadyReadReceipts(scopeKey: String, now: Long, limit: Int): List<ChatOutboxV2Entity>

    @Query("UPDATE chat_outbox_v2 SET state = 'READ_SENDING', lease_token = :leaseToken, " +
        "lease_expires_at = :expiresAt, updated_at = :now WHERE outbox_id = :id " +
        "AND state IN ('READ_PENDING','READ_SENDING') " +
        "AND (lease_expires_at IS NULL OR lease_expires_at <= :now)")
    suspend fun acquireReadReceipt(id: Long, leaseToken: String, expiresAt: Long, now: Long): Int

    @Query("UPDATE chat_outbox_v2 SET state = :state, next_attempt_at = :nextAt, " +
        "attempt_count = :attempts, last_error = :error, lease_token = NULL, lease_expires_at = NULL, " +
        "updated_at = :now WHERE outbox_id = :id AND lease_token = :leaseToken")
    suspend fun finishReadReceipt(id: Long, leaseToken: String, state: String, nextAt: Long,
        attempts: Int, error: String?, now: Long): Int

    @Query("SELECT COUNT(*) FROM chat_outbox_v2 WHERE text = :scopeKey AND conversation_id = :conversationId " +
        "AND state IN ('READ_PENDING','READ_SENDING')")
    suspend fun pendingReadReceiptCount(scopeKey: String, conversationId: String): Int

}
