package ru.childwatch.shared.chat

import org.junit.Assert.*
import org.junit.Test

class ChatV2ReadReceiptPolicyTest {
    private val scope = ChatV2ReceiptScope("https://family.example", "family", "self", "device")
    @Test fun `viewed full history clears local unread without counting own messages`() {
        assertEquals(0, ChatV2ReadReceiptPolicy.unreadAfterViewing(20, 20, 3))
        assertEquals(3, ChatV2ReadReceiptPolicy.unreadAfterViewing(20, 19, 3))
    }
    @Test fun `late ack from different context or actor cannot update receipts`() {
        assertTrue(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope, "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope.copy(member = "other"), "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope.copy(server = "https://other.example"), "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope.copy(family = "other"), "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope.copy(device = "other"), "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, scope, "other"))
    }
    @Test fun `missing canonical identity cannot deliver pending receipt`() {
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope.copy(member = ""), scope, "self"))
        assertFalse(ChatV2ReadReceiptPolicy.acceptsReceipt(scope, null, "self"))
    }
}
