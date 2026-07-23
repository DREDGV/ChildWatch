package ru.example.parentwatch.session

import android.content.Context

/** Local state only records completion. The server remains the source of family truth. */
class ChildFamilyOnboardingStore(context: Context) {

    private val appContext = context.applicationContext
    private val onboardingPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val primaryPrefs = appContext.getSharedPreferences(PRIMARY_PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = onboardingPrefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted(familyId: String, memberId: String) {
        onboardingPrefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .putString(KEY_FAMILY_ID, familyId.trim())
            .putString(KEY_MEMBER_ID, memberId.trim())
            .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
            .apply()
    }

    fun hasLegacyParentLink(): Boolean = sequenceOf(primaryPrefs, legacyPrefs)
        .flatMap { prefs ->
            sequenceOf(
                prefs.getString(KEY_SELECTED_PARENT_DEVICE_ID, null),
                prefs.getString(KEY_PARENT_DEVICE_ID, null),
                prefs.getString(KEY_LINKED_PARENT_DEVICE_ID, null)
            )
        }
        .any { !it.isNullOrBlank() }

    companion object {
        private const val PREFS_NAME = "family_onboarding"
        private const val PRIMARY_PREFS_NAME = "parentwatch_prefs"
        private const val LEGACY_PREFS_NAME = "childwatch_prefs"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_MEMBER_ID = "member_id"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_SELECTED_PARENT_DEVICE_ID = "selected_parent_device_id"
        private const val KEY_PARENT_DEVICE_ID = "parent_device_id"
        private const val KEY_LINKED_PARENT_DEVICE_ID = "linked_parent_device_id"
    }
}
