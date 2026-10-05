package ru.childwatch.shared.chat

/** Server unread counts exclude own messages; sequence distance is not an unread count. */
object ChatV2UnreadMergePolicy {
    fun merge(
        currentLastSequence: Long,
        currentReadSequence: Long,
        currentUnread: Int,
        incomingLastSequence: Long,
        incomingReadSequence: Long,
        incomingUnread: Int
    ): Int {
        val last = maxOf(currentLastSequence, incomingLastSequence)
        val read = maxOf(currentReadSequence, incomingReadSequence)
        if (last <= read) return 0

        // A late LIST response must not overwrite either a newer message snapshot
        // or a receipt committed while that request was in flight.
        val incomingIsOlder = incomingLastSequence < currentLastSequence ||
            (incomingLastSequence == currentLastSequence && incomingReadSequence < currentReadSequence)
        return (if (incomingIsOlder) currentUnread else incomingUnread).coerceAtLeast(0)
    }

    /** Legacy sequence numbers are a separate scale: only server watermarks prove server read state. */
    fun reconcileLegacy(serverLast: Long, serverRead: Long, serverUnread: Int, legacyUnread: Int): Int =
        if (serverLast > 0 && serverLast <= serverRead) 0 else maxOf(serverUnread, legacyUnread, 0)
}
