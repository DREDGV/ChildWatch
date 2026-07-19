package ru.example.childwatch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vanniktech.emoji.EmojiPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.chat.ChatAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.chat.ChatMessageRuntimeRegistry
import ru.example.childwatch.chat.withStatus
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.FamilyPresenceParticipant
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.viewmodel.ChatViewModel
import java.util.*
import java.util.Locale

/**
 * Chat Activity for communication between child and parents
 *
 * Features:
 * - Real-time messaging
 * - Message history
 * - Parent/Child message distinction
 * - Timestamp display
 * - Message status indicators
 */
class ChatActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_TARGET_DEVICE_ID = "TARGET_DEVICE_ID"
        
        /**
         * Глобальный флаг активности UI чата для использования в ChatBackgroundService
         */
        var isChatUiVisible = false
            private set
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatManager: ChatManager
    private lateinit var networkClient: NetworkClient
    private lateinit var contextProvider: ParentEffectiveContextProvider
    private lateinit var effectiveContextResolver: ParentEffectiveContextResolver
    private lateinit var activeSessionStore: ParentActiveSessionStore
    private lateinit var participantNameResolver: ParentParticipantNameResolver
    private lateinit var messageQueue: ru.example.childwatch.chat.MessageQueue
    private val messages = mutableListOf<ChatMessage>()
    private var hasPerformedInitialScroll = false
    private val currentUser = "parent" // ChildWatch - приложение родителя
    private var chatInfoDetailsText: String = ""
    private var familyPresenceParticipants: List<FamilyPresenceParticipant> = emptyList()
    private val ownParentDeviceId: String by lazy {
        effectiveContextResolver.resolveOwnParentId().ifBlank {
            activeSessionStore.getSession()?.ownParentDeviceId.orEmpty()
        }
    }


    private val viewModel: ChatViewModel by viewModels()
    private var activityChatListener: ((String, String, String, Long) -> Unit)? = null
    private var chatStatusListener: ((String, String, Long) -> Unit)? = null
    private var chatMessageSentListener: ((String, Boolean, Long) -> Unit)? = null
    private var chatStatusAckListener: ((String, String, Long) -> Unit)? = null
    private val readReceiptSentIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingReadReceiptIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val readReceiptRetryRunnables = Collections.synchronizedMap(mutableMapOf<String, Runnable>())
    private var isChatUiActive = false
    private var chatUiListenersRegistered = false
    private var currentConnectionStatus = ConnectionStatus.CONNECTING
    private var emojiPopup: EmojiPopup? = null
    private val chatNamespace: String by lazy {
        contextProvider.featureContext("chat")?.storageNamespace ?: "legacy"
    }
    private val chatOpenKey: String by lazy { "$chatNamespace::chat_open" }

    private val typingHandler = Handler(Looper.getMainLooper())
    private var typingRunnable: Runnable? = null
    private var isCurrentlyTyping = false
    private val TYPING_TIMEOUT = 5000L
    private val READ_RECEIPT_RETRY_MS = 4000L
    private val MAX_READ_RECEIPT_RETRIES = 3

    /**
     * Данные для повторной отправки read receipt
     */
    private data class ReadReceiptRetry(
        val messageId: String,
        val attempts: Int = 0,
        val lastAttemptTime: Long = System.currentTimeMillis()
    )
    private val readReceiptRetries = Collections.synchronizedMap(mutableMapOf<String, ReadReceiptRetry>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contextProvider = ParentEffectiveContextProvider.get(this)
        intent.getStringExtra(EXTRA_TARGET_DEVICE_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { contextProvider.updateSelection(focusedMemberId = null, targetDeviceId = it) }
        effectiveContextResolver = ParentEffectiveContextResolver(this)
        activeSessionStore = ParentActiveSessionStore(this)
        participantNameResolver = ParentParticipantNameResolver(this)
        chatManager = ChatManager(this)
        networkClient = NetworkClient(this)

        val deviceId = getChildDeviceId()
        val partnerId = if (deviceId.isBlank()) {
            getString(R.string.chat_partner_unknown_id)
        } else {
            deviceId
        }
        val contextSource = describeProfileContextSource(effectiveContextResolver.resolve().source)
        val childDisplayName = participantNameResolver.resolveFocusedChildDisplayName(deviceId)
        binding.chatPartnerName.text = getString(R.string.chat_header_participants_title)
        binding.chatPartnerMeta.text = getString(R.string.chat_partner_meta_family)
        chatInfoDetailsText = buildString {
            append(getString(R.string.chat_info_name_line, childDisplayName))
            append('\n')
            append(getString(R.string.chat_partner_device_id, partnerId))
            append('\n')
            append(getString(R.string.profile_switch_source_line, contextSource))
        }
        binding.chatInfoButton.setOnClickListener {
            showChatInfoDialog()
        }
        Log.d(TAG, "Resolved childDeviceId='$deviceId' (empty=${deviceId.isEmpty()})")

        if (deviceId.isNotBlank()) {
            viewModel.initialize(deviceId)
        }
        messageQueue = ru.example.childwatch.chat.MessageQueue(this)
        messageQueue.setSendCallback(object : ru.example.childwatch.chat.MessageQueue.SendCallback {
            override fun send(message: ChatMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
                sendMessageViaWebSocket(message, onSuccess, onError)
            }
        })
        messageQueue.setReadyProvider { WebSocketManager.isReady() }

        setupUI()
        setupRecyclerView()
        setupViewModelObservers()

        ru.example.childwatch.utils.NotificationManager.resetUnreadCount()

        syncChatHistory()

        if (deviceId.isEmpty()) {
            Log.w(TAG, "DeviceId is empty, WebSocket is not initialized")
            Toast.makeText(this, getString(R.string.chat_partner_unknown_id), Toast.LENGTH_LONG).show()
            updateConnectionStatus(ConnectionStatus.DISCONNECTED)
        } else {
            initializeWebSocket()
        }
    }

    private fun getServerUrl(): String {
        contextProvider.featureContext("chat")?.serverUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val resolved = effectiveContextResolver.resolveServerUrl()
        if (resolved.isNotBlank()) {
            return resolved
        }
        val sessionServerUrl = activeSessionStore.getSession()?.serverUrl?.trim().orEmpty()
        if (sessionServerUrl.isNotBlank()) {
            return sessionServerUrl
        }
        return SecureSettingsManager(this).getServerUrl().trim()
    }

    private fun getChildDeviceId(): String {
        contextProvider.featureContext("chat")?.targetDeviceId?.takeIf { it.isNotBlank() }?.let { return it }
        val resolved = listOf(
            effectiveContextResolver.resolveFocusedChildId(),
            activeSessionStore.getSession()?.linkedChildDeviceId.orEmpty(),
            SecureSettingsManager(this).getChildDeviceId().orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        if (resolved.isNotBlank()) {
            contextProvider.updateSelection(focusedMemberId = null, targetDeviceId = resolved)
            activeSessionStore.updateFocusedChildId(resolved)
        }
        return resolved
    }

    private fun describeProfileContextSource(source: String): String {
        return when (source.trim().lowercase(Locale.ROOT)) {
            "session", "active_session" -> getString(R.string.profile_switch_source_session)
            "legacy", "legacy_migration" -> getString(R.string.profile_switch_source_legacy)
            "empty" -> getString(R.string.profile_switch_source_unknown)
            else -> getString(R.string.profile_switch_source_current)
        }
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.chat_title_family)

        // Загрузить имя ребенка из БД
        loadChildName()
        
        // Configure input method for Cyrillic support
        binding.messageInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
        
        // Send button
        binding.sendButton.setOnClickListener {
            sendMessage()
        }

        // Hide clear chat button (removed feature)
        binding.clearButton.visibility = View.GONE

        setupEmojiPicker()
        
        // Setup typing indicator
        setupTypingIndicator()
        updateComposerState()
    }

    private fun setupEmojiPicker() {
        emojiPopup = EmojiPopup(binding.root, binding.messageInput)
        binding.emojiButton.setOnClickListener {
            binding.messageInput.requestFocus()
            emojiPopup?.toggle()
        }
    }

    /**
     * Setup typing indicator with debounce
     */
    private fun setupTypingIndicator() {
        // Listen for incoming typing events
        WebSocketManager.setTypingCallback { isTyping ->
            runOnUiThread {
                binding.typingIndicator.visibility = if (isTyping) View.VISIBLE else View.GONE
            }
        }
        
        // Send typing events when user types
        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Cancel previous typing stop runnable
                typingRunnable?.let { typingHandler.removeCallbacks(it) }
                
                val hasText = !s.isNullOrEmpty()
                
                if (hasText && !isCurrentlyTyping) {
                    // Start typing
                    isCurrentlyTyping = true
                    WebSocketManager.sendTypingStatus(true)
                    Log.d(TAG, "Started typing")
                }
                
                if (hasText) {
                    // Schedule typing stop after 5 seconds of inactivity
                    typingRunnable = Runnable {
                        if (isCurrentlyTyping) {
                            isCurrentlyTyping = false
                            WebSocketManager.sendTypingStatus(false)
                            Log.d(TAG, "Stopped typing (timeout)")
                        }
                    }
                    typingHandler.postDelayed(typingRunnable!!, TYPING_TIMEOUT)
                } else if (isCurrentlyTyping) {
                    // Stop typing if field is empty
                    isCurrentlyTyping = false
                    WebSocketManager.sendTypingStatus(false)
                    Log.d(TAG, "Stopped typing (empty)")
                }

                updateComposerState()
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(currentUser, ownParentDeviceId) { message ->
            retryFailedMessage(message)
        }
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true // Показываем новые сообщения снизу
            }
            adapter = chatAdapter
        }
    }

    /**
     * Настройка наблюдателей за ViewModel
     */
    private fun setupViewModelObservers() {
        // Наблюдение за списком сообщений
        viewModel.messages.observe(this) { messagesList ->
            Log.d(TAG, "ViewModel: получено ${messagesList.size} сообщений")
            val previousCount = messages.size
            val shouldScrollToBottom = shouldAutoScroll(previousCount)
            messages.clear()
            messages.addAll(messagesList)
            val forceInitialScroll = messagesList.isNotEmpty() && !hasPerformedInitialScroll
            chatAdapter.submitMessages(messagesList) {
                if (messages.isNotEmpty() && (forceInitialScroll || shouldScrollToBottom)) {
                    hasPerformedInitialScroll = true
                    scrollChatToBottom(force = true)
                }
            }
            updateEmptyState()
            sendReadReceiptsFor(messagesList)
        }

        // Наблюдение за непрочитанными сообщениями
        viewModel.unreadCount.observe(this) { count ->
            Log.d(TAG, "ViewModel: непрочитанных сообщений: $count")
            // Можно обновить счетчик в UI
        }

        // Наблюдение за состоянием загрузки
        viewModel.isLoading.observe(this) { isLoading ->
            if (isLoading) {
                binding.loadingIndicator.visibility = View.VISIBLE
                binding.messagesRecyclerView.visibility = View.GONE
            } else {
                binding.loadingIndicator.visibility = View.GONE
            }
            updateEmptyState(isLoading)
            Log.d(TAG, "ViewModel: загрузка = $isLoading")
        }

        // Наблюдение за ошибками
        viewModel.error.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, "Ошибка: $it", Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    private fun sendMessage() {
        val messageText = binding.messageInput.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, getString(R.string.chat_enter_message), Toast.LENGTH_SHORT).show()
            return
        }

        // Stop typing indicator when sending
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            WebSocketManager.sendTypingStatus(false)
            typingRunnable?.let { typingHandler.removeCallbacks(it) }
        }

        // Создаем сообщение от родителя (ChildWatch - это приложение родителя)
        val message = ChatMessage(
            id = createLocalMessageId(),
            text = messageText,
            sender = currentUser, // "parent"
            authorDeviceId = ownParentDeviceId.ifBlank { null },
            authorDisplayName = participantNameResolver.resolveOwnParentDisplayName(),
            timestamp = System.currentTimeMillis(),
            isRead = false,
            status = ChatMessage.MessageStatus.SENDING
        )
        
        binding.messageInput.text?.clear()
        updateEmptyState()

        viewModel.sendMessage(message)

        messageQueue.enqueue(message)
        scrollChatToBottom(force = true)

        Log.d(TAG, "Message queued: $messageText, pending: ${messageQueue.size()}")
    }


    private fun sendReadReceiptsFor(messageList: List<ChatMessage>) {
        if (!isChatUiActive) return
        val pendingMessages = messageList
            .filter { it.isIncoming(currentUser, ownParentDeviceId) && !it.isRead && it.status != ChatMessage.MessageStatus.READ }
            .filter { it.id !in readReceiptSentIds && it.id !in pendingReadReceiptIds }

        if (pendingMessages.isEmpty()) return

        if (!WebSocketManager.isReady()) {
            pendingReadReceiptIds.clear()
            WebSocketManager.ensureConnected(
                onReady = {
                    runOnUiThread { sendReadReceiptsFor(messages.toList()) }
                },
                onError = { error ->
                    Log.w(TAG, "Read receipt retry waiting for ready state: $error")
                }
            )
            return
        }

        pendingMessages.forEach { message ->
            val sent = WebSocketManager.sendChatStatus(message.id, "read", "parent")
            if (sent) {
                pendingReadReceiptIds.add(message.id)
                // Успешно отправлено - сохраняем для отслеживания подтверждения
                readReceiptRetries[message.id] = ReadReceiptRetry(message.id, attempts = 0)
                scheduleReadReceiptRetry(message.id)
            } else {
                // Не удалось отправить - добавляем в retry очередь
                scheduleReadReceiptRetryWithBackoff(message.id)
            }
        }
    }

    private fun handleReadReceiptAck(messageId: String, status: String) {
        if (!status.equals("read", ignoreCase = true) || messageId.isBlank()) return
        // Подтверждение получено - очищаем все pending
        cancelReadReceiptRetry(messageId)
        pendingReadReceiptIds.remove(messageId)
        readReceiptRetries.remove(messageId)  // Удаляем из retry очереди
        readReceiptSentIds.add(messageId)
        updateMessageStatus(messageId, ChatMessage.MessageStatus.READ)
        viewModel.markAsRead(messageId)
        chatManager.markAsRead(messageId)
        Log.d(TAG, "✅ Read receipt confirmed: $messageId")
    }

    private fun scheduleReadReceiptRetry(messageId: String) {
        cancelReadReceiptRetry(messageId)
        val retryRunnable = Runnable {
            // Проверяем не было ли подтверждение
            if (readReceiptSentIds.contains(messageId)) {
                Log.d(TAG, "Read receipt already confirmed: $messageId")
                return@Runnable
            }
            
            // Проверяем есть ли еще в pending
            if (!pendingReadReceiptIds.remove(messageId)) {
                Log.d(TAG, "Read receipt no longer pending: $messageId")
                return@Runnable
            }
            
            if (!isChatUiActive) {
                Log.w(TAG, "Chat UI not active, skipping retry for: $messageId")
                return@Runnable
            }
            
            // Пытаемся отправить снова
            val sent = WebSocketManager.sendChatStatus(messageId, "read", "parent")
            if (sent) {
                pendingReadReceiptIds.add(messageId)
                Log.d(TAG, "🔁 Read receipt retry sent: $messageId")
                scheduleReadReceiptRetry(messageId)
            } else {
                Log.w(TAG, "⚠️ Read receipt retry failed: $messageId")
                scheduleReadReceiptRetryWithBackoff(messageId)
            }
        }
        readReceiptRetryRunnables[messageId] = retryRunnable
        typingHandler.postDelayed(retryRunnable, READ_RECEIPT_RETRY_MS)
    }
    
    /**
     * Повторная отправка с exponential backoff и лимитом попыток
     */
    private fun scheduleReadReceiptRetryWithBackoff(messageId: String) {
        val currentRetry = readReceiptRetries[messageId] ?: ReadReceiptRetry(messageId)
        
        if (currentRetry.attempts >= MAX_READ_RECEIPT_RETRIES) {
            Log.e(TAG, "❌ Max retries ($MAX_READ_RECEIPT_RETRIES) reached for read receipt: $messageId")
            readReceiptRetries.remove(messageId)
            pendingReadReceiptIds.remove(messageId)
            // Не удаляем из readReceiptSentIds - чтобы не пытаться снова
            readReceiptSentIds.add(messageId)
            return
        }
        
        val newAttempts = currentRetry.attempts + 1
        val delayMs = READ_RECEIPT_RETRY_MS * (1L shl newAttempts) // Экспоненциальная задержка: 4s, 8s, 16s
        
        Log.d(TAG, "⏳ Scheduling read receipt retry #$newAttempts for $messageId in ${delayMs}ms")
        
        readReceiptRetries[messageId] = currentRetry.copy(
            attempts = newAttempts,
            lastAttemptTime = System.currentTimeMillis()
        )
        
        val retryRunnable = Runnable {
            if (!isChatUiActive) {
                Log.w(TAG, "Chat UI not active, skipping backoff retry for: $messageId")
                return@Runnable
            }
            
            val sent = WebSocketManager.sendChatStatus(messageId, "read", "parent")
            if (sent) {
                pendingReadReceiptIds.add(messageId)
                Log.d(TAG, "✅ Backoff retry #$newAttempts sent: $messageId")
                scheduleReadReceiptRetry(messageId)
            } else {
                Log.w(TAG, "⚠️ Backoff retry #$newAttempts failed: $messageId")
                scheduleReadReceiptRetryWithBackoff(messageId)
            }
        }
        
        readReceiptRetryRunnables[messageId] = retryRunnable
        typingHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun cancelReadReceiptRetry(messageId: String) {
        val runnable = readReceiptRetryRunnables.remove(messageId) ?: return
        typingHandler.removeCallbacks(runnable)
        Log.d(TAG, "Cancelled retry for: $messageId")
    }

    private fun clearPendingReadReceiptRetries() {
        val runnables = synchronized(readReceiptRetryRunnables) { readReceiptRetryRunnables.values.toList() }
        runnables.forEach { typingHandler.removeCallbacks(it) }
        readReceiptRetryRunnables.clear()
        pendingReadReceiptIds.clear()
        readReceiptRetries.clear()  // Очищаем retry очередь
        Log.d(TAG, "Cleared all pending read receipts")
    }

    private fun sendTestMessage() {
        val testMessages = listOf(
            "Привет! Как дела?",
            "Я в школе, все хорошо",
            "Когда заберешь меня?",
            "Мне нужна помощь с домашним заданием",
            "Я уже дома"
        )
        
        val randomMessage = testMessages.random()
        binding.messageInput.setText(randomMessage)
        sendMessage()
    }

    private fun clearChat() {
        messages.clear()
        hasPerformedInitialScroll = false
        readReceiptSentIds.clear()
        chatAdapter.submitMessages(emptyList())
        chatManager.clearAllMessages()
        viewModel.clearAllMessages()
        updateEmptyState(false)
        Toast.makeText(this, getString(R.string.chat_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun getRetrofitApi(serverUrl: String): ru.example.childwatch.network.ChildWatchApi {
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(serverUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()

        return retrofit.create(ru.example.childwatch.network.ChildWatchApi::class.java)
    }

    /**
     * Sync chat history from server
     */
    private fun syncChatHistory() {
        val candidateIds = listOf(getChildDeviceId()).filter(String::isNotBlank)
        if (candidateIds.isEmpty()) {
            Log.w(TAG, "Target Device ID not set, skipping chat sync")
            return
        }

        val serverUrl = getServerUrl()
        if (serverUrl.isBlank()) {
            Log.w(TAG, "Server URL not configured, skipping chat sync")
            Toast.makeText(this, getString(R.string.server_url_missing), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Syncing chat history from server for candidates: $candidateIds")
                val networkClient = ru.example.childwatch.network.NetworkClient(this@ChatActivity)
                val existingIds = messages.map { it.id }.toHashSet()
                val mergedById = linkedMapOf<String, ChatMessage>()

                for (candidateId in candidateIds) {
                    try {
                        val response = networkClient.getChatHistory(candidateId, limit = 200)
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Failed to sync chat history for $candidateId: ${response.code()}")
                            continue
                        }

                        val chatHistory = response.body()
                        if (chatHistory == null || !chatHistory.success) {
                            continue
                        }

                        chatHistory.messages.forEach { msgData ->
                            val messageId = msgData.id
                            val status = when {
                                msgData.isRead -> ChatMessage.MessageStatus.READ
                                else -> ChatMessage.MessageStatus.DELIVERED
                            }
                            val message = ChatMessage(
                                id = messageId,
                                text = msgData.message,
                                sender = msgData.senderRole ?: msgData.sender,
                                authorDeviceId = msgData.senderDeviceId,
                                authorDisplayName = msgData.senderDisplayName,
                                timestamp = msgData.timestamp,
                                isRead = msgData.isRead,
                                status = status
                            )
                            val normalized = if (message.isOutgoing(currentUser, ownParentDeviceId) && !msgData.isRead) {
                                message.withStatus(ChatMessage.MessageStatus.SENT)
                            } else {
                                message
                            }
                            val existing = mergedById[messageId]
                            if (existing == null ||
                                normalized.timestamp > existing.timestamp ||
                                (normalized.isRead && !existing.isRead)
                            ) {
                                mergedById[messageId] = normalized
                            }
                        }
                    } catch (inner: Exception) {
                        Log.w(TAG, "Error syncing chat history for $candidateId", inner)
                    }
                }

                val newMessages = mergedById.values
                    .filterNot { existingIds.contains(it.id) }
                    .sortedBy { it.timestamp }

                if (newMessages.isNotEmpty()) {
                    viewModel.saveMessages(newMessages)
                    Log.d(TAG, "Added ${newMessages.size} new messages from server")
                } else {
                    Log.d(TAG, "No new messages from server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing chat history", e)
            }
        }
    }

    /**
     * Initialize WebSocket connection via ChatBackgroundService
     */
    private fun initializeWebSocket() {
        val serverUrl = getServerUrl()
        val childDeviceId = getChildDeviceId()

        if (serverUrl.isBlank()) {
            Log.w(TAG, "Server URL not configured, cannot initialize WebSocket")
            Toast.makeText(this, getString(R.string.server_url_missing), Toast.LENGTH_SHORT).show()
            return
        }

        if (childDeviceId.isEmpty()) {
            Log.w(TAG, "Child Device ID not set, cannot initialize WebSocket")
            Toast.makeText(this, getString(R.string.chat_partner_unknown_id), Toast.LENGTH_SHORT).show()
            return
        }

        if (!ru.example.childwatch.service.ChatBackgroundService.isRunning) {
            ru.example.childwatch.service.ChatBackgroundService.start(this, serverUrl, childDeviceId)
            Log.d(TAG, "ChatBackgroundService started")
        }

        ensureChatUiListeners()
        registerChatUiListeners()
        WebSocketManager.setChildConnectedCallback {
            runOnUiThread { updateConnectionStatus(ConnectionStatus.CONNECTED) }
        }
        WebSocketManager.setChildDisconnectedCallback {
            runOnUiThread { updateConnectionStatus(ConnectionStatus.DISCONNECTED) }
        }

        if (WebSocketManager.isReady()) {
            updateConnectionStatus(ConnectionStatus.CONNECTED)
            messageQueue.retry()
        } else {
            updateConnectionStatus(ConnectionStatus.CONNECTING)
            WebSocketManager.ensureConnected(
                onReady = {
                    runOnUiThread {
                        updateConnectionStatus(ConnectionStatus.CONNECTED)
                        messageQueue.retry()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        updateConnectionStatus(ConnectionStatus.DISCONNECTED)
                        Log.e(TAG, "WebSocket connection error: $error")
                    }
                }
            )
        }
    }

    /**
     * Connection status enum
     */
    private enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    /**
     * Update connection status indicator
     */
    private fun updateConnectionStatus(status: ConnectionStatus) {
        currentConnectionStatus = status
        when (status) {
            ConnectionStatus.CONNECTED -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusCard.setCardBackgroundColor(Color.parseColor("#EAF8F0"))
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connected)
                binding.connectionStatusText.text = getString(R.string.chat_presence_online)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            ConnectionStatus.CONNECTING -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusCard.setCardBackgroundColor(Color.parseColor("#FFF4E5"))
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connecting)
                binding.connectionStatusText.text = getString(R.string.chat_presence_connecting)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
            ConnectionStatus.DISCONNECTED -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusCard.setCardBackgroundColor(Color.parseColor("#FDECEC"))
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_disconnected)
                binding.connectionStatusText.text = getString(R.string.chat_presence_offline)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }
        if (familyPresenceParticipants.isNotEmpty()) {
            renderFamilyPresenceSummary()
        }
        updateComposerState()
    }

    private fun updateComposerState() {
        val length = binding.messageInput.text?.length ?: 0
        val hasText = length > 0
        binding.sendButton.isEnabled = hasText
        binding.sendButton.alpha = if (hasText) 1f else 0.55f
        binding.composerMetaText.text = when {
            currentConnectionStatus == ConnectionStatus.DISCONNECTED ->
                getString(R.string.chat_composer_hint_offline)
            currentConnectionStatus == ConnectionStatus.CONNECTING ->
                getString(R.string.chat_composer_hint_connecting)
            hasText ->
                getString(R.string.chat_composer_hint_typing, length)
            else ->
                getString(R.string.chat_composer_hint_online)
        }
    }

    private fun updateEmptyState(isLoading: Boolean = binding.loadingIndicator.visibility == View.VISIBLE) {
        val hasMessages = messages.isNotEmpty()
        binding.emptyStateCard.visibility = if (!isLoading && !hasMessages) View.VISIBLE else View.GONE
        binding.messagesRecyclerView.visibility = if (!isLoading && hasMessages) View.VISIBLE else View.GONE
    }

    private fun shouldAutoScroll(previousCount: Int): Boolean {
        val layoutManager = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        return previousCount == 0 || lastVisible == RecyclerView.NO_POSITION || lastVisible >= previousCount - 2
    }

    private fun scrollChatToBottom(force: Boolean = false) {
        if (messages.isEmpty()) return
        if (!force && !shouldAutoScroll(messages.size)) return
        binding.messagesRecyclerView.post {
            val lastIndex = (chatAdapter.itemCount - 1).coerceAtLeast(0)
            binding.messagesRecyclerView.scrollToPosition(lastIndex)
        }
    }

    /**
     * Send message via WebSocket
     */
    private fun sendMessageViaWebSocket(
        message: ChatMessage,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        WebSocketManager.sendChatMessage(
            messageId = message.id,
            text = message.text,
            sender = message.sender,
            authorDeviceId = message.authorDeviceId,
            authorDisplayName = message.authorDisplayName,
            onSuccess = {
                runOnUiThread {
                    Log.d(TAG, "Message ${message.id} sent successfully")
                    updateMessageStatus(message.id, ChatMessage.MessageStatus.SENT)
                    onSuccess?.invoke()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Log.e(TAG, "Error sending message ${message.id}: $error")
                    updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                    onError?.invoke(error)
                }
            }
        )
    }

    private fun handleMessageSentAck(messageId: String, delivered: Boolean) {
        val status = if (delivered) {
            ChatMessage.MessageStatus.DELIVERED
        } else {
            ChatMessage.MessageStatus.SENT
        }
        updateMessageStatus(messageId, status)
        viewModel.updateMessageStatus(messageId, status)
        chatManager.updateMessageStatus(messageId, status)
    }

    private fun handleRemoteStatusUpdate(messageId: String, status: String) {
        val mapped = when (status.lowercase(Locale.ROOT)) {
            "sent" -> ChatMessage.MessageStatus.SENT
            "delivered" -> ChatMessage.MessageStatus.DELIVERED
            "read" -> ChatMessage.MessageStatus.READ
            "failed" -> ChatMessage.MessageStatus.FAILED
            else -> null
        } ?: return

        updateMessageStatus(messageId, mapped)
        viewModel.updateMessageStatus(messageId, mapped)
        chatManager.updateMessageStatus(messageId, mapped)
    }

    /**
     * Update message status in the list
     */
    private fun updateMessageStatus(messageId: String, newStatus: ChatMessage.MessageStatus) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentStatus = messages[index].status
            if (!shouldApplyStatus(currentStatus, newStatus)) {
                return
            }
            messages[index] = messages[index].withStatus(newStatus)
            chatAdapter.submitMessages(messages)
            Log.d(TAG, "Updated message $messageId status to $newStatus")
        }
    }

    private fun shouldApplyStatus(
        currentStatus: ChatMessage.MessageStatus,
        newStatus: ChatMessage.MessageStatus
    ): Boolean {
        if (newStatus == currentStatus) return false
        if (newStatus == ChatMessage.MessageStatus.FAILED) {
            return currentStatus == ChatMessage.MessageStatus.SENDING ||
                currentStatus == ChatMessage.MessageStatus.SENT
        }
        val rank = mapOf(
            ChatMessage.MessageStatus.FAILED to -1,
            ChatMessage.MessageStatus.SENDING to 0,
            ChatMessage.MessageStatus.SENT to 1,
            ChatMessage.MessageStatus.DELIVERED to 2,
            ChatMessage.MessageStatus.READ to 3
        )
        return (rank[newStatus] ?: 0) >= (rank[currentStatus] ?: 0)
    }

    /**
     * Receive message from WebSocket
     */
    private fun receiveMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        if (!isChatUiActive) {
            Log.d(TAG, "Ignoring activity chat event while UI is paused: $messageId")
            return
        }
        val existingMessage = messages.firstOrNull { it.id == messageId }
        if (existingMessage != null) {
            if (existingMessage.isIncoming(currentUser, ownParentDeviceId) && !existingMessage.isRead) {
                sendReadReceiptsFor(listOf(existingMessage))
            }
            Log.d(TAG, "Message $messageId already exists, skipping")
            return
        }

        val message = ChatMessageRuntimeRegistry.find(messageId) ?: ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = false,
            status = ChatMessage.MessageStatus.DELIVERED
        )

        viewModel.saveMessage(message)
        sendReadReceiptsFor(listOf(message))

        Log.d(TAG, "Received message from $sender: $text")
        Toast.makeText(this, getString(R.string.chat_new_message_from, message.getSenderName()), Toast.LENGTH_SHORT).show()
    }

    private fun ensureChatUiListeners() {
        if (activityChatListener == null) {
            activityChatListener = { messageId, text, sender, timestamp ->
                runOnUiThread { receiveMessage(messageId, text, sender, timestamp) }
            }
        }
        if (chatStatusListener == null) {
            chatStatusListener = { messageId, status, _ ->
                runOnUiThread { handleRemoteStatusUpdate(messageId, status) }
            }
        }
        if (chatMessageSentListener == null) {
            chatMessageSentListener = { messageId, delivered, _ ->
                runOnUiThread { handleMessageSentAck(messageId, delivered) }
            }
        }
        if (chatStatusAckListener == null) {
            chatStatusAckListener = { messageId, status, _ ->
                runOnUiThread { handleReadReceiptAck(messageId, status) }
            }
        }
    }

    private fun registerChatUiListeners() {
        if (chatUiListenersRegistered) return
        ensureChatUiListeners()
        activityChatListener?.let { WebSocketManager.addChatMessageListener(it) }
        chatStatusListener?.let { WebSocketManager.addChatStatusListener(it) }
        chatMessageSentListener?.let { WebSocketManager.addChatMessageSentListener(it) }
        chatStatusAckListener?.let { WebSocketManager.addChatStatusAckListener(it) }
        chatUiListenersRegistered = true
    }

    private fun unregisterChatUiListeners() {
        if (!chatUiListenersRegistered) return
        activityChatListener?.let { WebSocketManager.removeChatMessageListener(it) }
        chatStatusListener?.let { WebSocketManager.removeChatStatusListener(it) }
        chatMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        chatStatusAckListener?.let { WebSocketManager.removeChatStatusAckListener(it) }
        chatUiListenersRegistered = false
    }

    /**
     * Загрузить имя ребенка из БД и установить в заголовок
     */
    private fun loadChildName() {
        lifecycleScope.launch {
            try {
                val deviceId = getChildDeviceId()
                val database = ru.example.childwatch.database.ChildWatchDatabase.getInstance(this@ChatActivity)
                val child = database.childDao().getByDeviceId(deviceId)
                val childDisplayName = child?.name?.trim().takeUnless { it.isNullOrBlank() }
                    ?: participantNameResolver.resolveFocusedChildDisplayName(deviceId)

                runOnUiThread {
                    chatInfoDetailsText = updateChatInfoDetails(name = childDisplayName)
                    supportActionBar?.title = getString(R.string.chat_title_family)
                    refreshFamilyPresenceSummary()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading child name", e)
                runOnUiThread {
                    val fallbackName = participantNameResolver.resolveFocusedChildDisplayName()
                    chatInfoDetailsText = updateChatInfoDetails(name = fallbackName)
                    supportActionBar?.title = getString(R.string.chat_title_family)
                    refreshFamilyPresenceSummary()
                }
            }
        }
    }

    private fun updateChatInfoDetails(name: String): String {
        val deviceId = getChildDeviceId().ifBlank { getString(R.string.chat_partner_unknown_id) }
        val contextSource = describeProfileContextSource(effectiveContextResolver.resolve().source)
        return buildString {
            append(getString(R.string.chat_info_name_line, name))
            append('\n')
            append(getString(R.string.chat_info_total_line, familyPresenceParticipants.size.coerceAtLeast(1)))
            val onlineNames = familyPresenceParticipants.filter { it.isOnline }.map { it.displayName }
            val offlineNames = familyPresenceParticipants.filterNot { it.isOnline }.map { it.displayName }
            if (onlineNames.isNotEmpty()) {
                append('\n')
                append(getString(R.string.chat_info_online_line, onlineNames.joinToString(", ")))
            }
            if (offlineNames.isNotEmpty()) {
                append('\n')
                append(getString(R.string.chat_info_offline_line, offlineNames.joinToString(", ")))
            }
            append('\n')
            append(getString(R.string.chat_partner_device_id, deviceId))
            append('\n')
            append(getString(R.string.profile_switch_source_line, contextSource))
        }.also { chatInfoDetailsText = it }
    }

    private fun refreshFamilyPresenceSummary() {
        val childDeviceId = getChildDeviceId()
        if (childDeviceId.isBlank()) return

        lifecycleScope.launch {
            val response = runCatching { networkClient.getFamilyPresence(childDeviceId) }.getOrNull()
            val body = response?.takeIf { it.isSuccessful }?.body()
            if (body?.success != true) {
                renderFamilyPresenceSummary()
                return@launch
            }

            familyPresenceParticipants = body.participants
            val childName = familyPresenceParticipants
                .firstOrNull { it.role == "child" }
                ?.displayName
                .orEmpty()
                .ifBlank { participantNameResolver.resolveFocusedChildDisplayName(childDeviceId) }
            renderFamilyPresenceSummary()
            chatInfoDetailsText = updateChatInfoDetails(childName)
        }
    }

    private fun renderFamilyPresenceSummary() {
        binding.chatPartnerName.text = getString(R.string.chat_header_participants_title)
        if (familyPresenceParticipants.isEmpty()) {
            binding.connectionStatusText.text = getString(R.string.chat_presence_connecting)
            binding.chatPartnerMeta.text = getString(R.string.chat_partner_meta_family)
            return
        }

        val totalCount = familyPresenceParticipants.size.coerceAtLeast(1)
        val onlineCount = familyPresenceParticipants.count { it.isOnline }
        val offlineCount = (totalCount - onlineCount).coerceAtLeast(0)
        binding.connectionStatusText.text = if (onlineCount > 0) {
            getString(R.string.chat_presence_online_count, onlineCount)
        } else {
            getString(R.string.chat_presence_none_online)
        }
        binding.chatPartnerMeta.text = if (offlineCount > 0) {
            getString(R.string.chat_presence_offline_count, offlineCount)
        } else {
            getString(R.string.chat_presence_total_count, totalCount)
        }
    }

    private fun showChatInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.chat_info_dialog_title))
            .setMessage(chatInfoDetailsText)
            .setPositiveButton(R.string.chat_info_dialog_notifications) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.chat_info_dialog_close, null)
            .show()
    }

    private fun createLocalMessageId(): String {
        val authorId = ownParentDeviceId.ifBlank { "parent" }
        return "${authorId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    /**
     * Mark messages as read on the server.
     */
    private fun markMessagesAsReadOnServer(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val serverUrl = getServerUrl()
                if (serverUrl.isBlank()) {
                    Log.w(TAG, "Server URL not configured, cannot mark messages read")
                    return@withContext
                }
                val client = okhttp3.OkHttpClient()
                for (id in messageIds) {
                    try {
                        val url = "${serverUrl}/api/chat/messages/$id/read"
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .put(okhttp3.RequestBody.create(null, ByteArray(0)))
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Log.d(TAG, "Message $id marked as read on server")
                            } else {
                                Log.e(TAG, "Failed to mark message $id as read: ${response.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error marking message $id as read", e)
                    }
                }
            }
        }
    }

    /**
     * Повторная отправка неудавшегося сообщения
     */
    private fun retryFailedMessage(message: ChatMessage) {
        Log.d(TAG, "Повторная отправка сообщения: ${message.id}")

        // Обновляем статус на "отправка"
        updateMessageStatus(message.id, ChatMessage.MessageStatus.SENDING)
        
        // Повторно добавляем в очередь
        messageQueue.enqueue(message)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        isChatUiActive = true
        isChatUiVisible = true  // Устанавливаем глобальный флаг
        clearPendingReadReceiptRetries()
        getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("chat_open", true)
            .putBoolean(chatOpenKey, true)
            .apply()
        registerChatUiListeners()
        refreshFamilyPresenceSummary()
        sendReadReceiptsFor(messages.toList())
    }

    override fun onPause() {
        emojiPopup?.dismiss()
        isChatUiActive = false
        isChatUiVisible = false  // Сбрасываем глобальный флаг
        unregisterChatUiListeners()
        clearPendingReadReceiptRetries()
        getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("chat_open", false)
            .putBoolean(chatOpenKey, false)
            .apply()
        super.onPause()
    }

    override fun onDestroy() {
        emojiPopup?.dismiss()
        emojiPopup = null
        super.onDestroy()
        
        // Stop typing indicator
        if (isCurrentlyTyping) {
            WebSocketManager.sendTypingStatus(false)
        }
        typingRunnable?.let { typingHandler.removeCallbacks(it) }
        clearPendingReadReceiptRetries()
        
        chatManager.cleanup()
        messageQueue.release()
        unregisterChatUiListeners()
        activityChatListener = null
        chatStatusListener = null
        chatMessageSentListener = null
        chatStatusAckListener = null
        WebSocketManager.clearChildConnectedCallback()
        WebSocketManager.clearChildDisconnectedCallback()
    }
}
