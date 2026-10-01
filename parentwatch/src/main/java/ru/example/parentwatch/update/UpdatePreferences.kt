package ru.example.parentwatch.update

import android.content.Context
import android.util.Log

/**
 * What this application remembers about updates between launches.
 *
 * It is a separate file from the settings the person can edit: nothing here is a
 * preference, and nothing here should ever be cleared by "reset settings" — the
 * remembered time of the last successful check is what keeps the phone from asking
 * the server on every single resume.
 */
class UpdatePreferences(context: Context) {

    companion object {
        private const val TAG = "UpdatePreferences"
        private const val PREFS_NAME = "update_prefs"

        private const val KEY_SERVER = "update_server_base"
        private const val KEY_DISMISSED_AT = "dismissed_at"
        private const val KEY_LAST_CHECK = "last_successful_check_at"
        private const val KEY_DISMISSED_VERSION = "dismissed_version_code"
        private const val KEY_PENDING_NOTE = "pending_failure_note"
        private const val KEY_OFFERED_MANIFEST = "offered_manifest_json"
        private const val KEY_OFFERED_VERSION = "offered_version_code"
        private const val KEY_LAST_CHECK_VERSION = "last_successful_check_version_code"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cached offers and check times belong only to the server that produced them. */
    fun bindServer(base: String): Boolean {
        if (prefs.getString(KEY_SERVER, null) == base) return false
        prefs.edit().putString(KEY_SERVER, base)
            .remove(KEY_LAST_CHECK).remove(KEY_LAST_CHECK_VERSION)
            .remove(KEY_OFFERED_MANIFEST).remove(KEY_OFFERED_VERSION)
            .remove(KEY_DISMISSED_VERSION).remove(KEY_DISMISSED_AT).apply()
        return true
    }

    fun dismissalStillActive(versionCode: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (dismissedVersionCode() != versionCode) return false
        val at = prefs.getLong(KEY_DISMISSED_AT, 0L)
        return at > 0L && now >= at && now - at < 24L * 60L * 60L * 1000L
    }

    /**
     * The release found earlier and neither installed nor dismissed.
     *
     * The check runs at most once a day, so without this the offer lived only in the
     * session that discovered it: the next launch skipped the check, showed nothing,
     * and a person who missed the notice had no way to see it again until the
     * following day. The release is known, so it stays offered.
     *
     * @return the manifest that produced the offer, and the version it named.
     */
    fun offeredManifest(): Pair<String, Int>? {
        val raw = prefs.getString(KEY_OFFERED_MANIFEST, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val versionCode = prefs.getInt(KEY_OFFERED_VERSION, 0)
        if (versionCode <= 0) return null
        return raw to versionCode
    }

    fun rememberOffered(manifestRaw: String, versionCode: Int) {
        if (manifestRaw.isBlank() || versionCode <= 0) return
        prefs.edit()
            .putString(KEY_OFFERED_MANIFEST, manifestRaw)
            .putInt(KEY_OFFERED_VERSION, versionCode)
            .apply()
    }

    /**
     * Forgets the offer, because it was installed or closed.
     *
     * Closing it is remembered separately as a dismissal: an update that is no
     * longer offered must not come back on the next launch either.
     */
    fun forgetOffered() {
        prefs.edit()
            .remove(KEY_OFFERED_MANIFEST)
            .remove(KEY_OFFERED_VERSION)
            .apply()
    }

    /**
     * When the release manifest was last read successfully.
     *
     * This is written only when a check actually succeeded — either an update was
     * found or the server confirmed there is none. A check that failed for any reason
     * leaves it untouched, so the next launch tries again instead of staying silent
     * for a day after one moment without a network.
     */
    fun lastSuccessfulCheckAt(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    /**
     * The version code that made the last successful check.
     *
     * Kept so that a check can be told apart from a check made by an older build:
     * installing an update does not clear this file, so without the version the new
     * build would inherit the old one's "checked a minute ago" and say nothing for
     * the rest of the day.
     */
    fun lastSuccessfulCheckVersionCode(): Int = prefs.getInt(KEY_LAST_CHECK_VERSION, 0)

    /**
     * Records a successful check, made by [versionCode].
     *
     * The single writer for [KEY_LAST_CHECK].
     */
    fun recordSuccessfulCheck(atMillis: Long, versionCode: Int) {
        prefs.edit()
            .putLong(KEY_LAST_CHECK, atMillis)
            .putInt(KEY_LAST_CHECK_VERSION, versionCode)
            .apply()
    }

    /** The version the person closed the notice for, on this installation. */
    fun dismissedVersionCode(): Int = prefs.getInt(KEY_DISMISSED_VERSION, 0)

    /**
     * Remembers which version the person closed the notice for.
     *
     * The dismissal is deliberately not permanent: when a newer release appears its
     * version code differs and the notice is offered again. Closing the notice means
     * "not this one, not now", never "never ask me again" — an application that can
     * never be updated again is worse than one that asks twice.
     */
    fun dismissVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * A failure reported while no screen was running.
     *
     * An installation replaces the application, which kills this process, so the
     * outcome of an install arrives while nothing is on screen. It is written here
     * and read exactly once by the next launch, which shows it as a readable message
     * instead of losing it.
     */
    fun pendingFailureNote(): String? =
        prefs.getString(KEY_PENDING_NOTE, null)?.takeIf { it.isNotBlank() }

    /**
     * Writes the note, and answers whether it could be written.
     *
     * Losing it silently would leave a person who tapped "install" with no
     * explanation at all, so the answer is reported to the caller, which logs it.
     */
    fun writePendingFailureNote(note: String): Boolean {
        if (note.isBlank()) return true
        return try {
            prefs.edit().putString(KEY_PENDING_NOTE, note).commit()
        } catch (error: Throwable) {
            Log.e(TAG, "Could not store the update failure note", error)
            false
        }
    }

    /** Reads the note and removes it, so it is shown exactly once. */
    fun consumePendingFailureNote(): String? {
        // A failure to read must not be reported as "there is no note", so the read
        // is not wrapped in a silent catch — an unreadable preference file is a real
        // fault and the caller logs it.
        val note = prefs.getString(KEY_PENDING_NOTE, null)
        clearPendingFailureNote()
        return note?.takeIf { it.isNotBlank() }
    }

    /**
     * Removes any note without showing it.
     *
     * Used when an installation actually succeeded: a note left by an earlier failed
     * attempt would otherwise be shown on the next launch as the outcome of the
     * attempt that worked.
     */
    fun clearPendingFailureNote() {
        try {
            prefs.edit().remove(KEY_PENDING_NOTE).commit()
        } catch (error: Throwable) {
            // The note has already been read by whoever clears it, and it is cleared
            // again on the next launch; showing it once more is better than losing it.
            Log.e(TAG, "Could not clear the update failure note", error)
        }
    }
}
