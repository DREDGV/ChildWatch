package ru.example.childwatch

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.example.childwatch.databinding.ActivityChatBinding
import ru.example.childwatch.chat.ChatAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.ChatManager
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
        supportActionBar?.title = "Общение с ребенком"
        
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
        
        // Создаем сообщение от родителя
        val message = ChatMessage(
            id = System.currentTimeMillis().toString(),
            text = messageText,
            sender = "parent",
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
        
        // Отправляем на сервер (в реальном приложении)
        sendMessageToServer(message)
        
        Log.d(TAG, "Message sent: $messageText")
        Toast.makeText(this, "Сообщение отправлено", Toast.LENGTH_SHORT).show()
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
        
        Log.d(TAG, "Loaded ${messages.size} messages")
    }
    
    private fun sendMessageToServer(message: ChatMessage) {
        // В реальном приложении здесь будет отправка на сервер
        // Пока просто логируем
        Log.d(TAG, "Sending message to server: ${message.text}")
        
        // Симулируем ответ от ребенка через 2-5 секунд
        simulateChildResponse(message)
    }
    
    private fun simulateChildResponse(originalMessage: ChatMessage) {
        val responses = listOf(
            "Хорошо, мама/папа",
            "Я в безопасности",
            "Понял, буду осторожен",
            "Скоро буду дома",
            "Жду тебя",
            "Все в порядке",
            "Да, я помню"
        )
        
        val delay = (2000..5000).random().toLong()
        binding.messagesRecyclerView.postDelayed({
            val response = ChatMessage(
                id = System.currentTimeMillis().toString(),
                text = responses.random(),
                sender = "child",
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
