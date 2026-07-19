package ru.childwatch.shared.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDeliveryStateReducerTest {
    @Test
    fun `late acknowledgements never regress a read message`() {
        val result = ChatDeliveryStateReducer.merge(
            ChatDeliveryState.READ,
            ChatDeliveryState.DELIVERED
        )

        assertEquals(ChatDeliveryState.READ, result)
    }

    @Test
    fun `failure only replaces a message that was not accepted`() {
        assertEquals(
            ChatDeliveryState.FAILED,
            ChatDeliveryStateReducer.merge(
                ChatDeliveryState.SENDING,
                ChatDeliveryState.FAILED
            )
        )
        assertEquals(
            ChatDeliveryState.ACCEPTED,
            ChatDeliveryStateReducer.merge(
                ChatDeliveryState.ACCEPTED,
                ChatDeliveryState.FAILED
            )
        )
    }

    @Test
    fun `explicit retry returns a failed message to the queue`() {
        assertEquals(
            ChatDeliveryState.QUEUED,
            ChatDeliveryStateReducer.beginRetry(ChatDeliveryState.FAILED)
        )
    }
}
