package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * LocationPoint Entity - represents a location data point in the database
 *
 * This entity stores location coordinates and metadata captured from child devices.
 * Location points are linked to a specific child via foreign key relationship.
 */
@Entity(
    tableName = "location_points",
    foreignKeys = [
        ForeignKey(
            entity = Child::class,
            parentColumns = ["id"],
            childColumns = ["child_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["point_id"], unique = true),
        Index(value = ["child_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["created_at"])
    ]
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "point_id")
    val pointId: String,

    @ColumnInfo(name = "child_id")
    val childId: Long,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float? = null,

    @ColumnInfo(name = "altitude")
    val altitude: Double? = null,

    @ColumnInfo(name = "speed")
    val speed: Float? = null,

    @ColumnInfo(name = "bearing")
    val bearing: Float? = null,

    @ColumnInfo(name = "address")
    val address: String? = null,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "provider")
    val provider: String? = null, // gps, network, fused

    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
