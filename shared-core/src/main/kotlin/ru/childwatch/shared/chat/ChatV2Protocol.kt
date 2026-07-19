package ru.childwatch.shared.chat

/**
 * Wire models for the authenticated /api/chat/v2 HTTP API.
 *
 * They intentionally have no Retrofit/Gson annotations so both Android apps can
 * share and JVM-test the contract without pulling Android networking into
 * shared-core. Field names match the server JSON exactly.
 */
data class ChatV2ConversationsResponse(
    val success: Boolean = false,
    val conversations: List<ChatV2ConversationDto> = emptyList()
)

data class ChatV2DirectConversationRequest(
    val targetMemberId: String
)

data class ChatV2DirectConversationResponse(
    val success: Boolean = false,
    val created: Boolean = false,
    val conversation: ChatV2ConversationDto? = null
)

data class ChatV2ConversationDto(
    val conversationId: String,
    val familyId: String,
    val type: String,
    val title: String? = null,
    val actorMemberId: String? = null,
    val members: List<ChatV2MemberDto> = emptyList(),
    val lastSequence: Long = 0,
    val lastDeliveredSequence: Long = 0,
    val lastReadSequence: Long = 0,
    val mutedUntil: Long? = null,
    val lastMessagePreview: String? = null,
    val unreadCount: Long = 0,
    val updatedAt: Long? = null
)

data class ChatV2MemberDto(
    val memberId: String,
    val displayName: String? = null,
    val role: String? = null,
    val avatarKey: String? = null
)

data class ChatV2MessagesResponse(
    val success: Boolean = false,
    val conversationId: String,
    val messages: List<ChatV2MessageDto> = emptyList(),
    val nextBeforeSequence: Long? = null,
    val hasMore: Boolean = false
)

data class ChatV2SendMessageRequest(
    val clientMessageId: String,
    val text: String,
    val clientSentAt: Long
)

data class ChatV2SendMessageResponse(
    val success: Boolean = false,
    val created: Boolean = false,
    val deduplicated: Boolean = false,
    val message: ChatV2MessageDto? = null
)

data class ChatV2MessageDto(
    val messageId: String,
    val clientMessageId: String,
    val conversationId: String,
    val serverSequence: Long,
    val senderMemberId: String? = null,
    val senderDeviceId: String? = null,
    val senderDisplayName: String? = null,
    val senderRole: String? = null,
    val text: String,
    val clientSentAt: Long,
    val serverCreatedAt: Long,
    val legacyMessageId: String? = null,
    val deliveryState: String? = null,
    val receipts: List<ChatV2MessageReceiptDto> = emptyList()
)

data class ChatV2MessageReceiptDto(
    val recipientMemberId: String,
    val deliveredAt: Long? = null,
    val readAt: Long? = null
)

data class ChatV2ReceiptRequest(
    val deliveredThroughSequence: Long? = null,
    val readThroughSequence: Long? = null
)

data class ChatV2ReceiptResponse(
    val success: Boolean = false,
    val receipt: ChatV2ReceiptDto? = null
)

data class ChatV2ReceiptDto(
    val conversationId: String,
    val memberId: String,
    val deliveredThroughSequence: Long = 0,
    val readThroughSequence: Long = 0
)

fun ChatV2ConversationDto.toDomain(): Conversation {
    val normalizedType = if (type.equals("DIRECT", ignoreCase = true)) {
        ConversationType.DIRECT
    } else {
        ConversationType.FAMILY
    }
    val resolvedTitle = title?.takeIf { it.isNotBlank() }
        ?: if (normalizedType == ConversationType.DIRECT) "Личный чат" else "Семейный чат"
    return Conversation(
        conversationId = conversationId,
        familyId = familyId,
        type = normalizedType,
        title = resolvedTitle,
        members = members.map { member ->
            member.toDomain(isLocalUser = member.memberId == actorMemberId)
        },
        lastMessagePreview = lastMessagePreview,
        lastSequence = lastSequence,
        lastReadSequence = lastReadSequence,
        muted = mutedUntil != null,
        updatedAt = updatedAt ?: 0
    )
}

