package ru.example.childwatch.chat.v2

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import retrofit2.Response
import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatDeliveryStateReducer
import ru.childwatch.shared.chat.ChatTextPolicy
import ru.childwatch.shared.chat.ChatTextValidation
import ru.childwatch.shared.chat.ChatV2ConversationDto
import ru.childwatch.shared.chat.ChatV2CreateGroupRequest
import ru.childwatch.shared.chat.ChatV2DirectConversationRequest
import ru.childwatch.shared.chat.ChatV2EditMessageRequest
import ru.childwatch.shared.chat.ChatV2GroupMembersRequest
import ru.childwatch.shared.chat.ChatV2GroupSettingsResponse
import ru.childwatch.shared.chat.ChatV2LegacyReconcilePolicy
import ru.childwatch.shared.chat.ChatV2MessageDto
import ru.childwatch.shared.chat.ChatV2PagingPolicy
import ru.childwatch.shared.chat.ChatV2ReceiptDto
import ru.childwatch.shared.chat.ChatV2ReceiptRequest
import ru.childwatch.shared.chat.ChatV2RetryPolicy
import ru.childwatch.shared.chat.ChatV2ReceiptScope
import ru.childwatch.shared.chat.ChatV2ReadReceiptPolicy
import kotlinx.coroutines.CancellationException
import ru.childwatch.shared.chat.ChatV2UnreadMergePolicy
import ru.childwatch.shared.chat.ChatV2SendMessageRequest
import ru.childwatch.shared.chat.ChatV2TransferGroupAdminRequest
import ru.childwatch.shared.chat.ChatV2UpdateGroupAvatarRequest
import ru.childwatch.shared.chat.ChatV2UpdateGroupTitleRequest
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
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val receiptScope: ChatV2ReceiptScope? = null,
    private val receiptScopeProvider: () -> ChatV2ReceiptScope? = { receiptScope }
) {
    private val gson = Gson()
    fun attachments(context: Context) = ChatAttachmentService(context.applicationContext, this,
        api, database.chatAttachmentDraftV2Dao(), database.chatMediaCapabilitiesDao(), { mediaScope() }, clock)
    internal fun mediaScope(): ChatV2ReceiptScope? = receiptScope?.takeIf {
        it.isComplete() && it == receiptScopeProvider()
    }
    private var attachmentContext: Context? = null

    private val conversations = database.chatConversationV2Dao()
    private val members = database.chatConversationMemberV2Dao()
    private val messages = database.chatMessageV2Dao()
    private val outbox = database.chatOutboxV2Dao()

    companion object {
        private const val TAG = "ChatV2Repository"
        private const val DEFAULT_PAGE_SIZE = ChatV2PagingPolicy.DEFAULT_SERVER_PAGE_SIZE
        private const val OUTBOX_BATCH_SIZE = 50
        private const val OUTBOX_LEASE_MS = 60_000L

        fun create(
            context: Context,
            serverUrl: String,
            networkClient: NetworkClient = NetworkClient(context.applicationContext)
        ): ChatV2Repository {
            val scope = resolveReceiptScope(context, serverUrl)
            return ChatV2Repository(
                database = ChildWatchDatabase.getInstance(context.applicationContext),
                api = networkClient.getChatV2Api(serverUrl),
                receiptScope = scope,
                receiptScopeProvider = { resolveReceiptScope(context, serverUrl) }
            ).also { it.attachmentContext = context.applicationContext }
        }

        private fun resolveReceiptScope(context: Context, serverUrl: String): ChatV2ReceiptScope? {
            val resolver = ru.example.childwatch.profile.ParentEffectiveContextResolver(context.applicationContext)
            val actualServer = resolver.resolveServerUrl().trimEnd('/')
            if (actualServer != serverUrl.trimEnd('/')) return null
            return ChatV2ReceiptScope(actualServer, resolver.resolveFamilyId().orEmpty(),
                resolver.resolveSelfMemberId().orEmpty(), resolver.resolveOwnParentId()).takeIf { it.isComplete() }
        }
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

    /**
     * One conversation as this device last cached it, members and all.
     *
     * This snapshot can be stale. Group management reads membership and rights
     * together from loadGroupSettings instead of treating this cache as authority.
     */
    suspend fun getCachedConversation(conversationId: String): Conversation? =
        conversations.getById(conversationId)?.let { row ->
            row.toModel(members.getForConversation(row.conversationId).map { it.toModel() })
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
        flushReadReceipts()
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

    /**
     * Creates a group with a chosen name and membership.
     *
     * The caller is put in it by the server and becomes its administrator; everybody
     * named here has to belong to the family, which the server enforces so a group
     * cannot reach outside it.
     */
    suspend fun createGroup(title: String, memberIds: List<String>): Conversation {
        val name = title.trim()
        require(name.isNotEmpty()) { "title must not be empty" }
        val members = memberIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(members.isNotEmpty()) { "A group needs at least one other member" }
        val response = api.createChatV2Group(ChatV2CreateGroupRequest(name, members))
        val body = requireSuccessful(response, "CREATE_GROUP")
        val dto = body.conversation
            ?.takeIf { body.success }
            ?: throw ChatV2RepositoryException("CREATE_GROUP_REJECTED")
        database.withTransaction { cacheConversation(dto, null, null, null) }
        return dto.toDomain()
    }

    /**
     * Adds people to a group. Only its administrator may.
     *
     * Answers only whether it worked: the caller refreshes the conversation list
     * afterwards, which is the one source of the group's membership on the device.
     */
    suspend fun addGroupMembers(conversationId: String, memberIds: List<String>): Boolean {
        val members = memberIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(members.isNotEmpty()) { "No members to add" }
        val response = api.addChatV2GroupMembers(
            conversationId,
            ChatV2GroupMembersRequest(members)
        )
        return requireSuccessful(response, "ADD_GROUP_MEMBERS").success
    }

    /** Takes one person out of a group. Only its administrator may. */
    suspend fun removeGroupMember(conversationId: String, memberId: String): Boolean {
        val target = memberId.trim()
        require(target.isNotEmpty()) { "memberId must not be empty" }
        val response = api.removeChatV2GroupMember(conversationId, target)
        return requireSuccessful(response, "REMOVE_GROUP_MEMBER").success
    }

    /** Hands administration of the group to another member. Only the administrator may. */
    suspend fun transferGroupAdmin(conversationId: String, memberId: String): Boolean {
        val target = memberId.trim()
        require(target.isNotEmpty()) { "memberId must not be empty" }
        val response = api.transferChatV2GroupAdmin(
            conversationId,
            ChatV2TransferGroupAdminRequest(target)
        )
        return requireSuccessful(response, "TRANSFER_GROUP_ADMIN").success
    }

    /**
     * Leaves a group.
     *
     * An administrator is refused by the server: they hand the group over or close
     * it, because a group whose administrator walked away would have a name and a
     * membership nobody could change.
     */
    suspend fun leaveGroup(conversationId: String): Boolean {
        val response = api.leaveChatV2Group(conversationId)
        return requireSuccessful(response, "LEAVE_GROUP").left
    }

    /** Closes the group for everybody. Only its administrator may. */
    suspend fun closeGroup(conversationId: String): Boolean {
        val response = api.closeChatV2Group(conversationId)
        return requireSuccessful(response, "CLOSE_GROUP").closed
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
        clientMessageId: String = idFactory(),
        attachments: List<ru.childwatch.shared.chat.ChatV2AttachmentDto> = emptyList(),
        messageType: String = "TEXT"
    ): ConversationMessage {
        require(attachments.size <= 1) { "ONE_ATTACHMENT_SUPPORTED" }
        require((messageType == "TEXT") == attachments.isEmpty()) { "MESSAGE_ATTACHMENT_TYPE_MISMATCH" }
        if (attachments.isNotEmpty()) require(attachments.single().type == messageType) { "MESSAGE_ATTACHMENT_TYPE_MISMATCH" }
        when (val validation = ChatTextPolicy.validate(text)) {
            ChatTextValidation.Empty -> if (attachments.isEmpty()) throw IllegalArgumentException("Message must not be blank")
            is ChatTextValidation.TooLarge -> throw IllegalArgumentException(
                "Message is ${validation.utf8Bytes} bytes; maximum is ${validation.maxUtf8Bytes}"
            )
            is ChatTextValidation.Valid -> Unit
        }
        require(clientMessageId.isNotBlank()) { "clientMessageId must not be blank" }
        val now = clock()
        val request = ChatV2SendMessageRequest(clientMessageId, text, now, messageType, attachments.map { it.attachmentId })
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
            deliveryState = ChatDeliveryState.QUEUED,
            messageType = messageType,
            attachments = attachments
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
        flushReadReceipts()
        attachmentContext?.let { attachments(it).flushPending() }
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

            val mediaDraft = database.chatAttachmentDraftV2Dao().get(item.clientMessageId)
            if (mediaDraft != null && !ChatAttachmentService.matchesScope(mediaDraft, mediaScope())) {
                outbox.scheduleNextAttempt(item.outboxId, ChatOutboxV2Entity.STATE_RETRY,
                    item.attemptCount, clock() + 30_000, "CONTEXT_CHANGED", clock())
                continue
            }
            setLocalMessageState(item.clientMessageId, ChatDeliveryState.SENDING, null)
            try {
                val response = api.sendChatV2Message(
                    item.conversationId,
                    gson.fromJson(item.payloadJson, ChatV2SendMessageRequest::class.java)
                )
                if (mediaDraft != null && !ChatAttachmentService.matchesScope(mediaDraft, mediaScope())) {
                    outbox.scheduleNextAttempt(item.outboxId, ChatOutboxV2Entity.STATE_RETRY,
                        item.attemptCount, clock() + 30_000, "CONTEXT_CHANGED", clock())
                    continue
                }
                val body = response.body()
                val serverMessage = body?.message
                if (response.isSuccessful && body?.success == true && serverMessage != null &&
                    serverMessage.conversationId == item.conversationId && serverMessage.clientMessageId == item.clientMessageId) {
                    database.withTransaction {
                        importServerMessage(serverMessage)
                        outbox.markSent(item.clientMessageId, clock())
                    }
                    attachmentContext?.let { attachments(it).accepted(item.clientMessageId) }
                    sent++
                } else {
                    val result = scheduleFailure(item, "HTTP_${response.code()}", isPermanent(response.code()))
                    if (result) permanentlyFailed++ else retryScheduled++
                }
            } catch (error: CancellationException) {
                outbox.scheduleNextAttempt(item.outboxId, ChatOutboxV2Entity.STATE_RETRY,
                    item.attemptCount, clock() + 1_000, "SEND_INTERRUPTED", clock())
                throw error
            } catch (error: Exception) {
                val code = if (error is IOException) "NETWORK_IO" else "SEND_EXCEPTION"
                val result = scheduleFailure(item, code, permanent = false)
                if (result) permanentlyFailed++ else retryScheduled++
            }
        }
        return ChatV2FlushResult(ready.size, sent, retryScheduled, permanentlyFailed)
    }

    /**
     * Rewrites the author's own message.
     *
     * The server returns the stored message, which is cached so the new text and
     * the "edited" mark survive the next synchronisation.
     */
    /**
     * Finds a message by any of its identifiers.
     *
     * The list gives the UI the client identifier, while the stored row is keyed by
     * the identifier the server assigned once it accepted the message. Looking in
     * only one column made a removal fail with "no local row" while the message was
     * plainly on screen, because the two identifiers are different values.
     */
    private suspend fun findMessage(messageId: String): ChatMessageV2Entity? =
        messages.getByMessageId(messageId)
            ?: messages.getByServerMessageId(messageId)
            ?: messages.getByClientMessageId(messageId)

    suspend fun editMessage(messageId: String, newText: String): Boolean {
        val existing = findMessage(messageId)
        if (existing == null) {
            Log.w(
                TAG,
                "editMessage: no local row for id=$messageId (stored=${messages.countAll()})"
            )
            return false
        }
        val text = newText.trim()
        if (text.isEmpty()) return false

        val response = runCatching {
            api.editChatV2Message(
                existing.conversationId,
                // The server knows the message by its own identifier; a locally
                // created row still carries a UUID in the id column.
                existing.serverMessageId ?: existing.messageId,
                ChatV2EditMessageRequest(text)
            )
        }.onFailure { Log.e(TAG, "editMessage request failed", it) }.getOrNull()
        Log.i(TAG, "editMessage: http=${response?.code()}")
        val updated = response?.takeIf { it.isSuccessful }?.body()?.message
            ?: return false

        messages.update(
            updated.toDomain().toEntity().copy(
                localId = existing.localId,
                syncState = ChatMessageV2Entity.SYNC_STATE_SYNCED
            )
        )
        return true
    }

    /**
     * Removes a message from this device's list.
     *
     * The row is deleted locally and hidden on the server for this device only,
     * so the other participants keep their copy.
     */
    suspend fun deleteMessageForMe(messageId: String): Boolean {
        val existing = findMessage(messageId)
        if (existing == null) {
            Log.w(
                TAG,
                "deleteMessageForMe: no local row for id=$messageId (stored=${messages.countAll()})"
            )
            return false
        }

        val response = runCatching {
            // The server knows the message by its own identifier. A message this
            // device sent carries a local UUID in the id column until the server
            // answers, so the server identifier is preferred and the other two are
            // only fallbacks.
            api.deleteChatV2Message(
                existing.conversationId,
                existing.serverMessageId ?: existing.messageId,
                false
            )
        }.onFailure { Log.e(TAG, "deleteMessageForMe request failed", it) }.getOrNull()
        Log.i(TAG, "deleteMessageForMe: http=${response?.code()}")
        if (response?.isSuccessful != true) return false

        messages.deleteById(existing.messageId)
        return true
    }

    /**
     * Withdraws a message for every participant.
     *
     * Only the author may do this; the server enforces it. The cached row keeps
     * its place but loses its text, matching what the others now see.
     */
    suspend fun deleteMessageForEveryone(messageId: String): Boolean {
        val existing = findMessage(messageId)
        if (existing == null) {
            Log.w(
                TAG,
                "deleteMessageForEveryone: no local row for id=$messageId (stored=${messages.countAll()})"
            )
            return false
        }

        val response = runCatching {
            api.deleteChatV2Message(
                existing.conversationId,
                existing.serverMessageId ?: existing.messageId,
                true
            )
        }.onFailure { Log.e(TAG, "deleteMessageForEveryone request failed", it) }.getOrNull()
        val updated = response?.takeIf { it.isSuccessful }?.body()?.message
            ?: return false

        messages.update(
            updated.toDomain().toEntity().copy(
                localId = existing.localId,
                syncState = ChatMessageV2Entity.SYNC_STATE_SYNCED
            )
        )
        return true
    }

    /**
     * Reads the shared settings of a group conversation.
     *
     * These live on the server, not in the local cache, so they are fetched when
     * needed; the answer also says whether this device may change them.
     */
    suspend fun loadGroupSettings(conversationId: String): ChatV2GroupSettingsResponse? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                val response = api.getChatV2GroupSettings(conversationId)
                response.takeIf { it.isSuccessful }?.body()?.takeIf {
                    it.success && it.conversationId == conversationId
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the family roster from this response, not from accumulated cached chats. */
    suspend fun loadFamilyMembers(familyId: String): List<ru.childwatch.shared.chat.ConversationMember> {
        val body = requireSuccessful(api.getChatV2Conversations(), "LIST_CONVERSATIONS")
        if (!body.success) throw ChatV2RepositoryException("LIST_CONVERSATIONS_REJECTED")
        val family = body.conversations.firstOrNull {
            it.type.equals("FAMILY", true) && it.familyId == familyId
        } ?: throw ChatV2RepositoryException("CONVERSATION_ACCESS_DENIED")
        return family.members.map { it.toDomain() }
    }

    /**
     * Renames the group for everyone. The server refuses a non-administrator.
     *
     * A refusal is raised rather than answered with null: the caller has to be able to
     * say which rule was broken, and a screen that only learns "it did not work" ends
     * up with a sentence that fits none of the reasons.
     */
    suspend fun renameGroup(conversationId: String, title: String): ChatV2GroupSettingsResponse {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "title must not be empty" }
        val response = api.updateChatV2GroupTitle(
            conversationId,
            ChatV2UpdateGroupTitleRequest(trimmed)
        )
        val body = requireSuccessful(response, "UPDATE_GROUP_TITLE")
        return body.takeIf { it.success }
            ?: throw ChatV2RepositoryException("UPDATE_GROUP_TITLE_REJECTED")
    }

    /** Sets the shared picture of the group. The server refuses a non-administrator. */
    suspend fun updateGroupAvatar(
        conversationId: String,
        avatarKey: String?
    ): ChatV2GroupSettingsResponse {
        val response = api.updateChatV2GroupAvatar(
            conversationId,
            ChatV2UpdateGroupAvatarRequest(avatarKey)
        )
        val body = requireSuccessful(response, "UPDATE_GROUP_AVATAR")
        return body.takeIf { it.success }
            ?: throw ChatV2RepositoryException("UPDATE_GROUP_AVATAR_REJECTED")
    }

    suspend fun retryFailed(clientMessageId: String): Boolean = database.withTransaction {
        val changed = outbox.retryFailed(clientMessageId, clock()) == 1
        if (changed) setLocalMessageState(clientMessageId, ChatDeliveryState.QUEUED, null)
        changed
    }

    /**
     * Renames a conversation on this device.
     *
     * The name is local on purpose: it is a personal label, and the server has
     * no concept of a per-device title. A blank name clears it, so the original
     * title from the server is shown again.
     */
    suspend fun renameConversation(conversationId: String, title: String?): Boolean {
        val normalized = title?.trim()?.takeIf { it.isNotEmpty() }
        return conversations.updateTitle(conversationId, normalized, clock()) == 1
    }

    /**
     * Removes a conversation from this device's list.
     *
     * Messages are kept: the row is archived rather than deleted, so an
     * accidental removal can be undone and history survives if the same
     * conversation is opened again.
     */
    suspend fun removeConversationFromList(conversationId: String): Boolean =
        conversations.setArchived(conversationId, archived = true, updatedAt = clock()) == 1

    /** Brings every removed conversation back into the list. */
    suspend fun restoreAllConversations(): Int {
        val hidden = conversations.getAllIncludingArchived().filter { it.isArchived }
        var restored = 0
        hidden.forEach { row ->
            restored += conversations.setArchived(row.conversationId, archived = false, updatedAt = clock())
        }
        return restored
    }

    suspend fun markDeliveredThrough(conversationId: String, sequence: Long): ChatV2ReceiptDto =
        advanceReceipt(conversationId, deliveredThrough = sequence, readThrough = null)

    suspend fun markReadThrough(conversationId: String, sequence: Long) {
        require(sequence >= 0)
        val scope = activeReceiptScope() ?: throw ChatV2RepositoryException("READ_CONTEXT_UNAVAILABLE")
        val now = clock()
        database.withTransaction {
            val conversation = conversations.getById(conversationId)
                ?: throw ChatV2RepositoryException("READ_CONVERSATION_UNAVAILABLE")
            if (conversation.familyId != scope.family || members.get(conversationId, scope.member)?.isLocalUser != true) {
                throw ChatV2RepositoryException("READ_CONTEXT_MISMATCH")
            }
            if (activeReceiptScope() != scope) throw ChatV2RepositoryException("READ_CONTEXT_CHANGED")
            val read = maxOf(conversation.lastReadSequence, sequence)
            val key = org.json.JSONArray(listOf(scopeKey(scope), conversationId, read)).toString()
            outbox.enqueueIfAbsent(ChatOutboxV2Entity(
                clientMessageId = "read:" + key, conversationId = conversationId,
                payloadJson = JSONObject().put("readThrough", read).toString(), text = scopeKey(scope),
                clientSentAt = now, state = "READ_PENDING", nextAttemptAt = now,
                createdAt = now, updatedAt = now
            ))
            conversations.updateSequenceState(conversationId, maxOf(conversation.lastSequence, read), read,
                ChatV2ReadReceiptPolicy.unreadAfterViewing(conversation.lastSequence, read, conversation.unreadCount), now)
            messages.markLocallyViewed(conversationId, read, scope.member)
        }
    }

    suspend fun hasPendingReadReceipt(conversationId: String): Boolean {
        val scope = activeReceiptScope() ?: return false
        return outbox.pendingReadReceiptCount(scopeKey(scope), conversationId) > 0
    }

    private fun activeReceiptScope(): ChatV2ReceiptScope? = receiptScope
        ?.takeIf { it.isComplete() && it == receiptScopeProvider() }

    private fun scopeKey(scope: ChatV2ReceiptScope): String = org.json.JSONArray(
        listOf(scope.server, scope.family, scope.member, scope.device)).toString()

    suspend fun flushReadReceipts() {
        val scope = activeReceiptScope() ?: return
        val ready = outbox.getReadyReadReceipts(scopeKey(scope), clock(), OUTBOX_BATCH_SIZE)
        for (item in ready) {
            if (activeReceiptScope() != scope) return
            val conversation = conversations.getById(item.conversationId) ?: continue
            if (conversation.familyId != scope.family || members.get(item.conversationId, scope.member)?.isLocalUser != true) continue
            val now = clock()
            val lease = idFactory()
            if (outbox.acquireReadReceipt(item.outboxId, lease, now + OUTBOX_LEASE_MS, now) != 1) continue
            try {
                val read = JSONObject(item.payloadJson).getLong("readThrough")
                val receipt = advanceReceipt(item.conversationId, read, read, scope)
                if (receipt.readThroughSequence < read) throw ChatV2RepositoryException("READ_ACK_BEHIND")
                outbox.finishReadReceipt(item.outboxId, lease, "READ_SENT", clock(), item.attemptCount, null, clock())
            } catch (cancelled: CancellationException) {
                // The lease expires after process death/cancellation; the durable row remains pending.
                throw cancelled
            } catch (error: Exception) {
                val attempts = (item.attemptCount + 1).coerceAtMost(30)
                outbox.finishReadReceipt(item.outboxId, lease, "READ_PENDING",
                    ChatV2RetryPolicy.nextAttemptAt(clock(), attempts), attempts,
                    (error as? ChatV2RepositoryException)?.code ?: "READ_NETWORK_FAILURE", clock())
                break // Avoid a batch of network timeouts delaying other chat work.
            }
        }
    }

    private suspend fun advanceReceipt(
        conversationId: String,
        deliveredThrough: Long?,
        readThrough: Long?,
        expectedScope: ChatV2ReceiptScope? = activeReceiptScope()
    ): ChatV2ReceiptDto {
        require((deliveredThrough ?: readThrough ?: -1) >= 0) { "Receipt sequence must not be negative" }
        if (expectedScope == null || activeReceiptScope() != expectedScope) throw ChatV2RepositoryException("RECEIPT_CONTEXT_CHANGED")
        val response = api.sendChatV2Receipt(
            conversationId,
            ChatV2ReceiptRequest(deliveredThrough, readThrough)
        )
        val body = requireSuccessful(response, "SEND_RECEIPT")
        val receipt = body.receipt
            ?.takeIf { body.success }
            ?: throw ChatV2RepositoryException("SEND_RECEIPT_REJECTED")
        if (receipt.conversationId != conversationId || !ChatV2ReadReceiptPolicy.acceptsReceipt(expectedScope, activeReceiptScope(), receipt.memberId)) {
            throw ChatV2RepositoryException("RECEIPT_CONTEXT_CHANGED")
        }
        val now = clock()
        database.withTransaction {
            if (activeReceiptScope() != expectedScope) throw ChatV2RepositoryException("RECEIPT_CONTEXT_CHANGED")
            val conversation = conversations.getById(conversationId)
            if (conversation != null) {
                val lastRead = maxOf(conversation.lastReadSequence, receipt.readThroughSequence)
                val lastSequence = maxOf(conversation.lastSequence, lastRead)
                conversations.updateSequenceState(
                    conversationId,
                    lastSequence,
                    lastRead,
                    ChatV2ReadReceiptPolicy.unreadAfterViewing(lastSequence, lastRead, conversation.unreadCount),
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
                    // Carried from the server like every other field here; without it
                    // a refresh wrote an empty picture over the stored one.
                    avatarKey = model.avatarKey,
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
            unreadCount = ChatV2UnreadMergePolicy.reconcileLegacy(
                server.lastSequence, server.lastReadSequence, server.unreadCount, local.unreadCount
            ),
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
            isRead = existing?.isRead == true || incoming.deliveryState == ChatDeliveryState.READ.name,
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
            // Every kind the server sends is stored as itself. Folding everything
            // that is not a direct chat into FAMILY made a group come back from the
            // cache as the family chat, and the list then offered the family's
            // settings for it.
            type = when {
                type.equals(ChatConversationV2Entity.TYPE_DIRECT, true) ->
                    ChatConversationV2Entity.TYPE_DIRECT
                type.equals(ChatConversationV2Entity.TYPE_GROUP, true) ->
                    ChatConversationV2Entity.TYPE_GROUP
                else -> ChatConversationV2Entity.TYPE_FAMILY
            },
            title = title,
            // The conversation's own picture; the server sends it for a group and
            // null for a direct chat, which has no shared settings.
            avatarKey = avatarKey,
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
            // A local rename must survive a server refresh, so the user's own
            // name is carried over rather than overwritten by the server title.
            customTitle = existing?.customTitle,
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
        unreadCount = ChatV2UnreadMergePolicy.merge(
            current.lastSequence, current.lastReadSequence, current.unreadCount,
            incoming.lastSequence, incoming.lastReadSequence, incoming.unreadCount
        ),
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
            // The server explains itself in the body, and the sentence the user
            // needs is chosen from its code. The failing status is carried along for
            // the cases that answer with nothing to explain, such as a 500.
            throw ChatV2RepositoryException(errorCode(response, operation))
        }
        return response.body() ?: throw ChatV2RepositoryException("${operation}_EMPTY_BODY")
    }

    /** The server's own code from a refused request, or a readable stand-in for it. */
    private fun <T> errorCode(response: Response<T>, operation: String): String {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val code = body
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it).optString("code") }.getOrNull() }
            ?.trim()
        return if (code.isNullOrEmpty()) "${operation}_HTTP_${response.code()}" else code
    }
}

data class ChatV2FlushResult(
    val considered: Int,
    val sent: Int,
    val retryScheduled: Int,
    val permanentlyFailed: Int
)

class ChatV2RepositoryException(val code: String) : IllegalStateException(code)
