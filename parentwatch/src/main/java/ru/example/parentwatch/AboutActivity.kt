package ru.example.parentwatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.example.parentwatch.databinding.ActivityAboutBinding

/**
 * About Activity for ParentWatch
 *
 * Displays:
 * - Application information
 * - Version details
 * - Features list
 * - Developer info
 * - GitHub repository link
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "О приложении"

        setupUI()
    }

    private fun setupUI() {
        // Get version info from BuildConfig
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        binding.versionText.text = "Версия $versionName (build $versionCode)"

        // Application description
        binding.descriptionText.text = """
            ParentWatch (ChildDevice) - это часть системы родительского контроля ChildWatch.

            Устанавливается на устройство ребенка для мониторинга:
            • Местоположения в реальном времени
            • Удаленного прослушивания микрофона
            • Двусторонней связи через чат
        """.trimIndent()

        // Features list
        binding.featuresText.text = """
            📍 GPS-мониторинг
            Отслеживание местоположения с точностью до 10 метров

            🎤 Удаленное прослушивание
            Потоковая передача аудио через WebSocket

            💬 Встроенный чат
            Безопасная связь родитель-ребенок

            🔐 Безопасность
            Шифрованное соединение и защита данных

            ⚙️ Гибкая настройка
            Настройка интервалов и параметров мониторинга
        """.trimIndent()

        // GitHub button
        binding.githubButton.setOnClickListener {
            openGitHub()
        }

        // Close button
        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    private fun openGitHub() {
        val githubUrl = "https://github.com/your-repo/ChildWatch"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
