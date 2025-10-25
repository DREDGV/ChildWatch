package ru.example.childwatch.database.repository

import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.dao.AudioRecordingDao
import ru.example.childwatch.database.entity.AudioRecording

/**
 * Repository для работы с аудиозаписями
 *
 * Предоставляет единую точку доступа к данным аудиозаписей.
 */
class AudioRepository(private val audioRecordingDao: AudioRecordingDao) {

    /**
     * Получить запись по ID
     */
    suspend fun getRecordingById(id: Long): AudioRecording? {
        return audioRecordingDao.getById(id)
    }

    /**
     * Получить запись по recordingId
     */
    suspend fun getRecordingByRecordingId(recordingId: String): AudioRecording? {
        return audioRecordingDao.getByRecordingId(recordingId)
    }

    /**
     * Получить записи для ребенка (с пагинацией)
     */
    suspend fun getRecordingsForChild(childId: Long, limit: Int = 50, offset: Int = 0): List<AudioRecording> {
        return audioRecordingDao.getRecordingsForChild(childId, limit, offset)
    }

    /**
     * Получить записи для ребенка (Flow)
     */
    fun getRecordingsForChildFlow(childId: Long): Flow<List<AudioRecording>> {
        return audioRecordingDao.getRecordingsForChildFlow(childId)
    }

    /**
     * Получить загруженные записи
     */
    suspend fun getDownloadedRecordings(childId: Long): List<AudioRecording> {
        return audioRecordingDao.getDownloadedRecordings(childId)
    }

    /**
     * Получить непрослушанные записи
     */
    suspend fun getUnplayedRecordings(childId: Long): List<AudioRecording> {
        return audioRecordingDao.getUnplayedRecordings(childId)
    }

    /**
     * Получить количество непрослушанных записей
     */
    suspend fun getUnplayedCount(childId: Long): Int {
        return audioRecordingDao.getUnplayedCount(childId)
    }

    /**
     * Получить количество непрослушанных записей (Flow)
     */
    fun getUnplayedCountFlow(childId: Long): Flow<Int> {
        return audioRecordingDao.getUnplayedCountFlow(childId)
    }

    /**
     * Добавить запись
     */
    suspend fun insertRecording(recording: AudioRecording): Long {
        return audioRecordingDao.insert(recording)
    }

    /**
     * Добавить несколько записей
     */
    suspend fun insertRecordings(recordings: List<AudioRecording>): List<Long> {
        return audioRecordingDao.insertAll(recordings)
    }

    /**
     * Обновить запись
     */
    suspend fun updateRecording(recording: AudioRecording) {
        audioRecordingDao.update(recording)
    }

    /**
     * Удалить запись
     */
    suspend fun deleteRecording(recording: AudioRecording) {
        audioRecordingDao.delete(recording)
    }

    /**
     * Пометить как прослушанную
     */
    suspend fun markAsPlayed(recordingId: Long) {
        audioRecordingDao.markAsPlayed(recordingId)
    }

    /**
     * Пометить как загруженную
     */
    suspend fun markAsDownloaded(recordingId: Long) {
        audioRecordingDao.markAsDownloaded(recordingId)
    }

    /**
     * Получить последнюю запись
     */
    suspend fun getLatestRecording(childId: Long): AudioRecording? {
        return audioRecordingDao.getLatestRecording(childId)
    }

    /**
     * Получить последнюю запись (Flow)
     */
    fun getLatestRecordingFlow(childId: Long): Flow<AudioRecording?> {
        return audioRecordingDao.getLatestRecordingFlow(childId)
    }

    /**
     * Получить записи по режиму громкости
     */
    suspend fun getRecordingsByVolumeMode(childId: Long, volumeMode: String, limit: Int = 50): List<AudioRecording> {
        return audioRecordingDao.getRecordingsByVolumeMode(childId, volumeMode, limit)
    }

    /**
     * Получить записи в диапазоне времени
     */
    suspend fun getRecordingsInTimeRange(childId: Long, startTime: Long, endTime: Long): List<AudioRecording> {
        return audioRecordingDao.getRecordingsInTimeRange(childId, startTime, endTime)
    }

    /**
     * Удалить записи для ребенка
     */
    suspend fun deleteRecordingsForChild(childId: Long) {
        audioRecordingDao.deleteRecordingsForChild(childId)
    }

    /**
     * Удалить старые записи
     */
    suspend fun deleteOldRecordings(timestamp: Long) {
        audioRecordingDao.deleteOldRecordings(timestamp)
    }

    /**
     * Получить общее количество записей
     */
    suspend fun getRecordingCount(childId: Long): Int {
        return audioRecordingDao.getRecordingCount(childId)
    }

    /**
     * Получить общую продолжительность записей
     */
    suspend fun getTotalDuration(childId: Long): Long {
        return audioRecordingDao.getTotalDuration(childId) ?: 0L
    }

    /**
     * Получить общий размер файлов
     */
    suspend fun getTotalFileSize(childId: Long): Long {
        return audioRecordingDao.getTotalFileSize(childId) ?: 0L
    }

    /**
     * Удалить все записи
     */
    suspend fun deleteAllRecordings() {
        audioRecordingDao.deleteAll()
    }
}
