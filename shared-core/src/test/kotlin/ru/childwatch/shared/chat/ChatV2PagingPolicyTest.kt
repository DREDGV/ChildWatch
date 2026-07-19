package ru.childwatch.shared.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatV2PagingPolicyTest {
    @Test
    fun `network pages stay bounded`() {
        assertEquals(1, ChatV2PagingPolicy.serverPageSize(0))
        assertEquals(100, ChatV2PagingPolicy.serverPageSize(100))
        assertEquals(200, ChatV2PagingPolicy.serverPageSize(5_000))
    }

    @Test
    fun `local window can display multiple downloaded pages`() {
        assertEquals(500, ChatV2PagingPolicy.localMessageWindow(500))
        assertEquals(10_000, ChatV2PagingPolicy.localMessageWindow(Int.MAX_VALUE))
    }
}
