package ru.example.childwatch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.repository.ChatRepository
import ru.example.childwatch.database.repository.ChildRepository
import android.util.Log

/**
 * ViewModel для ChatActivity
 *
 * Управляет состоянием чата, загрузкой сообщений и взаимодействием с БД.
 * Использует LiveData и Flow для реактивных обновлений UI.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val database = ChildWatchDatabase.getInstance(application)
    private val childRepository = ChildRepository(database.childDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())

    // ID текущего ребенка
    private val _currentChildId = MutableLiveData<Long?>()
    val currentChildId: LiveData<Long?> = _currentChildId

    // Список сообщений (реактивный)
    private var messagesFlow: Flow<List<ChatMessage>>? = null
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

    /**
     * Инициализация ViewModel с deviceId
     */
    fun initialize(deviceId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "Инициализация ChatViewModel для устройства: $deviceId")

                // Получить или создать профиль ребенка
                val child = childRepository.getOrCreateChild(deviceId, "Ребенок")
                _currentChildId.value = child.id
                Log.d(TAG, "Профиль ребенка загружен: ID=${child.id}, имя=${child.name}")

                // Подписаться на обновления сообщений
                subscribeToMessages(child.id)

                // Загрузить количество непрочитанных
                loadUnreadCount(child.id)

                _isInitialized.value = true
                _isLoading.value = false
                Log.d(TAG, "ChatViewModel инициализирован успешно")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации ChatViewModel", e)
                _error.value = "Ошибка инициализации: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Подписка на реактивные обновления сообщений
     */
    private fun subscribeToMessages(childId: Long) {
        viewModelScope.launch {
            try {
                // Подписываемся на Flow сообщений
                chatRepository.getMessagesForChildFlow(childId)
                    .asLiveData(viewModelScope.coroutineContext)
                    .observeForever { messagesList ->
                        _messages.value = messagesList.sortedBy { it.timestamp }
                        Log.d(TAG, "Сообщения обновлены: ${messagesList.size} шт.")
                    }

                // Подписываемся на количество непрочитанных
                chatRepository.getUnreadCountFlow(childId)
                    .asLiveData(viewModelScope.coroutineContext)
                    .observeForever { count ->
                        _unreadCount.value = count
                        Log.d(TAG, "Непрочитанных сообщений: $count")
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подписки на сообщения", e)
                _error.value = "Ошибка загрузки сообщений: ${e.message}"
            }
        }
    }

    /**
     * Загрузить количество непрочитанных сообщений
     */
    private suspend fun loadUnreadCount(childId: Long) {
        try {
            val count = chatRepository.getUnreadCount(childId)
            _unreadCount.postValue(count)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки количества непрочитанных", e)
        }
    }

    /**
     * Отправить сообщение
     */
    fun sendMessage(message: ChatMessage) {
        val childId = _currentChildId.value
        if (childId == null) {
            _error.value = "Профиль ребенка не инициализирован"
            return
        }

        viewModelScope.launch {
            try {
                chatRepository.insertMessage(message, childId)
                Log.d(TAG, "Сообщение отправлено: ${message.text}")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки сообщения", e)
                _error.value = "Ошибка отправки: ${e.message}"
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
        val childId = _currentChildId.value ?: return

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
        super.onCleared()
        Log.d(TAG, "ChatViewModel очищен")
    }
}
