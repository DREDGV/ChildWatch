package ru.example.parentwatch.chat

import java.util.LinkedHashMap

object ChatMessageRuntimeRegistry {
    private val messages =
        object : LinkedHashMap<String, ChatMessage>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChatMessage>?): Boolean {
                return size > 200
            }
        }

    @Synchronized
    fun remember(message: ChatMessage) {
        if (message.id.isBlank()) return
        messages[message.id] = message
    }

    @Synchronized
    fun find(messageId: String): ChatMessage? = messages[messageId]
}
