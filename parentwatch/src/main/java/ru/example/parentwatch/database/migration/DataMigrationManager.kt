package ru.example.parentwatch.database.migration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.database.ParentWatchDatabase
import ru.example.parentwatch.database.entity.ChatMessageEntity
import ru.example.parentwatch.database.repository.ChatRepository
import ru.example.parentwatch.database.repository.ChildRepository
import ru.example.parentwatch.utils.SecurePreferences

/**
 * Менеджер миграции данных с SharedPreferences на Room Database
 *
 * Обрабатывает миграцию:
 * - Сообщений чата
 * - Профилей детей
 * - Других данных при необходимости
 */
class DataMigrationManager(private val context: Context) {

    companion object {
        private const val TAG = "DataMigrationManager"
        private const val MIGRATION_PREFS = "migration_status"
        private const val KEY_CHAT_MIGRATED = "chat_messages_migrated"
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val CURRENT_MIGRATION_VERSION = 1

        // Ключи из старого ChatManager
        private const val CHAT_PREFS = "chat_prefs"
        private const val MESSAGES_KEY = "chat_messages"
    }

    private val migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    private val database = ParentWatchDatabase.getInstance(context)
    private val childRepository = ChildRepository(database.childDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())

    /**
     * Set only when the current run proved that every message found in the old
     * store reached Room. Cleanup is gated on it, so a partial or empty read can
     * never erase history that the user still needs.
     */
    @Volatile
    private var legacyDataVerifiedForCleanup = false

    /**
     * Проверяет, нужна ли миграция
     */
    fun isMigrationNeeded(): Boolean {
        val currentVersion = migrationPrefs.getInt(KEY_MIGRATION_VERSION, 0)
        return currentVersion < CURRENT_MIGRATION_VERSION
    }

    /**
     * Проверяет, была ли выполнена миграция чата
     */
    fun isChatMigrated(): Boolean {
        return migrationPrefs.getBoolean(KEY_CHAT_MIGRATED, false)
    }

    /**
     * Выполняет полную миграцию данных
     */
    suspend fun performMigration(deviceId: String): MigrationResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Начало миграции данных для устройства: $deviceId")

        val result = MigrationResult()

        try {
            // 1. Создаем или получаем профиль ребенка
            val child = childRepository.getOrCreateChild(deviceId, "Ребенок")
            Log.d(TAG, "Профиль ребенка создан/получен: ID=${child.id}")
            result.childCreated = true

            // 2. Мигрируем сообщения чата
            if (!isChatMigrated()) {
                val transfer = migrateChatMessages(child.id)
                result.messagesMigrated = transfer.migratedCount
                result.messagesInSource = transfer.sourceCount

                // Legacy data may only be removed when everything that was in the
                // old store is provably present in Room. A partial read (for
                // example a renamed or context-namespaced preferences key) must
                // never turn into a silent loss of the user's history.
                legacyDataVerifiedForCleanup =
                    transfer.sourceCount > 0 && transfer.migratedCount == transfer.sourceCount

                if (!legacyDataVerifiedForCleanup) {
                    Log.w(
                        TAG,
                        "Legacy chat data was not verified (source=${transfer.sourceCount}, " +
                            "migrated=${transfer.migratedCount}); keeping it in place"
                    )
                }

                // Отмечаем миграцию чата как выполненную
                migrationPrefs.edit().putBoolean(KEY_CHAT_MIGRATED, true).apply()
                Log.i(TAG, "Миграция чата завершена: ${transfer.migratedCount} сообщений")
            } else {
                Log.d(TAG, "Миграция чата уже была выполнена ранее")
                result.messagesMigrated = 0
            }

            // 3. Обновляем версию миграции
            migrationPrefs.edit()
                .putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION)
                .apply()

