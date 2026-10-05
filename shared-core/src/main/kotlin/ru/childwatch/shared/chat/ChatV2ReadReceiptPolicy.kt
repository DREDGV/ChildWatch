package ru.childwatch.shared.chat

data class ChatV2ReceiptScope(val server: String, val family: String, val member: String, val device: String) {
    fun isComplete(): Boolean = listOf(server, family, member, device).all { it.isNotBlank() }
}

object ChatV2ReadReceiptPolicy {
    fun unreadAfterViewing(lastSequence: Long, readSequence: Long, previousUnread: Int): Int =
        if (lastSequence <= readSequence) 0 else previousUnread.coerceAtLeast(0)

    fun acceptsReceipt(expected: ChatV2ReceiptScope, current: ChatV2ReceiptScope?, responseMember: String): Boolean =
        expected.isComplete() && expected == current && expected.member == responseMember
}
