package ru.childwatch.shared.chat

/**
 * Applies acknowledgements without allowing late or duplicated network events to move
 * a message backwards (for example READ -> DELIVERED or DELIVERED -> ACCEPTED).
 */
object ChatDeliveryStateReducer {
    private val progressRank = mapOf(
        ChatDeliveryState.QUEUED to 0,
        ChatDeliveryState.SENDING to 1,
        ChatDeliveryState.ACCEPTED to 2,
        ChatDeliveryState.DELIVERED to 3,
        ChatDeliveryState.READ to 4
    )

    fun merge(
        current: ChatDeliveryState,
        incoming: ChatDeliveryState
    ): ChatDeliveryState {
        if (current == incoming) return current

        if (incoming == ChatDeliveryState.FAILED) {
            return if (current == ChatDeliveryState.QUEUED || current == ChatDeliveryState.SENDING) {
                ChatDeliveryState.FAILED
            } else {
                current
            }
        }

        if (current == ChatDeliveryState.FAILED) {
            return current
        }

        val currentRank = progressRank.getValue(current)
        val incomingRank = progressRank.getValue(incoming)
        return if (incomingRank > currentRank) incoming else current
    }

    fun beginRetry(current: ChatDeliveryState): ChatDeliveryState =
        if (current == ChatDeliveryState.FAILED) ChatDeliveryState.QUEUED else current
}
