package ru.example.parentwatch.database.entity
import androidx.room.*
@Entity(tableName = "chat_media_capabilities_v2")
data class ChatMediaCapabilitiesEntity(
    @PrimaryKey @ColumnInfo(name="scope_key") val scopeKey: String,
    @ColumnInfo(name="capabilities_json") val capabilitiesJson: String,
    @ColumnInfo(name="captured_at") val capturedAt: Long
)
