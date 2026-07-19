package ru.childwatch.shared.chat

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatV2UiRegistryTest {
    private val conversationId = "conversation-notification-test"

    @After
    fun cleanUp() {
        ChatV2UiRegistry.leave(conversationId)
    }

    @Test
    fun `active conversation suppresses only its own notification`() {
        ChatV2UiRegistry.enter("  $conversationId  ")

        assertTrue(ChatV2UiRegistry.isActive(conversationId))
        assertFalse(ChatV2UiRegistry.isActive("another-conversation"))

        ChatV2UiRegistry.leave(" $conversationId ")
        assertFalse(ChatV2UiRegistry.isActive(conversationId))
    }
}
