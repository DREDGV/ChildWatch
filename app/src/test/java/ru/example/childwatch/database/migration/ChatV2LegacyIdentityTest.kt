package ru.example.childwatch.database.migration

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
            ChatV2LegacyIdentity.conversationId("  ", 41)
        )
    }

    @Test
    fun `explicit author device has priority over legacy role`() {
        assertEquals(
            "device:parent-device-7",
            ChatV2LegacyIdentity.memberId(" parent-device-7 ", "parent", "child-device", 4)
        )
    }

    @Test
    fun `legacy child without author uses child device identity`() {
        assertEquals(
            "device:child-device",
            ChatV2LegacyIdentity.memberId(null, "CHILD", "child-device", 4)
        )
    }

    @Test
    fun `legacy guardian without device gets deterministic role identity`() {
        assertEquals(
            "legacy-role:parent",
            ChatV2LegacyIdentity.memberId(null, " Parent ", "child-device", 4)
        )
    }
}
