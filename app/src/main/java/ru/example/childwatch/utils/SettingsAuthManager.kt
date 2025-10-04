package ru.example.childwatch.utils

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Settings authentication manager
 * 
 * Features:
 * - Password/PIN authentication for settings
 * - Brute force protection
 * - Session management
 * - Biometric authentication support
 * - Password recovery
 * - Audit logging
 */
class SettingsAuthManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SettingsAuthManager"
        private const val PREFS_NAME = "settings_auth"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_LOGIN_ATTEMPTS = "login_attempts"
        private const val KEY_LAST_FAILED_LOGIN = "last_failed_login"
        private const val KEY_SESSION_START = "session_start"
        private const val KEY_SESSION_DURATION = "session_duration"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PASSWORD_RECOVERY_QUESTION = "recovery_question"
        private const val KEY_PASSWORD_RECOVERY_ANSWER = "recovery_answer_hash"
        
        // Session settings
        private const val DEFAULT_SESSION_DURATION_MS = 15 * 60 * 1000L // 15 minutes
        private const val MAX_SESSION_DURATION_MS = 60 * 60 * 1000L // 1 hour
        private const val MIN_SESSION_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        
        // Brute force protection
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    /**
     * Authentication types
     */
    enum class AuthType {
        PASSWORD, PIN, BIOMETRIC, NONE
    }
    
    /**
     * Authentication result
     */
    data class AuthResult(
        val success: Boolean,
        val message: String,
        val remainingAttempts: Int = 0,
        val lockoutTime: Long = 0,
        val sessionDuration: Long = 0
    )
    
    /**
     * Session info
     */
    data class SessionInfo(
        val isActive: Boolean,
        val startTime: Long,
        val duration: Long,
        val remainingTime: Long,
        val authType: AuthType
    )
    
    private val securePrefs = SecurePreferences(context, PREFS_NAME, true)
    
    init {
        // Initialize encryption if needed
        if (securePrefs.getEncryptionStatus().needsMigration) {
            securePrefs.migrateToEncryption()
        }
    }
    
    /**
     * Set up password authentication
     */
    fun setupPassword(password: String): PasswordManager.AuthResult {
        val errors = PasswordManager.validatePasswordRequirements(password)
        if (errors.isNotEmpty()) {
            return PasswordManager.AuthResult(
                success = false,
                message = errors.joinToString(", ")
            )
        }
        
        if (PasswordManager.isCommonPassword(password)) {
            return PasswordManager.AuthResult(
                success = false,
                message = "Пароль слишком простой. Выберите более надежный пароль."
            )
        }
        
        val (hash, salt) = PasswordManager.hashPassword(password)
        securePrefs.putString(KEY_PASSWORD_HASH, hash)
        securePrefs.putString(KEY_PASSWORD_SALT, salt)
        securePrefs.putString(KEY_AUTH_TYPE, AuthType.PASSWORD.name)
        
        Log.i(TAG, "Password authentication set up successfully")
        return PasswordManager.AuthResult(success = true, message = "Пароль установлен успешно")
    }
    
    /**
     * Set up PIN authentication
     */
    fun setupPin(pin: String): PasswordManager.AuthResult {
        if (!PasswordManager.validatePin(pin)) {
            return PasswordManager.AuthResult(
                success = false,
                message = "PIN должен содержать 4 цифры"
            )
        }
        
        val (hash, salt) = PasswordManager.hashPassword(pin)
        securePrefs.putString(KEY_PIN_HASH, hash)
        securePrefs.putString(KEY_PIN_SALT, salt)
        securePrefs.putString(KEY_AUTH_TYPE, AuthType.PIN.name)
        
        Log.i(TAG, "PIN authentication set up successfully")
        return PasswordManager.AuthResult(success = true, message = "PIN установлен успешно")
    }
    
    /**
     * Enable biometric authentication
     */
    fun enableBiometric(): Boolean {
        securePrefs.putBoolean(KEY_BIOMETRIC_ENABLED, true)
        Log.i(TAG, "Biometric authentication enabled")
        return true
    }
    
    /**
     * Disable biometric authentication
     */
    fun disableBiometric(): Boolean {
        securePrefs.putBoolean(KEY_BIOMETRIC_ENABLED, false)
        Log.i(TAG, "Biometric authentication disabled")
        return true
    }
    
    /**
     * Authenticate with password
     */
    fun authenticateWithPassword(password: String): AuthResult {
        return authenticate(password, AuthType.PASSWORD)
    }
    
    /**
     * Authenticate with PIN
     */
    fun authenticateWithPin(pin: String): AuthResult {
        return authenticate(pin, AuthType.PIN)
    }
    
    /**
     * Authenticate with biometric
     */
    fun authenticateWithBiometric(): AuthResult {
        // This would integrate with Android's BiometricPrompt
        // For now, we'll simulate biometric authentication
        if (isBiometricEnabled()) {
            startSession(AuthType.BIOMETRIC)
            Log.i(TAG, "Biometric authentication successful")
            return AuthResult(
                success = true,
                message = "Биометрическая аутентификация успешна",
                sessionDuration = getSessionDuration()
            )
        }
        
        return AuthResult(
            success = false,
            message = "Биометрическая аутентификация не настроена"
        )
    }
    
    /**
     * Internal authentication method
     */
    private fun authenticate(credential: String, authType: AuthType): AuthResult {
        // Check if account is locked
        val lockoutTime = getLockoutTime()
        if (lockoutTime > 0) {
            val remainingLockout = lockoutTime - System.currentTimeMillis()
            if (remainingLockout > 0) {
                return AuthResult(
                    success = false,
                    message = "Аккаунт заблокирован. Попробуйте через ${remainingLockout / 1000 / 60} минут",
                    lockoutTime = remainingLockout
                )
            } else {
                // Lockout expired, reset attempts
                resetLoginAttempts()
            }
        }
        
        // Verify credential
        val isValid = when (authType) {
            AuthType.PASSWORD -> {
                val hash = securePrefs.getString(KEY_PASSWORD_HASH)
                val salt = securePrefs.getString(KEY_PASSWORD_SALT)
                if (hash != null && salt != null) {
                    PasswordManager.verifyPassword(credential, hash, salt)
                } else {
                    false
                }
            }
            AuthType.PIN -> {
                val hash = securePrefs.getString(KEY_PIN_HASH)
                val salt = securePrefs.getString(KEY_PIN_SALT)
                if (hash != null && salt != null) {
                    PasswordManager.verifyPassword(credential, hash, salt)
                } else {
                    false
                }
            }
            else -> false
        }
        
        if (isValid) {
            // Authentication successful
            resetLoginAttempts()
            startSession(authType)
            Log.i(TAG, "Authentication successful with $authType")
            return AuthResult(
                success = true,
                message = "Аутентификация успешна",
                sessionDuration = getSessionDuration()
            )
        } else {
            // Authentication failed
            val remainingAttempts = incrementLoginAttempts()
            Log.w(TAG, "Authentication failed with $authType, remaining attempts: $remainingAttempts")
            
            if (remainingAttempts <= 0) {
                setLockoutTime(System.currentTimeMillis() + LOCKOUT_DURATION_MS)
                return AuthResult(
                    success = false,
                    message = "Слишком много неудачных попыток. Аккаунт заблокирован на 5 минут",
                    remainingAttempts = 0,
                    lockoutTime = LOCKOUT_DURATION_MS
                )
            }
            
            return AuthResult(
                success = false,
                message = "Неверный пароль. Осталось попыток: $remainingAttempts",
                remainingAttempts = remainingAttempts
            )
        }
    }
    
    /**
     * Start authentication session
     */
    private fun startSession(authType: AuthType) {
        val sessionStart = System.currentTimeMillis()
        securePrefs.putLong(KEY_SESSION_START, sessionStart)
        securePrefs.putString(KEY_AUTH_TYPE, authType.name)
        Log.d(TAG, "Session started with $authType")
    }
    
    /**
     * End authentication session
     */
    fun endSession() {
        securePrefs.remove(KEY_SESSION_START)
        Log.d(TAG, "Session ended")
    }
    
    /**
     * Check if session is active
     */
    fun isSessionActive(): Boolean {
        val sessionStart = securePrefs.getLong(KEY_SESSION_START, 0)
        if (sessionStart == 0L) return false
        
        val sessionDuration = getSessionDuration()
        val elapsed = System.currentTimeMillis() - sessionStart
        
        return elapsed < sessionDuration
    }
    
    /**
     * Get session info
     */
    fun getSessionInfo(): SessionInfo {
        val sessionStart = securePrefs.getLong(KEY_SESSION_START, 0)
        val sessionDuration = getSessionDuration()
        val elapsed = System.currentTimeMillis() - sessionStart
        val remainingTime = maxOf(0, sessionDuration - elapsed)
        
        val authTypeStr = securePrefs.getString(KEY_AUTH_TYPE, AuthType.NONE.name)
        val authType = try {
            AuthType.valueOf(authTypeStr ?: AuthType.NONE.name)
        } catch (e: Exception) {
            AuthType.NONE
        }
        
        return SessionInfo(
            isActive = isSessionActive(),
            startTime = sessionStart,
            duration = sessionDuration,
            remainingTime = remainingTime,
            authType = authType
        )
    }
    
    /**
     * Set session duration
     */
    fun setSessionDuration(durationMs: Long) {
        val validDuration = when {
            durationMs < MIN_SESSION_DURATION_MS -> MIN_SESSION_DURATION_MS
            durationMs > MAX_SESSION_DURATION_MS -> MAX_SESSION_DURATION_MS
            else -> durationMs
        }
        
        securePrefs.putLong(KEY_SESSION_DURATION, validDuration)
        Log.d(TAG, "Session duration set to ${validDuration / 1000 / 60} minutes")
    }
    
    /**
     * Get session duration
     */
    private fun getSessionDuration(): Long {
        return securePrefs.getLong(KEY_SESSION_DURATION, DEFAULT_SESSION_DURATION_MS)
    }
    
    /**
     * Check if authentication is set up
     */
    fun isAuthenticationSetUp(): Boolean {
        val authType = securePrefs.getString(KEY_AUTH_TYPE, AuthType.NONE.name)
        return authType != AuthType.NONE.name && (
            securePrefs.contains(KEY_PASSWORD_HASH) || 
            securePrefs.contains(KEY_PIN_HASH) ||
            isBiometricEnabled()
        )
    }
    
    /**
     * Get current authentication type
     */
    fun getCurrentAuthType(): AuthType {
        val authTypeStr = securePrefs.getString(KEY_AUTH_TYPE, AuthType.NONE.name)
        return try {
            AuthType.valueOf(authTypeStr ?: AuthType.NONE.name)
        } catch (e: Exception) {
            AuthType.NONE
        }
    }
    
    /**
     * Check if biometric is enabled
     */
    fun isBiometricEnabled(): Boolean {
        return securePrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }
    
    /**
     * Increment login attempts
     */
    private fun incrementLoginAttempts(): Int {
        val currentAttempts = securePrefs.getInt(KEY_LOGIN_ATTEMPTS, 0)
        val newAttempts = currentAttempts + 1
        securePrefs.putInt(KEY_LOGIN_ATTEMPTS, newAttempts)
        securePrefs.putLong(KEY_LAST_FAILED_LOGIN, System.currentTimeMillis())
        return maxOf(0, MAX_LOGIN_ATTEMPTS - newAttempts)
    }
    
    /**
     * Reset login attempts
     */
    private fun resetLoginAttempts() {
        securePrefs.putInt(KEY_LOGIN_ATTEMPTS, 0)
        securePrefs.remove(KEY_LAST_FAILED_LOGIN)
    }
    
    /**
     * Get lockout time
     */
    private fun getLockoutTime(): Long {
        return securePrefs.getLong(KEY_LAST_FAILED_LOGIN, 0)
    }
    
    /**
     * Set lockout time
     */
    private fun setLockoutTime(lockoutTime: Long) {
        securePrefs.putLong(KEY_LAST_FAILED_LOGIN, lockoutTime)
    }
    
    /**
     * Clear all authentication data
     */
    fun clearAuthentication() {
        securePrefs.remove(KEY_PASSWORD_HASH)
        securePrefs.remove(KEY_PASSWORD_SALT)
        securePrefs.remove(KEY_PIN_HASH)
        securePrefs.remove(KEY_PIN_SALT)
        securePrefs.remove(KEY_AUTH_TYPE)
        securePrefs.remove(KEY_SESSION_START)
        securePrefs.putBoolean(KEY_BIOMETRIC_ENABLED, false)
        resetLoginAttempts()
        Log.i(TAG, "All authentication data cleared")
    }
    
    /**
     * Set up password recovery
     */
    fun setupPasswordRecovery(question: String, answer: String): Boolean {
        val (hash, salt) = PasswordManager.hashPassword(answer)
        securePrefs.putString(KEY_PASSWORD_RECOVERY_QUESTION, question)
        securePrefs.putString(KEY_PASSWORD_RECOVERY_ANSWER, hash)
        securePrefs.putString(KEY_PASSWORD_SALT, salt) // Reuse salt for recovery
        Log.i(TAG, "Password recovery set up")
        return true
    }
    
    /**
     * Verify recovery answer
     */
    fun verifyRecoveryAnswer(answer: String): Boolean {
        val hash = securePrefs.getString(KEY_PASSWORD_RECOVERY_ANSWER)
        val salt = securePrefs.getString(KEY_PASSWORD_SALT)
        
        if (hash != null && salt != null) {
            return PasswordManager.verifyPassword(answer, hash, salt)
        }
        
        return false
    }
    
    /**
     * Get recovery question
     */
    fun getRecoveryQuestion(): String? {
        return securePrefs.getString(KEY_PASSWORD_RECOVERY_QUESTION)
    }
    
    /**
     * Get authentication status
     */
    fun getAuthenticationStatus(): AuthenticationStatus {
        return AuthenticationStatus(
            isSetUp = isAuthenticationSetUp(),
            authType = getCurrentAuthType(),
            biometricEnabled = isBiometricEnabled(),
            sessionActive = isSessionActive(),
            sessionInfo = getSessionInfo(),
            lockoutTime = getLockoutTime()
        )
    }
}

/**
 * Data class for authentication status
 */
data class AuthenticationStatus(
    val isSetUp: Boolean,
    val authType: SettingsAuthManager.AuthType,
    val biometricEnabled: Boolean,
    val sessionActive: Boolean,
    val sessionInfo: SettingsAuthManager.SessionInfo,
    val lockoutTime: Long
)
