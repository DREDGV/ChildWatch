package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.example.childwatch.databinding.ActivityParentSetupBinding
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Parent
import ru.example.childwatch.database.repository.ChildRepository
import java.util.UUID

/**
 * Экран настройки профиля родителя (онбординг)
 *
 * Показывается при первом запуске приложения.
 * Позволяет ввести имя, email, телефон и выбрать аватар.
 */
class ParentSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ParentSetupActivity"
        private const val PREFS_NAME = "parent_onboarding"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_PARENT_ID = "parent_id"
    }

    private lateinit var binding: ActivityParentSetupBinding
    private val database by lazy { ChildWatchDatabase.getInstance(this) }
    private var selectedAvatarUri: String? = null

    // Launcher для выбора фото
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            binding.avatarImage.setImageURI(it)
            selectedAvatarUri = it.toString()
            Log.d(TAG, "Аватар выбран: $selectedAvatarUri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkIfAlreadyCompleted()
    }

    private fun setupUI() {
        // Кнопка выбора аватара
        binding.changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Кнопка продолжить
        binding.continueButton.setOnClickListener {
            validateAndSave()
        }

        // Кнопка пропустить
        binding.skipButton.setOnClickListener {
            skipOnboarding()
        }
    }

    /**
     * Проверка, завершен ли уже онбординг
     */
    private fun checkIfAlreadyCompleted() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

        if (isCompleted) {
            Log.d(TAG, "Онбординг уже завершен, переход к MainActivity")
            navigateToMain()
        }
    }

    /**
     * Валидация и сохранение профиля
     */
    private fun validateAndSave() {
        val name = binding.nameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val phone = binding.phoneInput.text.toString().trim()

        // Валидация имени
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Введите ваше имя"
            binding.nameInput.requestFocus()
            return
        }

        if (name.length < 2) {
            binding.nameInputLayout.error = "Имя слишком короткое"
            binding.nameInput.requestFocus()
            return
        }

        binding.nameInputLayout.error = null

        // Валидация email (если указан)
        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Неверный формат email"
            binding.emailInput.requestFocus()
            return
        }

        binding.emailInputLayout.error = null

        // Валидация телефона (если указан)
        if (phone.isNotEmpty() && phone.length < 10) {
            binding.phoneInputLayout.error = "Неверный формат телефона"
            binding.phoneInput.requestFocus()
            return
        }

        binding.phoneInputLayout.error = null

        // Сохранение профиля
        saveParentProfile(name, email, phone)
    }

    /**
     * Сохранение профиля родителя в БД
     */
    private fun saveParentProfile(name: String, email: String, phone: String) {
        showLoading(true)

        lifecycleScope.launch {
            try {
                val accountId = UUID.randomUUID().toString()
                val parent = Parent(
                    accountId = accountId,
                    name = name,
                    email = email.ifEmpty { "parent@childwatch.local" },
                    phoneNumber = phone.ifEmpty { null },
                    avatarUrl = selectedAvatarUri,
                    isVerified = false
                )

                val parentId = database.parentDao().insert(parent)
                Log.d(TAG, "Профиль родителя создан: ID=$parentId, имя=$name")

                // Сохраняем статус завершения онбординга
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                    .putLong(KEY_PARENT_ID, parentId)
                    .apply()

                showLoading(false)
                Toast.makeText(this@ParentSetupActivity, "Профиль создан!", Toast.LENGTH_SHORT).show()

                // Переход к главному экрану
                navigateToMain()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения профиля", e)
                showLoading(false)
                Toast.makeText(
                    this@ParentSetupActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Пропуск онбординга (создание профиля по умолчанию)
     */
    private fun skipOnboarding() {
        saveParentProfile("Родитель", "", "")
    }

    /**
     * Показ/скрытие индикатора загрузки
     */
    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.continueButton.isEnabled = !show
        binding.skipButton.isEnabled = !show
        binding.changeAvatarButton.isEnabled = !show
        binding.nameInput.isEnabled = !show
        binding.emailInput.isEnabled = !show
        binding.phoneInput.isEnabled = !show
    }

    /**
     * Переход к главному экрану
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        // Не даем выйти из онбординга
        Toast.makeText(this, "Пожалуйста, завершите настройку профиля", Toast.LENGTH_SHORT).show()
    }
}
