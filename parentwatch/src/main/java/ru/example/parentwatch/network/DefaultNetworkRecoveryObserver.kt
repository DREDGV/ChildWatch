package ru.example.parentwatch.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import ru.childwatch.shared.audio.DefaultNetworkRecoveryPolicy

/** Rebuild only transport when a usable default route changes; never starts microphone capture. */
internal class DefaultNetworkRecoveryObserver(context: Context, private val recover: () -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val initial = connectivity?.activeNetwork?.takeIf { usable(it) }
    private val policy = DefaultNetworkRecoveryPolicy(initial?.networkHandle?.toString())
    @Volatile private var closed = false
    private var registered = false
    private var pending: Runnable? = null
    private var route: Network? = initial

    private fun usable(network: Network): Boolean = connectivity?.getNetworkCapabilities(network)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (closed || connectivity?.activeNetwork != network) return
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                if (route == network) invalidate(network)
                return
            }
            if (!policy.validated(network.networkHandle.toString())) return
            route = network
            pending?.let(handler::removeCallbacks)
            pending = Runnable {
                pending = null
                if (!closed && route == network && connectivity?.activeNetwork == network && usable(network)) {
                    Log.i("DefaultNetworkRecovery", "Validated default route changed; refreshing shared transport")
                    recover()
                }
            }.also { handler.postDelayed(it, 500L) }
        }
        override fun onLost(network: Network) {
            if (!closed && route == network) invalidate(network)
        }
    }

    private fun invalidate(network: Network) {
        policy.lost(network.networkHandle.toString())
        route = null
        pending?.let(handler::removeCallbacks)
        pending = null
    }
    fun start() {
        if (closed || registered || connectivity == null) return
        try {
            connectivity.registerDefaultNetworkCallback(callback, handler)
            registered = true
        } catch (error: RuntimeException) {
            Log.w("DefaultNetworkRecovery", "Default route observer unavailable", error)
        }
    }
    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
