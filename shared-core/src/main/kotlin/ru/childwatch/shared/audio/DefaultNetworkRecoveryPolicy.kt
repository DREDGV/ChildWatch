package ru.childwatch.shared.audio

/** A usable default route changed; socket flags alone cannot prove transport health. */
class DefaultNetworkRecoveryPolicy(initialNetwork: String? = null) {
    private var current = initialNetwork
    fun validated(network: String): Boolean {
        if (network.isBlank() || current == network) return false
        current = network
        return true
    }
    fun lost(network: String) {
        if (current == network) current = null
    }
}
