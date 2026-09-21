package ru.example.childwatch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import ru.example.childwatch.chat.presence.PeerPresenceWatcher
import androidx.core.widget.doAfterTextChanged
import ru.example.childwatch.chat.v2.ChatV2Repository
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.FamilyAvatarRenderer
import ru.example.childwatch.utils.SecureSettingsManager

class ChatConversationV2Activity : AppCompatActivity() {
    companion object {
        const val EXTRA_CONVERSATION_ID = "CHAT_V2_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_TITLE = "CHAT_V2_CONVERSATION_TITLE"

        /**
         * Fallback poll interval.
         *
         * Socket events refresh the screen immediately; this only bounds how
         * late a change can arrive when the socket is down. At 30 seconds a read
         * receipt could sit unnoticed for half a minute, which is what made the
         * "read" mark feel broken.
         */
        private const val SYNC_INTERVAL_MS = 8_000L
        private const val INITIAL_MESSAGE_LIMIT = 200
        private const val OLDER_MESSAGE_PAGE_SIZE = 100

        /**
         * How long the author may still edit a message.
         *
         * Mirrors the server rule: editing is for fixing a mistake, not for
         * rewriting what the others have already read.
         */
        private const val MESSAGE_EDIT_WINDOW_MS = 30 * 60 * 1000L

        /**
         * How long after the last keystroke the other side is told that writing
         * stopped, and how long an unanswered indicator stays on screen.
         */
        private const val TYPING_REPORT_INTERVAL_MS = 1_500L
        private const val TYPING_INDICATOR_TIMEOUT_MS = 6_000L
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

    /** Pending "is writing" report and the timer that clears a stale indicator. */
    private var typingReportJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var hasReportedTyping = false
    private val peerNetworkClient by lazy { NetworkClient(this) }
    private val presenceWatcher by lazy {
        PeerPresenceWatcher(
            networkClient = peerNetworkClient,
            scope = lifecycleScope
        ) { snapshot -> runOnUiThread { renderPeerPresence(snapshot) } }
    }
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
        WebSocketManager.addChatV2TypingListener(chatV2TypingListener)
        WebSocketManager.subscribeChatV2(conversationId)
        ChatV2UiRegistry.enter(conversationId)
        startSyncLoop()
        startPeerPresence()
    }

