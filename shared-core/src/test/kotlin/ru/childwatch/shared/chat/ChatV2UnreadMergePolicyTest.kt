package ru.childwatch.shared.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatV2UnreadMergePolicyTest {
    @Test fun `late snapshot cannot resurrect fully read messages`() {
        assertEquals(0, ChatV2UnreadMergePolicy.merge(10, 10, 0, 10, 0, 10))
    }

    @Test fun `late snapshot must preserve unread from newer messages`() {
        assertEquals(2, ChatV2UnreadMergePolicy.merge(12, 10, 2, 10, 0, 10))
    }

    @Test fun `older read watermark cannot overwrite partial local progress`() {
        assertEquals(2, ChatV2UnreadMergePolicy.merge(12, 10, 2, 12, 8, 4))
    }

    @Test fun `receipt in an older message snapshot cannot hide newer unread messages`() {
        assertEquals(2, ChatV2UnreadMergePolicy.merge(12, 8, 2, 10, 10, 0))
    }

    @Test fun `new message snapshot retains server count excluding own messages`() {
        assertEquals(1, ChatV2UnreadMergePolicy.merge(10, 10, 0, 14, 10, 1))
    }

    @Test fun `new read snapshot clears unread`() {
        assertEquals(0, ChatV2UnreadMergePolicy.merge(14, 10, 1, 14, 14, 0))
    }

    @Test fun `equal watermarks permit authoritative count correction`() {
        assertEquals(1, ChatV2UnreadMergePolicy.merge(14, 10, 3, 14, 10, 1))
    }

    @Test fun `legacy unread cannot resurrect fully read canonical conversation`() {
        assertEquals(0, ChatV2UnreadMergePolicy.reconcileLegacy(14, 14, 0, 7))
    }

    @Test fun `legacy partial state does not assume a shared sequence scale`() {
        assertEquals(7, ChatV2UnreadMergePolicy.reconcileLegacy(14, 10, 1, 7))
    }

    @Test fun `empty canonical history does not prove legacy messages were read`() {
        assertEquals(7, ChatV2UnreadMergePolicy.reconcileLegacy(0, 0, 0, 7))
    }
}
