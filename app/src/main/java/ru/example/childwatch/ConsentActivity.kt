package ru.example.childwatch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.example.childwatch.databinding.ActivityConsentBinding
import ru.example.childwatch.utils.PermissionHelper

/**
 * Consent screen for user agreement to monitoring.
 */
class ConsentActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ConsentActivity"
        private const val PREFS_NAME = "childwatch_consent"
        private const val KEY_CONSENT_GIVEN = "consent_given"
        private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"

        fun hasConsent(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CONSENT_GIVEN, false)
        }

        fun getConsentTimestamp(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_CONSENT_TIMESTAMP, 0L)
        }

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
        binding.btnAgree.setOnClickListener { giveConsent() }
        binding.btnDecline.setOnClickListener { declineConsent() }
    }

    private fun giveConsent() {
        Log.d(TAG, "User gave consent")

        val timestamp = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .putLong(KEY_CONSENT_TIMESTAMP, timestamp)
            .apply()

        Toast.makeText(this, getString(R.string.consent_given), Toast.LENGTH_SHORT).show()

        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            proceedToMainActivity()
        } else {
            showPermissionDialog()
        }
    }

    private fun showPermissionDialog() {
        val permissionNames = PermissionHelper.getMissingPermissions(this).map { permission ->
            when (permission) {
                android.Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.consent_permission_name_fine_location)
                android.Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.consent_permission_name_coarse_location)
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION -> getString(R.string.consent_permission_name_background_location)
                android.Manifest.permission.RECORD_AUDIO -> getString(R.string.consent_permission_name_audio)
                android.Manifest.permission.CAMERA -> getString(R.string.consent_permission_name_camera)
                else -> permission
            }
        }

        val message = buildString {
            append(getString(R.string.consent_permission_dialog_intro))
            append("\n\n")
            append(permissionNames.joinToString(separator = "\n", prefix = "• "))
            append("\n\n")
            append(getString(R.string.consent_permission_dialog_action))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.consent_permission_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.consent_permission_allow) { _, _ ->
                PermissionHelper.requestAllRequiredPermissions(this)
            }
            .setNegativeButton(R.string.consent_permission_cancel) { _, _ ->
                Toast.makeText(this, getString(R.string.consent_permission_required_exit), Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun declineConsent() {
        Log.d(TAG, "User declined consent")

        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, false)
            .putLong(KEY_CONSENT_TIMESTAMP, 0L)
            .apply()

        Toast.makeText(this, getString(R.string.consent_revoked), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun proceedToMainActivity() {
        Log.d(TAG, "Proceeding to main activity")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.d(TAG, "Permission result: requestCode=$requestCode, granted=${grantResults.all { it == PackageManager.PERMISSION_GRANTED }}")

        if (requestCode != PermissionHelper.REQUEST_CODE_ALL_PERMISSIONS) return

        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            Log.d(TAG, "All permissions granted, proceeding to main activity")
            proceedToMainActivity()
            return
        }

        Log.w(TAG, "Some permissions denied")
        val deniedNames = permissions.filterIndexed { index, _ ->
            grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
        }.map { permission ->
            when (permission) {
                android.Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.consent_permission_name_fine_location)
                android.Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.consent_permission_name_coarse_location)
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION -> getString(R.string.consent_permission_name_background_location)
                android.Manifest.permission.RECORD_AUDIO -> getString(R.string.consent_permission_name_audio)
                android.Manifest.permission.CAMERA -> getString(R.string.consent_permission_name_camera)
                else -> permission
            }
        }

        val message = buildString {
            append(getString(R.string.consent_permission_denied_intro))
            append("\n")
            append(deniedNames.joinToString(separator = "\n", prefix = "• "))
            append("\n\n")
            append(getString(R.string.consent_permission_denied_action))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.consent_permission_denied_title)
            .setMessage(message)
            .setPositiveButton(R.string.consent_permission_settings) { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNeutralButton(R.string.consent_permission_retry) { _, _ ->
                PermissionHelper.requestAllRequiredPermissions(this)
            }
            .setNegativeButton(R.string.consent_permission_exit) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (hasConsent(this) && PermissionHelper.hasAllRequiredPermissions(this)) {
            Log.d(TAG, "Consent given and all permissions granted, proceeding to main activity")
            proceedToMainActivity()
        }
    }

    override fun onBackPressed() {
        Toast.makeText(this, getString(R.string.consent_back_choice_required), Toast.LENGTH_SHORT).show()
    }
}
