package ru.example.parentwatch.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chat_conversation_members_v2",
    primaryKeys = ["conversation_id", "member_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationV2Entity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["member_id"]), Index(value = ["device_id"])]
)
data class ChatConversationMemberV2Entity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
    @ColumnInfo(name = "server_member_id") val serverMemberId: String? = null,
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "is_local_user") val isLocalUser: Boolean = false,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long? = null,
    @ColumnInfo(name = "last_delivered_at") val lastDeliveredAt: Long? = null,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long? = null,
    @ColumnInfo(name = "is_muted") val isMuted: Boolean = false
) {
    companion object {
        const val ROLE_CHILD = "CHILD"
        const val ROLE_GUARDIAN = "GUARDIAN"
        const val ROLE_MEMBER = "MEMBER"
    }
}
