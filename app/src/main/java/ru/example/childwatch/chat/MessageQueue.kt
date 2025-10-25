package ru.example.childwatch.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import ru.example.childwatch.utils.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Очередь сообщений для обработки неотправленных сообщений
 *
 * Функции:
 * - Сохранение неотправленных сообщений
 * - Автоматическая повторная отправка
 * - Персистентное хранилище (не теряются при перезапуске)
 */
class MessageQueue(private val context: Context) {

    companion object {
        private const val TAG = "MessageQueue"
        private const val PREFS_NAME = "message_queue"
        private const val KEY_PENDING_MESSAGES = "pending_messages"
        private const val RETRY_DELAY_MS = 5000L // 5 секунд между попытками
        private const val MAX_RETRIES = 10 // Максимум 10 попыток
    }

    private val securePrefs = SecurePreferences(context, PREFS_NAME)
    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private var retryJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Сообщение с метаданными для отправки
     */
    data class PendingMessage(
        val message: ChatMessage,
        val retryCount: Int = 0,
        val addedTimestamp: Long = System.currentTimeMillis()
    )

    /**
     * Callback для отправки сообщения
     */
    interface SendCallback {
        fun send(message: ChatMessage, onSuccess: () -> Unit, onError: (String) -> Unit)
    }

    private var sendCallback: SendCallback? = null

    init {
        loadPendingMessages()
    }

    /**
     * Установить callback для отправки сообщений
     */
    fun setSendCallback(callback: SendCallback) {
        this.sendCallback = callback
    }

    /**
     * Добавить сообщение в очередь
     */
    fun enqueue(message: ChatMessage) {
        val pending = PendingMessage(message)
        pendingMessages.offer(pending)
        savePendingMessages()
        Log.d(TAG, "Message enqueued: ${message.id}, queue size: ${pendingMessages.size}")

        // Запускаем обработку очереди
        processQueue()
    }

    /**
     * Получить количество сообщений в очереди
     */
    fun size(): Int = pendingMessages.size

    /**
     * Проверить, пуста ли очередь
     */
    fun isEmpty(): Boolean = pendingMessages.isEmpty()

    /**
     * Обработать очередь сообщений
     */
    private fun processQueue() {
        if (retryJob?.isActive == true) {
            // Уже обрабатывается
            return
        }

        retryJob = coroutineScope.launch {
            while (pendingMessages.isNotEmpty()) {
                val pending = pendingMessages.peek() ?: break

                try {
                    // Проверяем количество попыток
                    if (pending.retryCount >= MAX_RETRIES) {
                        Log.e(TAG, "Message ${pending.message.id} exceeded max retries, removing")
                        pendingMessages.poll()
                        savePendingMessages()
                        continue
                    }

                    // Пытаемся отправить
                    var success = false
                    val errorMsg = CompletableDeferred<String?>()

                    withContext(Dispatchers.Main) {
                        sendCallback?.send(
                            pending.message,
                            onSuccess = {
                                success = true
                                errorMsg.complete(null)
                            },
                            onError = { error ->
                                errorMsg.complete(error)
                            }
                        ) ?: run {
                            errorMsg.complete("SendCallback not set")
                        }
                    }

                    val error = errorMsg.await()

                    if (success) {
                        // Успешно отправлено - удаляем из очереди
                        pendingMessages.poll()
                        savePendingMessages()
                        Log.d(TAG, "Message ${pending.message.id} sent successfully")
                    } else {
                        // Ошибка - увеличиваем счетчик попыток
                        pendingMessages.poll()
                        pendingMessages.offer(pending.copy(retryCount = pending.retryCount + 1))
                        savePendingMessages()
                        Log.w(TAG, "Message ${pending.message.id} failed (attempt ${pending.retryCount + 1}/$MAX_RETRIES): $error")

                        // Ждем перед следующей попыткой
                        delay(RETRY_DELAY_MS)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message ${pending.message.id}", e)
                    delay(RETRY_DELAY_MS)
                }
            }

            Log.d(TAG, "Queue processing completed, ${pendingMessages.size} messages remaining")
        }
    }

    /**
     * Принудительно повторить отправку всех сообщений
     */
    fun retry() {
        Log.d(TAG, "Manual retry requested, queue size: ${pendingMessages.size}")
        processQueue()
    }

    /**
     * Очистить очередь
     */
    fun clear() {
        pendingMessages.clear()
        savePendingMessages()
        Log.d(TAG, "Queue cleared")
    }

    /**
     * Сохранить очередь в хранилище
     */
    private fun savePendingMessages() {
        try {
            val jsonArray = JSONArray()
            pendingMessages.forEach { pending ->
                val json = JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("id", pending.message.id)
                        put("text", pending.message.text)
                        put("sender", pending.message.sender)
                        put("timestamp", pending.message.timestamp)
                        put("isRead", pending.message.isRead)
                    })
                    put("retryCount", pending.retryCount)
                    put("addedTimestamp", pending.addedTimestamp)
                }
                jsonArray.put(json)
            }
            securePrefs.putString(KEY_PENDING_MESSAGES, jsonArray.toString())
            Log.d(TAG, "Saved ${pendingMessages.size} pending messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pending messages", e)
        }
    }

    /**
     * Загрузить очередь из хранилища
     */
    private fun loadPendingMessages() {
        try {
            val json = securePrefs.getString(KEY_PENDING_MESSAGES, "[]") ?: "[]"
            val jsonArray = JSONArray(json)

            pendingMessages.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val messageObj = obj.getJSONObject("message")

                val message = ChatMessage(
                    id = messageObj.getString("id"),
                    text = messageObj.getString("text"),
                    sender = messageObj.getString("sender"),
                    timestamp = messageObj.getLong("timestamp"),
                    isRead = messageObj.getBoolean("isRead")
                )

                val pending = PendingMessage(
                    message = message,
                    retryCount = obj.getInt("retryCount"),
                    addedTimestamp = obj.getLong("addedTimestamp")
                )

                pendingMessages.offer(pending)
            }

            Log.d(TAG, "Loaded ${pendingMessages.size} pending messages")

            // Автоматически запускаем обработку если есть сообщения
            if (pendingMessages.isNotEmpty()) {
                processQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending messages", e)
        }
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        retryJob?.cancel()
        coroutineScope.cancel()
        savePendingMessages()
    }
}
