package ru.example.childwatch.database.repository

import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.dao.ParentLocationDao
import ru.example.childwatch.database.entity.ParentLocation

/**
 * Repository для работы с локацией родителя
 * Поддержка функции "Где родители?"
 */
class ParentLocationRepository(private val parentLocationDao: ParentLocationDao) {

    /**
     * Получить последнюю локацию родителя
     */
    suspend fun getLatestLocation(parentId: String): ParentLocation? {
        return parentLocationDao.getLatestLocation(parentId)
    }

    /**
     * Получить поток всех локаций родителя
     */
    fun getAllLocationsFlow(parentId: String): Flow<List<ParentLocation>> {
        return parentLocationDao.getAllLocationsFlow(parentId)
    }

    /**
     * Получить историю локаций за период
     */
    suspend fun getLocationHistory(parentId: String, hoursBack: Int = 24): List<ParentLocation> {
        val startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
        return parentLocationDao.getLocationHistory(parentId, startTime)
    }

    /**
     * Сохранить новую локацию родителя
     */
    suspend fun insertLocation(location: ParentLocation): Long {
        return parentLocationDao.insertLocation(location)
    }

    /**
     * Обновить локацию
     */
    suspend fun updateLocation(location: ParentLocation) {
        parentLocationDao.update(location)
    }

    /**
     * Получить количество сохраненных локаций
     */
    suspend fun getLocationsCount(parentId: String): Int {
        return parentLocationDao.getLocationsCount(parentId)
    }

    /**
     * Удалить старые локации
     * @param hoursOld Количество часов, после которых локации удаляются
     */
    suspend fun deleteOldLocations(hoursOld: Int = 48): Int {
        val cutoffTime = System.currentTimeMillis() - (hoursOld * 60 * 60 * 1000L)
        return parentLocationDao.deleteOldLocations(cutoffTime)
    }

    /**
     * Рассчитать ETA (Estimated Time of Arrival)
     * @return Время в секундах до прибытия, или null если родитель не движется
     */
    fun calculateETA(
        parentLat: Double,
        parentLon: Double,
        childLat: Double,
        childLon: Double,
        parentSpeed: Float? // м/с
    ): ETAInfo {
        val distance = calculateDistance(parentLat, parentLon, childLat, childLon)
        
        if (parentSpeed == null || parentSpeed < 0.5f) {
            // Родитель на месте или скорость неизвестна
            return ETAInfo(
                distanceMeters = distance,
                etaSeconds = null,
                isMoving = false
            )
        }

        val etaSeconds = (distance / parentSpeed).toLong()
        
        return ETAInfo(
            distanceMeters = distance,
            etaSeconds = etaSeconds,
            isMoving = true
        )
    }

    /**
     * Расстояние между двумя точками (Haversine formula)
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // метры
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
}

/**
 * Информация о ETA
 */
data class ETAInfo(
    val distanceMeters: Float,
    val etaSeconds: Long?,
    val isMoving: Boolean
) {
    val distanceKm: Float
        get() = distanceMeters / 1000f

    val formattedDistance: String
        get() = if (distanceMeters < 1000) {
            "${distanceMeters.toInt()} м"
        } else {
            "%.1f км".format(distanceKm)
        }

    val formattedETA: String
        get() = when {
            !isMoving -> "Родитель на месте"
            etaSeconds == null -> "Неизвестно"
            etaSeconds < 60 -> "Меньше минуты"
            etaSeconds < 3600 -> "${etaSeconds / 60} мин"
            else -> {
                val hours = etaSeconds / 3600
                val minutes = (etaSeconds % 3600) / 60
                "$hours ч $minutes мин"
            }
        }
}
