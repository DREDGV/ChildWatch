package ru.example.childwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.childwatch.databinding.ActivityAboutBinding
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.utils.SecureSettingsManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * About Activity showing app information, changelog, roadmap, and instructions.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AboutActivity"
        private const val PREFS_NAME = "childwatch_prefs"
        private const val GITHUB_URL = "https://github.com/DREDGV/ChildWatch"
        private const val FEEDBACK_EMAIL = "feedback@childwatch.app"
    }

    private lateinit var binding: ActivityAboutBinding
    private lateinit var prefs: SharedPreferences
    private val secureSettings by lazy { SecureSettingsManager(this) }
    private val effectiveContextResolver by lazy { ParentEffectiveContextResolver(this) }
    private val aboutScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupUI()
        loadAppInfo()
    }

    private fun setupUI() {
        binding.openGitHubBtn.setOnClickListener {
            openGitHub()
        }

        binding.sendFeedbackBtn.setOnClickListener {
            sendFeedback()
        }

        binding.copyInfoBtn.setOnClickListener {
            copyAppInfo()
        }
    }

    private fun loadAppInfo() {
        try {
            val versionName = "1.0.0"
            val versionCode = "1"
            binding.versionText.text = "Версия $versionName ($versionCode)"
            binding.deviceIdText.text = getString(R.string.device_id, resolveOwnDeviceIdForDisplay())
            loadServerVersion()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app info", e)
        }
    }

    private fun loadServerVersion() {
        aboutScope.launch {
            try {
                val serverUrl = effectiveContextResolver.resolveServerUrl().ifBlank {
                    secureSettings.getServerUrl().trim()
                }
                if (serverUrl.isBlank()) {
                    binding.serverVersionText.text = getString(
                        R.string.server_version,
                        getString(R.string.device_info_unknown)
                    )
                    return@launch
                }

                val version = withContext(Dispatchers.IO) {
                    getServerVersion(serverUrl)
                }

                binding.serverVersionText.text = getString(
                    R.string.server_version,
                    version ?: getString(R.string.device_info_unknown)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading server version", e)
                binding.serverVersionText.text = getString(R.string.server_version, "ошибка подключения")
            }
        }
    }

    private suspend fun getServerVersion(serverUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().readText()
                    "1.0.0"
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting server version", e)
                null
            }
        }
    }

    private fun openGitHub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GitHub", e)
            Toast.makeText(this, "Не удалось открыть GitHub", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFeedback() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "Отзыв о ChildWatch")
                putExtra(Intent.EXTRA_TEXT, createFeedbackText())
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Отправить отзыв"))
            } else {
                Toast.makeText(this, "Нет приложения для отправки email", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending feedback", e)
            Toast.makeText(this, "Ошибка при отправке отзыва", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFeedbackText(): String {
        val versionName = "1.0.0"
        val versionCode = "1"
        return """
            Приложение: ChildWatch
            Версия: $versionName ($versionCode)
            ID устройства: ${resolveOwnDeviceIdForDisplay()}

            Ваш отзыв:

        """.trimIndent()
    }

    private fun copyAppInfo() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val versionName = "1.0.0"
            val versionCode = "1"
            val serverUrl = effectiveContextResolver.resolveServerUrl()
                .ifBlank { secureSettings.getServerUrl() }
                .ifBlank { getString(R.string.device_info_unknown) }

            val appInfo = """
                ChildWatch
                Версия: $versionName ($versionCode)
                ID устройства: ${resolveOwnDeviceIdForDisplay()}
                Сервер: $serverUrl
            """.trimIndent()

            clipboard.setPrimaryClip(ClipData.newPlainText("ChildWatch Info", appInfo))
            Toast.makeText(this, "Информация скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying app info", e)
            Toast.makeText(this, "Ошибка при копировании информации", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveOwnDeviceIdForDisplay(): String {
        return effectiveContextResolver.resolveOwnParentId()
            .ifBlank { secureSettings.getDeviceId()?.trim().orEmpty() }
            .ifBlank { getString(R.string.device_info_unknown) }
    }

    override fun onDestroy() {
        super.onDestroy()
        aboutScope.cancel()
    }
}
