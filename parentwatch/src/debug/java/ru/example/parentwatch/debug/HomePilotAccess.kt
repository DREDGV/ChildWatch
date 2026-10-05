package ru.example.parentwatch.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ru.example.parentwatch.BuildConfig

/** Explicit local opt-in; never changes Android's default Home or family configuration. */
internal object HomePilotAccess {
    private const val PREFS = "home_recovery_pilot"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = BuildConfig.DEBUG &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun isAllowed(context: Context): Boolean =
        HomeBackgroundCaptureProbe.isIsolated(context) || isEnabled(context)

    fun isCurrentHome(context: Context): Boolean = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.let {
        it.packageName == context.packageName && it.name == HomeRecoveryProbeActivity::class.java.name
    } == true

    fun enableCandidate(context: Context) {
        check(BuildConfig.DEBUG)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, true).commit())
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, HomeRecoveryProbeActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } catch (error: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, false).commit()
            throw error
        }
    }

    fun disableCandidate(context: Context): Boolean {
        // Keep a functioning launcher until the owner has selected another one in Android.
        if (isCurrentHome(context)) return false
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, HomeRecoveryProbeActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, false).commit()
        return true
    }
}
