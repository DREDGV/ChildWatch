package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локация родителя для функции "Где родители?"
 * Позволяет ребенку видеть местоположение родителя
 */
@Entity(
    tableName = "parent_locations",
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["timestamp"])
    ]
)
data class ParentLocation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "parent_id")
    val parentId: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "provider")
    val provider: String,

    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int? = null,

    @ColumnInfo(name = "speed")
    val speed: Float? = null,

    @ColumnInfo(name = "bearing")
    val bearing: Float? = null
)
