package ru.example.parentwatch.profile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.parentwatch.R

/**
 * Gets a chosen photograph into the profile and onto the server.
 *
 * Both screens that offer the profile editor — the home screen and settings —
 * need the same steps in the same order, and doing them separately is how two
 * copies drift apart. The steps are:
 *
 * 1. upload the picture and take the value the server returned, unchanged;
 * 2. remember that value in the profile on this phone;
 * 3. tell the family, otherwise the next directory refresh restores the old
 *    picture and the change looks like it did nothing.
 *
 * The upload runs while the editor is still open and a small progress window is
 * shown: a photograph over a slow connection takes long enough that a silent
 * screen would look like a frozen one.
 */
object AvatarPhotoSession {

    /**
     * Uploads [photo] and, once it is stored, saves and publishes the profile.
     *
     * @param previousAvatarKey retained for existing callers; removal happens in
     *        publish only after the family accepts the replacement
     * @param onUploaded called on the main thread with the value the server
     *        returned, so the screen can save the profile and close the editor
     * @param onFailed called on the main thread when the photograph could not be
     *        stored; the editor stays open so the person can retry
     */
    fun uploadChosenPhoto(
        context: Context,
        scope: CoroutineScope,
        photo: Uri,
        previousAvatarKey: String?,
        onUploaded: (avatarValue: String) -> Unit,
        onFailed: (messageRes: Int) -> Unit
    ) {
        val serverUrl = AvatarPhotoUpload.serverUrl(context)
        if (serverUrl.isNullOrBlank()) {
            onFailed(R.string.avatar_photo_no_server)
            return
        }

        val progress = AlertDialog.Builder(context)
            .setMessage(R.string.avatar_photo_uploading)
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AvatarPhotoUpload.upload(context, serverUrl, photo)
            }
            runCatching { progress.dismiss() }

            val avatarValue = result.avatarValue
            if (!result.isSuccess || avatarValue.isNullOrBlank()) {
                onFailed(messageRes(result.failure))
                return@launch
            }

            onUploaded(avatarValue)
        }
    }

    /**
     * Sends this device's name and picture to the family after the editor saved.
     *
     * Shared so that both screens report the same way, and so that a silent
     * failure — which once made an avatar change look like it did nothing — is
     * not possible.
     */
    fun publish(
        context: Context,
        scope: CoroutineScope,
        name: String,
        avatarKey: String?,
        previousAvatarKey: String?,
        onFinished: (published: Boolean) -> Unit
    ) {
        val replaced = previousAvatarKey?.trim().orEmpty()
        OwnProfilePublisher.publish(
            context = context,
            scope = scope,
            name = name,
            avatarKey = avatarKey
        ) { published ->
            Toast.makeText(
                context,
                context.getString(
                    if (published) {
                        OwnProfilePublisher.successMessageRes()
                    } else {
                        OwnProfilePublisher.failureMessageRes()
                    }
                ),
                if (published) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
            onFinished(published)
            if (published && replaced.startsWith("/avatars/") && replaced != avatarKey?.trim()) {
                removeUploadedPhoto(context, scope, replaced)
            }
        }
    }

    /**
     * Removes a photograph this device uploaded earlier.
     *
     * A failure is ignored on purpose: the profile has already been changed, and
     * a leftover file is not something the person can see or act on.
     */
    fun removeUploadedPhoto(context: Context, scope: CoroutineScope, avatarValue: String?) {
        val value = avatarValue?.trim().orEmpty()
        if (!value.startsWith("/avatars/")) return
        val serverUrl = AvatarPhotoUpload.serverUrl(context) ?: return

        scope.launch {
            withContext(Dispatchers.IO) {
                AvatarPhotoUpload.deleteUploadedPhoto(context, serverUrl, value)
            }
        }
    }

    /** The message for a refused photograph, taken from the string resources. */
    fun messageRes(failure: AvatarPhotoUpload.Failure?): Int = when (failure) {
        AvatarPhotoUpload.Failure.TOO_LARGE -> R.string.avatar_photo_too_large
        AvatarPhotoUpload.Failure.UNSUPPORTED_FORMAT -> R.string.avatar_photo_unsupported
        AvatarPhotoUpload.Failure.NO_SERVER -> R.string.avatar_photo_no_server
        AvatarPhotoUpload.Failure.UPLOAD_FAILED, null -> R.string.avatar_photo_failed
    }
}
