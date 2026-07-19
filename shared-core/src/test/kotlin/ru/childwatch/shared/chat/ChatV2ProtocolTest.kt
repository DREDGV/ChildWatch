package ru.childwatch.shared.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatV2ProtocolTest {
    @Test
    fun dtoMappingPreservesUnicodeWhitespaceAndIdentity() {
        val text = "  Привет 👋🏽 семье 👨‍👩‍👧‍👦  "
        val dto = ChatV2MessageDto(
            messageId = "message-7",
            clientMessageId = "client-stable-7",
            conversationId = "family-1",
            serverSequence = 7,
            senderMemberId = "member-1",
            senderDisplayName = "Марина",
            senderRole = "guardian",
            text = text,
            clientSentAt = 100,
            serverCreatedAt = 120,
            deliveryState = "delivered"
        )

        val model = dto.toDomain()

        assertEquals(text, model.text)
        assertEquals("client-stable-7", model.clientMessageId)
        assertEquals(7L, model.serverSequence)
        assertEquals(ChatDeliveryState.DELIVERED, model.deliveryState)
        assertEquals(ConversationMemberRole.GUARDIAN, model.senderRole)
    }

    @Test
    fun sendRequestKeepsCallerGeneratedIdAcrossRetries() {
        val first = ChatV2SendMessageRequest("one-id", "😊", 42)
        val retry = first.copy()

        assertEquals(first.clientMessageId, retry.clientMessageId)
        assertEquals(first, retry)
    }

    @Test
    fun retryBackoffIsBoundedAndEventuallyExhausted() {
        assertEquals(1_000L, ChatV2RetryPolicy.delayAfterFailure(1))
        assertEquals(2_000L, ChatV2RetryPolicy.delayAfterFailure(2))
        assertEquals(ChatV2RetryPolicy.MAX_DELAY_MS, ChatV2RetryPolicy.delayAfterFailure(30))
        assertFalse(ChatV2RetryPolicy.isExhausted(ChatV2RetryPolicy.MAX_ATTEMPTS - 1))
        assertTrue(ChatV2RetryPolicy.isExhausted(ChatV2RetryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun legacyFamilyProjectionIsReconciledOnlyOnceIntoServerIdentity() {
        val localId = ChatV2LegacyReconcilePolicy.localConversationId(" child-7 ")

        assertEquals("local-family-v2:child-7", localId)
        assertTrue(
            ChatV2LegacyReconcilePolicy.shouldReconcile(
                localConversationId = localId!!,
                serverConversationId = "family-server-1",
                localType = "FAMILY",
                mappedServerConversationId = null
            )
        )
        assertFalse(
            ChatV2LegacyReconcilePolicy.shouldReconcile(
                localConversationId = localId,
                serverConversationId = "family-server-1",
                localType = "FAMILY",
                mappedServerConversationId = "family-server-1"
            )
        )
    }
}
