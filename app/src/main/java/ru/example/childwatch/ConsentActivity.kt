package ru.example.childwatch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.example.childwatch.databinding.ActivityConsentBinding
import ru.example.childwatch.utils.PermissionHelper

/**
 * Consent screen for user agreement to monitoring
 * 
 * This screen must be shown before any monitoring functionality
 * and the user's consent must be stored persistently.
 */
class ConsentActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ConsentActivity"
        private const val PREFS_NAME = "childwatch_consent"
        private const val KEY_CONSENT_GIVEN = "consent_given"
        private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"
        
        /**
         * Check if user has given consent
         */
        fun hasConsent(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CONSENT_GIVEN, false)
        }
        
        /**
         * Get consent timestamp
         */
        fun getConsentTimestamp(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_CONSENT_TIMESTAMP, 0L)
        }
        
        /**
         * Revoke consent (for settings)
         */
        fun revokeConsent(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_CONSENT_GIVEN, false)
                .putLong(KEY_CONSENT_TIMESTAMP, 0L)
                .apply()
            Log.d(TAG, "Consent revoked")
        }
    }
    
    private lateinit var binding: ActivityConsentBinding
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        setupUI()
    }
    
    private fun setupUI() {
        // Set up button click listeners
        binding.btnAgree.setOnClickListener {
            giveConsent()
        }
        
        binding.btnDecline.setOnClickListener {
            declineConsent()
        }
    }
    
    private fun giveConsent() {
        Log.d(TAG, "User gave consent")
        
        // Store consent with timestamp
        val timestamp = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .putLong(KEY_CONSENT_TIMESTAMP, timestamp)
            .apply()
        
        // Show confirmation
        Toast.makeText(this, getString(R.string.consent_given), Toast.LENGTH_SHORT).show()
        
        // Check permissions and proceed to main activity
        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            proceedToMainActivity()
        } else {
            // Request permissions first
            PermissionHelper.requestAllRequiredPermissions(this)
        }
    }
    
    private fun declineConsent() {
        Log.d(TAG, "User declined consent")
        
        // Clear any existing consent
        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, false)
            .putLong(KEY_CONSENT_TIMESTAMP, 0L)
            .apply()
        
        // Show message and finish
        Toast.makeText(this, getString(R.string.consent_revoked), Toast.LENGTH_LONG).show()
        
        // Finish activity - user can restart app later
        finish()
    }
    
    private fun proceedToMainActivity() {
        Log.d(TAG, "Proceeding to main activity")
        
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            proceedToMainActivity()
        } else {
            // Show error and finish
            Toast.makeText(
                this, 
                getString(R.string.permissions_denied, PermissionHelper.getDeniedPermissions(this).joinToString(", ")),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
    
    override fun onBackPressed() {
        // Prevent going back without making a choice
        Toast.makeText(this, "Пожалуйста, выберите один из вариантов", Toast.LENGTH_SHORT).show()
    }
}
