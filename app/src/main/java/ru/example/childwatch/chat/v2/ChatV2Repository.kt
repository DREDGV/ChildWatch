package ru.example.childwatch.chat.v2

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.Response
import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatDeliveryStateReducer
import ru.childwatch.shared.chat.ChatTextPolicy
import ru.childwatch.shared.chat.ChatTextValidation
import ru.childwatch.shared.chat.ChatV2ConversationDto
import ru.childwatch.shared.chat.ChatV2DirectConversationRequest
import ru.childwatch.shared.chat.ChatV2LegacyReconcilePolicy
import ru.childwatch.shared.chat.ChatV2MessageDto
import ru.childwatch.shared.chat.ChatV2PagingPolicy
import ru.childwatch.shared.chat.ChatV2ReceiptDto
import ru.childwatch.shared.chat.ChatV2ReceiptRequest
import ru.childwatch.shared.chat.ChatV2RetryPolicy
import ru.childwatch.shared.chat.ChatV2SendMessageRequest
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationMessage
import ru.childwatch.shared.chat.ConversationPage
import ru.childwatch.shared.chat.ConversationType
import ru.childwatch.shared.chat.toDomain
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.ChatConversationMemberV2Entity
import ru.example.childwatch.database.entity.ChatConversationV2Entity
import ru.example.childwatch.database.entity.ChatMessageV2Entity
import ru.example.childwatch.database.entity.ChatOutboxV2Entity
import ru.example.childwatch.database.mapping.toEntity
import ru.example.childwatch.database.mapping.toModel
import ru.example.childwatch.network.ChildWatchApi
import ru.example.childwatch.network.NetworkClient
import java.io.IOException
import java.util.UUID

/**
 * Room-first data source for conversation chat. It deliberately has no UI,
 * WorkManager or foreground-service ownership; a later integration layer may
 * call [flushOutbox] whenever connectivity is available.
 */
