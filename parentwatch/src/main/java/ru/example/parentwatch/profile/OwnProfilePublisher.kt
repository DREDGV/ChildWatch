package ru.example.parentwatch.profile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.session.ChildFamilyOnboardingStore
import ru.example.parentwatch.session.ChildParticipantNameResolver

/**
 * Writes this device's own name and picture to the family on the server.
 *
 * The value also lives in the family, not only on the phone, so a change has to
 * be published; otherwise the next family directory refresh restores the old
 * picture and the change looks like it did nothing. It is a separate object
 * because both the home screen and the settings screen offer the editor, and the
 * first version only published from settings.
 *
 * The member id comes from the family directory: the id saved during onboarding
 * can point at a different member record, which the server refuses to update.
 */
object OwnProfilePublisher {

    private const val TAG = "OwnProfilePublisher"

    /**
     * Sends the change and then refreshes the cached directory.
     *
     * @param onFinished called on the main thread with true when the server kept
     *        the change, false when it did not
     */
    fun publish(
        context: Context,
        scope: CoroutineScope,
        name: String?,
        avatarKey: String?,
        onFinished: (Boolean) -> Unit
    ) {
        val onboarding = ChildFamilyOnboardingStore(context)
        val resolver = ChildParticipantNameResolver(context)
        val familyId = onboarding.familyId()
        val memberId = resolver.resolveOwnMemberId() ?: onboarding.memberId()

        if (familyId.isBlank() || memberId.isBlank()) {
            Log.w(TAG, "Not published: family id (${familyId.isBlank()}) or member id (${memberId.isBlank()}) is unknown")
            onFinished(false)
            return
        }

        val displayName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolver.resolveChildDisplayName()

        scope.launch {
            val response = runCatching {
                NetworkClient(context).updateOwnFamilyProfile(
                    familyId = familyId,
                    memberId = memberId,
                    displayName = displayName,
                    avatarKey = avatarKey
                )
            }.getOrNull()

            if (response?.isSuccessful == true) {
                // Refresh the cache so the new values survive synchronisation.
                runCatching { resolver.refreshCanonicalDirectory(force = true) }
                    .onFailure { Log.w(TAG, "Directory refresh after publish failed", it) }
                onFinished(true)
            } else {
                Log.w(
                    TAG,
                    "Publish failed: http=${response?.code()} " +
                        "body=${runCatching { response?.errorBody()?.string()?.take(160) }.getOrNull()}"
                )
                onFinished(false)
            }
        }
    }

    /** Message shown when the server did not keep the change. */
    fun failureMessageRes(): Int = R.string.profile_publish_failed

    /** Message shown when the device is not in a family yet. */
    fun unavailableMessageRes(): Int = R.string.profile_publish_unavailable

    /** Message shown when the server kept the change. */
    fun successMessageRes(): Int = R.string.profile_published
}
