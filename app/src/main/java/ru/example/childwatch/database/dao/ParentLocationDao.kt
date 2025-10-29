package ru.example.childwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.ParentLocation

/**
 * Data Access Object для работы с локацией родителя
 */
@Dao
interface ParentLocationDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: ParentLocation): Long

    @Update
    suspend fun update(location: ParentLocation)

    @Delete
    suspend fun delete(location: ParentLocation)

    @Query("SELECT * FROM parent_locations WHERE parent_id = :parentId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(parentId: String): ParentLocation?

    @Query("SELECT * FROM parent_locations WHERE parent_id = :parentId ORDER BY timestamp DESC")
    fun getAllLocationsFlow(parentId: String): Flow<List<ParentLocation>>

    @Query("SELECT * FROM parent_locations WHERE parent_id = :parentId AND timestamp >= :startTime ORDER BY timestamp DESC")
    suspend fun getLocationHistory(parentId: String, startTime: Long): List<ParentLocation>

    @Query("DELETE FROM parent_locations WHERE timestamp < :cutoffTime")
    suspend fun deleteOldLocations(cutoffTime: Long): Int

    @Query("SELECT COUNT(*) FROM parent_locations WHERE parent_id = :parentId")
    suspend fun getLocationsCount(parentId: String): Int
}
