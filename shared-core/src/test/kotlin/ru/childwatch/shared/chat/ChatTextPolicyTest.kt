package ru.childwatch.shared.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextPolicyTest {
    @Test
    fun `complex emoji sequences are accepted without modification`() {
        val original = "Семья 👨‍👩‍👧‍👦 👍🏽 ❤️ 🇷🇺 1️⃣"
        val result = ChatTextPolicy.validate(original)

        assertTrue(result is ChatTextValidation.Valid)
        assertEquals(original, original.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `oversized messages are rejected instead of cutting unicode`() {
        val text = "🙂".repeat(ChatTextPolicy.MAX_UTF8_BYTES)
        val result = ChatTextPolicy.validate(text)

        assertTrue(result is ChatTextValidation.TooLarge)
    }
}
