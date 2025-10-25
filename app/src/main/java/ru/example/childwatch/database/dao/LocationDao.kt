package ru.example.childwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.LocationPoint

/**
 * DAO for LocationPoint entity
 *
 * Provides database access methods for managing location data points.
 */
@Dao
interface LocationDao {

    /**
     * Insert a new location point
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationPoint): Long

    /**
     * Insert multiple location points
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<LocationPoint>): List<Long>

    /**
     * Update an existing location point
     */
    @Update
    suspend fun update(location: LocationPoint)

    /**
     * Delete a location point
     */
    @Delete
    suspend fun delete(location: LocationPoint)

    /**
     * Get location by ID
     */
    @Query("SELECT * FROM location_points WHERE id = :id")
    suspend fun getById(id: Long): LocationPoint?

    /**
     * Get location by point ID
     */
    @Query("SELECT * FROM location_points WHERE point_id = :pointId")
    suspend fun getByPointId(pointId: String): LocationPoint?

    /**
     * Get all locations for a child (paginated)
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getLocationsForChild(childId: Long, limit: Int = 100, offset: Int = 0): List<LocationPoint>

    /**
     * Get all locations for a child as Flow (reactive, latest 200)
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 200")
    fun getLocationsForChildFlow(childId: Long): Flow<List<LocationPoint>>

    /**
     * Get latest location for a child
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(childId: Long): LocationPoint?

    /**
     * Get latest location as Flow (reactive)
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestLocationFlow(childId: Long): Flow<LocationPoint?>

    /**
     * Get locations in time range
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getLocationsInTimeRange(childId: Long, startTime: Long, endTime: Long): List<LocationPoint>

    /**
     * Get locations near a coordinate (within radius in degrees, ~1 degree = 111km)
     */
    @Query("""
        SELECT * FROM location_points
        WHERE child_id = :childId
        AND latitude BETWEEN :centerLat - :radiusDegrees AND :centerLat + :radiusDegrees
        AND longitude BETWEEN :centerLon - :radiusDegrees AND :centerLon + :radiusDegrees
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getLocationsNearPoint(
        childId: Long,
        centerLat: Double,
        centerLon: Double,
        radiusDegrees: Double = 0.01, // ~1km
        limit: Int = 50
    ): List<LocationPoint>

    /**
     * Get locations by provider
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId AND provider = :provider ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLocationsByProvider(childId: Long, provider: String, limit: Int = 100): List<LocationPoint>

    /**
     * Get locations with high accuracy (accuracy < threshold in meters)
     */
    @Query("SELECT * FROM location_points WHERE child_id = :childId AND accuracy IS NOT NULL AND accuracy < :maxAccuracy ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getHighAccuracyLocations(childId: Long, maxAccuracy: Float = 50f, limit: Int = 100): List<LocationPoint>

    /**
     * Delete locations for a child
     */
    @Query("DELETE FROM location_points WHERE child_id = :childId")
    suspend fun deleteLocationsForChild(childId: Long)

    /**
     * Delete old locations (older than timestamp)
     */
    @Query("DELETE FROM location_points WHERE timestamp < :timestamp")
    suspend fun deleteOldLocations(timestamp: Long)

    /**
     * Delete all locations
     */
    @Query("DELETE FROM location_points")
    suspend fun deleteAll()

    /**
     * Get total location count for a child
     */
    @Query("SELECT COUNT(*) FROM location_points WHERE child_id = :childId")
    suspend fun getLocationCount(childId: Long): Int

    /**
     * Get location count in time range
     */
    @Query("SELECT COUNT(*) FROM location_points WHERE child_id = :childId AND timestamp BETWEEN :startTime AND :endTime")
    suspend fun getLocationCountInTimeRange(childId: Long, startTime: Long, endTime: Long): Int

    /**
     * Get average speed for child in time range
     */
    @Query("SELECT AVG(speed) FROM location_points WHERE child_id = :childId AND timestamp BETWEEN :startTime AND :endTime AND speed IS NOT NULL")
    suspend fun getAverageSpeed(childId: Long, startTime: Long, endTime: Long): Float?
}
