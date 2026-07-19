package ru.example.childwatch.database.mapping

import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatOutboxItem
import ru.childwatch.shared.chat.Conversation
import ru.childwatch.shared.chat.ConversationMember
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationMessage
import ru.childwatch.shared.chat.ConversationType
import ru.example.childwatch.database.entity.ChatConversationMemberV2Entity
import ru.example.childwatch.database.entity.ChatConversationV2Entity
import ru.example.childwatch.database.entity.ChatMessageV2Entity
import ru.example.childwatch.database.entity.ChatOutboxV2Entity

fun Conversation.toEntity(legacyChildId: Long? = null): ChatConversationV2Entity {
    return ChatConversationV2Entity(
        conversationId = conversationId,
        serverConversationId = conversationId,
        familyId = familyId,
        type = type.name,
        title = title,
        legacyChildId = legacyChildId,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        lastSequence = lastSequence,
        lastReadSequence = lastReadSequence,
        unreadCount = unreadCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        muted = muted,
        syncState = ChatConversationV2Entity.SYNC_STATE_SYNCED
    )
}

fun ChatConversationV2Entity.toModel(
    members: List<ConversationMember> = emptyList()
): Conversation {
    return Conversation(
        conversationId = conversationId,
        familyId = familyId ?: conversationId,
        type = enumValueOrDefault(type, ConversationType.FAMILY),
        title = title ?: conversationId,
        members = members,
        lastMessagePreview = lastMessagePreview,
        lastSequence = lastSequence,
        lastReadSequence = lastReadSequence,
        muted = muted || mutedUntil != null,
        updatedAt = updatedAt
    )
}

fun ConversationMember.toEntity(
    conversationId: String,
    isLocalUser: Boolean,
    joinedAt: Long
): ChatConversationMemberV2Entity {
    return ChatConversationMemberV2Entity(
        conversationId = conversationId,
        memberId = memberId,
        serverMemberId = memberId,
        displayName = displayName,
        role = role.name,
        isLocalUser = isLocalUser,
        joinedAt = joinedAt
    )
}

fun ChatConversationMemberV2Entity.toModel(): ConversationMember {
    return ConversationMember(
        memberId = memberId,
        displayName = displayName ?: memberId,
        role = enumValueOrDefault(role, ConversationMemberRole.GUARDIAN),
        isLocalUser = isLocalUser
    )
}

fun ConversationMessage.toEntity(legacySender: String? = null): ChatMessageV2Entity {
    val state = deliveryState
    return ChatMessageV2Entity(
        messageId = messageId,
        serverMessageId = messageId.takeIf { serverSequence != null },
        clientMessageId = clientMessageId,
        serverSequence = serverSequence,
        conversationId = conversationId,
        senderMemberId = senderMemberId,
        senderDeviceId = senderDeviceId,
        senderDisplayName = senderDisplayName,
        senderRole = senderRole?.name,
        legacySender = legacySender ?: senderRole?.name?.lowercase() ?: "member",
        text = text,
        sentAt = clientSentAt,
        clientSentAt = clientSentAt,
        createdAt = serverCreatedAt ?: clientSentAt,
        serverCreatedAt = serverCreatedAt,
        status = state.toLegacyStatus(),
        deliveryState = state.name,
        failureCode = failureCode,
        legacyMessageId = legacyMessageId,
        isRead = state == ChatDeliveryState.READ,
        syncState = if (serverSequence == null) {
            ChatMessageV2Entity.SYNC_STATE_LOCAL_ONLY
        } else {
            ChatMessageV2Entity.SYNC_STATE_SYNCED
        }
    )
}

fun ChatMessageV2Entity.toModel(): ConversationMessage {
    return ConversationMessage(
        messageId = messageId,
        clientMessageId = clientMessageId ?: messageId,
        conversationId = conversationId,
        serverSequence = serverSequence,
        senderMemberId = senderMemberId,
        senderDeviceId = senderDeviceId,
        senderDisplayName = senderDisplayName ?: legacySender,
        senderRole = senderRole?.let {
            enumValueOrDefault(it, ConversationMemberRole.GUARDIAN)
        },
        text = text,
        clientSentAt = clientSentAt,
        serverCreatedAt = serverCreatedAt,
        deliveryState = enumValueOrDefault(deliveryState, status.toDeliveryState()),
        failureCode = failureCode,
        legacyMessageId = legacyMessageId
    )
}

fun ChatOutboxItem.toEntity(payloadJson: String, now: Long): ChatOutboxV2Entity {
    return ChatOutboxV2Entity(
        clientMessageId = clientMessageId,
        conversationId = conversationId,
        payloadJson = payloadJson,
        text = text,
        clientSentAt = clientSentAt,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = lastErrorCode,
        createdAt = now,
        updatedAt = now
    )
}

fun ChatOutboxV2Entity.toModel(): ChatOutboxItem {
    return ChatOutboxItem(
        clientMessageId = clientMessageId,
        conversationId = conversationId,
        text = text,
        clientSentAt = clientSentAt,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastErrorCode = lastError
    )
}

private fun ChatDeliveryState.toLegacyStatus(): String = when (this) {
    ChatDeliveryState.QUEUED -> "queued"
    ChatDeliveryState.SENDING -> "sending"
    ChatDeliveryState.ACCEPTED -> "sent"
    ChatDeliveryState.DELIVERED -> "delivered"
    ChatDeliveryState.READ -> "read"
    ChatDeliveryState.FAILED -> "failed"
}

private fun String.toDeliveryState(): ChatDeliveryState = when (lowercase()) {
    "queued" -> ChatDeliveryState.QUEUED
    "sending" -> ChatDeliveryState.SENDING
    "sent", "accepted" -> ChatDeliveryState.ACCEPTED
    "delivered" -> ChatDeliveryState.DELIVERED
    "read" -> ChatDeliveryState.READ
    "failed" -> ChatDeliveryState.FAILED
    else -> ChatDeliveryState.ACCEPTED
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T {
    return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback
}
