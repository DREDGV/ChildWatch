package ru.example.parentwatch.profile

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.utils.ChildDeviceProfileManager

/** Publish first; only then commit the same person's local profile and remove replaced media. */
object OwnProfileEditor {
    private val opening = java.util.WeakHashMap<ComponentActivity, Boolean>()
    fun show(activity: ComponentActivity, requestPhoto: ((Uri?) -> Unit) -> Unit, onStored: () -> Unit) {
        if (opening[activity] == true || ru.example.childwatch.designsystem.FamilyProfileEditor.isOpen(activity)) return
        val profiles = ChildDeviceProfileManager(activity)
        val resolver = ChildParticipantNameResolver(activity)
        val profile = profiles.getActiveProfile()
        if (profile == null) {
            android.widget.Toast.makeText(activity, ru.example.parentwatch.R.string.profile_switch_no_active, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val serverUrl = profiles.resolveCurrentServerUrl()
        val familyId = ru.example.parentwatch.session.ChildFamilyOnboardingStore(activity).familyId()
        opening[activity] = true
        activity.lifecycleScope.launch {
            try {
                try { resolver.refreshCanonicalDirectory(force = true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { android.util.Log.w("OwnProfileEditor", "Using cached own profile", error) }
                if (activity.isDestroyed || activity.isFinishing) return@launch
                val name = resolver.resolveChildDisplayName()
                val originalAvatar = resolver.resolveChildAvatarKey()
                var uploadedUri: Uri? = null
                var uploadedAvatar: String? = null
                ProfileEditDialog.show(
                    activity, profile.copy(name = name), originalAvatar, true, requestPhoto,
                    org.json.JSONArray(listOf(serverUrl, familyId, profile.ownChildDeviceId)).toString()
                ) { displayName, choice, done ->
                    activity.lifecycleScope.launch save@ {
                        try {
                            if (profiles.getActiveProfileId() != profile.id || profiles.resolveCurrentChildId() != profile.ownChildDeviceId ||
                                profiles.resolveCurrentServerUrl() != serverUrl ||
                                ru.example.parentwatch.session.ChildFamilyOnboardingStore(activity).familyId() != familyId) {
                                done(false)
                                return@save
                            }
                            val avatar = when (choice) {
                                is ProfileEditDialog.AvatarChoice.BuiltIn -> choice.value
                                is ProfileEditDialog.AvatarChoice.Photo -> {
                                    if (uploadedUri != choice.uri || uploadedAvatar == null) {
                                        val server = AvatarPhotoUpload.serverUrl(activity)
                                        if (server.isNullOrBlank()) { done(false); return@save }
                                        val uploaded = withContext(Dispatchers.IO) {
                                            AvatarPhotoUpload.upload(activity, server, choice.uri)
                                        }
                                        if (!uploaded.isSuccess) { done(false); return@save }
                                        uploadedUri = choice.uri
                                        uploadedAvatar = uploaded.avatarValue
                                    }
                                    uploadedAvatar
                                }
                            }
                            if (profiles.resolveCurrentChildId() != profile.ownChildDeviceId ||
                                profiles.resolveCurrentServerUrl() != serverUrl ||
                                ru.example.parentwatch.session.ChildFamilyOnboardingStore(activity).familyId() != familyId) {
                                done(false)
                                return@save
                            }
                            // Keep the previous profile until the family accepts both fields.
                            AvatarPhotoSession.publish(activity, activity.lifecycleScope, displayName, avatar, originalAvatar) { published ->
                                try {
                                    if (published) {
                                        profiles.saveProfile(profile.copy(name = displayName, avatarKey = avatar, updatedAt = System.currentTimeMillis()))
                                        onStored()
                                    }
                                    done(published)
                                } catch (error: Exception) {
                                    android.util.Log.w("OwnProfileEditor", "Local profile update failed", error)
                                    done(false)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            android.util.Log.w("OwnProfileEditor", "Profile save failed", error)
                            done(false)
                        }
                    }
                }
            } finally { opening.remove(activity) }
        }
    }
}
