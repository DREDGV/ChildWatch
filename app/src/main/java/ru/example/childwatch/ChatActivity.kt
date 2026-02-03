package ru.example.childwatch

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.chat.ChatAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.chat.ChatManagerAdapter
import ru.example.childwatch.chat.withStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.utils.SecurePreferences
import ru.example.childwatch.viewmodel.ChatViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.text.SimpleDateFormat
import java.util.*

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
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatManager: ChatManager
    private lateinit var chatManagerAdapter: ChatManagerAdapter
    private lateinit var networkClient: NetworkClient
    private lateinit var securePreferences: SecurePreferences
    private lateinit var messageQueue: ru.example.childwatch.chat.MessageQueue
    private val messages = mutableListOf<ChatMessage>()
    private val currentUser = "parent" // ChildWatch - приложение родителя

    // ViewModel для управления состоянием
    private val viewModel: ChatViewModel by viewModels()
    
    // Activity-scoped chat message listener
    private var activityChatListener: ((String, String, String, Long) -> Unit)? = null
    
    // Typing indicator
    private val typingHandler = Handler(Looper.getMainLooper())
    private var typingRunnable: Runnable? = null
    private var isCurrentlyTyping = false
    private val TYPING_TIMEOUT = 5000L // 5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize chat manager (старый для совместимости)
        chatManager = ChatManager(this)

    // Получаем deviceId (единый источник childwatch_prefs + SecurePreferences fallback)
    val deviceId = getChildDeviceId()
    val partnerId = if (deviceId.isBlank()) {
        getString(R.string.chat_partner_unknown_id)
    } else {
        deviceId
    }
    binding.chatPartnerName.text = getString(R.string.chat_partner_child)
    binding.chatPartnerMeta.text = getString(R.string.chat_partner_device_id, partnerId)
    binding.chatInfoButton.setOnClickListener {
        Toast.makeText(this, binding.chatPartnerMeta.text, Toast.LENGTH_SHORT).show()
    }
    Log.d(TAG, "Resolved childDeviceId='$deviceId' (empty=${deviceId.isEmpty()})")

    // Initialize новый адаптер с Room Database и автоматической миграцией (даже если ID пустой – позволит показать локальные сообщения)
    chatManagerAdapter = ChatManagerAdapter(this, deviceId)

        // Инициализация ViewModel
        viewModel.initialize(deviceId)

        networkClient = NetworkClient(this)
        securePreferences = SecurePreferences(this, "childwatch_prefs")

        // Инициализация очереди сообщений
        messageQueue = ru.example.childwatch.chat.MessageQueue(this)
        messageQueue.setSendCallback(object : ru.example.childwatch.chat.MessageQueue.SendCallback {
            override fun send(message: ChatMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
                sendMessageViaWebSocket(message, onSuccess, onError)
            }
        })

        // Setup UI
        setupUI()
        setupRecyclerView()
        setupViewModelObservers()
        loadMessages()

        // Mark all messages as read (обе подсистемы хранения: Room и legacy SecurePreferences)
        chatManagerAdapter.markAllAsRead()
        viewModel.markAllAsRead()
        // Также синхронизируем со старым менеджером, чтобы бейдж на главной корректно обнулялся
        try {
            chatManager.markAllAsRead()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось пометить сообщения как прочитанные в legacy-хранилище", e)
        }

        // Reset unread count in NotificationManager
        ru.example.childwatch.utils.NotificationManager.resetUnreadCount()

        // Sync chat history from server
        syncChatHistory()

        // Откладываем инициализацию WebSocket до проверки корректности deviceId
        if (deviceId.isEmpty()) {
            Log.w(TAG, "DeviceId пустой – WebSocket не будет инициализирован. Пользователь должен привязать устройство.")
            Toast.makeText(this, "⚠️ Устройство не привязано. Настройте ID ребенка в настройках.", Toast.LENGTH_LONG).show()
            updateConnectionStatus(ConnectionStatus.DISCONNECTED)
        } else {
            initializeWebSocket()
        }
    }

    // Получить URL сервера (единый источник как в MainActivity/service)
    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", "https://childwatch-production.up.railway.app")
            ?: "https://childwatch-production.up.railway.app"
    }

    // Получить deviceId (может быть из настроек/SharedPrefs)
    private fun getChildDeviceId(): String {
        // Единый источник: сначала SecurePreferences, затем shared prefs childwatch_prefs
        return try {
            val secure = SecurePreferences(this, "childwatch_prefs")
            val fromSecure = secure.getString("child_device_id", null)
            if (!fromSecure.isNullOrEmpty()) return fromSecure

            val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            val fromPrefs = prefs.getString("child_device_id", null)
            if (!fromPrefs.isNullOrEmpty()) return fromPrefs

            // Fallback: попытка получить из legacy prefs (app_prefs)
            val legacy = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("child_device_id", null)
            legacy ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения childDeviceId", e)
            ""
        }
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
                    Log.d(TAG, "📝 Started typing")
                }
                
                if (hasText) {
                    // Schedule typing stop after 5 seconds of inactivity
                    typingRunnable = Runnable {
                        if (isCurrentlyTyping) {
                            isCurrentlyTyping = false
                            WebSocketManager.sendTypingStatus(false)
                            Log.d(TAG, "📝 Stopped typing (timeout)")
                        }
                    }
                    typingHandler.postDelayed(typingRunnable!!, TYPING_TIMEOUT)
                } else if (isCurrentlyTyping) {
                    // Stop typing if field is empty
                    isCurrentlyTyping = false
                    WebSocketManager.sendTypingStatus(false)
                    Log.d(TAG, "📝 Stopped typing (empty)")
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
            messages.clear()
            messages.addAll(messagesList)
            chatAdapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) {
                binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
            }
            sendReadReceiptsFor(messagesList)
        }

        // Наблюдение за непрочитанными сообщениями
        viewModel.unreadCount.observe(this) { count ->
            Log.d(TAG, "ViewModel: непрочитанных сообщений: $count")
            // Можно обновить счетчик в UI
        }

        // Наблюдение за состоянием загрузки
        viewModel.isLoading.observe(this) { isLoading ->
            // TODO: Показать/скрыть индикатор загрузки
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
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show()
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
            id = System.currentTimeMillis().toString(),
            text = messageText,
            sender = currentUser, // "parent"
            timestamp = System.currentTimeMillis(),
            isRead = false,
            status = ChatMessage.MessageStatus.SENDING
        )
        
        // Добавляем в список
        messages.add(message)
        val messagePosition = messages.size - 1
        chatAdapter.notifyItemInserted(messagePosition)
        
        // Прокручиваем к последнему сообщению
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
        
        // Очищаем поле ввода
        binding.messageInput.text?.clear()

        // Сохраняем сообщение (используем новый адаптер и ViewModel)
        chatManagerAdapter.saveMessage(message)
        viewModel.sendMessage(message)

        // Добавляем в очередь для надежной отправки
        messageQueue.enqueue(message)

        Log.d(TAG, "Message queued: $messageText, pending: ${messageQueue.size()}")
    }


    private fun sendReadReceiptsFor(messageList: List<ChatMessage>) {
        messageList
            .filter { it.sender != currentUser && it.status != ChatMessage.MessageStatus.READ }
            .forEach { message ->
                viewModel.updateMessageStatus(message.id, ChatMessage.MessageStatus.READ)
                chatManagerAdapter.updateMessageStatus(message.id, ChatMessage.MessageStatus.READ)
                WebSocketManager.sendChatStatus(message.id, "read", "parent")
            }
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
        chatAdapter.notifyDataSetChanged()
        chatManagerAdapter.clearAllMessages()
        viewModel.clearAllMessages()
        Toast.makeText(this, "Чат очищен", Toast.LENGTH_SHORT).show()
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

        Log.d(TAG, "Loaded ${messages.size} messages from local storage")
    }

    /**
     * Get Retrofit API instance
     */
    private fun getRetrofitApi(): ru.example.childwatch.network.ChildWatchApi {
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app")
            ?: "https://childwatch-production.up.railway.app"

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
        val childDeviceId = securePreferences.getString("child_device_id", null)
        if (childDeviceId.isNullOrEmpty()) {
            Log.w(TAG, "Child Device ID not set, skipping chat sync")
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Syncing chat history from server...")
                val response = getRetrofitApi().getChatHistory(childDeviceId, limit = 200)

                if (response.isSuccessful) {
                    val chatHistory = response.body()
                    if (chatHistory != null && chatHistory.success) {
                        val serverMessages = chatHistory.messages
                        Log.d(TAG, "Received ${serverMessages.size} messages from server")

                        // Merge server messages with local messages
                        var newMessagesCount = 0
                        serverMessages.forEach { msgData ->
                            val exists = messages.any { it.id == msgData.id.toString() }
                            if (!exists) {
                                val message = ChatMessage(
                                    id = msgData.id.toString(),
                                    text = msgData.message,
                                    sender = msgData.sender,
                                    timestamp = msgData.timestamp,
                                    isRead = msgData.isRead
                                )
                                messages.add(message)
                                chatManagerAdapter.saveMessage(message)
                                newMessagesCount++
                            }
                        }

                        if (newMessagesCount > 0) {
                            // Sort messages by timestamp
                            messages.sortBy { it.timestamp }

                            runOnUiThread {
                                chatAdapter.notifyDataSetChanged()
                                binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                                Toast.makeText(
                                    this@ChatActivity,
                                    "✅ Синхронизировано: $newMessagesCount новых сообщений",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            Log.d(TAG, "Added $newMessagesCount new messages from server")
                        } else {
                            Log.d(TAG, "No new messages from server")
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to sync chat history: ${response.code()}")
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
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "https://childwatch-production.up.railway.app")
            ?: "https://childwatch-production.up.railway.app"
        val childDeviceId = prefs.getString("child_device_id", "") ?: ""

        if (childDeviceId.isEmpty()) {
            Log.w(TAG, "Child Device ID not set, cannot initialize WebSocket")
            Toast.makeText(this, "⚠️ Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        // Start ChatBackgroundService if not running
        if (!ru.example.childwatch.service.ChatBackgroundService.isRunning) {
            ru.example.childwatch.service.ChatBackgroundService.start(this, serverUrl, childDeviceId)
            Log.d(TAG, "ChatBackgroundService started")
        }

        // Register activity-scoped listener (do not override service)
        activityChatListener = { messageId, text, sender, timestamp ->
            runOnUiThread { receiveMessage(messageId, text, sender, timestamp) }
        }
        WebSocketManager.addChatMessageListener(activityChatListener!!)
        WebSocketManager.setChildConnectedCallback {
            runOnUiThread { updateConnectionStatus(ConnectionStatus.CONNECTED) }
        }
        WebSocketManager.setChildDisconnectedCallback {
            runOnUiThread { updateConnectionStatus(ConnectionStatus.DISCONNECTED) }
        }

        // Check if already connected and update status
        if (WebSocketManager.isConnected()) {
            updateConnectionStatus(ConnectionStatus.CONNECTED)
        } else {
            updateConnectionStatus(ConnectionStatus.CONNECTING)
            // Attempt to connect
            WebSocketManager.connect(
                onConnected = {
                    runOnUiThread {
                        updateConnectionStatus(ConnectionStatus.CONNECTED)
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
        CONNECTED,      // 🟢 Green
        CONNECTING,     // 🟡 Yellow
        DISCONNECTED    // 🔴 Red
    }

    /**
     * Update connection status indicator
     */
    private fun updateConnectionStatus(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.CONNECTED -> {
                binding.connectionStatusCard.visibility = View.GONE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connected)
                binding.connectionStatusText.text = "Подключено"
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            ConnectionStatus.CONNECTING -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_connecting)
                binding.connectionStatusText.text = "Подключение..."
                binding.connectionStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
            ConnectionStatus.DISCONNECTED -> {
                binding.connectionStatusCard.visibility = View.VISIBLE
                binding.connectionStatusIcon.setBackgroundResource(R.drawable.status_disconnected)
                binding.connectionStatusText.text = "Офлайн"
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
                    Log.d(TAG, "✅ Message ${message.id} sent successfully")
                    // Update message status to SENT
                    updateMessageStatus(message.id, ChatMessage.MessageStatus.SENT)
                    onSuccess?.invoke()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Log.e(TAG, "❌ Error sending message ${message.id}: $error")
                    // Update message status to FAILED
                    updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                    onError?.invoke(error)
                }
            }
        )
    }

    /**
     * Update message status in the list
     */
    private fun updateMessageStatus(messageId: String, newStatus: ChatMessage.MessageStatus) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            messages[index] = messages[index].withStatus(newStatus)
            chatAdapter.notifyItemChanged(index)
            Log.d(TAG, "Updated message $messageId status to $newStatus")
        }
    }

    /**
     * Receive message from WebSocket
     */
    private fun receiveMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        // Check if message already exists
        if (messages.any { it.id == messageId }) {
            Log.d(TAG, "Message $messageId already exists, skipping")
            return
        }

        val message = ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = true,
            status = ChatMessage.MessageStatus.READ
        )

        // Add to list
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Scroll to last message
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

        // Save message (используем новый адаптер)
        chatManagerAdapter.saveMessage(message)
        sendReadReceiptsFor(listOf(message))

        Log.d(TAG, "Received message from $sender: $text")
        Toast.makeText(this, "💬 Новое сообщение от ${message.getSenderName()}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Show emoji picker dialog
     */
    private fun showEmojiPicker() {
        val emojis = listOf(
            "😊", "😂", "❤️", "👍", "👋", "🙏", "😍", "😢", "😭", "😡",
            "🎉", "🎊", "🎈", "🎁", "⭐", "✨", "🔥", "💯", "✅", "❌",
            "👶", "👦", "👧", "👨", "👩", "👪", "🏠", "🏫", "📚", "✏️",
            "🍎", "🍕", "🍰", "🎮", "⚽", "🏀", "🎵", "📱", "💻", "🚗"
        )

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Выберите emoji")

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
                textSize = 28f // Увеличен размер emoji
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                iconPadding = 0

                val size = (64 * resources.displayMetrics.density).toInt() // Увеличен с 48dp до 64dp
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
        builder.setNegativeButton("Закрыть", null)
        dialogInstance = builder.show()
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

                val title = if (child != null) {
                    "Чат с ${child.name}"
                } else {
                    "Чат с ребенком"
                }

                runOnUiThread {
                    supportActionBar?.title = title
                    Log.d(TAG, "Chat title set to: $title")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading child name", e)
                runOnUiThread {
                    supportActionBar?.title = "Чат"
                }
            }
        }
    }

    /**
     * Пометить сообщения как прочитанные на сервере
     */
    private fun markMessagesAsReadOnServer(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient()
                for (id in messageIds) {
                    try {
                        val url = "${getServerUrl()}/api/chat/messages/$id/read"
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

    override fun onDestroy() {
        super.onDestroy()
        
        // Stop typing indicator
        if (isCurrentlyTyping) {
            WebSocketManager.sendTypingStatus(false)
        }
        typingRunnable?.let { typingHandler.removeCallbacks(it) }
        
        chatManager.cleanup()
        chatManagerAdapter.cleanup()
        messageQueue.release()
        // Remove only this activity's listener to keep background service receiving
        activityChatListener?.let { WebSocketManager.removeChatMessageListener(it) }
        activityChatListener = null
        WebSocketManager.clearChildConnectedCallback()
        WebSocketManager.clearChildDisconnectedCallback()
    }
}