class ChatV2Repository(
    private val database: ChildWatchDatabase,
    private val api: ChildWatchApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val gson = Gson()
    private val conversations = database.chatConversationV2Dao()
    private val members = database.chatConversationMemberV2Dao()
    private val messages = database.chatMessageV2Dao()
    private val outbox = database.chatOutboxV2Dao()

    companion object {
        private const val DEFAULT_PAGE_SIZE = ChatV2PagingPolicy.DEFAULT_SERVER_PAGE_SIZE
        private const val OUTBOX_BATCH_SIZE = 50
        private const val OUTBOX_LEASE_MS = 60_000L

        fun create(
            context: Context,
            serverUrl: String,
            networkClient: NetworkClient = NetworkClient(context.applicationContext)
        ): ChatV2Repository = ChatV2Repository(
            database = ChildWatchDatabase.getInstance(context.applicationContext),
            api = networkClient.getChatV2Api(serverUrl)
        )
    }

    fun observeConversations(): Flow<List<Conversation>> = conversations.observeAll().map { rows ->
        rows.map { it.toModel() }
    }

    fun observeMessages(
        conversationId: String,
        limit: Int = DEFAULT_PAGE_SIZE
    ): Flow<List<ConversationMessage>> = messages.observeLatest(
        conversationId,
        ChatV2PagingPolicy.localMessageWindow(limit)
    ).map { rows -> rows.map { it.toModel() } }

    fun observePendingOutboxCount(): Flow<Int> = outbox.observePendingCount()

    suspend fun getCachedConversations(): List<Conversation> = conversations.getAll().map { row ->
        val memberModels = members.getForConversation(row.conversationId).map { it.toModel() }
        row.toModel(memberModels)
    }

    suspend fun getCachedPage(
        conversationId: String,
        beforeSequence: Long? = null,
        limit: Int = DEFAULT_PAGE_SIZE
    ): List<ConversationMessage> {
        val boundedLimit = ChatV2PagingPolicy.serverPageSize(limit)
        val rows = if (beforeSequence != null) {
            messages.getPageBeforeSequence(conversationId, beforeSequence, boundedLimit)
        } else {
            messages.getPageNewestFirst(conversationId, boundedLimit, 0)
        }
        return rows.map { it.toModel() }
    }

    /**
     * Refreshes server projections and safely folds the one selected legacy
     * child thread into the permanent server FAMILY conversation. If no child
     * context is supplied, reconciliation happens only when both sides have a
     * single unambiguous family projection.
     */
    suspend fun refreshConversations(
        legacyChildDeviceId: String? = null,
        legacyChildId: Long? = null
    ): List<Conversation> {
        val response = api.getChatV2Conversations()
        val body = requireSuccessful(response, "LIST_CONVERSATIONS")
        if (!body.success) throw ChatV2RepositoryException("LIST_CONVERSATIONS_REJECTED")

        database.withTransaction {
            val serverFamilyCount = body.conversations.count { it.type.equals("FAMILY", true) }
            val unmappedLocalFamilies = conversations.getAll().filter {
                it.type.equals(ChatConversationV2Entity.TYPE_FAMILY, true) &&
                    it.serverConversationId.isNullOrBlank() &&
                    it.conversationId.startsWith(ChatV2LegacyReconcilePolicy.LOCAL_FAMILY_PREFIX)
            }
            val unambiguousFallback = unmappedLocalFamilies.singleOrNull()
                ?.takeIf { serverFamilyCount == 1 }

            body.conversations.forEach { dto ->
                cacheConversation(
                    dto = dto,
                    legacyChildDeviceId = legacyChildDeviceId,
                    legacyChildId = legacyChildId,
                    fallbackLocalConversation = unambiguousFallback
                )
            }
        }
        return getCachedConversations()
    }

    suspend fun createDirect(targetMemberId: String): Conversation {
        val target = targetMemberId.trim()
        require(target.isNotEmpty()) { "targetMemberId must not be empty" }
        val response = api.createChatV2DirectConversation(ChatV2DirectConversationRequest(target))
        val body = requireSuccessful(response, "CREATE_DIRECT")
        val dto = body.conversation
            ?.takeIf { body.success }
            ?: throw ChatV2RepositoryException("CREATE_DIRECT_REJECTED")
        database.withTransaction { cacheConversation(dto, null, null, null) }
        return dto.toDomain()
    }

    suspend fun syncMessagesPage(
        conversationId: String,
        beforeSequence: Long? = null,
        limit: Int = DEFAULT_PAGE_SIZE
    ): ConversationPage {
        require(beforeSequence == null || beforeSequence > 0) { "beforeSequence must be positive" }
        val boundedLimit = ChatV2PagingPolicy.serverPageSize(limit)
        val response = api.getChatV2Messages(conversationId, beforeSequence, boundedLimit)
        val body = requireSuccessful(response, "GET_MESSAGES")
        if (!body.success) throw ChatV2RepositoryException("GET_MESSAGES_REJECTED")
        database.withTransaction {
            requireNotNull(conversations.getById(body.conversationId)) {
                "Conversation must be synchronized before messages"
            }
            body.messages.forEach { importServerMessage(it) }
        }
        return ConversationPage(
            conversationId = body.conversationId,
            messages = body.messages.map { it.toDomain() },
            nextBeforeSequence = body.nextBeforeSequence,
            hasMore = body.hasMore
        )
    }

    /** Stores the canonical message already carried by a WebSocket event. */
    suspend fun cacheRealtimeMessage(dto: ChatV2MessageDto) {
        database.withTransaction {
            requireNotNull(conversations.getById(dto.conversationId)) {
                "Conversation must be synchronized before realtime messages"
            }
            importServerMessage(dto)
            outbox.markSent(dto.clientMessageId, clock())
        }
    }

    /** Adds one durable optimistic message. The caller may supply an ID for deterministic retry. */
    suspend fun enqueueMessage(
        conversationId: String,
        text: String,
        senderDisplayName: String,
        senderRole: ConversationMemberRole,
        senderMemberId: String? = null,
        senderDeviceId: String? = null,
        clientMessageId: String = idFactory()
    ): ConversationMessage {
        when (val validation = ChatTextPolicy.validate(text)) {
            ChatTextValidation.Empty -> throw IllegalArgumentException("Message must not be blank")
            is ChatTextValidation.TooLarge -> throw IllegalArgumentException(
                "Message is ${validation.utf8Bytes} bytes; maximum is ${validation.maxUtf8Bytes}"
            )
            is ChatTextValidation.Valid -> Unit
        }
        require(clientMessageId.isNotBlank()) { "clientMessageId must not be blank" }
        val now = clock()
        val request = ChatV2SendMessageRequest(clientMessageId, text, now)
        val model = ConversationMessage(
            messageId = clientMessageId,
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            senderMemberId = senderMemberId,
            senderDeviceId = senderDeviceId,
            senderDisplayName = senderDisplayName,
            senderRole = senderRole,
            text = text,
            clientSentAt = now,
            deliveryState = ChatDeliveryState.QUEUED
        )

        return database.withTransaction {
            requireNotNull(conversations.getById(conversationId)) { "Unknown conversation: $conversationId" }
            messages.getByClientMessageId(clientMessageId)?.let { return@withTransaction it.toModel() }
            messages.insertIfAbsent(model.toEntity())
            outbox.enqueueIfAbsent(
                ChatOutboxV2Entity(
                    clientMessageId = clientMessageId,
                    messageId = clientMessageId,
                    conversationId = conversationId,
                    payloadJson = gson.toJson(request),
                    text = text,
                    clientSentAt = now,
                    nextAttemptAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            )
            conversations.updateLastMessage(conversationId, now, text, now)
            messages.getByClientMessageId(clientMessageId)?.toModel() ?: model
        }
    }

    /**
     * Sends independent leased rows. A broken message is rescheduled/failed and
     * never prevents later rows in the same batch from being attempted.
     */
    suspend fun flushOutbox(limit: Int = OUTBOX_BATCH_SIZE): ChatV2FlushResult {
        val ready = outbox.getReady(clock(), limit.coerceIn(1, OUTBOX_BATCH_SIZE))
        var sent = 0
        var retryScheduled = 0
        var permanentlyFailed = 0

        for (item in ready) {
            val acquiredAt = clock()
            val leaseToken = idFactory()
            val acquired = outbox.tryAcquire(
                item.outboxId,
                leaseToken,
                acquiredAt + OUTBOX_LEASE_MS,
                acquiredAt
            ) == 1
            if (!acquired) continue

            setLocalMessageState(item.clientMessageId, ChatDeliveryState.SENDING, null)
            try {
                val response = api.sendChatV2Message(
                    item.conversationId,
                    ChatV2SendMessageRequest(item.clientMessageId, item.text, item.clientSentAt)
                )
                val body = response.body()
                val serverMessage = body?.message
                if (response.isSuccessful && body?.success == true && serverMessage != null) {
                    database.withTransaction {
                        importServerMessage(serverMessage)
                        outbox.markSent(item.clientMessageId, clock())
                    }
                    sent++
                } else {
                    val result = scheduleFailure(item, "HTTP_${response.code()}", isPermanent(response.code()))
                    if (result) permanentlyFailed++ else retryScheduled++
                }
            } catch (error: Exception) {
                val code = if (error is IOException) "NETWORK_IO" else "SEND_EXCEPTION"
                val result = scheduleFailure(item, code, permanent = false)
                if (result) permanentlyFailed++ else retryScheduled++
            }
        }
        return ChatV2FlushResult(ready.size, sent, retryScheduled, permanentlyFailed)
    }

    suspend fun retryFailed(clientMessageId: String): Boolean = database.withTransaction {
        val changed = outbox.retryFailed(clientMessageId, clock()) == 1
        if (changed) setLocalMessageState(clientMessageId, ChatDeliveryState.QUEUED, null)
        changed
    }

    suspend fun markDeliveredThrough(conversationId: String, sequence: Long): ChatV2ReceiptDto =
        advanceReceipt(conversationId, deliveredThrough = sequence, readThrough = null)

    suspend fun markReadThrough(conversationId: String, sequence: Long): ChatV2ReceiptDto =
        advanceReceipt(conversationId, deliveredThrough = sequence, readThrough = sequence)

    private suspend fun advanceReceipt(
        conversationId: String,
        deliveredThrough: Long?,
        readThrough: Long?
    ): ChatV2ReceiptDto {
        require((deliveredThrough ?: readThrough ?: -1) >= 0) { "Receipt sequence must not be negative" }
        val response = api.sendChatV2Receipt(
            conversationId,
            ChatV2ReceiptRequest(deliveredThrough, readThrough)
        )
        val body = requireSuccessful(response, "SEND_RECEIPT")
        val receipt = body.receipt
            ?.takeIf { body.success }
            ?: throw ChatV2RepositoryException("SEND_RECEIPT_REJECTED")
        val now = clock()
        database.withTransaction {
            val conversation = conversations.getById(conversationId)
            if (conversation != null) {
                val lastRead = maxOf(conversation.lastReadSequence, receipt.readThroughSequence)
                val lastSequence = maxOf(conversation.lastSequence, lastRead)
                conversations.updateSequenceState(
                    conversationId,
                    lastSequence,
                    lastRead,
                    (lastSequence - lastRead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    now
                )
            }
            members.get(conversationId, receipt.memberId)?.let { member ->
                members.upsert(member.copy(isLocalUser = true))
            }
            if (receipt.deliveredThroughSequence > 0) {
                members.updateDeliveredAt(conversationId, receipt.memberId, now)
            }
            if (receipt.readThroughSequence > 0) {
                members.updateReadAt(conversationId, receipt.memberId, now)
                messages.markIncomingReadThroughSequence(
                    conversationId,
                    receipt.readThroughSequence,
                    receipt.memberId,
                    now
                )
            }
        }
        return receipt
    }

    private suspend fun cacheConversation(
        dto: ChatV2ConversationDto,
        legacyChildDeviceId: String?,
        legacyChildId: Long?,
        fallbackLocalConversation: ChatConversationV2Entity?
    ) {
        val existingServer = conversations.getById(dto.conversationId)
        var serverEntity = dto.toEntity(existingServer, clock())
        if (dto.type.equals("FAMILY", true)) {
            val explicitLocal = legacyChildDeviceId
                ?.let(ChatV2LegacyReconcilePolicy::localConversationId)
                ?.let { conversations.getById(it) }
                ?: legacyChildId?.let { conversations.getByLegacyChildId(it) }
            val local = explicitLocal ?: fallbackLocalConversation
            if (local != null && ChatV2LegacyReconcilePolicy.shouldReconcile(
                    local.conversationId,
                    dto.conversationId,
                    local.type,
                    local.serverConversationId
                )
            ) {
                serverEntity = reconcileLegacyFamily(local, serverEntity)
            }
        }
        val current = conversations.getById(dto.conversationId)
        conversations.upsert(mergeConversation(current, serverEntity))
        dto.members.forEach { memberDto ->
            val old = members.get(dto.conversationId, memberDto.memberId)
            val model = memberDto.toDomain()
            members.upsert(
                ChatConversationMemberV2Entity(
                    conversationId = dto.conversationId,
                    memberId = model.memberId,
                    serverMemberId = model.memberId,
                    deviceId = old?.deviceId,
                    displayName = model.displayName,
                    role = model.role.name,
                    isLocalUser = memberDto.memberId == dto.actorMemberId,
                    joinedAt = old?.joinedAt ?: clock(),
                    lastActiveAt = old?.lastActiveAt,
                    lastDeliveredAt = old?.lastDeliveredAt,
                    lastReadAt = old?.lastReadAt,
                    isMuted = old?.isMuted ?: false
                )
            )
        }
    }

    private suspend fun reconcileLegacyFamily(
        local: ChatConversationV2Entity,
        server: ChatConversationV2Entity
    ): ChatConversationV2Entity {
        // The target must exist before foreign keys can be reassigned.
        conversations.upsert(server.copy(legacyChildId = null))
        messages.moveToConversation(local.conversationId, server.conversationId)
        outbox.moveToConversation(local.conversationId, server.conversationId)
        members.getForConversation(local.conversationId).forEach { legacyMember ->
            members.upsert(legacyMember.copy(conversationId = server.conversationId))
        }
        members.deleteForConversation(local.conversationId)
        conversations.deleteById(local.conversationId)
        return server.copy(
            legacyChildId = local.legacyChildId,
            createdAt = minOf(local.createdAt, server.createdAt),
            updatedAt = maxOf(local.updatedAt, server.updatedAt),
            lastMessageAt = listOfNotNull(local.lastMessageAt, server.lastMessageAt).maxOrNull(),
            lastMessagePreview = server.lastMessagePreview ?: local.lastMessagePreview,
            unreadCount = maxOf(local.unreadCount, server.unreadCount),
            syncState = ChatConversationV2Entity.SYNC_STATE_SYNCED
        )
    }

    private suspend fun importServerMessage(dto: ChatV2MessageDto) {
        val incoming = dto.toDomain().toEntity()
        val existing = messages.getByClientMessageId(dto.clientMessageId)
            ?: messages.getByServerMessageId(dto.messageId)
            ?: messages.getByMessageId(dto.messageId)
        val readAt = dto.receipts.mapNotNull { it.readAt }.maxOrNull()
        val deliveredAt = dto.receipts.mapNotNull { it.deliveredAt }.maxOrNull()
        val serverEntity = incoming.copy(
            localId = existing?.localId ?: 0,
            messageId = dto.messageId,
            serverMessageId = dto.messageId,
            sentAt = dto.serverCreatedAt,
            createdAt = dto.serverCreatedAt,
            deliveredAt = deliveredAt,
            readAt = readAt,
            isRead = incoming.deliveryState == ChatDeliveryState.READ.name,
            syncState = ChatMessageV2Entity.SYNC_STATE_SYNCED
        )
        if (existing == null) {
            messages.insertIfAbsent(serverEntity)
        } else {
            val currentState = existing.toModel().deliveryState
            val incomingState = incoming.toModel().deliveryState
            val mergedState = if (incoming.serverSequence != null && currentState == ChatDeliveryState.FAILED) {
                incomingState
            } else {
                ChatDeliveryStateReducer.merge(currentState, incomingState)
            }
            messages.update(
                serverEntity.copy(
                    deliveryState = mergedState.name,
                    status = mergedState.toLegacyStatus(),
                    failureCode = null
                )
            )
        }

        conversations.getById(dto.conversationId)?.let { current ->
            val isNewest = dto.serverSequence >= current.lastSequence
            conversations.upsert(
                current.copy(
                    updatedAt = maxOf(current.updatedAt, dto.serverCreatedAt),
                    lastMessageAt = if (isNewest) dto.serverCreatedAt else current.lastMessageAt,
                    lastMessagePreview = if (isNewest) dto.text else current.lastMessagePreview,
                    lastSequence = maxOf(current.lastSequence, dto.serverSequence)
                )
            )
        }
    }

    private suspend fun setLocalMessageState(
        clientMessageId: String,
        state: ChatDeliveryState,
        failureCode: String?
    ) {
        val message = messages.getByClientMessageId(clientMessageId) ?: return
        messages.update(
            message.copy(
                status = state.toLegacyStatus(),
                deliveryState = state.name,
                failureCode = failureCode,
                syncState = if (state == ChatDeliveryState.FAILED) {
                    ChatMessageV2Entity.SYNC_STATE_FAILED
                } else {
                    message.syncState
                }
            )
        )
    }

    /** @return true when the row reached a terminal FAILED state. */
    private suspend fun scheduleFailure(
        item: ChatOutboxV2Entity,
        errorCode: String,
        permanent: Boolean
    ): Boolean {
        val now = clock()
        val attempts = item.attemptCount + 1
        val exhausted = permanent || ChatV2RetryPolicy.isExhausted(attempts)
        outbox.scheduleNextAttempt(
            item.outboxId,
            if (exhausted) ChatOutboxV2Entity.STATE_FAILED else ChatOutboxV2Entity.STATE_RETRY,
            attempts,
            if (exhausted) now else ChatV2RetryPolicy.nextAttemptAt(now, attempts),
            errorCode,
            now
        )
        setLocalMessageState(
            item.clientMessageId,
            if (exhausted) ChatDeliveryState.FAILED else ChatDeliveryState.QUEUED,
            errorCode
        )
        return exhausted
    }

    private fun ChatV2ConversationDto.toEntity(
        existing: ChatConversationV2Entity?,
        now: Long
    ): ChatConversationV2Entity {
        val updated = updatedAt ?: now
        return ChatConversationV2Entity(
            conversationId = conversationId,
            serverConversationId = conversationId,
            familyId = familyId,
            type = if (type.equals("DIRECT", true)) {
                ChatConversationV2Entity.TYPE_DIRECT
            } else {
                ChatConversationV2Entity.TYPE_FAMILY
            },
            title = title,
            legacyChildId = existing?.legacyChildId,
            createdAt = existing?.createdAt ?: updated,
            updatedAt = updated,
            lastMessageAt = if (lastMessagePreview != null) updated else existing?.lastMessageAt,
            lastMessagePreview = lastMessagePreview ?: existing?.lastMessagePreview,
            lastSequence = lastSequence,
            lastReadSequence = lastReadSequence,
            unreadCount = unreadCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            lastReadAt = existing?.lastReadAt,
            mutedUntil = mutedUntil,
            muted = mutedUntil != null,
            isArchived = existing?.isArchived ?: false,
            syncState = ChatConversationV2Entity.SYNC_STATE_SYNCED
        )
    }

    private fun mergeConversation(
        current: ChatConversationV2Entity?,
        incoming: ChatConversationV2Entity
    ): ChatConversationV2Entity = if (current == null) incoming else incoming.copy(
        legacyChildId = incoming.legacyChildId ?: current.legacyChildId,
        createdAt = minOf(current.createdAt, incoming.createdAt),
        updatedAt = maxOf(current.updatedAt, incoming.updatedAt),
        lastMessageAt = listOfNotNull(current.lastMessageAt, incoming.lastMessageAt).maxOrNull(),
        lastMessagePreview = incoming.lastMessagePreview ?: current.lastMessagePreview,
        lastSequence = maxOf(current.lastSequence, incoming.lastSequence),
        lastReadSequence = maxOf(current.lastReadSequence, incoming.lastReadSequence),
        lastReadAt = incoming.lastReadAt ?: current.lastReadAt
    )

    private fun isPermanent(httpCode: Int): Boolean =
        httpCode in 400..499 && httpCode !in setOf(401, 408, 425, 429)

    private fun ChatDeliveryState.toLegacyStatus(): String = when (this) {
        ChatDeliveryState.QUEUED -> "queued"
        ChatDeliveryState.SENDING -> "sending"
        ChatDeliveryState.ACCEPTED -> "sent"
        ChatDeliveryState.DELIVERED -> "delivered"
        ChatDeliveryState.READ -> "read"
        ChatDeliveryState.FAILED -> "failed"
    }

    private fun <T> requireSuccessful(response: Response<T>, operation: String): T {
        if (!response.isSuccessful) {
            throw ChatV2RepositoryException("${operation}_HTTP_${response.code()}")
        }
        return response.body() ?: throw ChatV2RepositoryException("${operation}_EMPTY_BODY")
    }
}

data class ChatV2FlushResult(
    val considered: Int,
    val sent: Int,
    val retryScheduled: Int,
    val permanentlyFailed: Int
)

class ChatV2RepositoryException(val code: String) : IllegalStateException(code)
