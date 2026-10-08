package ru.example.parentwatch.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Passive prerequisites only: no recording, camera access, network probe or extra permission. */
object ConnectionDiagnostics {
    fun network(context: Context): JSONObject = JSONObject().apply {
        put("collectedAt", System.currentTimeMillis())
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val active = manager.activeNetwork
            put("networkPresent", active != null)
            val caps = active?.let(manager::getNetworkCapabilities)
            if (caps != null) {
                put("internetValidated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("transport", when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "OTHER"
                })
            }
        }
    }

    fun microphone(context: Context): JSONObject = JSONObject().apply {
        put("collectedAt", System.currentTimeMillis())
        put("permissionGranted", ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        runCatching { context.getSystemService(AudioManager::class.java)?.isMicrophoneMute }
            .getOrNull()?.let { put("microphoneMuted", it) }
        // No public snapshot here proves sensor privacy or Android background capture eligibility.
    }
}