fun ChatV2MemberDto.toDomain(isLocalUser: Boolean = false): ConversationMember = ConversationMember(
    memberId = memberId,
    displayName = displayName?.takeIf { it.isNotBlank() } ?: memberId,
    role = role.toConversationMemberRole(),
    avatarKey = avatarKey,
    isLocalUser = isLocalUser
)

fun ChatV2MessageDto.toDomain(): ConversationMessage = ConversationMessage(
    messageId = messageId,
    clientMessageId = clientMessageId,
    conversationId = conversationId,
    serverSequence = serverSequence,
    senderMemberId = senderMemberId,
    senderDeviceId = senderDeviceId,
    senderDisplayName = senderDisplayName?.takeIf { it.isNotBlank() } ?: "Участник",
    senderRole = senderRole?.toConversationMemberRole(),
    text = text,
    clientSentAt = clientSentAt,
    serverCreatedAt = serverCreatedAt,
    deliveryState = deliveryState.toChatDeliveryState(),
    legacyMessageId = legacyMessageId
)

fun String?.toConversationMemberRole(): ConversationMemberRole = when {
    this.equals("CHILD", ignoreCase = true) -> ConversationMemberRole.CHILD
    this.equals("PARENT", ignoreCase = true) -> ConversationMemberRole.PARENT
    else -> ConversationMemberRole.GUARDIAN
}

fun String?.toChatDeliveryState(): ChatDeliveryState = when {
    this.equals("READ", ignoreCase = true) -> ChatDeliveryState.READ
    this.equals("DELIVERED", ignoreCase = true) -> ChatDeliveryState.DELIVERED
    this.equals("FAILED", ignoreCase = true) -> ChatDeliveryState.FAILED
    else -> ChatDeliveryState.ACCEPTED
}

/** Pure bounded retry policy persisted by the Android outbox. */
object ChatV2RetryPolicy {
    const val MAX_ATTEMPTS: Int = 12
    const val BASE_DELAY_MS: Long = 1_000
    const val MAX_DELAY_MS: Long = 5 * 60 * 1_000

    /** [attemptCount] is one-based and represents the failure just recorded. */
    fun delayAfterFailure(attemptCount: Int): Long {
        val exponent = (attemptCount.coerceAtLeast(1) - 1).coerceAtMost(20)
        val multiplier = 1L shl exponent
        return (BASE_DELAY_MS * multiplier).coerceAtMost(MAX_DELAY_MS)
    }

    fun nextAttemptAt(now: Long, attemptCount: Int): Long =
        now + delayAfterFailure(attemptCount)

    fun isExhausted(attemptCount: Int): Boolean = attemptCount >= MAX_ATTEMPTS
}

/** Keeps network pages bounded without hiding older rows already stored in Room. */
object ChatV2PagingPolicy {
    const val DEFAULT_SERVER_PAGE_SIZE = 50
    const val MAX_SERVER_PAGE_SIZE = 200
    const val MAX_LOCAL_MESSAGE_WINDOW = 10_000

    fun serverPageSize(requested: Int): Int =
        requested.coerceIn(1, MAX_SERVER_PAGE_SIZE)

    fun localMessageWindow(requested: Int): Int =
        requested.coerceIn(1, MAX_LOCAL_MESSAGE_WINDOW)
}

/** Contract shared with the additive Room 7 -> 8 legacy projection. */
object ChatV2LegacyReconcilePolicy {
    const val LOCAL_FAMILY_PREFIX: String = "local-family-v2:"

    fun localConversationId(childDeviceId: String): String? =
        childDeviceId.trim().takeIf { it.isNotEmpty() }?.let { LOCAL_FAMILY_PREFIX + it }

    fun shouldReconcile(
        localConversationId: String,
        serverConversationId: String,
        localType: String,
        mappedServerConversationId: String?
    ): Boolean =
        localConversationId != serverConversationId &&
            localConversationId.startsWith(LOCAL_FAMILY_PREFIX) &&
            localType.equals("FAMILY", ignoreCase = true) &&
            mappedServerConversationId.isNullOrBlank()
}
