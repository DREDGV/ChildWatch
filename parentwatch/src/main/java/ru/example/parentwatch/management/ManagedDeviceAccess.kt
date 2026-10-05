package ru.example.parentwatch.management

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/** Only the OS can establish this role; a preference or active admin is insufficient. */
object ManagedDeviceAccess {
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isDeviceOwnerApp(context.packageName) == true
    }.onFailure {
        Log.w("ManagedDeviceAccess", "Unable to verify device owner role", it)
    }.getOrDefault(false)
}