            result.success = true
            Log.i(TAG, "Миграция данных завершена успешно")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при миграции данных", e)
            result.success = false
            result.error = e.message
        }

        return@withContext result
    }

    /**
     * Счётчики переноса сообщений: сколько было в старом хранилище и сколько
     * реально записано в Room.
     */
    private data class ChatTransferResult(
        val sourceCount: Int,
        val migratedCount: Int
    )

    /**
     * Мигрирует сообщения чата из SharedPreferences в Room
     */
    private suspend fun migrateChatMessages(childId: Long): ChatTransferResult = withContext(Dispatchers.IO) {
        var migratedCount = 0
        var sourceCount = 0

        try {
            // Читаем старые сообщения из SecurePreferences
            val securePrefs = SecurePreferences(context, CHAT_PREFS)
            val messagesJson = securePrefs.getString(MESSAGES_KEY, "[]")

            if (messagesJson.isBlank() || messagesJson == "[]") {
                Log.d(TAG, "Нет сообщений для миграции")
                return@withContext ChatTransferResult(0, 0)
            }

            val jsonArray = try {
                JSONArray(messagesJson)
            } catch (jsonError: Exception) {
                Log.e(TAG, "Некорректный JSON истории чата, пропускаем миграцию", jsonError)
                return@withContext ChatTransferResult(0, 0)
            }
            sourceCount = jsonArray.length()
            val messages = mutableListOf<ChatMessage>()

            // Парсим JSON
            for (i in 0 until jsonArray.length()) {
                try {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val message = ChatMessage.fromJson(jsonObject)
                    messages.add(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка парсинга сообщения #$i", e)
                }
            }

            Log.d(TAG, "Загружено ${messages.size} сообщений из SharedPreferences")

            // Сохраняем в Room
            if (messages.isNotEmpty()) {
                val entities = messages.map { ChatMessageEntity.fromChatMessage(it, childId) }
                val insertedIds = database.chatMessageDao().insertAll(entities)
                migratedCount = insertedIds.size
                Log.i(TAG, "Сохранено $migratedCount сообщений в Room Database")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при миграции сообщений чата", e)
            throw e
        }

        return@withContext ChatTransferResult(sourceCount, migratedCount)
    }

    /**
     * Очищает старые данные из SharedPreferences после успешной миграции
     */
    suspend fun cleanupOldData(): CleanupResult = withContext(Dispatchers.IO) {
        val result = CleanupResult()

        try {
            if (isChatMigrated() && legacyDataVerifiedForCleanup) {
                // Очищаем старые сообщения чата
                val securePrefs = SecurePreferences(context, CHAT_PREFS)
                securePrefs.remove(MESSAGES_KEY)
                result.chatDataCleared = true
                Log.i(TAG, "Старые данные чата очищены из SharedPreferences")
            } else if (isChatMigrated()) {
                Log.w(
                    TAG,
                    "Старые данные чата оставлены: перенос не подтверждён в этой сессии"
                )
            }

            result.success = true

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при очистке старых данных", e)
            result.success = false
            result.error = e.message
        }

        return@withContext result
    }

    /**
     * Сбрасывает статус миграции (для тестирования)
     */
    fun resetMigrationStatus() {
        migrationPrefs.edit().clear().apply()
        Log.w(TAG, "Статус миграции сброшен")
    }

    /**
     * Результат миграции
     */
    data class MigrationResult(
        var success: Boolean = false,
        var childCreated: Boolean = false,
        var messagesMigrated: Int = 0,
        var messagesInSource: Int = 0,
        var error: String? = null
    ) {
        override fun toString(): String {
            return if (success) {
                "Миграция успешна: профиль=${if (childCreated) "создан" else "существует"}, " +
                    "сообщений=$messagesMigrated из $messagesInSource"
            } else {
                "Миграция не удалась: $error"
            }
        }
    }

    /**
     * Результат очистки
     */
    data class CleanupResult(
        var success: Boolean = false,
        var chatDataCleared: Boolean = false,
        var error: String? = null
    )
}

