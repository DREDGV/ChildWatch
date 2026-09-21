package ru.example.parentwatch.session

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The one place that decides which device identifier this phone uses.
 *
 * There used to be three answers. The token manager built `device_<androidId>`
 * and saved it under its own key, while the rest of the application stored a
 * `child-xxxxxxxx` value under `device_id`. Whichever code ran first decided what
 * the server saw, so one phone could register twice under two identities — which
 * is exactly what happened: the server held two member records for one device,
 * and later requests from the application addressed the wrong one.
 *
 * The rule is now: an identifier that has already been used is kept forever, and
 * a new one is only invented when none exists. Changing it would orphan the
 * device from its family, its chats and its history on the server, so it is never
 * regenerated once stored.
 */
object ChildDeviceIdentity {

    private const val TAG = "ChildDeviceIdentity"

    /** Owned by the token manager; the identifier used for registration. */
    private const val TOKEN_PREFS = "parentwatch_tokens"

    /** The application's own store, which holds the `child-xxxxxxxx` value. */
    private const val APP_PREFS = "parentwatch_prefs"

    /** Store used by an older version, still read for an existing identity. */
    private const val LEGACY_PREFS = "childwatch_prefs"

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CHILD_DEVICE_ID = "child_device_id"

    /**
     * Returns the identifier of this device, creating one only if needed.
     *
     * Every reader of a device identifier must go through here so the application
     * can never disagree with itself again.
     */
    fun resolve(context: Context): String {
        val tokenPrefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val appPrefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

        val stored = selectStored(
            appChildDeviceId = appPrefs.getString(KEY_CHILD_DEVICE_ID, null),
            appDeviceId = appPrefs.getString(KEY_DEVICE_ID, null),
            tokenDeviceId = tokenPrefs.getString(KEY_DEVICE_ID, null),
            legacyChildDeviceId = legacyChildDeviceId(context),
            legacyDeviceId = legacyDeviceId(context)
        )
        if (stored != null) {
            persist(tokenPrefs, appPrefs, stored)
            return stored
        }

        // Nothing stored yet: derive a stable value from the device itself rather
        // than inventing a random one, so a device that never stored an identifier
        // still resolves to the same value after a reinstall.
        val derived = androidId(context)?.let { "device_$it" }
            ?: "device_${System.currentTimeMillis() % 100000}"
        Log.i(TAG, "No stored identifier found; using $derived")
        persist(tokenPrefs, appPrefs, derived)
        return derived
    }

    /**
     * Chooses between the stored identifiers.
     *
     * The application's own store comes first: it holds the identifier the device
     * is actually bound to on the server. Preferring the token store would adopt
     * the generated form and orphan an already-registered device, which is how one
     * phone ended up registered twice.
     *
     * Kept separate from preference access so the rule itself can be tested
     * without a device.
     */
    internal fun selectStored(
        appChildDeviceId: String?,
        appDeviceId: String?,
        tokenDeviceId: String?,
        legacyChildDeviceId: String? = null,
        legacyDeviceId: String? = null
    ): String? = clean(appChildDeviceId)
        ?: clean(appDeviceId)
        ?: clean(tokenDeviceId)
        ?: clean(legacyChildDeviceId)
        ?: clean(legacyDeviceId)

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reads without creating.
     *
     * Used by diagnostics and by callers that must not start a registration.
     */
    fun peek(context: Context): String? {
        val tokenPrefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val appPrefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        return selectStored(
            appChildDeviceId = appPrefs.getString(KEY_CHILD_DEVICE_ID, null),
            appDeviceId = appPrefs.getString(KEY_DEVICE_ID, null),
            tokenDeviceId = tokenPrefs.getString(KEY_DEVICE_ID, null),
            legacyChildDeviceId = legacyChildDeviceId(context),
            legacyDeviceId = legacyDeviceId(context)
        )
    }

    private fun persist(
        tokenPrefs: android.content.SharedPreferences,
        appPrefs: android.content.SharedPreferences,
        deviceId: String
    ) {
        // Written to both stores so no reader can ever see a different value.
        tokenPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        appPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        if (!appPrefs.contains(KEY_CHILD_DEVICE_ID)) {
            appPrefs.edit().putString(KEY_CHILD_DEVICE_ID, deviceId).apply()
        }
    }

    private fun androidId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Values written by older versions, which used their own preference file.
     *
     * Reading them keeps an already-registered device on the identity the server
     * knows instead of silently adopting a new one.
     */
    private fun legacyPrefs(context: Context) = runCatching {
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    private fun legacyChildDeviceId(context: Context): String? =
        legacyPrefs(context)?.getString(KEY_CHILD_DEVICE_ID, null)

    private fun legacyDeviceId(context: Context): String? =
        legacyPrefs(context)?.getString(KEY_DEVICE_ID, null)
}
