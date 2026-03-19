package ru.example.childwatch.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.repository.ChatRepository
import ru.example.childwatch.database.repository.ChildRepository

/**
 * ViewModel для ChatActivity
 *
 * Управляет состоянием чата, загрузкой сообщений и взаимодействием с БД.
 * Использует LiveData и Flow для реактивных обновлений UI.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val INCOMING_SENDER = "child"
    }

    private val database = ChildWatchDatabase.getInstance(application)
    private val childRepository = ChildRepository(database.childDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())

    // ID текущего ребенка
    private val _currentChildId = MutableLiveData<Long?>()
    val currentChildId: LiveData<Long?> = _currentChildId

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    // Количество непрочитанных сообщений
    private val _unreadCount = MutableLiveData<Int>(0)
    val unreadCount: LiveData<Int> = _unreadCount

    // Состояние загрузки
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Ошибки
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    // Статус инициализации
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private var messagesJob: Job? = null
    private var unreadCountJob: Job? = null

    /**
     * Инициализация ViewModel с deviceId
     */
    fun initialize(deviceId: String) {
        val normalizedDeviceId = deviceId.trim()
        if (normalizedDeviceId.isEmpty()) {
            _error.value = "Chat device ID is not configured"
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "Initializing ChatViewModel for device: $normalizedDeviceId")

                val child = childRepository.getOrCreateChild(normalizedDeviceId, "Ребенок")
                _currentChildId.value = child.id
                Log.d(TAG, "Chat child profile ready: id=${child.id}, name=${child.name}")

                subscribeToMessages(child.id)

                _isInitialized.value = true
                Log.d(TAG, "ChatViewModel initialized successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ChatViewModel", e)
                _error.value = "Chat initialization failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Подписка на реактивные обновления сообщений
     */
    private fun subscribeToMessages(childId: Long) {
        messagesJob?.cancel()
        unreadCountJob?.cancel()

        messagesJob = viewModelScope.launch {
            chatRepository.getMessagesForChildFlow(childId)
                .catch { error ->
                    Log.e(TAG, "Failed to observe chat messages", error)
                    _error.postValue("Failed to load chat messages: ${error.message}")
                }
                .collectLatest { messagesList ->
                    _messages.postValue(messagesList.sortedBy { it.timestamp })
                    Log.d(TAG, "Messages updated: ${messagesList.size}")
                }
        }

        unreadCountJob = viewModelScope.launch {
            chatRepository.getUnreadCountFlowBySender(childId, INCOMING_SENDER)
                .catch { error ->
                    Log.e(TAG, "Failed to observe unread message count", error)
                }
                .collectLatest { count ->
                    _unreadCount.postValue(count)
                    Log.d(TAG, "Unread incoming messages: $count")
                }
        }
    }

    /**
     * Отправить сообщение
     */
    fun sendMessage(message: ChatMessage) {
        persistMessages(listOf(message), "outgoing message")
    }

    fun saveMessage(message: ChatMessage) {
        persistMessages(listOf(message), "chat message")
    }

    fun saveMessages(messages: List<ChatMessage>) {
        persistMessages(messages, "chat batch")
    }

    private fun persistMessages(messages: List<ChatMessage>, label: String) {
        val childId = _currentChildId.value
        if (childId == null) {
            _error.value = "Chat profile is not initialized"
            return
        }
        if (messages.isEmpty()) return

        viewModelScope.launch {
            try {
                chatRepository.insertMessages(messages, childId)
                Log.d(TAG, "Persisted ${messages.size} $label item(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist $label", e)
                _error.value = "Failed to persist chat data: ${e.message}"
            }
        }
    }

    /**
     * Пометить все сообщения как прочитанные
     */
    fun markAllAsRead() {
        val childId = _currentChildId.value ?: return

        viewModelScope.launch {
            try {
                chatRepository.markAllAsRead(childId)
                Log.d(TAG, "Все сообщения помечены как прочитанные")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка пометки сообщений", e)
                _error.value = "Ошибка: ${e.message}"
            }
        }
    }

    /**
     * Пометить сообщение как прочитанное
     */
    fun markAsRead(messageId: String) {
        _currentChildId.value ?: return

        viewModelScope.launch {
            try {
                val message = chatRepository.getMessageByMessageId(messageId)
                if (message != null) {
                    val entity = database.chatMessageDao().getByMessageId(messageId)
                    entity?.let {
                        chatRepository.markAsRead(it.id)
                        Log.d(TAG, "Сообщение помечено как прочитанное: $messageId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка пометки сообщения", e)
            }
        }
    }

    /**
     * Обновить статус сообщения
     */
    fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        viewModelScope.launch {
            try {
                chatRepository.updateMessageStatus(messageId, status.name.lowercase())
                Log.d(TAG, "Статус сообщения обновлен: $messageId -> $status")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления статуса", e)
            }
        }
    }

    /**
     * Очистить все сообщения
     */
    fun clearAllMessages() {
        val childId = _currentChildId.value ?: return

        viewModelScope.launch {
            try {
                chatRepository.deleteMessagesForChild(childId)
                Log.d(TAG, "Все сообщения удалены")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки сообщений", e)
                _error.value = "Ошибка очистки: ${e.message}"
            }
        }
    }

    /**
     * Поиск сообщений
     */
    fun searchMessages(query: String, limit: Int = 50): LiveData<List<ChatMessage>> {
        val result = MutableLiveData<List<ChatMessage>>()
        val childId = _currentChildId.value ?: return result

        viewModelScope.launch {
            try {
                val messages = chatRepository.searchMessages(childId, query, limit)
                result.postValue(messages)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка поиска сообщений", e)
                result.postValue(emptyList())
            }
        }

        return result
    }

    /**
     * Получить последнее сообщение
     */
    fun getLatestMessage(): LiveData<ChatMessage?> {
        val result = MutableLiveData<ChatMessage?>()
        val childId = _currentChildId.value ?: return result

        viewModelScope.launch {
            try {
                val message = chatRepository.getLatestMessage(childId)
                result.postValue(message)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка получения последнего сообщения", e)
                result.postValue(null)
            }
        }

        return result
    }

    /**
     * Очистить ошибку
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        messagesJob?.cancel()
        unreadCountJob?.cancel()
        super.onCleared()
        Log.d(TAG, "ChatViewModel очищен")
    }
}
