package ru.childwatch.shared.chat

import java.util.concurrent.ConcurrentHashMap

/** Process-local guard used to avoid notifying for a conversation already on screen. */
object ChatV2UiRegistry {
    private val activeConversations = ConcurrentHashMap.newKeySet<String>()

    fun enter(conversationId: String) {
        conversationId.trim().takeIf { it.isNotEmpty() }?.let(activeConversations::add)
    }

    fun leave(conversationId: String) {
        activeConversations.remove(conversationId.trim())
    }

    fun isActive(conversationId: String): Boolean =
        activeConversations.contains(conversationId.trim())
}