    override fun onStop() {
        // Leaving the screen must not leave the other side looking at an indicator
        // that will never be cleared.
        reportTyping(false)
        presenceWatcher.stop()
        syncJob?.cancel()
        syncJob = null
        WebSocketManager.unsubscribeChatV2(conversationId)
        WebSocketManager.removeChatV2MessageListener(chatV2MessageListener)
        WebSocketManager.removeChatV2ReceiptListener(chatV2ReceiptListener)
        WebSocketManager.removeChatV2ErrorListener(chatV2ErrorListener)
        WebSocketManager.removeChatV2TypingListener(chatV2TypingListener)
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
            ?.takeIf { it.isNotBlank() } ?: "РЎРµРјРµР№РЅС‹Р№ С‡Р°С‚"
        chatPartnerMeta.text = "Р—Р°С‰РёС‰С‘РЅРЅС‹Р№ СЃРµРјРµР№РЅС‹Р№ РґРёР°Р»РѕРі"
        connectionStatusText.setText(R.string.chat_v2_syncing)
        typingIndicator.visibility = View.GONE
        chatInfoButton.visibility = View.GONE
        emptyStateTitle.setText(R.string.chat_v2_no_messages)
        emptyStateText.text = "РќР°РїРёС€РёС‚Рµ РїРµСЂРІРѕРµ СЃРѕРѕР±С‰РµРЅРёРµ"

        emojiPopup = EmojiPopup(root, messageInput)
        emojiButton.setOnClickListener { emojiPopup?.toggle() }
        sendButton.setOnClickListener { sendMessage() }
        // The send button now shows its own empty state, so it must follow the
        // input instead of looking ready when there is nothing to send.
        messageInput.doAfterTextChanged { editable ->
            sendButton.isEnabled = !editable.isNullOrBlank()
            reportTyping(!editable.isNullOrBlank())
        }
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

    /**
     * Shows "who is writing" in this conversation.
     *
     * The event carries the conversation it belongs to, so an indicator meant for a
     * different chat never appears here. It is cleared by a stop event, and also by
     * a timeout, because a phone that goes offline mid-sentence would otherwise
     * leave the indicator on forever.
     */
    private val chatV2TypingListener: (JSONObject) -> Unit = { payload ->
        val forThisConversation =
            payload.optString("conversationId").trim() == conversationId
        if (forThisConversation) {
            val isTyping = payload.optBoolean("isTyping", false)
            runOnUiThread { renderTypingIndicator(isTyping) }
        }
    }

    private fun renderTypingIndicator(isTyping: Boolean) {
        binding.typingIndicator.visibility = if (isTyping) View.VISIBLE else View.GONE
        typingTimeoutJob?.cancel()
        if (!isTyping) return
        // A stop event is not guaranteed: the other side may lose the connection
        // while writing, so the indicator is cleared on its own after a while.
        typingTimeoutJob = lifecycleScope.launch {
            delay(TYPING_INDICATOR_TIMEOUT_MS)
            binding.typingIndicator.visibility = View.GONE
        }
    }

    /**
     * Tells the other participants that this device is writing.
     *
     * Reports are throttled: a person produces a keystroke every moment, and sending
     * an event for each of them would flood the connection for no benefit. The stop
     * report is sent immediately so the indicator disappears as soon as writing ends.
     */
    private fun reportTyping(isTyping: Boolean) {
        typingReportJob?.cancel()
        if (!isTyping) {
            if (hasReportedTyping) {
                hasReportedTyping = false
                WebSocketManager.sendChatV2Typing(conversationId, false)
            }
            return
        }
        typingReportJob = lifecycleScope.launch {
            delay(TYPING_REPORT_INTERVAL_MS)
            hasReportedTyping = true
            WebSocketManager.sendChatV2Typing(conversationId, true)
        }
    }

    /**
     * Shows whether the other side is online, next to the header status.
     *
     * The server already tracks this per device link; nothing in the chat asked
     * for it, so a parent could not tell whether the child was reachable.
     */
    private fun startPeerPresence() {
        val target = resolveTargetChildDeviceId() ?: return
        val local = conversation?.members?.filter { it.isLocalUser }?.map { it.memberId }.orEmpty()
        presenceWatcher.start(
            target,
            (local + peerNetworkClient.ownDeviceId())
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        )
    }

    private fun renderPeerPresence(snapshot: PeerPresenceWatcher.PresenceSnapshot) {
        if (!snapshot.isKnown) return
        binding.peerPresenceRow.visibility = View.VISIBLE
        binding.peerPresenceText.setText(
            if (snapshot.isOnline) R.string.chat_presence_online else R.string.chat_presence_offline
        )
        val color = androidx.core.content.ContextCompat.getColor(
            this,
            if (snapshot.isOnline) R.color.presence_online else R.color.presence_offline
        )
        binding.peerPresenceText.setTextColor(color)
        binding.peerPresenceDot.background?.mutate()?.setTint(color)
    }

    /**
     * Offers the actions for one message.
     *
     * Only the author may rewrite or withdraw a message, and only while it is
     * recent, so the menu shows exactly what is possible for this message instead
     * of failing afterwards.
     */
    private fun showMessageActions(message: ChatMessage) {
        if (message.deletedAt != null) {
            Toast.makeText(this, R.string.chat_message_deleted, Toast.LENGTH_SHORT).show()
            return
        }

        val age = System.currentTimeMillis() - message.timestamp
        val isMine = message.isMine
        val labels = mutableListOf<String>()
        if (isMine && age <= MESSAGE_EDIT_WINDOW_MS) {
            labels += getString(R.string.chat_action_edit_message)
        }
        if (isMine) {
            labels += getString(R.string.chat_action_delete_for_everyone)
        }
        labels += getString(R.string.chat_action_delete_for_me)
        val actions = labels.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (isMine) message.text else message.getSenderName())
            .setItems(actions) { _, which ->
                val chosen = actions[which]
                when {
                    chosen == getString(R.string.chat_action_edit_message) ->
                        promptEditMessage(message)
                    chosen == getString(R.string.chat_action_delete_for_everyone) ->
                        confirmDeleteMessage(message, forEveryone = true)
                    else -> confirmDeleteMessage(message, forEveryone = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptEditMessage(message: ChatMessage) {
        val input = android.widget.EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            maxLines = 4
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_message_edit_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newText = input.text?.toString().orEmpty().trim()
                if (newText.isEmpty() || newText == message.text) return@setPositiveButton
                lifecycleScope.launch {
                    val done = repository.editMessage(message.id, newText)
                    Toast.makeText(
                        this@ChatConversationV2Activity,
                        if (done) R.string.chat_message_updated else R.string.chat_message_action_failed,
                        if (done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteMessage(message: ChatMessage, forEveryone: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(
                if (forEveryone) {
                    R.string.chat_action_delete_for_everyone
                } else {
                    R.string.chat_action_delete_for_me
                }
            )
            .setMessage(
                if (forEveryone) {
                    R.string.chat_delete_for_everyone_confirm
                } else {
                    R.string.chat_delete_for_me_confirm
                }
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val done = if (forEveryone) {
                        repository.deleteMessageForEveryone(message.id)
                    } else {
                        repository.deleteMessageForMe(message.id)
                    }
                    Toast.makeText(
                        this@ChatConversationV2Activity,
                        if (done) R.string.chat_message_deleted_done else R.string.chat_message_action_failed,
                        if (done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            FamilyAvatarRenderer.bind(
                binding.chatAvatar,
                otherMember.avatarKey,
                otherMember.displayName
            )
        } else {
            binding.chatPartnerName.text = current.title
            binding.chatPartnerMeta.text = resources.getQuantityString(
                R.plurals.chat_v2_family_member_count,
                current.members.size,
                current.members.size
            )
            // A family chat has no single peer, but the group itself owns a shared
            // picture; only a conversation without one falls back to the letter
            // drawn from the title.
            FamilyAvatarRenderer.bind(binding.chatAvatar, current.avatarKey, current.title)
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
            },
            onMessageLongPress = { message -> showMessageActions(message) }
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
                    senderDisplayName = localMember?.displayName ?: "Р’С‹",
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
                    "РЎРѕРѕР±С‰РµРЅРёРµ СЃР»РёС€РєРѕРј РґР»РёРЅРЅРѕРµ РёР»Рё РїСѓСЃС‚РѕРµ",
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
            isMine = senderMemberId != null && senderMemberId == localMemberId,
            editedAt = editedAt,
            deletedAt = deletedAt,
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
