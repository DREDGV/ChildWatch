package ru.example.childwatch

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.utils.SecurePreferences
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
    // Пометить сообщения как прочитанные на сервере
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

    companion object {
        private const val TAG = "ChatActivity"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatManager: ChatManager
    private lateinit var networkClient: NetworkClient
    private lateinit var securePreferences: SecurePreferences
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize chat manager
        chatManager = ChatManager(this)
        networkClient = NetworkClient(this)
        securePreferences = SecurePreferences(this, "childwatch_prefs")

        // Setup UI
        setupUI()
        setupRecyclerView()
        loadMessages()

        // Mark all messages as read
        chatManager.markAllAsRead()

        // Reset unread count in NotificationManager
        ru.example.childwatch.utils.NotificationManager.resetUnreadCount()

        // Sync chat history from server
        syncChatHistory()

        // Initialize WebSocket with missed messages callback
        WebSocketManager.initialize(
            this,
            getServerUrl(),
            getChildDeviceId(),
            onMissedMessages = { missed ->
                runOnUiThread {
                    val newIds = mutableListOf<String>()
                    for (msg in missed) {
                        if (messages.none { it.id == msg.id }) {
                            messages.add(msg.copy(isRead = true))
                            chatAdapter.notifyItemInserted(messages.size - 1)
                            chatManager.saveMessage(msg.copy(isRead = true))
                            newIds.add(msg.id)
                            // Показываем уведомление для каждого нового сообщения
                            ru.example.childwatch.utils.NotificationManager.showChatNotification(
                                this,
                                msg.getSenderName(),
                                msg.text,
                                msg.timestamp
                            )
                        }
                    }
                    if (newIds.isNotEmpty()) {
                        markMessagesAsReadOnServer(newIds)
                        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                        Toast.makeText(this, "📥 Догружено сообщений: ${newIds.size}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        initializeWebSocket()
    }

    // Получить URL сервера (может быть из настроек)
    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
    }

    // Получить deviceId (может быть из настроек/SharedPrefs)
    private fun getChildDeviceId(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("child_device_id", "") ?: ""
    }
    
    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Общение с ребенком"
        
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
        
        // Clear chat button
        binding.clearButton.setOnClickListener {
            clearChat()
        }
        
        // Test message button
        binding.testButton.setOnClickListener {
            sendTestMessage()
        }
    }
    
    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true // Показываем новые сообщения снизу
            }
            adapter = chatAdapter
        }
    }
    
    private fun sendMessage() {
        val messageText = binding.messageInput.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаем сообщение от ребенка (ChildWatch - это приложение ребенка)
        val message = ChatMessage(
            id = System.currentTimeMillis().toString(),
            text = messageText,
            sender = "child",
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        
        // Добавляем в список
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        
        // Прокручиваем к последнему сообщению
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
        
        // Очищаем поле ввода
        binding.messageInput.text?.clear()
        
        // Сохраняем сообщение
        chatManager.saveMessage(message)

        // Отправляем через WebSocket
        sendMessageViaWebSocket(message)

        Log.d(TAG, "Message sent: $messageText")
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
        chatManager.clearAllMessages()
        Toast.makeText(this, "Чат очищен", Toast.LENGTH_SHORT).show()
    }
    
    private fun loadMessages() {
        val savedMessages = chatManager.getAllMessages()
        messages.clear()
        messages.addAll(savedMessages)
        chatAdapter.notifyDataSetChanged()

        if (messages.isNotEmpty()) {
            binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
        }

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
                                chatManager.saveMessage(message)
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

        // Set up message callback for this activity
        WebSocketManager.setChatMessageCallback { messageId, text, sender, timestamp ->
            runOnUiThread {
                receiveMessage(messageId, text, sender, timestamp)
            }
        }

        // Check if already connected
        if (WebSocketManager.isConnected()) {
            Toast.makeText(this, "✅ Подключено к серверу", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Send message via WebSocket
     */
    private fun sendMessageViaWebSocket(message: ChatMessage) {
        WebSocketManager.sendChatMessage(
            messageId = message.id,
            text = message.text,
            sender = message.sender,
            onSuccess = {
                runOnUiThread {
                    Toast.makeText(this, "✅ Сообщение отправлено", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Log.e(TAG, "Error sending message: $error")
                    Toast.makeText(this, "❌ Ошибка отправки: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
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
            isRead = true
        )

        // Add to list
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Scroll to last message
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

        // Save message
        chatManager.saveMessage(message)

        Log.d(TAG, "Received message from $sender: $text")
        Toast.makeText(this, "💬 Новое сообщение от ${message.getSenderName()}", Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        chatManager.cleanup()
        WebSocketManager.clearChatMessageCallback()
    }
}
