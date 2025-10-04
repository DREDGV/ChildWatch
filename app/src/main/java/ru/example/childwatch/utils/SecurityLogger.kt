package ru.example.childwatch.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Security logger for tracking security events and violations
 * 
 * Features:
 * - Log security events
 * - Track security violations
 * - Store security reports
 * - Generate security alerts
 */
object SecurityLogger {
    
    private const val TAG = "SecurityLogger"
    private const val PREFS_NAME = "childwatch_security"
    private const val KEY_SECURITY_EVENTS = "security_events"
    private const val KEY_VIOLATION_COUNT = "violation_count"
    private const val KEY_LAST_VIOLATION = "last_violation"
    private const val KEY_SECURITY_SCORE = "security_score"
    
    private const val MAX_EVENTS = 100 // Maximum number of events to store
    
    /**
     * Log a security event
     */
    fun logSecurityEvent(context: Context, event: SecurityEvent) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val events = getSecurityEvents(context).toMutableList()
            
            // Add new event
            events.add(event)
            
            // Keep only the last MAX_EVENTS events
            if (events.size > MAX_EVENTS) {
                events.removeAt(0)
            }
            
            // Save events
            val eventsJson = events.joinToString("|") { it.toJson() }
            prefs.edit()
                .putString(KEY_SECURITY_EVENTS, eventsJson)
                .apply()
            
            // Update violation count if it's a violation
            if (event.isViolation) {
                val violationCount = prefs.getInt(KEY_VIOLATION_COUNT, 0) + 1
                prefs.edit()
                    .putInt(KEY_VIOLATION_COUNT, violationCount)
                    .putLong(KEY_LAST_VIOLATION, System.currentTimeMillis())
                    .apply()
            }
            
            // Log to system log
            Log.w(TAG, "Security event: ${event.type} - ${event.description}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error logging security event", e)
        }
    }
    
    /**
     * Get all security events
     */
    fun getSecurityEvents(context: Context): List<SecurityEvent> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val eventsJson = prefs.getString(KEY_SECURITY_EVENTS, "") ?: ""
            
            if (eventsJson.isEmpty()) {
                emptyList()
            } else {
                eventsJson.split("|").mapNotNull { json ->
                    try {
                        SecurityEvent.fromJson(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing security event: $json", e)
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting security events", e)
            emptyList()
        }
    }
    
    /**
     * Get violation count
     */
    fun getViolationCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_VIOLATION_COUNT, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting violation count", e)
            0
        }
    }
    
    /**
     * Get last violation timestamp
     */
    fun getLastViolationTimestamp(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_LAST_VIOLATION, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last violation timestamp", e)
            0
        }
    }
    
    /**
     * Clear all security events
     */
    fun clearSecurityEvents(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_SECURITY_EVENTS)
                .remove(KEY_VIOLATION_COUNT)
                .remove(KEY_LAST_VIOLATION)
                .apply()
            
            Log.i(TAG, "Security events cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing security events", e)
        }
    }
    
    /**
     * Generate security report
     */
    fun generateSecurityReport(context: Context): SecurityLoggerReport {
        val events = getSecurityEvents(context)
        val violationCount = getViolationCount(context)
        val lastViolation = getLastViolationTimestamp(context)
        
        val recentEvents = events.filter { 
            System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000 // Last 24 hours
        }
        
        val recentViolations = recentEvents.count { it.isViolation }
        
        return SecurityLoggerReport(
            totalEvents = events.size,
            totalViolations = violationCount,
            recentEvents = recentEvents.size,
            recentViolations = recentViolations,
            lastViolationTimestamp = lastViolation,
            events = events.takeLast(10) // Last 10 events
        )
    }
    
    /**
     * Check if security is compromised based on recent events
     */
    fun isSecurityCompromised(context: Context): Boolean {
        val report = generateSecurityReport(context)
        return report.recentViolations > 3 || report.totalViolations > 10
    }
    
    /**
     * Get security warnings
     */
    fun getSecurityWarnings(context: Context): List<String> {
        val warnings = mutableListOf<String>()
        val report = generateSecurityReport(context)
        
        if (report.recentViolations > 0) {
            warnings.add("Обнаружены нарушения безопасности за последние 24 часа: ${report.recentViolations}")
        }
        
        if (report.totalViolations > 5) {
            warnings.add("Общее количество нарушений безопасности: ${report.totalViolations}")
        }
        
        if (report.lastViolationTimestamp > 0) {
            val lastViolation = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(report.lastViolationTimestamp))
            warnings.add("Последнее нарушение: $lastViolation")
        }
        
        return warnings
    }
}

/**
 * Data class for security event
 */
data class SecurityEvent(
    val type: SecurityEventType,
    val description: String,
    val isViolation: Boolean,
    val timestamp: Long,
    val details: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return "$type|$description|$isViolation|$timestamp|${details.entries.joinToString(",") { "${it.key}=${it.value}" }}"
    }
    
    companion object {
        fun fromJson(json: String): SecurityEvent {
            val parts = json.split("|")
            val type = SecurityEventType.valueOf(parts[0])
            val description = parts[1]
            val isViolation = parts[2].toBoolean()
            val timestamp = parts[3].toLong()
            val details = if (parts.size > 4 && parts[4].isNotEmpty()) {
                parts[4].split(",").associate { 
                    val keyValue = it.split("=")
                    keyValue[0] to keyValue[1]
                }
            } else {
                emptyMap()
            }
            
            return SecurityEvent(type, description, isViolation, timestamp, details)
        }
    }
}

/**
 * Enum for security event types
 */
enum class SecurityEventType {
    DEBUG_BUILD_DETECTED,
    DEVELOPER_OPTIONS_ENABLED,
    USB_DEBUGGING_ENABLED,
    ROOT_DETECTED,
    EMULATOR_DETECTED,
    DEBUGGER_ATTACHED,
    APP_DEBUGGABLE,
    MOCK_LOCATION_ENABLED,
    UNAUTHORIZED_ACCESS,
    TOKEN_TAMPERING,
    NETWORK_INTERCEPTION,
    PERMISSION_ESCALATION,
    SECURITY_CHECK_FAILED,
    UNKNOWN_THREAT
}

/**
 * Data class for security report
 */
data class SecurityLoggerReport(
    val totalEvents: Int,
    val totalViolations: Int,
    val recentEvents: Int,
    val recentViolations: Int,
    val lastViolationTimestamp: Long,
    val events: List<SecurityEvent>
) {
    val securityLevel: String
        get() = when {
            recentViolations == 0 && totalViolations < 3 -> "Безопасно"
            recentViolations < 2 && totalViolations < 5 -> "Низкий риск"
            recentViolations < 5 && totalViolations < 10 -> "Средний риск"
            else -> "Высокий риск"
        }
}
