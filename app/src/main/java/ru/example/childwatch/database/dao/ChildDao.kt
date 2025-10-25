package ru.example.childwatch.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.entity.Child

/**
 * DAO for Child entity
 *
 * Provides database access methods for managing child records.
 */
@Dao
interface ChildDao {

    /**
     * Insert a new child
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(child: Child): Long

    /**
     * Insert multiple children
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(children: List<Child>): List<Long>

    /**
     * Update an existing child
     */
    @Update
    suspend fun update(child: Child)

    /**
     * Delete a child
     */
    @Delete
    suspend fun delete(child: Child)

    /**
     * Get child by ID
     */
    @Query("SELECT * FROM children WHERE id = :id")
    suspend fun getById(id: Long): Child?

    /**
     * Get child by device ID
     */
    @Query("SELECT * FROM children WHERE device_id = :deviceId")
    suspend fun getByDeviceId(deviceId: String): Child?

    /**
     * Get child by device ID as Flow (reactive)
     */
    @Query("SELECT * FROM children WHERE device_id = :deviceId")
    fun getByDeviceIdFlow(deviceId: String): Flow<Child?>

    /**
     * Get all children
     */
    @Query("SELECT * FROM children ORDER BY created_at DESC")
    suspend fun getAll(): List<Child>

    /**
     * Get all children as Flow (reactive)
     */
    @Query("SELECT * FROM children ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<Child>>

    /**
     * Get active children
     */
    @Query("SELECT * FROM children WHERE is_active = 1 ORDER BY created_at DESC")
    suspend fun getActiveChildren(): List<Child>

    /**
     * Get active children as Flow (reactive)
     */
    @Query("SELECT * FROM children WHERE is_active = 1 ORDER BY created_at DESC")
    fun getActiveChildrenFlow(): Flow<List<Child>>

    /**
     * Update last seen timestamp
     */
    @Query("UPDATE children SET last_seen_at = :timestamp, updated_at = :timestamp WHERE id = :childId")
    suspend fun updateLastSeen(childId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Set child active status
     */
    @Query("UPDATE children SET is_active = :isActive, updated_at = :timestamp WHERE id = :childId")
    suspend fun setActiveStatus(childId: Long, isActive: Boolean, timestamp: Long = System.currentTimeMillis())

    /**
     * Delete all children
     */
    @Query("DELETE FROM children")
    suspend fun deleteAll()

    /**
     * Get count of children
     */
    @Query("SELECT COUNT(*) FROM children")
    suspend fun getCount(): Int
}
