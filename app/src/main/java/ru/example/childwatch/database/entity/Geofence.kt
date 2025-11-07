package ru.example.childwatch.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * Entity representing a geofence zone
 */
@Entity(tableName = "geofences")
data class Geofence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    
    @ColumnInfo(name = "radius")
    val radius: Float, // meters
    
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "notification_on_enter")
    val notificationOnEnter: Boolean = false,
    
    @ColumnInfo(name = "notification_on_exit")
    val notificationOnExit: Boolean = true
) {
    companion object {
        const val MIN_RADIUS = 100f // минимальный радиус 100м
        const val MAX_RADIUS = 5000f // максимальный радиус 5км
        const val DEFAULT_RADIUS = 200f // по умолчанию 200м
    }
}
