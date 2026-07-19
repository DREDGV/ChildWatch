package ru.childwatch.shared.chat

/**
 * Network policy for text messages. The original Unicode string is never normalized
 * or truncated, which preserves emoji variation selectors, skin tones and ZWJ families.
 */
object ChatTextPolicy {
    const val MAX_UTF8_BYTES: Int = 16 * 1024

    fun validate(text: String): ChatTextValidation {
        if (text.isBlank()) return ChatTextValidation.Empty
        val byteCount = text.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= MAX_UTF8_BYTES) {
            ChatTextValidation.Valid(byteCount)
        } else {
            ChatTextValidation.TooLarge(byteCount, MAX_UTF8_BYTES)
        }
    }
}

sealed interface ChatTextValidation {
    data class Valid(val utf8Bytes: Int) : ChatTextValidation
    data object Empty : ChatTextValidation
    data class TooLarge(val utf8Bytes: Int, val maxUtf8Bytes: Int) : ChatTextValidation
}
