package ru.example.childwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Child Entity - represents a child device in the system
 *
 * This entity stores information about child devices that are being monitored
 * by parent devices. Each child has a unique device ID and profile information.
 */
@Entity(
    tableName = "children",
    indices = [
        Index(value = ["device_id"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class Child(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "role", defaultValue = "'child'")
    val role: String = "child",

    @ColumnInfo(name = "icon_id", defaultValue = "0")
    val iconId: Int = 0,

    @ColumnInfo(name = "allowed_features", defaultValue = "15")
    val allowedFeatures: Int = 15,

    @ColumnInfo(name = "alias")
    val alias: String? = null,

    @ColumnInfo(name = "age")
    val age: Int? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
