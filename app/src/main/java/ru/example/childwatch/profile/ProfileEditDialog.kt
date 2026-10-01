package ru.example.childwatch.profile

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.example.childwatch.designsystem.FamilyProfileEditor

data class ProfileEditResult(val name: String, val avatarKey: String?, val photoUri: Uri? = null)

/** Upload and publication are deferred until Save; cancellation never changes the profile. */
object ProfileEditDialog {
    suspend fun show(
        activity: ComponentActivity,
        initialName: String?,
        currentAvatarKey: String?,
        photoSession: ProfilePhotoSession? = null,
        draftScope: String,
        onProfileChanged: suspend (ProfileEditResult) -> Boolean
    ) {
        if (FamilyProfileEditor.isOpen(activity)) return
        val closed = CompletableDeferred<Unit>()
        var uploadedUri: Uri? = null
        var uploadedValue: String? = null
        val originalAvatar = ru.example.childwatch.designsystem.AvatarPresetCatalog.offeredPresetFor(currentAvatarKey) ?: currentAvatarKey
        val dialog = FamilyProfileEditor.show(
            activity, initialName, originalAvatar, FamilyAvatarRenderer.selectableValues(),
            { image, key, name -> FamilyAvatarRenderer.bind(image, key, name) },
            if (photoSession == null) null else FamilyProfileEditor.Picker { result ->
                activity.lifecycleScope.launch { result.picked(ProfileImagePicker.pick(activity)) }
            },
            { name, avatar, photo, done ->
                activity.lifecycleScope.launch {
                    try {
                        val saved = withTimeoutOrNull(45_000L) {
                            val value = if (photo != null) {
                                if (uploadedUri != photo || uploadedValue == null) {
                                    when (val upload = photoSession?.upload(photo)) {
                                        is ProfilePhotoResult.Stored -> {
                                            uploadedUri = photo
                                            uploadedValue = upload.avatarValue
                                        }
                                        else -> return@withTimeoutOrNull false
                                    }
                                }
                                uploadedValue
                            } else avatar
                            onProfileChanged(ProfileEditResult(name, value))
                        } ?: false
                        done.complete(saved)
                        // Remote cleanup is secondary: an accepted profile must close immediately.
                        if (saved && currentAvatarKey != (if (photo != null) uploadedValue else avatar))
                            photoSession?.deleteReplacedPicture(currentAvatarKey)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("ProfileEditDialog", "Profile save failed", error)
                        done.complete(false)
                    }
                }
            },
            { closed.complete(Unit) }, draftScope
        )
        try { closed.await() } finally { dialog.dismiss() }
    }
}
