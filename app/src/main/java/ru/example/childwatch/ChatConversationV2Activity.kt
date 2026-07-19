package ru.example.childwatch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vanniktech.emoji.EmojiPopup
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatV2UiRegistry
import ru.childwatch.shared.chat.ChatV2MessageDto
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationType
import ru.childwatch.shared.chat.ConversationMessage
import ru.example.childwatch.chat.ChatAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.v2.ChatV2Repository
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.utils.SecureSettingsManager

class ChatConversationV2Activity : AppCompatActivity() {
    companion object {
        const val EXTRA_CONVERSATION_ID = "CHAT_V2_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_TITLE = "CHAT_V2_CONVERSATION_TITLE"
        private const val SYNC_INTERVAL_MS = 30_000L
        private const val INITIAL_MESSAGE_LIMIT = 200
        private const val OLDER_MESSAGE_PAGE_SIZE = 100
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var repository: ChatV2Repository
    private lateinit var conversationId: String
    private var conversation: Conversation? = null
    private var adapter: ChatAdapter? = null
    private var emojiPopup: EmojiPopup? = null
    private var syncJob: Job? = null
    private var messageObserverJob: Job? = null
    private var initialScrollDone = false
    private var previousMessageCount = 0
    private var pendingNewMessages = 0
    private var displayedMessageLimit = INITIAL_MESSAGE_LIMIT
    private var nextBeforeSequence: Long? = null
    private var loadingOlderMessages = false
    private var olderMessagesPendingRender = false
    private val gson = Gson()
    private val chatV2MessageListener: (JSONObject) -> Unit = { payload ->
        handleRealtimeMessage(payload)
    }
    private val chatV2ReceiptListener: (JSONObject) -> Unit = { payload ->
        handleRealtimeEvent(payload)
    }
    private val chatV2ErrorListener: (JSONObject) -> Unit = { payload ->
        if (payload.optString("conversationId") == conversationId) {
            runOnUiThread { binding.connectionStatusText.setText(R.string.chat_v2_offline) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty().trim()
        if (conversationId.isBlank()) {
            finish()
            return
        }
        val serverUrl = resolveServerUrl()
        if (serverUrl.isBlank()) {
            Toast.makeText(this, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        repository = ChatV2Repository.create(this, serverUrl)
        configureStaticUi()
        lifecycleScope.launch { loadConversation() }
    }

    override fun onStart() {
        super.onStart()
        WebSocketManager.addChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.addChatV2ReceiptListener(chatV2ReceiptListener)
        WebSocketManager.addChatV2ErrorListener(chatV2ErrorListener)
        WebSocketManager.subscribeChatV2(conversationId)
        ChatV2UiRegistry.enter(conversationId)
        startSyncLoop()
    }

    override fun onStop() {
        syncJob?.cancel()
        syncJob = null
        WebSocketManager.unsubscribeChatV2(conversationId)
        WebSocketManager.removeChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.removeChatV2ReceiptListener(chatV2ReceiptListener)
        WebSocketManager.removeChatV2ErrorListener(chatV2ErrorListener)
        ChatV2UiRegistry.leave(conversationId)
        super.onStop()
    }

    override fun onDestroy() {
        emojiPopup?.dismiss()
        emojiPopup = null
        super.onDestroy()
    }

    private fun configureStaticUi() = with(binding) {
        chatPartnerName.text = intent.getStringExtra(EXTRA_CONVERSATION_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Семейный чат"
        chatPartnerMeta.text = "Защищённый семейный диалог"
        connectionStatusText.setText(R.string.chat_v2_syncing)
        typingIndicator.visibility = View.GONE
        chatInfoButton.visibility = View.GONE
        emptyStateTitle.setText(R.string.chat_v2_no_messages)
        emptyStateText.text = "Напишите первое сообщение"

        emojiPopup = EmojiPopup(root, messageInput)
        emojiButton.setOnClickListener { emojiPopup?.toggle() }
        sendButton.setOnClickListener { sendMessage() }
        newMessagesButton.setOnClickListener { scrollToBottom() }
        messagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isNearBottom()) {
                    pendingNewMessages = 0
                    newMessagesButton.visibility = View.GONE
                }
                val manager = recyclerView.layoutManager as? LinearLayoutManager
                if ((manager?.findFirstVisibleItemPosition() ?: Int.MAX_VALUE) <= 2) {
                    loadOlderMessages()
                }
            }
        })
    }

    private suspend fun loadConversation() {
        binding.loadingIndicator.visibility = View.VISIBLE
        conversation = repository.getCachedConversations()
            .firstOrNull { it.conversationId == conversationId }
        if (conversation == null) {
            runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
            conversation = repository.getCachedConversations()
                .firstOrNull { it.conversationId == conversationId }
        }
        val current = conversation
        if (current == null) {
            Toast.makeText(this, R.string.chat_v2_offline, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val localMember = current.members.firstOrNull { it.isLocalUser }
        val otherMember = current.members.firstOrNull { !it.isLocalUser }
        if (current.type == ConversationType.DIRECT && otherMember != null) {
            binding.chatPartnerName.text = otherMember.displayName
            binding.chatPartnerMeta.text = getString(
                R.string.chat_v2_direct_meta,
                roleLabel(otherMember.role)
            )
            FamilyAvatarRenderer.bind(binding.chatAvatar, otherMember.avatarKey)
        } else {
            binding.chatPartnerName.text = current.title
            binding.chatPartnerMeta.text = resources.getQuantityString(
                R.plurals.chat_v2_family_member_count,
                current.members.size,
                current.members.size
            )
            FamilyAvatarRenderer.bind(
                binding.chatAvatar,
                null,
                R.drawable.avatar_family_ocean
            )
        }
        val currentRole = if (localMember?.role == ConversationMemberRole.CHILD) "child" else "parent"
        adapter = ChatAdapter(
            currentUser = currentRole,
            currentUserDeviceId = localMember?.memberId,
            onRetryMessage = { failed ->
                lifecycleScope.launch {
                    repository.retryFailed(failed.id)
                    syncOnce()
                }
            }
        ).also { chatAdapter ->
            binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
            binding.messagesRecyclerView.adapter = chatAdapter
        }
        binding.loadingIndicator.visibility = View.GONE

        observeMessages(localMember?.memberId)
        lifecycleScope.launch {
            runCatching { repository.refreshConversations(resolveTargetChildDeviceId()) }
            conversation = repository.getCachedConversations()
                .firstOrNull { it.conversationId == conversationId } ?: conversation
        }
        syncOnce()
    }

    private fun roleLabel(role: ConversationMemberRole): String = when (role) {
        ConversationMemberRole.PARENT -> getString(R.string.family_role_parent)
        ConversationMemberRole.CHILD -> getString(R.string.family_role_child)
        ConversationMemberRole.GUARDIAN -> getString(R.string.family_role_relative)
    }

    private fun observeMessages(localMemberId: String?) {
        messageObserverJob?.cancel()
        messageObserverJob = lifecycleScope.launch {
            repository.observeMessages(conversationId, displayedMessageLimit).collectLatest { newestFirst ->
                renderMessages(newestFirst.asReversed(), localMemberId)
            }
        }
    }

    private fun renderMessages(messages: List<ConversationMessage>, localMemberId: String?) {
        val wasNearBottom = isNearBottom()
        val added = (messages.size - previousMessageCount).coerceAtLeast(0)
        previousMessageCount = messages.size
        val rows = messages.map { it.toLegacy(localMemberId) }
        adapter?.submitMessages(rows) {
            binding.emptyStateCard.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (!initialScrollDone || wasNearBottom) {
                initialScrollDone = true
                scrollToBottom()
            } else if (added > 0 && !olderMessagesPendingRender) {
                pendingNewMessages += added
                binding.newMessagesButton.text = getString(
                    R.string.chat_v2_new_messages,
                    pendingNewMessages
                )
                binding.newMessagesButton.visibility = View.VISIBLE
            }
            if (olderMessagesPendingRender && added > 0) olderMessagesPendingRender = false
        }
    }

    private fun sendMessage() {
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        val current = conversation ?: return
        val localMember = current.members.firstOrNull { it.isLocalUser }
        binding.messageInput.text?.clear()
        lifecycleScope.launch {
            try {
                val queued = repository.enqueueMessage(
                    conversationId = conversationId,
                    text = text,
                    senderDisplayName = localMember?.displayName ?: "Вы",
                    senderRole = localMember?.role ?: ConversationMemberRole.GUARDIAN,
                    senderMemberId = localMember?.memberId
                )
                scrollToBottom()
                val sentRealtime = WebSocketManager.sendChatV2Message(
                    conversationId = conversationId,
                    clientMessageId = queued.clientMessageId,
                    text = queued.text,
                    clientSentAt = queued.clientSentAt
                ) { acknowledgement ->
                    lifecycleScope.launch {
                        val dto = acknowledgement.optJSONObject("message")?.let { message ->
                            runCatching {
                                gson.fromJson(message.toString(), ChatV2MessageDto::class.java)
                            }.getOrNull()
                        }
                        if (acknowledgement.optBoolean("success") && dto != null) {
                            runCatching { repository.cacheRealtimeMessage(dto) }
                                .onFailure { repository.flushOutbox() }
                        } else {
                            repository.flushOutbox()
                        }
                    }
                }
                if (!sentRealtime) {
                    repository.flushOutbox()
                } else {
                    // Socket.IO acknowledgements can be lost on a network handover even when
                    // the emit itself succeeded. A delayed durable flush is idempotent and keeps
                    // the fallback latency bounded without duplicating acknowledged messages.
                    lifecycleScope.launch {
                        delay(3_000L)
                        repository.flushOutbox()
                    }
                }
            } catch (_: IllegalArgumentException) {
                Toast.makeText(
                    this@ChatConversationV2Activity,
                    "Сообщение слишком длинное или пустое",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startSyncLoop() {
        if (!::repository.isInitialized || syncJob?.isActive == true) return
        syncJob = lifecycleScope.launch {
            while (isActive) {
                syncOnce()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun handleRealtimeEvent(payload: JSONObject) {
        if (payload.optString("conversationId") != conversationId) return
        lifecycleScope.launch { syncOnce() }
    }

    private fun handleRealtimeMessage(payload: JSONObject) {
        if (payload.optString("conversationId") != conversationId) return
        val messageJson = payload.optJSONObject("message") ?: return
        lifecycleScope.launch {
            val dto = runCatching {
                gson.fromJson(messageJson.toString(), ChatV2MessageDto::class.java)
            }.getOrNull()
            if (dto == null) {
                syncOnce()
                return@launch
            }
            runCatching { repository.cacheRealtimeMessage(dto) }
                .onSuccess {
                    binding.connectionStatusText.setText(R.string.chat_v2_ready)
                    if (dto.serverSequence > 0) {
                        runCatching { repository.markDeliveredThrough(conversationId, dto.serverSequence) }
                        runCatching { repository.markReadThrough(conversationId, dto.serverSequence) }
                    }
                }
                .onFailure { syncOnce() }
        }
    }

    private fun loadOlderMessages() {
        val before = nextBeforeSequence ?: return
        if (loadingOlderMessages || !::repository.isInitialized) return
        loadingOlderMessages = true
        lifecycleScope.launch {
            try {
                val page = repository.syncMessagesPage(
                    conversationId = conversationId,
                    beforeSequence = before,
                    limit = OLDER_MESSAGE_PAGE_SIZE
                )
                nextBeforeSequence = page.nextBeforeSequence
                if (page.messages.isNotEmpty()) {
                    olderMessagesPendingRender = true
                    displayedMessageLimit += page.messages.size
                    observeMessages(conversation?.localMemberId)
                }
            } catch (_: Exception) {
                binding.connectionStatusText.setText(R.string.chat_v2_offline)
            } finally {
                loadingOlderMessages = false
            }
        }
    }

    private suspend fun syncOnce() {
        if (!::repository.isInitialized || conversation == null) return
        try {
            repository.flushOutbox()
            val page = repository.syncMessagesPage(conversationId, limit = 200)
            if (displayedMessageLimit == INITIAL_MESSAGE_LIMIT) {
                nextBeforeSequence = page.nextBeforeSequence
            }
            val latestSequence = page.messages.maxOfOrNull { it.serverSequence ?: 0 } ?: 0
            if (latestSequence > 0) {
                runCatching { repository.markDeliveredThrough(conversationId, latestSequence) }
                runCatching { repository.markReadThrough(conversationId, latestSequence) }
            }
            binding.connectionStatusText.setText(R.string.chat_v2_ready)
        } catch (_: Exception) {
            binding.connectionStatusText.setText(R.string.chat_v2_offline)
        }
    }

    private fun isNearBottom(): Boolean {
        val manager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter?.itemCount ?: 0
        return count == 0 || manager.findLastVisibleItemPosition() >= count - 3
    }

    private fun scrollToBottom() {
        val last = (adapter?.itemCount ?: 0) - 1
        if (last >= 0) binding.messagesRecyclerView.scrollToPosition(last)
        pendingNewMessages = 0
        binding.newMessagesButton.visibility = View.GONE
    }

    private fun ConversationMessage.toLegacy(localMemberId: String?): ChatMessage {
        val role = if (senderRole == ConversationMemberRole.CHILD) "child" else "parent"
        return ChatMessage(
            id = clientMessageId,
            text = text,
            sender = role,
            authorDeviceId = senderMemberId,
            authorDisplayName = if (senderMemberId == localMemberId) "Вы" else senderDisplayName,
            timestamp = serverCreatedAt ?: clientSentAt,
            isRead = deliveryState == ChatDeliveryState.READ,
            status = when (deliveryState) {
                ChatDeliveryState.QUEUED, ChatDeliveryState.SENDING -> ChatMessage.MessageStatus.SENDING
                ChatDeliveryState.ACCEPTED -> ChatMessage.MessageStatus.SENT
                ChatDeliveryState.DELIVERED -> ChatMessage.MessageStatus.DELIVERED
                ChatDeliveryState.READ -> ChatMessage.MessageStatus.READ
                ChatDeliveryState.FAILED -> ChatMessage.MessageStatus.FAILED
            }
        )
    }

    private fun resolveServerUrl(): String =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.serverUrl
            ?.trim().orEmpty()
            .ifBlank { SecureSettingsManager(this).getServerUrl().trim() }

    private fun resolveTargetChildDeviceId(): String? =
        ParentEffectiveContextProvider.get(this).featureContext("chat")?.targetDeviceId
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: SecureSettingsManager(this).getChildDeviceId()?.trim()?.takeIf { it.isNotEmpty() }
}
