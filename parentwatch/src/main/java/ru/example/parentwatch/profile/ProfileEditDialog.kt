package ru.example.parentwatch.profile

import android.net.Uri
import androidx.activity.ComponentActivity
import ru.example.childwatch.designsystem.FamilyProfileEditor
import ru.example.parentwatch.utils.ChildDeviceProfile

/** Uses the same layout, selection and crop flow as ParentMonitor. */
object ProfileEditDialog {
    sealed interface AvatarChoice {
        data class BuiltIn(val value: String?) : AvatarChoice
        data class Photo(val uri: Uri) : AvatarChoice
    }

    fun show(
        activity: ComponentActivity,
        initial: ChildDeviceProfile?,
        currentAvatarKey: String?,
        canChoosePhoto: Boolean = false,
        requestPhoto: ((onPicked: (Uri?) -> Unit) -> Unit)? = null,
        draftScope: String,
        onSave: (name: String, choice: AvatarChoice, done: (stored: Boolean) -> Unit) -> Unit
    ) {
        if (FamilyProfileEditor.isOpen(activity)) return
        FamilyProfileEditor.show(
            activity, initial?.name, FamilyAvatarRenderer.selectedValueFor(currentAvatarKey) ?: currentAvatarKey,
            FamilyAvatarRenderer.selectableValues(),
            { image, key, name -> FamilyAvatarRenderer.bind(image, key, name) },
            if (!canChoosePhoto || requestPhoto == null) null else FamilyProfileEditor.Picker { result ->
                requestPhoto { uri -> result.picked(uri) }
            },
            { name, avatar, photo, done ->
                val choice = if (photo == null) AvatarChoice.BuiltIn(avatar) else AvatarChoice.Photo(photo)
                onSave(name, choice) { stored -> done.complete(stored) }
            },
            {}, draftScope
        )
    }
}
