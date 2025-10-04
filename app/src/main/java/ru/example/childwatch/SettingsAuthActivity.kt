package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.example.childwatch.utils.PasswordManager
import ru.example.childwatch.utils.SettingsAuthManager

/**
 * Settings Authentication Activity
 * 
 * Features:
 * - Password/PIN authentication
 * - Password strength indicator
 * - Brute force protection
 * - Session management
 * - Password recovery
 */
class SettingsAuthActivity : AppCompatActivity() {
    
    private lateinit var authManager: SettingsAuthManager
    private lateinit var statusText: TextView
    private lateinit var passwordInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var passwordStrengthText: TextView
    private lateinit var pinStatusText: TextView
    private lateinit var remainingAttemptsText: TextView
    private lateinit var passwordRadio: RadioButton
    private lateinit var pinRadio: RadioButton
    private lateinit var passwordLayout: LinearLayout
    private lateinit var pinLayout: LinearLayout
    private lateinit var loginLayout: LinearLayout
    private lateinit var setupLayout: LinearLayout
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_auth)
        
        authManager = SettingsAuthManager(this)
        
        setupUI()
        checkAuthenticationStatus()
    }
    
    private fun setupUI() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_auth_title)
        
        // Find views
        statusText = findViewById(R.id.statusText)
        passwordInput = findViewById(R.id.loginPasswordInput)
        pinInput = findViewById(R.id.loginPinInput)
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput)
        passwordStrengthText = findViewById(R.id.passwordStrengthText)
        pinStatusText = findViewById(R.id.pinStatusText)
        remainingAttemptsText = findViewById(R.id.remainingAttemptsText)
        passwordRadio = findViewById(R.id.passwordRadio)
        pinRadio = findViewById(R.id.pinRadio)
        passwordLayout = findViewById(R.id.passwordLayout)
        pinLayout = findViewById(R.id.pinLayout)
        loginLayout = findViewById(R.id.loginLayout)
        setupLayout = findViewById(R.id.setupLayout)
        
        // Password input listener
        passwordInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                findViewById<LinearLayout>(R.id.passwordStrengthIndicator).visibility = android.view.View.VISIBLE
            }
        }
        
        passwordInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updatePasswordStrength(s?.toString() ?: "")
            }
        })
        
        // PIN input listener
        pinInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        pinInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val pin = s?.toString() ?: ""
                if (pin.length == 4) {
                    pinStatusText.text = "PIN готов"
                    pinStatusText.setTextColor(ContextCompat.getColor(this@SettingsAuthActivity, android.R.color.holo_green_dark))
                } else {
                    pinStatusText.text = "Введите 4 цифры"
                    pinStatusText.setTextColor(ContextCompat.getColor(this@SettingsAuthActivity, android.R.color.darker_gray))
                }
            }
        })
        
        // Button click listeners
        findViewById<Button>(R.id.loginButton).setOnClickListener { performLogin() }
        findViewById<Button>(R.id.setupPasswordButton).setOnClickListener { setupPassword() }
        findViewById<Button>(R.id.setupPinButton).setOnClickListener { setupPin() }
        findViewById<Button>(R.id.recoveryButton).setOnClickListener { showRecoveryDialog() }
        findViewById<Button>(R.id.clearAuthButton).setOnClickListener { clearAuthentication() }
        
        // Auth type selection
        findViewById<RadioGroup>(R.id.authTypeGroup).setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.passwordRadio -> {
                    passwordLayout.visibility = android.view.View.VISIBLE
                    pinLayout.visibility = android.view.View.GONE
                }
                R.id.pinRadio -> {
                    passwordLayout.visibility = android.view.View.GONE
                    pinLayout.visibility = android.view.View.VISIBLE
                }
            }
        }
    }
    
    private fun checkAuthenticationStatus() {
        val status = authManager.getAuthenticationStatus()
        
        if (!status.isSetUp) {
            // Show setup options
            setupLayout.visibility = android.view.View.VISIBLE
            loginLayout.visibility = android.view.View.GONE
            statusText.text = "Настройте аутентификацию для доступа к настройкам"
        } else {
            // Show login options
            setupLayout.visibility = android.view.View.GONE
            loginLayout.visibility = android.view.View.VISIBLE
            
            when (status.authType) {
                SettingsAuthManager.AuthType.PASSWORD -> {
                    passwordRadio.isChecked = true
                    passwordLayout.visibility = android.view.View.VISIBLE
                    pinLayout.visibility = android.view.View.GONE
                }
                SettingsAuthManager.AuthType.PIN -> {
                    pinRadio.isChecked = true
                    passwordLayout.visibility = android.view.View.GONE
                    pinLayout.visibility = android.view.View.VISIBLE
                }
                else -> {
                    passwordRadio.isChecked = true
                    passwordLayout.visibility = android.view.View.VISIBLE
                    pinLayout.visibility = android.view.View.GONE
                }
            }
            
            statusText.text = "Войдите для доступа к настройкам"
        }
    }
    
    private fun performLogin() {
        val authType = if (passwordRadio.isChecked) {
            SettingsAuthManager.AuthType.PASSWORD
        } else {
            SettingsAuthManager.AuthType.PIN
        }
        
        val credential = if (authType == SettingsAuthManager.AuthType.PASSWORD) {
            passwordInput.text.toString()
        } else {
            pinInput.text.toString()
        }
        
        if (credential.isEmpty()) {
            statusText.text = "Введите пароль или PIN"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            return
        }
        
        val authResult = if (authType == SettingsAuthManager.AuthType.PASSWORD) {
            authManager.authenticateWithPassword(credential)
        } else {
            authManager.authenticateWithPin(credential)
        }
        
        if (authResult.success) {
            statusText.text = authResult.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            proceedToSettings()
        } else {
            statusText.text = authResult.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            
            if (authResult.remainingAttempts > 0) {
                remainingAttemptsText.text = "Осталось попыток: ${authResult.remainingAttempts}"
                remainingAttemptsText.visibility = android.view.View.VISIBLE
            } else {
                remainingAttemptsText.visibility = android.view.View.GONE
            }
        }
    }
    
    private fun setupPassword() {
        val password = findViewById<EditText>(R.id.passwordInput).text.toString()
        val confirmPassword = confirmPasswordInput.text.toString()
        
        if (password.isEmpty()) {
            statusText.text = "Введите пароль"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            return
        }
        
        if (password != confirmPassword) {
            statusText.text = "Пароли не совпадают"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            return
        }
        
        val result = authManager.setupPassword(password)
        if (result.success) {
            statusText.text = result.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            checkAuthenticationStatus()
        } else {
            statusText.text = result.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }
    
    private fun setupPin() {
        val pin = findViewById<EditText>(R.id.pinInput).text.toString()
        
        if (pin.isEmpty()) {
            statusText.text = "Введите PIN"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            return
        }
        
        val result = authManager.setupPin(pin)
        if (result.success) {
            statusText.text = result.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            checkAuthenticationStatus()
        } else {
            statusText.text = result.message
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }
    
    private fun updatePasswordStrength(password: String) {
        if (password.isEmpty()) {
            findViewById<LinearLayout>(R.id.passwordStrengthIndicator).visibility = android.view.View.GONE
            return
        }
        
        val strength = PasswordManager.validatePasswordStrength(password)
        passwordStrengthText.text = PasswordManager.getPasswordStrengthDescription(strength)
        passwordStrengthText.setTextColor(PasswordManager.getPasswordStrengthColor(strength))
        
        // Show suggestions for weak passwords
        if (strength == PasswordManager.PasswordStrength.WEAK) {
            val suggestions = PasswordManager.generatePasswordSuggestions()
            findViewById<TextView>(R.id.passwordSuggestionsText).text = "Предложения: ${suggestions.take(3).joinToString(", ")}"
            findViewById<TextView>(R.id.passwordSuggestionsText).visibility = android.view.View.VISIBLE
        } else {
            findViewById<TextView>(R.id.passwordSuggestionsText).visibility = android.view.View.GONE
        }
    }
    
    private fun showRecoveryDialog() {
        val question = authManager.getRecoveryQuestion()
        if (question == null) {
            Toast.makeText(this, "Восстановление пароля не настроено", Toast.LENGTH_SHORT).show()
            return
        }
        
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Восстановление пароля")
        builder.setMessage("Ответьте на вопрос: $question")
        
        val input = EditText(this)
        builder.setView(input)
        
        builder.setPositiveButton("Проверить") { _, _ ->
            val answer = input.text.toString()
            if (authManager.verifyRecoveryAnswer(answer)) {
                Toast.makeText(this, "Ответ правильный! Теперь вы можете войти в настройки", Toast.LENGTH_LONG).show()
                proceedToSettings()
            } else {
                Toast.makeText(this, "Неверный ответ", Toast.LENGTH_SHORT).show()
            }
        }
        
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }
    
    private fun clearAuthentication() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Очистить аутентификацию")
        builder.setMessage("Вы уверены, что хотите удалить все настройки аутентификации?")
        
        builder.setPositiveButton("Да") { _, _ ->
            authManager.clearAuthentication()
            Toast.makeText(this, "Аутентификация очищена", Toast.LENGTH_SHORT).show()
            checkAuthenticationStatus()
        }
        
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }
    
    private fun proceedToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        finish()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}