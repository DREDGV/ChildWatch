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

    private const val TAG = "ProfileImagePicker"

    /** Launcher registered by the host activity, or null when none was registered. */
    @Volatile
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    /** The one call currently waiting for the chooser, resumed when it answers. */
    @Volatile
    private var pending: CompletableDeferred<Uri?>? = null

    /**
     * Provides the host activity's launcher.
     *
     * Called once the activity can register result launchers. When the editor is
     * opened from a screen that has not registered one the call simply finds no
     * picture, and the editor keeps working with the built-in avatars.
     */
    fun attach(launcher: ActivityResultLauncher<PickVisualMediaRequest>?) {
        this.launcher = launcher
    }

    /**
     * Registers a photo picker launcher on the host activity and wires it here.
     *
     * The activity must call this where it registers its other result launchers,
     * because a launcher has to be taken before the activity is started. The
     * returned launcher is the activity's own and is not used by the editor, which
     * asks for a picture through [pick] instead.
     */
    fun registerLauncher(
        caller: ActivityResultCaller
    ): ActivityResultLauncher<PickVisualMediaRequest> {
        return caller.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            pending?.complete(uri)
        }.also(::attach)
    }

    /** Opens the chooser and answers with the chosen picture, or null when dismissed. */
    suspend fun pick(): Uri? {
        val registered = launcher ?: return null
        // A second request would leave the first one waiting forever.
        pending?.complete(null)
        val answer = CompletableDeferred<Uri?>()
        pending = answer
        return try {
            registered.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            answer.await()
        } catch (error: Exception) {
            // A chooser that cannot open must not leave the editor stuck.
            android.util.Log.w(TAG, "The picture chooser could not be opened", error)
            null
        } finally {
            if (pending === answer) pending = null
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
