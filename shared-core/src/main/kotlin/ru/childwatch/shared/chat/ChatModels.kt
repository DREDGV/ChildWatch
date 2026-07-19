package ru.childwatch.shared.chat

/**
 * Stable, device-independent chat identities shared by ParentMonitor and ChildDevice.
 *
 * A conversation belongs to a family. Members are people; devices are only delivery
 * endpoints and must never be used as the conversation identity.
 */
enum class ConversationType {
    FAMILY,
    DIRECT
}

enum class ConversationMemberRole {
    PARENT,
    CHILD,
    GUARDIAN
}

data class ConversationMember(
    val memberId: String,
    val displayName: String,
    val role: ConversationMemberRole,
    val avatarKey: String? = null,
    val isActive: Boolean = true,
    val isLocalUser: Boolean = false
)

data class Conversation(
    val conversationId: String,
    val familyId: String,
    val type: ConversationType,
    val title: String,
    val members: List<ConversationMember>,
    val lastMessagePreview: String? = null,
    val lastSequence: Long = 0,
    val lastReadSequence: Long = 0,
    val muted: Boolean = false,
    val updatedAt: Long = 0
) {
    val unreadCount: Long
        get() = (lastSequence - lastReadSequence).coerceAtLeast(0)

    val localMemberId: String?
        get() = members.firstOrNull { it.isLocalUser }?.memberId
}

/**
 * Delivery states are intentionally more precise than the legacy sent/read booleans.
 * ACCEPTED means the server durably stored the message. DELIVERED and READ are based
 * on receipts from another family member, not on the sender's network request alone.
 */
enum class ChatDeliveryState {
    QUEUED,
    SENDING,
    ACCEPTED,
    DELIVERED,
    READ,
    FAILED
}

data class ConversationMessage(
    val messageId: String,
    val clientMessageId: String,
    val conversationId: String,
    val serverSequence: Long? = null,
    val senderMemberId: String? = null,
    val senderDeviceId: String? = null,
    val senderDisplayName: String,
    val senderRole: ConversationMemberRole? = null,
    val text: String,
    val clientSentAt: Long,
    val serverCreatedAt: Long? = null,
    val deliveryState: ChatDeliveryState = ChatDeliveryState.QUEUED,
    val failureCode: String? = null,
    val legacyMessageId: String? = null
)

data class ConversationPage(
    val conversationId: String,
    val messages: List<ConversationMessage>,
    val nextBeforeSequence: Long? = null,
    val hasMore: Boolean = false
)

enum class ChatReceiptType {
    DELIVERED,
    READ
}

data class ChatReceipt(
    val conversationId: String,
    val messageId: String,
    val recipientMemberId: String,
    val type: ChatReceiptType,
    val sequence: Long,
    val occurredAt: Long
)

/** A durable command stored locally until the server accepts it idempotently. */
data class ChatOutboxItem(
    val clientMessageId: String,
    val conversationId: String,
    val text: String,
    val clientSentAt: Long,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastErrorCode: String? = null
)
