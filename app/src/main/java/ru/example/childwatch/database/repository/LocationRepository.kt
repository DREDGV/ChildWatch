package ru.example.childwatch.database.repository

import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.dao.LocationDao
import ru.example.childwatch.database.entity.LocationPoint

/**
 * Repository для работы с данными о местоположении
 *
 * Предоставляет единую точку доступа к данным геолокации.
 */
class LocationRepository(private val locationDao: LocationDao) {

    /**
     * Получить точку по ID
     */
    suspend fun getLocationById(id: Long): LocationPoint? {
        return locationDao.getById(id)
    }

    /**
     * Получить точку по pointId
     */
    suspend fun getLocationByPointId(pointId: String): LocationPoint? {
        return locationDao.getByPointId(pointId)
    }

    /**
     * Получить локации для ребенка (с пагинацией)
     */
    suspend fun getLocationsForChild(childId: Long, limit: Int = 100, offset: Int = 0): List<LocationPoint> {
        return locationDao.getLocationsForChild(childId, limit, offset)
    }

    /**
     * Получить локации для ребенка (Flow)
     */
    fun getLocationsForChildFlow(childId: Long): Flow<List<LocationPoint>> {
        return locationDao.getLocationsForChildFlow(childId)
    }

    /**
     * Получить последнюю локацию
     */
    suspend fun getLatestLocation(childId: Long): LocationPoint? {
        return locationDao.getLatestLocation(childId)
    }

    /**
     * Получить последнюю локацию (Flow)
     */
    fun getLatestLocationFlow(childId: Long): Flow<LocationPoint?> {
        return locationDao.getLatestLocationFlow(childId)
    }

    /**
     * Получить локации в диапазоне времени
     */
    suspend fun getLocationsInTimeRange(childId: Long, startTime: Long, endTime: Long): List<LocationPoint> {
        return locationDao.getLocationsInTimeRange(childId, startTime, endTime)
    }

    /**
     * Получить локации рядом с точкой
     */
    suspend fun getLocationsNearPoint(
        childId: Long,
        centerLat: Double,
        centerLon: Double,
        radiusDegrees: Double = 0.01, // ~1км
        limit: Int = 50
    ): List<LocationPoint> {
        return locationDao.getLocationsNearPoint(childId, centerLat, centerLon, radiusDegrees, limit)
    }

    /**
     * Получить локации по провайдеру
     */
    suspend fun getLocationsByProvider(childId: Long, provider: String, limit: Int = 100): List<LocationPoint> {
        return locationDao.getLocationsByProvider(childId, provider, limit)
    }

    /**
     * Получить локации с высокой точностью
     */
    suspend fun getHighAccuracyLocations(childId: Long, maxAccuracy: Float = 50f, limit: Int = 100): List<LocationPoint> {
        return locationDao.getHighAccuracyLocations(childId, maxAccuracy, limit)
    }

    /**
     * Добавить локацию
     */
    suspend fun insertLocation(location: LocationPoint): Long {
        return locationDao.insert(location)
    }

    /**
     * Добавить несколько локаций
     */
    suspend fun insertLocations(locations: List<LocationPoint>): List<Long> {
        return locationDao.insertAll(locations)
    }

    /**
     * Обновить локацию
     */
    suspend fun updateLocation(location: LocationPoint) {
        locationDao.update(location)
    }

    /**
     * Удалить локацию
     */
    suspend fun deleteLocation(location: LocationPoint) {
        locationDao.delete(location)
    }

    /**
     * Удалить локации для ребенка
     */
    suspend fun deleteLocationsForChild(childId: Long) {
        locationDao.deleteLocationsForChild(childId)
    }

    /**
     * Удалить старые локации
     */
    suspend fun deleteOldLocations(timestamp: Long) {
        locationDao.deleteOldLocations(timestamp)
    }

    /**
     * Получить общее количество локаций
     */
    suspend fun getLocationCount(childId: Long): Int {
        return locationDao.getLocationCount(childId)
    }

    /**
     * Получить количество локаций в диапазоне
     */
    suspend fun getLocationCountInTimeRange(childId: Long, startTime: Long, endTime: Long): Int {
        return locationDao.getLocationCountInTimeRange(childId, startTime, endTime)
    }

    /**
     * Получить среднюю скорость
     */
    suspend fun getAverageSpeed(childId: Long, startTime: Long, endTime: Long): Float {
        return locationDao.getAverageSpeed(childId, startTime, endTime) ?: 0f
    }

    /**
     * Удалить все локации
     */
    suspend fun deleteAllLocations() {
        locationDao.deleteAll()
    }

    /**
     * Получить маршрут за день
     */
    suspend fun getDailyRoute(childId: Long, dayStartTime: Long, dayEndTime: Long): List<LocationPoint> {
        return getLocationsInTimeRange(childId, dayStartTime, dayEndTime)
    }

    /**
     * Проверить, находится ли ребенок в заданной зоне
     */
    suspend fun isChildInArea(
        childId: Long,
        centerLat: Double,
        centerLon: Double,
        radiusDegrees: Double = 0.01
    ): Boolean {
        val nearbyLocations = getLocationsNearPoint(childId, centerLat, centerLon, radiusDegrees, limit = 1)
        return nearbyLocations.isNotEmpty()
    }
}
