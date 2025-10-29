package ru.example.parentwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Parent Entity - represents a parent user in the system
 *
 * This entity stores information about parent users who monitor child devices.
 * Each parent has a unique account ID and profile information.
 */
@Entity(
    tableName = "parents",
    indices = [
        Index(value = ["account_id"], unique = true),
        Index(value = ["email"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class Parent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "password_hash")
    val passwordHash: String? = null,

    @ColumnInfo(name = "is_verified")
    val isVerified: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

