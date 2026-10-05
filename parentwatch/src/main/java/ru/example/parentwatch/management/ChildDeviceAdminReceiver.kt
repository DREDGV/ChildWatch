package ru.example.parentwatch.management

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Enrollment is performed by Android provisioning or explicit test-device setup. */
class ChildDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("ChildDeviceAdmin", "Admin enabled; deviceOwner=${ManagedDeviceAccess.isDeviceOwner(context)}")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i("ChildDeviceAdmin", "Provisioning complete; deviceOwner=${ManagedDeviceAccess.isDeviceOwner(context)}")
        // Enrollment never enables monitoring or changes an existing family session.
    }
}
