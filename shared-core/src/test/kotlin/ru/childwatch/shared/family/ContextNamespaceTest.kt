package ru.childwatch.shared.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContextNamespaceTest {
    @Test
    fun `different profiles have different local namespaces`() {
        val first = context(self = "parent-a", target = "child-a")
        val second = context(self = "parent-a", target = "child-b")

        assertNotEquals(
            first.storageNamespace("parent", "chat"),
            second.storageNamespace("parent", "chat")
        )
    }

    @Test
    fun `parent and child scopes remain separate`() {
        val context = context(self = "parent", target = "child")

        assertNotEquals(
            context.storageNamespace("parent", "chat"),
            context.storageNamespace("child", "chat")
        )
    }

    @Test
    fun `namespace is stable and escapes separators`() {
        val context = context(self = "parent/one", target = "child two")
        val first = context.storageNamespace("parent", "map/history")
        val second = context.storageNamespace("parent", "map/history")

        assertEquals(first, second)
        assertFalse(first.contains("parent/one"))
        assertFalse(first.contains("map/history"))
    }

    private fun context(self: String, target: String): ActiveContext {
        return ActiveContext(
            familyId = "family",
            selfMemberId = "self-member",
            selfDeviceId = self,
            focusedMemberId = "focused-member",
            targetDeviceId = target,
            serverUrl = "https://server.example",
            source = ContextSource.CANONICAL,
            updatedAt = 100L
        )
    }
}
