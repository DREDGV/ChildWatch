package ru.example.childwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.example.childwatch.BuildConfig
import ru.example.childwatch.R
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.onboarding.ParentOnboardingSyncWorker
import ru.example.childwatch.profile.ParentActiveSession
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.utils.SecureSettingsManager

/**
 * Debug-build-only bridge used by scripts/emulator-lab.ps1.
 * It changes only this emulator installation and is absent from release APKs.
 */
class EmulatorLabReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION_SETUP) return
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?.trim()
            ?.takeIf { it.startsWith("http://10.0.2.2:") }
            ?: return
        val grantTestConsents = intent.getBooleanExtra(EXTRA_GRANT_TEST_CONSENTS, false)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                configure(context.applicationContext, serverUrl, grantTestConsents)
                Log.i(TAG, "Parent emulator configured for $serverUrl")
            } catch (error: Exception) {
                Log.e(TAG, "Parent emulator setup failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun configure(
        context: Context,
        serverUrl: String,
        grantTestConsents: Boolean
    ) {
        val secureSettings = SecureSettingsManager(context)
        val sessionStore = ParentActiveSessionStore(context)
        val current = sessionStore.getSession()
        val previousUrl = current?.serverUrl.orEmpty().ifBlank { secureSettings.getServerUrl() }
        val ownDeviceId = current?.ownParentDeviceId.orEmpty()
            .ifBlank { secureSettings.getDeviceId().orEmpty() }
        val linkedChildDeviceId = current?.linkedChildDeviceId.orEmpty()

        if (!previousUrl.equals(serverUrl, ignoreCase = true)) {
            NetworkClient(context).clearTokens()
        }
        sessionStore.setSession(
            ParentActiveSession(
                profileId = ParentActiveSession.buildDerivedProfileId(
                    serverUrl,
                    ownDeviceId,
                    linkedChildDeviceId
                ),
                profileName = current?.profileName
                    ?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.profile_switch_current_name),
                serverUrl = serverUrl,
                ownParentDeviceId = ownDeviceId,
                linkedChildDeviceId = linkedChildDeviceId
            )
        )
        context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", serverUrl)
            .apply()

        if (grantTestConsents) {
            val timestamp = System.currentTimeMillis()
            context.getSharedPreferences("childwatch_consent", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("consent_given", true)
                .putLong("consent_timestamp", timestamp)
                .apply()
            context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("consent_given", true)
                .apply()
            secureSettings.setUserConsent(true)
            secureSettings.setLocationEnabled(true)
            secureSettings.setAudioEnabled(true)
            secureSettings.setPhotoEnabled(true)
            Log.i(TAG, "Parent emulator test consent enabled")
        }

        ParentOnboardingSyncWorker.enqueueFromLocalProfile(
            context = context,
            onlyIfPending = false
        )
    }

    companion object {
        private const val TAG = "EmulatorLab"
        private const val ACTION_SETUP = "ru.example.childwatch.DEBUG_EMULATOR_SETUP"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_GRANT_TEST_CONSENTS = "grant_test_consents"
    }
}
