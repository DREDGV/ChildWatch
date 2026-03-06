package ru.example.parentwatch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ru.example.parentwatch.databinding.ActivityChatBinding
import ru.example.parentwatch.chat.ChatAdapter
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatManagerAdapter
import ru.example.parentwatch.chat.withStatus
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.NotificationManager
import ru.example.parentwatch.utils.ServerUrlResolver

/**
 * Chat Activity for ParentWatch (ChildDevice)
 *
 * Features:
 * - Real-time messaging with parents
 * - Message history
 * - Parent/Child message distinction
 * - Timestamp display
 * - Message status indicators
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatManagerAdapter: ChatManagerAdapter
    private lateinit var messageQueue: ru.example.parentwatch.chat.MessageQueue
    private val messages = mutableListOf<ChatMessage>()
    private val currentUser = "child"
    private val chatListener: (String, String, String, Long) -> Unit = { messageId, text, sender, timestamp ->
        runOnUiThread {
            receiveMessage(messageId, text, sender, timestamp)
        }
    }
    private var chatStatusListener: ((String, String, Long) -> Unit)? = null
    private var chatMessageSentListener: ((String, Boolean, Long) -> Unit)? = null
    
    private val readReceiptSentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // Typing indicator
    private val typingHandler = Handler(Looper.getMainLooper())
    private var typingRunnable: Runnable? = null
    private var isCurrentlyTyping = false
    private val TYPING_TIMEOUT = 5000L // 5 seconds


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get device ID for ChatManagerAdapter initialization
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "") ?: ""
        val partnerId = if (deviceId.isBlank()) {
            getString(R.string.chat_partner_unknown_id)
        } else {
            deviceId
        }
        binding.chatPartnerName.text = getString(R.string.chat_partner_parent)
        binding.chatPartnerMeta.text = getString(R.string.chat_partner_device_id, partnerId)
        binding.chatInfoButton.setOnClickListener {
            Toast.makeText(this, binding.chatPartnerMeta.text, Toast.LENGTH_SHORT).show()
        }
        
        if (deviceId.isEmpty()) {
            Log.e(TAG, "Device ID not set - cannot initialize chat")
            Toast.makeText(this, getString(R.string.chat_error_device_not_registered), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize chat manager (Room-based, replaces old JSON ChatManager)
        chatManagerAdapter = ChatManagerAdapter(this, deviceId)

        // Initialize message queue
        messageQueue = ru.example.parentwatch.chat.MessageQueue(this)
        messageQueue.setSendCallback(object : ru.example.parentwatch.chat.MessageQueue.SendCallback {
            override fun send(message: ChatMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
                sendMessageViaWebSocket(message, onSuccess, onError)
            }
        })
        messageQueue.setReadyProvider { WebSocketManager.isReady() }

        // Setup UI
        setupUI()
        setupRecyclerView()
        loadMessages()
        NotificationManager.resetUnreadCount()

        // Initialize WebSocket if not connected
        initializeWebSocket()
    }

    private fun setupUI() {
        // Set up toolbar as action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Load parent name from preferences
        loadParentName()

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

        // Clear chat removed from UI

        // Emoji button
        binding.emojiButton.setOnClickListener {
            showEmojiPicker()
        }
        
        // Setup typing indicator
        setupTypingIndicator()
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
                    Log.d(TAG, "рџ“ќ Started typing")
                }
                
                if (hasText) {
                    // Schedule typing stop after 5 seconds of inactivity
                    typingRunnable = Runnable {
                        if (isCurrentlyTyping) {
                            isCurrentlyTyping = false
                            WebSocketManager.sendTypingStatus(false)
                            Log.d(TAG, "рџ“ќ Stopped typing (timeout)")
                        }
                    }
                    typingHandler.postDelayed(typingRunnable!!, TYPING_TIMEOUT)
                } else if (isCurrentlyTyping) {
                    // Stop typing if field is empty
                    isCurrentlyTyping = false
                    WebSocketManager.sendTypingStatus(false)
                    Log.d(TAG, "рџ“ќ Stopped typing (empty)")
                }
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages, currentUser) { message ->
            retryFailedMessage(message)
        }
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true // Show new messages at bottom
            }
            adapter = chatAdapter
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

        // Create message from child with SENDING status
        val message = ChatMessage(
            id = System.currentTimeMillis().toString(),
            text = messageText,
            sender = "child",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            status = ChatMessage.MessageStatus.SENDING
        )

        // Add to list
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Scroll to last message
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

        // Clear input field
        binding.messageInput.text?.clear()

        // Save message locally to Room Database
        chatManagerAdapter.saveMessage(message)

        // Add to queue for reliable delivery
        messageQueue.enqueue(message)

        Log.d(TAG, "Message queued: $messageText, pending: ${messageQueue.size()}")
    }



    private fun sendReadReceiptsFor(messageList: List<ChatMessage>) {
        messageList
            .filter { it.sender != currentUser && !it.isRead && it.status != ChatMessage.MessageStatus.READ }
            .filter { readReceiptSentIds.add(it.id) }
            .forEach { message ->
                updateMessageStatus(message.id, ChatMessage.MessageStatus.READ)
                chatManagerAdapter.markAsRead(message.id)
                WebSocketManager.sendChatStatus(message.id, "read", "child")
            }
    }

    private fun clearChat() {
        messages.clear()
        readReceiptSentIds.clear()
        chatAdapter.notifyDataSetChanged()
        chatManagerAdapter.clearAllMessages()
        Toast.makeText(this, getString(R.string.chat_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun loadMessages() {
        val savedMessages = chatManagerAdapter.getAllMessages()
        messages.clear()
        messages.addAll(savedMessages)
        chatAdapter.notifyDataSetChanged()

        if (messages.isNotEmpty()) {
            binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
        }

        sendReadReceiptsFor(savedMessages)

        Log.d(TAG, "Loaded ${messages.size} messages")
    }

    /**
     * Initialize WebSocket connection via ChatBackgroundService
     */
    private fun initializeWebSocket() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val serverUrl = ServerUrlResolver.getServerUrl(this)
        val deviceId = prefs.getString("device_id", "") ?: ""

        if (serverUrl.isNullOrBlank()) {
            Log.w(TAG, "Server URL not configured, cannot initialize WebSocket")
            Toast.makeText(this, getString(R.string.server_url_not_configured), Toast.LENGTH_SHORT).show()
            return
        }

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID not set, cannot initialize WebSocket")
            Toast.makeText(this, getString(R.string.chat_error_device_id_not_set), Toast.LENGTH_SHORT).show()
            return
        }

        // Start ChatBackgroundService if not running
        if (!ru.example.parentwatch.service.ChatBackgroundService.isRunning) {
            ru.example.parentwatch.service.ChatBackgroundService.start(this, serverUrl, deviceId)
            Log.d(TAG, "ChatBackgroundService started")
        }

        // Set up message callback for this activity
        WebSocketManager.addChatMessageListener(chatListener)
        if (chatStatusListener == null) {
            chatStatusListener = { messageId, status, _ ->
                runOnUiThread { handleRemoteStatusUpdate(messageId, status) }
            }
            WebSocketManager.addChatStatusListener(chatStatusListener!!)
        }
        if (chatMessageSentListener == null) {
            chatMessageSentListener = { messageId, delivered, _ ->
                runOnUiThread { handleMessageSentAck(messageId, delivered) }
            }
            WebSocketManager.addChatMessageSentListener(chatMessageSentListener!!)
        }
        WebSocketManager.setParentConnectedCallback {
            runOnUiThread { updateConnectionStatus(ConnectionStatus.CONNECTED) }
        }
        WebSocketManager.setParentDisconnectedCallback {
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
     * Connection status indicator for parent device
     */
    private enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    private fun updateConnectionStatus(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.CONNECTED -> {
                binding.connectionStatusCard.visibility = View.GONE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connected)
                binding.connectionStatusText.text = getString(R.string.chat_presence_online)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
            }

            ConnectionStatus.CONNECTING -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connecting)
                binding.connectionStatusText.text = getString(R.string.chat_presence_connecting)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }

            ConnectionStatus.DISCONNECTED -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_disconnected)
                binding.connectionStatusText.text = getString(R.string.chat_presence_offline)
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
            }
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
            onSuccess = {
                runOnUiThread {
                    Log.d(TAG, "вњ… Message ${message.id} sent successfully")
                    updateMessageStatus(message.id, ChatMessage.MessageStatus.SENT)
                    onSuccess?.invoke()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Log.e(TAG, "вќЊ Error sending message ${message.id}: $error")
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
        chatManagerAdapter.updateMessageStatus(messageId, status)
    }

    private fun handleRemoteStatusUpdate(messageId: String, status: String) {
        val mapped = when (status.lowercase(java.util.Locale.ROOT)) {
            "sent" -> ChatMessage.MessageStatus.SENT
            "delivered" -> ChatMessage.MessageStatus.DELIVERED
            "read" -> ChatMessage.MessageStatus.READ
            "failed" -> ChatMessage.MessageStatus.FAILED
            else -> null
        } ?: return

        updateMessageStatus(messageId, mapped)
        chatManagerAdapter.updateMessageStatus(messageId, mapped)
    }

    /**
     * Receive message from WebSocket
     */
    private fun receiveMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        val existingMessage = messages.firstOrNull { it.id == messageId }
        if (existingMessage != null) {
            if (existingMessage.sender != currentUser && !existingMessage.isRead) {
                sendReadReceiptsFor(listOf(existingMessage))
            }
            Log.d(TAG, "Message $messageId already exists, skipping")
            return
        }

        val message = ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = false,
            status = ChatMessage.MessageStatus.DELIVERED
        )

        // Add to list
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Scroll to last message
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

        // Save message to Room Database
        chatManagerAdapter.saveMessage(message)
        sendReadReceiptsFor(listOf(message))

        Log.d(TAG, "Received message from $sender: $text")
        Toast.makeText(this, getString(R.string.chat_new_message_from, message.getSenderName()), Toast.LENGTH_SHORT).show()
        
        // Show notification if app is not in foreground
        if (!isAppInForeground()) {
            NotificationManager.showChatNotification(
                context = this,
                senderName = message.getSenderName(),
                messageText = text,
                timestamp = timestamp
            )
        }
    }

    /**
     * Check if app is in foreground
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningTasks = activityManager.getRunningTasks(1)
        if (runningTasks.isNotEmpty()) {
            val topActivity = runningTasks[0].topActivity
            return topActivity?.packageName == packageName
        }
        return false
    }

    /**
     * Show emoji picker dialog
     */
    private fun showEmojiPicker() {
        val emojis = listOf(
            cp(0x1F60A), cp(0x1F602), cp(0x2764, 0xFE0F), cp(0x1F44D), cp(0x1F44B),
            cp(0x1F64F), cp(0x1F60D), cp(0x1F622), cp(0x1F62D), cp(0x1F621),
            cp(0x1F389), cp(0x1F38A), cp(0x1F380), cp(0x1F381), cp(0x2B50),
            cp(0x2728), cp(0x1F525), cp(0x1F4AF), cp(0x2705), cp(0x274C),
            cp(0x1F476), cp(0x1F466), cp(0x1F467), cp(0x1F468), cp(0x1F469),
            cp(0x1F46A), cp(0x1F3E0), cp(0x1F3EB), cp(0x1F4DA), cp(0x270F, 0xFE0F),
            cp(0x1F34E), cp(0x1F355), cp(0x1F370), cp(0x1F3AE), cp(0x26BD),
            cp(0x1F3C0), cp(0x1F3B5), cp(0x1F4F1), cp(0x1F4BB), cp(0x1F697)
        )

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.chat_emoji_picker_title))

        // Create grid layout for emojis
        val gridLayout = android.widget.GridLayout(this).apply {
            columnCount = 5
            setPadding(16, 16, 16, 16)
        }

        var dialogInstance: androidx.appcompat.app.AlertDialog? = null

        emojis.forEach { emoji ->
            val button = com.google.android.material.button.MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = emoji
                textSize = 28f // РЈРІРµР»РёС‡РµРЅ СЂР°Р·РјРµСЂ emoji
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                iconPadding = 0

                val size = (64 * resources.displayMetrics.density).toInt() // РЈРІРµР»РёС‡РµРЅ СЃ 48dp РґРѕ 64dp
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener {
                    // Insert emoji at cursor position
                    val cursorPosition = binding.messageInput.selectionStart
                    val currentText = binding.messageInput.text.toString()
                    val newText = currentText.substring(0, cursorPosition) +
                                 emoji +
                                 currentText.substring(cursorPosition)
                    binding.messageInput.setText(newText)
                    binding.messageInput.setSelection(cursorPosition + emoji.length)

                    // Close dialog
                    dialogInstance?.dismiss()
                }
            }
            gridLayout.addView(button)
        }

        builder.setView(gridLayout)
        builder.setNegativeButton(getString(R.string.chat_close), null)
        dialogInstance = builder.show()
    }

    private fun cp(vararg codePoints: Int): String = buildString {
        codePoints.forEach { append(String(Character.toChars(it))) }
    }

    /**
     * Load parent name from SharedPreferences and set chat title
     */
    private fun loadParentName() {
        try {
            val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            val parentName = prefs.getString("parent_name", null)

            val title = if (!parentName.isNullOrEmpty()) {
                getString(R.string.chat_title_with_name, parentName)
            } else {
                getString(R.string.chat_title_with_parents)
            }

            supportActionBar?.title = title
            Log.d(TAG, "Chat title set to: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading parent name", e)
            supportActionBar?.title = getString(R.string.chat_title_generic)
        }
    }

    /**
     * РћР±РЅРѕРІР»РµРЅРёРµ СЃС‚Р°С‚СѓСЃР° СЃРѕРѕР±С‰РµРЅРёСЏ
     */
    private fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentStatus = messages[index].status
            if (!shouldApplyStatus(currentStatus, status)) {
                return
            }
            messages[index] = messages[index].withStatus(status)
            chatAdapter.notifyItemChanged(index)
            chatManagerAdapter.saveMessage(messages[index])
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
     * РџРѕРІС‚РѕСЂРЅР°СЏ РѕС‚РїСЂР°РІРєР° РЅРµСѓРґР°РІС€РµРіРѕСЃСЏ СЃРѕРѕР±С‰РµРЅРёСЏ
     */
    private fun retryFailedMessage(message: ChatMessage) {
        Log.d(TAG, "РџРѕРІС‚РѕСЂРЅР°СЏ РѕС‚РїСЂР°РІРєР° СЃРѕРѕР±С‰РµРЅРёСЏ: ${message.id}")
        
        // РћР±РЅРѕРІР»СЏРµРј СЃС‚Р°С‚СѓСЃ РЅР° "РѕС‚РїСЂР°РІРєР°"
        updateMessageStatus(message.id, ChatMessage.MessageStatus.SENDING)
        
        // РџРѕРІС‚РѕСЂРЅРѕ РґРѕР±Р°РІР»СЏРµРј РІ РѕС‡РµСЂРµРґСЊ
        messageQueue.enqueue(message)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("chat_open", true)
            .apply()
        NotificationManager.resetUnreadCount()
    }

    override fun onPause() {
        getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("chat_open", false)
            .apply()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Stop typing indicator
        if (isCurrentlyTyping) {
            WebSocketManager.sendTypingStatus(false)
        }
        typingRunnable?.let { typingHandler.removeCallbacks(it) }
        
        chatManagerAdapter.cleanup()
        messageQueue.release()
        WebSocketManager.removeChatMessageListener(chatListener)
        chatStatusListener?.let { WebSocketManager.removeChatStatusListener(it) }
        chatStatusListener = null
        chatMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        chatMessageSentListener = null
        WebSocketManager.clearParentConnectedCallback()
        WebSocketManager.clearParentDisconnectedCallback()
    }
}
