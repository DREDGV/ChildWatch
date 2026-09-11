package ru.example.parentwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.example.parentwatch.BuildConfig
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.session.ChildActiveSessionStore

/**
 * Debug-build-only bridge used by scripts/emulator-lab.ps1.
 * It is deliberately limited to the Android Emulator host address.
 */
class EmulatorLabReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION_SETUP) return
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?.trim()
            ?.takeIf { it.startsWith("http://10.0.2.2:") }
            ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                configure(context.applicationContext, serverUrl)
                Log.i(TAG, "Child emulator configured for $serverUrl")
            } catch (error: Exception) {
                Log.e(TAG, "Child emulator setup failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun configure(context: Context, serverUrl: String) {
        val store = ChildActiveSessionStore(context)
        val current = store.getActiveSession()
        val previousUrl = current?.serverUrl.orEmpty().ifBlank { store.resolveCurrentServerUrl() }
        if (!previousUrl.equals(serverUrl, ignoreCase = true)) {
            NetworkClient(context).clearTokens()
        }

        val session = store.buildSession(
            name = current?.name
                ?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.profile_switch_current_name),
            serverUrl = serverUrl,
            ownChildDeviceId = store.resolveCurrentChildId(),
            linkedParentDeviceId = store.resolveCurrentParentId()
        )
        store.applySession(session)
        NetworkClient(context).ensureOnboardingAuthentication()
    }

    companion object {
        private const val TAG = "EmulatorLab"
        private const val ACTION_SETUP = "ru.example.parentwatch.DEBUG_EMULATOR_SETUP"
        private const val EXTRA_SERVER_URL = "server_url"
    }
}
