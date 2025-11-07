package ru.example.childwatch.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import ru.example.childwatch.database.entity.Geofence

/**
 * DAO for Geofence operations
 */
@Dao
interface GeofenceDao {
    
    @Query("SELECT * FROM geofences WHERE device_id = :deviceId ORDER BY created_at DESC")
    fun getAllGeofences(deviceId: String): LiveData<List<Geofence>>
    
    @Query("SELECT * FROM geofences WHERE device_id = :deviceId AND is_active = 1")
    fun getActiveGeofences(deviceId: String): LiveData<List<Geofence>>
    
    @Query("SELECT * FROM geofences WHERE id = :id")
    suspend fun getGeofenceById(id: Long): Geofence?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(geofence: Geofence): Long
    
    @Update
    suspend fun update(geofence: Geofence)
    
    @Delete
    suspend fun delete(geofence: Geofence)
    
    @Query("DELETE FROM geofences WHERE device_id = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)
    
    @Query("UPDATE geofences SET is_active = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)
}
