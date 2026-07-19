package ru.example.parentwatch.database.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatV2LegacyIdentityTest {
    @Test
    fun `conversation identity is stable and keeps unicode device id`() {
        assertEquals(
            "local-family-v2:телефон-Лёвы-📱",
            ChatV2LegacyIdentity.conversationId("  телефон-Лёвы-📱  ", 41)
        )
    }

    @Test
    fun `conversation identity falls back to legacy child row`() {
        assertEquals(
            "local-family-v2:child-41",
            ChatV2LegacyIdentity.conversationId(null, 41)
        )
    }

    @Test
    fun `author and legacy role identities are deterministic`() {
        assertEquals(
            "device:parent-device-7",
            ChatV2LegacyIdentity.memberId("parent-device-7", "parent", "child-device", 4)
        )
        assertEquals(
            "device:child-device",
            ChatV2LegacyIdentity.memberId(null, "child", "child-device", 4)
        )
        assertEquals(
            "legacy-role:parent",
            ChatV2LegacyIdentity.memberId(null, "PARENT", "child-device", 4)
        )
    }
}
