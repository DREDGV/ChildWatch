package ru.example.parentwatch.management

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** System-only provisioning callbacks, protected by BIND_DEVICE_ADMIN in the manifest. */
class ManagedProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val allowed = intent.getIntegerArrayListExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
                    )
                    val mode = DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                    if (allowed?.contains(mode) == true) {
                        setResult(RESULT_OK, Intent().putExtra(
                            DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode
                        ))
                    }
                }
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                // A work profile or ordinary administrator cannot satisfy this mode.
                if (ManagedDeviceAccess.isDeviceOwner(this)) setResult(RESULT_OK)
            }
        }
        finish()
    }
}
