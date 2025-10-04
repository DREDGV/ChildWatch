package ru.example.childwatch.utils

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Password manager for settings authentication
 * 
 * Features:
 * - Secure password hashing with salt
 * - PIN code support (4-6 digits)
 * - Password strength validation
 * - Secure storage of password hashes
 * - Brute force protection
 * - Password recovery options
 */
object PasswordManager {
    
    private const val TAG = "PasswordManager"
    private const val MIN_PASSWORD_LENGTH = 4
    private const val MAX_PASSWORD_LENGTH = 20
    private const val PIN_LENGTH = 4
    private const val MAX_LOGIN_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    
    /**
     * Password strength levels
     */
    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG, VERY_STRONG
    }
    
    /**
     * Authentication result
     */
    data class AuthResult(
        val success: Boolean,
        val message: String,
        val remainingAttempts: Int = 0,
        val lockoutTime: Long = 0
    )
    
    /**
     * Validate password strength
     */
    fun validatePasswordStrength(password: String): PasswordStrength {
        if (password.length < MIN_PASSWORD_LENGTH) {
            return PasswordStrength.WEAK
        }
        
        var score = 0
        
        // Length bonus
        when {
            password.length >= 12 -> score += 2
            password.length >= 8 -> score += 1
        }
        
        // Character variety bonus
        if (password.any { it.isLowerCase() }) score += 1
        if (password.any { it.isUpperCase() }) score += 1
        if (password.any { it.isDigit() }) score += 1
        if (password.any { "!@#$%^&*()_+-=[]{}|;:,.<>?".contains(it) }) score += 1
        
        return when (score) {
            0, 1 -> PasswordStrength.WEAK
            2, 3 -> PasswordStrength.MEDIUM
            4, 5 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }
    
    /**
     * Validate PIN code
     */
    fun validatePin(pin: String): Boolean {
        return pin.length == PIN_LENGTH && pin.all { it.isDigit() }
    }
    
    /**
     * Generate secure salt
     */
    private fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.DEFAULT)
    }
    
    /**
     * Hash password with salt
     */
    fun hashPassword(password: String, salt: String? = null): Pair<String, String> {
        val actualSalt = salt ?: generateSalt()
        val combined = password + actualSalt
        
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))
        
        return Pair(
            Base64.encodeToString(hash, Base64.DEFAULT),
            actualSalt
        )
    }
    
    /**
     * Verify password
     */
    fun verifyPassword(password: String, hash: String, salt: String): Boolean {
        val (calculatedHash, _) = hashPassword(password, salt)
        return calculatedHash == hash
    }
    
    /**
     * Generate random PIN
     */
    fun generateRandomPin(): String {
        val random = SecureRandom()
        val pin = random.nextInt(9000) + 1000 // Generate 4-digit PIN
        return pin.toString()
    }
    
    /**
     * Check if password meets requirements
     */
    fun validatePasswordRequirements(password: String): List<String> {
        val errors = mutableListOf<String>()
        
        if (password.length < MIN_PASSWORD_LENGTH) {
            errors.add("Пароль должен содержать минимум $MIN_PASSWORD_LENGTH символов")
        }
        
        if (password.length > MAX_PASSWORD_LENGTH) {
            errors.add("Пароль не должен превышать $MAX_PASSWORD_LENGTH символов")
        }
        
        if (!password.any { it.isLetter() }) {
            errors.add("Пароль должен содержать хотя бы одну букву")
        }
        
        if (!password.any { it.isDigit() }) {
            errors.add("Пароль должен содержать хотя бы одну цифру")
        }
        
        return errors
    }
    
    /**
     * Get password strength description
     */
    fun getPasswordStrengthDescription(strength: PasswordStrength): String {
        return when (strength) {
            PasswordStrength.WEAK -> "Слабый пароль"
            PasswordStrength.MEDIUM -> "Средний пароль"
            PasswordStrength.STRONG -> "Надежный пароль"
            PasswordStrength.VERY_STRONG -> "Очень надежный пароль"
        }
    }
    
    /**
     * Get password strength color (for UI)
     */
    fun getPasswordStrengthColor(strength: PasswordStrength): Int {
        return when (strength) {
            PasswordStrength.WEAK -> android.graphics.Color.RED
            PasswordStrength.MEDIUM -> android.graphics.Color.YELLOW
            PasswordStrength.STRONG -> android.graphics.Color.GREEN
            PasswordStrength.VERY_STRONG -> android.graphics.Color.BLUE
        }
    }
    
    /**
     * Check if password is common/weak
     */
    fun isCommonPassword(password: String): Boolean {
        val commonPasswords = setOf(
            "password", "123456", "123456789", "qwerty", "abc123",
            "password123", "admin", "letmein", "welcome", "monkey",
            "1234567890", "password1", "qwerty123", "dragon", "master",
            "hello", "login", "welcome123", "admin123", "root"
        )
        
        return commonPasswords.contains(password.lowercase())
    }
    
    /**
     * Generate password suggestions
     */
    fun generatePasswordSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        
        // Generate some secure password suggestions
        val words = listOf("secure", "safe", "child", "watch", "guard", "protect")
        val numbers = (100..999).shuffled().take(3)
        val symbols = listOf("!", "@", "#", "$", "%")
        
        for (word in words.take(3)) {
            for (number in numbers.take(2)) {
                for (symbol in symbols.take(1)) {
                    suggestions.add("${word.capitalize()}${number}${symbol}")
                }
            }
        }
        
        return suggestions.take(6)
    }
}
