package ru.example.childwatch.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.migration.DataMigrationManager

/**
 * Адаптер для постепенной миграции с ChatManager на ChatManagerV2
 *
 * Предоставляет синхронный API (как старый ChatManager),
 * но внутри использует ChatManagerV2 с Room Database.
 *
 * При первом использовании автоматически выполняет миграцию данных.
 */
class ChatManagerAdapter(
    private val context: Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "ChatManagerAdapter"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatManagerV2 = ChatManagerV2(context, deviceId)
    private val migrationManager = DataMigrationManager(context)

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isMigrationComplete = false

    /**
     * Инициализация с автоматической миграцией
     */
    init {
        scope.launch {
            try {
                // Инициализируем ChatManagerV2
                chatManagerV2.initialize()
                isInitialized = true
                Log.d(TAG, "ChatManagerV2 инициализирован")

                // Выполняем миграцию если нужно
                if (migrationManager.isMigrationNeeded()) {
                    Log.i(TAG, "Начинаем миграцию данных...")
                    val result = migrationManager.performMigration(deviceId)
                    Log.i(TAG, "Результат миграции: $result")

                    if (result.success) {
                        // Опционально: очищаем старые данные
                        val cleanupResult = migrationManager.cleanupOldData()
                        Log.i(TAG, "Очистка старых данных: $cleanupResult")
                    }
                }
                isMigrationComplete = true
                Log.d(TAG, "Миграция завершена или не требуется")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка инициализации ChatManagerAdapter", e)
            }
        }
    }

    /**
     * Ожидание завершения инициализации
     */
    private suspend fun ensureInitialized() {
        var attempts = 0
        while (!isInitialized && attempts < 50) {
            delay(100)
            attempts++
        }
        if (!isInitialized) {
            throw IllegalStateException("ChatManagerAdapter не удалось инициализировать за 5 секунд")
        }
    }

    /**
     * Сохранить сообщение (синхронный вызов)
     */
    fun saveMessage(message: ChatMessage) {
        scope.launch {
            try {
                ensureInitialized()
                chatManagerV2.saveMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения сообщения", e)
            }
        }
    }

    /**
     * Получить все сообщения (синхронный вызов)
     * ВНИМАНИЕ: Может вернуть пустой список при первом вызове до завершения инициализации
     */
    fun getAllMessages(): List<ChatMessage> {
        return runBlocking {
            try {
                ensureInitialized()
                chatManagerV2.getAllMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки сообщений", e)
                emptyList()
            }
        }
    }

    /**
     * Получить все сообщения асинхронно (suspend)
     */
    suspend fun getAllMessagesAsync(): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                chatManagerV2.getAllMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки сообщений", e)
                emptyList()
            }
        }
    }

    /**
     * Получить сообщения как Flow (реактивное обновление)
     */
    fun getAllMessagesFlow(): Flow<List<ChatMessage>> {
        return chatManagerV2.getAllMessagesFlow()
    }

    /**
     * Получить количество непрочитанных сообщений
     */
    fun getUnreadCount(): Int {
        return runBlocking {
            try {
                ensureInitialized()
                chatManagerV2.getUnreadCount()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка получения количества непрочитанных", e)
                0
            }
        }
    }

    /**
     * Получить количество непрочитанных сообщений (Flow)
     */
    fun getUnreadCountFlow(): Flow<Int> {
        return chatManagerV2.getUnreadCountFlow()
    }

    /**
     * Пометить сообщение как прочитанное
     */
    fun markAsRead(messageId: String) {
        scope.launch {
            try {
                ensureInitialized()
                chatManagerV2.markAsRead(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка пометки сообщения как прочитанного", e)
            }
        }
    }

    /**
     * Пометить все сообщения как прочитанные
     */
    fun markAllAsRead() {
        scope.launch {
            try {
                ensureInitialized()
                chatManagerV2.markAllAsRead()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка пометки всех сообщений", e)
            }
        }
    }

    /**
     * Обновить статус сообщения
     */
    fun updateMessageStatus(messageId: String, status: ChatMessage.MessageStatus) {
        scope.launch {
            try {
                ensureInitialized()
                chatManagerV2.updateMessageStatus(messageId, status)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления статуса", e)
            }
        }
    }

    /**
     * Очистить все сообщения
     */
    fun clearAllMessages() {
        scope.launch {
            try {
                ensureInitialized()
                chatManagerV2.clearAllMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки сообщений", e)
            }
        }
    }

    /**
     * Очистка ресурсов
     */
    fun cleanup() {
        scope.cancel()
        chatManagerV2.cleanup()
        Log.d(TAG, "ChatManagerAdapter cleanup completed")
    }

    /**
     * Проверить статус миграции
     */
    fun isMigrationComplete(): Boolean = isMigrationComplete
}
