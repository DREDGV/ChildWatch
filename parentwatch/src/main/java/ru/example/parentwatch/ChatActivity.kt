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
    }

    private fun setupUI() {
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Чат с родителями"

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

        // Save message
        chatManager.saveMessage(message)

        // Send to server (in real application)
        sendMessageToServer(message)

        Log.d(TAG, "Message sent: $messageText")
        Toast.makeText(this, "Сообщение отправлено", Toast.LENGTH_SHORT).show()
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

    private fun sendMessageToServer(message: ChatMessage) {
        // In real application, send to server via WebSocket or HTTP
        // For now, just log it
        Log.d(TAG, "Sending message to server: ${message.text}")

        // Simulate parent response after 3-6 seconds
        simulateParentResponse(message)
    }

    private fun simulateParentResponse(originalMessage: ChatMessage) {
        val responses = listOf(
            "Хорошо, дорогой",
            "Скоро приедем за тобой",
            "Молодец, береги себя",
            "Мы тебя любим",
            "Будь осторожен",
            "Обязательно поможем",
            "Ждем тебя дома"
        )

        val delay = (3000..6000).random().toLong()
        binding.messagesRecyclerView.postDelayed({
            val response = ChatMessage(
                id = System.currentTimeMillis().toString(),
                text = responses.random(),
                sender = "parent",
                timestamp = System.currentTimeMillis(),
                isRead = true
            )

            messages.add(response)
            chatAdapter.notifyItemInserted(messages.size - 1)
            binding.messagesRecyclerView.scrollToPosition(messages.size - 1)

            chatManager.saveMessage(response)

        }, delay)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        chatManager.cleanup()
    }
}
