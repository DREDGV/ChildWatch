package ru.example.childwatch.database.mapping

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.childwatch.shared.chat.ChatDeliveryState
import ru.childwatch.shared.chat.ChatOutboxItem
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationMessage

class ChatV2EntityMapperTest {
    @Test
    fun `message round trip preserves complex unicode and canonical ids`() {
        val text = "👨‍👩‍👧‍👦 Привет 👍🏽 e\u0301"
        val original = ConversationMessage(
            messageId = "message-17",
            clientMessageId = "client-17",
            conversationId = "family-1",
            serverSequence = 71,
            senderMemberId = "member-2",
            senderDeviceId = "device-2",
            senderDisplayName = "Мария 👩🏻",
            senderRole = ConversationMemberRole.GUARDIAN,
            text = text,
            clientSentAt = 1_700_000_001,
            serverCreatedAt = 1_700_000_010,
            deliveryState = ChatDeliveryState.DELIVERED,
            legacyMessageId = "legacy-17"
        )

        val restored = original.toEntity().toModel()

        assertEquals(original, restored)
        assertEquals(text, restored.text)
    }

    @Test
    fun `outbox round trip retains retry state and text verbatim`() {
        val original = ChatOutboxItem(
            clientMessageId = "client-18",
            conversationId = "family-1",
            text = "Семья ❤️‍🔥",
            clientSentAt = 1_700_000_020,
            attemptCount = 3,
            nextAttemptAt = 1_700_000_999,
            lastErrorCode = "OFFLINE"
        )

        assertEquals(original, original.toEntity(payloadJson = "{}", now = 5).toModel())
    }
}
