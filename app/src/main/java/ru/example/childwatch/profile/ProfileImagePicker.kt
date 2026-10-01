package ru.example.childwatch.profile

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Why a chosen picture could not be stored, so the editor can say which it was. */
enum class ProfilePhotoFailure {
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    UNREADABLE,
    REJECTED
}

/**
 * The answer to offering a picture to the server.
 *
 * [Stored] carries the value the server returned — a path such as
 * `/avatars/ab12….jpg` — which is what the profile keeps; the picture is only
 * shown from this phone while it is on its way.
 */
sealed interface ProfilePhotoResult {
    data class Stored(val avatarValue: String) : ProfilePhotoResult
    data class Failed(val reason: ProfilePhotoFailure) : ProfilePhotoResult
}

/**
 * Opens the phone's picture chooser for the profile editor.
 *
 * The chooser is the system photo picker, which needs no permission to read the
 * person's pictures on any supported Android version and shows only the images
 * they decide to share. It is launched by the host activity — only an activity
 * may register a result launcher — and awaited here, so the editor reads like a
 * straight line of code instead of a callback chain.
 */
object ProfileImagePicker {
    private class PickerState {
        lateinit var launcher: ActivityResultLauncher<PickVisualMediaRequest>
        var pending: CompletableDeferred<Uri?>? = null
    }
    private val states = mutableMapOf<ComponentActivity, PickerState>()

    /** Register on the host before STARTED. Each screen owns its own request/result. */
    fun registerLauncher(caller: ComponentActivity): ActivityResultLauncher<PickVisualMediaRequest> {
        val state = PickerState()
        state.launcher = caller.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            state.pending?.complete(uri)
        }
        states[caller] = state
        caller.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                state.pending?.complete(null)
                if (states[caller] === state) states.remove(caller)
            }
        })
        return state.launcher
    }

    suspend fun pick(caller: ComponentActivity): Uri? {
        val state = states[caller] ?: return null
        state.pending?.complete(null)
        val answer = CompletableDeferred<Uri?>()
        state.pending = answer
        return try {
            state.launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            answer.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("ProfileImagePicker", "Cannot open photo picker", error)
            null
        } finally {
            if (state.pending === answer) state.pending = null
        }
    }
}

/**
 * Sends a chosen picture to the server and remembers what it answered.
 *
 * The stored value is remembered, because the editor publishes it when the person
 * saves and the file must not be sent a second time for that. Removing a replaced
 * picture is deliberately not waited for: a file nobody shows any more matters far
 * less than the profile change that replaced it.
 */
class ProfilePhotoSession(
    private val context: Context,
    private val host: ComponentActivity
) {

    private val uploader by lazy { ProfilePhotoUploader(context) }

    /** The value the server gave for the currently displayed picture, if any. */
    @Volatile
    private var storedValue: String? = null

    /** Sends [uri] and answers as the editor should show the picture afterwards. */
    suspend fun upload(uri: Uri): ProfilePhotoResult {
        return when (val result = uploader.upload(uri)) {
            is ProfilePhotoUpload.Stored -> {
                storedValue = result.avatarValue
                ProfilePhotoResult.Stored(result.avatarValue)
            }

            ProfilePhotoUpload.FileTooLarge ->
                ProfilePhotoResult.Failed(ProfilePhotoFailure.FILE_TOO_LARGE)

            ProfilePhotoUpload.UnsupportedFormat ->
                ProfilePhotoResult.Failed(ProfilePhotoFailure.UNSUPPORTED_FORMAT)

            ProfilePhotoUpload.Unreadable ->
                ProfilePhotoResult.Failed(ProfilePhotoFailure.UNREADABLE)

            ProfilePhotoUpload.Rejected ->
                ProfilePhotoResult.Failed(ProfilePhotoFailure.REJECTED)
        }
    }

    /** The stored value of the picture currently shown, ready to be saved. */
    fun valueToStore(): String? = storedValue

    /** Forgets the stored value, for a person who went back to a built-in avatar. */
    fun forgetStoredValue() {
        storedValue = null
    }

    /**
     * Removes a picture this device uploaded earlier, after the profile has moved
     * on. Safe to call with anything: only a value this server stored is touched,
     * and a failure is only logged.
     */
    fun deleteReplacedPicture(previousValue: String?) {
        val path = previousValue?.trim().orEmpty()
        if (!path.startsWith(UPLOADED_AVATAR_PREFIX)) return
        host.lifecycleScope.launch {
            runCatching { uploader.remove(path) }
                .onFailure { android.util.Log.w(TAG, "The replaced picture was left on the server") }
        }
    }

    companion object {
        private const val TAG = "ProfilePhotoSession"

        /** Every uploaded picture lives here, which is what the server also accepts. */
        private const val UPLOADED_AVATAR_PREFIX = "/avatars/"
    }
}
