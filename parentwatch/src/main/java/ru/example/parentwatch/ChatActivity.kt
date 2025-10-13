package ru.example.parentwatch

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ru.example.parentwatch.databinding.ActivityChatBinding
import ru.example.parentwatch.chat.ChatAdapter
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.NotificationManager

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
    private lateinit var chatManager: ChatManager
    private val messages = mutableListOf<ChatMessage>()
    private val chatListener: (String, String, String, Long) -> Unit = { messageId, text, sender, timestamp ->
        runOnUiThread {
            receiveMessage(messageId, text, sender, timestamp)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize chat manager
        chatManager = ChatManager(this)

        // Setup UI
        setupUI()
        setupRecyclerView()
        loadMessages()

        // Initialize WebSocket if not connected
        initializeWebSocket()
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Чат с родителями"

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
                stackFromEnd = true // Show new messages at bottom
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

        // Create message from child
        val message = ChatMessage(
            id = System.currentTimeMillis().toString(),
            text = messageText,
            sender = "child",
            timestamp = System.currentTimeMillis(),
            isRead = false
        )

        // Add to list
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)

        // Scroll to last message
        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

        // Clear input field
        binding.messageInput.text?.clear()

        // Save message locally
        chatManager.saveMessage(message)

        // Send via WebSocket
        sendMessageViaWebSocket(message)

        Log.d(TAG, "Message sent: $messageText")
    }

    private fun sendTestMessage() {
        val testMessages = listOf(
            "Привет! Я в школе",
            "Все хорошо, не волнуйтесь",
            "Когда заберете меня?",
            "Я уже дома",
            "Нужна помощь с домашним заданием",
            "Спасибо за заботу!"
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

        Log.d(TAG, "Loaded ${messages.size} messages")
    }

    /**
     * Initialize WebSocket connection via ChatBackgroundService
     */
    private fun initializeWebSocket() {
        val prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", MainActivity.RAILWAY_URL) ?: MainActivity.RAILWAY_URL
        val deviceId = prefs.getString("device_id", "") ?: ""

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID not set, cannot initialize WebSocket")
            Toast.makeText(this, "⚠️ Device ID не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        // Start ChatBackgroundService if not running
        if (!ru.example.parentwatch.service.ChatBackgroundService.isRunning) {
            ru.example.parentwatch.service.ChatBackgroundService.start(this, serverUrl, deviceId)
            Log.d(TAG, "ChatBackgroundService started")
        }

        // Set up message callback for this activity
        WebSocketManager.addChatMessageListener(chatListener)

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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        chatManager.cleanup()
        WebSocketManager.removeChatMessageListener(chatListener)
    }
}
