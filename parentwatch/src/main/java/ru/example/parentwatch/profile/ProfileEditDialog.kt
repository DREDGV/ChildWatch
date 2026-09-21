package ru.example.parentwatch.profile

import android.content.Context
import android.net.Uri
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import ru.example.parentwatch.R
import ru.example.parentwatch.utils.ChildDeviceProfile

/**
 * The editor for this device's own name and picture.
 *
 * It is a dialog on purpose and lives outside the settings screen: the profile is
 * something people change from the home screen, and routing them into a different
 * tab made the app feel like it lost their place. Settings still offers the same
 * dialog for the advanced fields.
 *
 * A person may use their own photograph instead of one of the built-in pictures.
 * The photograph itself is not stored in the dialog: choosing one here only
 * picks the file, and the screen that opened the dialog uploads it and keeps the
 * value the server returned. Everything a person can read is a string resource.
 */
object ProfileEditDialog {

    /**
     * What the person chose as a picture.
     *
     * A choice rather than a loose value: the two cases are handled very
     * differently — one is stored as it is, the other has to be uploaded first —
     * and a wrong type here would fail inside a click handler, where nobody is
     * looking.
     */
    sealed interface AvatarChoice {
        /** One of the built-in pictures, or none at all. */
        data class BuiltIn(val value: String?) : AvatarChoice

        /** A photograph from the phone, which still has to be uploaded. */
        data class Photo(val uri: Uri) : AvatarChoice
    }

    /**
     * Shows the editor.
     *
     * @param initial the profile being edited, or null to create one
     * @param currentAvatarKey avatar currently resolved for this device
     * @param canChoosePhoto whether this screen can upload a photograph; when
     *        false the section is not offered at all, so no button leads nowhere
     * @param onSave receives the chosen name and picture, together with a
     *        callback that closes the editor when the change has been stored —
     *        or, with `false`, hands the editor back so the person can try again
     */
    fun show(
        activity: ComponentActivity,
        initial: ChildDeviceProfile?,
        currentAvatarKey: String?,
        canChoosePhoto: Boolean = false,
        onSave: (name: String, choice: AvatarChoice, done: (stored: Boolean) -> Unit) -> Unit
    ) {
        val context: Context = activity
        val density = context.resources.displayMetrics.density

        val nameInput = android.widget.EditText(context).apply {
            hint = context.getString(R.string.profile_switch_name_hint)
            setText(initial?.name.orEmpty())
            setSingleLine()
            setSelection(text.length)
        }

        // A stored picture that is one of the six old colour-named values is shown as the offered
        // preset it is drawn from, so it reads as already chosen instead of as "no picture".
        var selectedAvatar = FamilyAvatarRenderer.selectedValueFor(currentAvatarKey)
            ?: currentAvatarKey?.takeIf { it.isNotBlank() }
        val initialAvatar = selectedAvatar

        // A photograph already in the profile is shown where it is, so switching
        // to a built-in picture is a choice and not an accident.
        var pendingPhoto: Uri? = null
        var photoSection: AvatarPhotoSection.Section? = null

        val photoPicker = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { picked ->
            if (picked == null) return@registerForActivityResult
            pendingPhoto = picked
            photoSection?.refresh(picked)
        }

        val avatarValues = FamilyAvatarRenderer.selectableValues()

        val avatarRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val avatarScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(avatarRow)
        }
        val avatarLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.profile_avatar_section_title)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }

        val avatarViews = mutableListOf<ShapeableImageView>()
        val size = (52 * density).toInt()
        val spacing = (8 * density).toInt()

        fun refreshAvatars() {
            val primary = ContextCompat.getColor(context, R.color.cw_color_primary)
            val outline = ContextCompat.getColor(context, R.color.cw_color_outline_variant)
            avatarValues.zip(avatarViews).forEach { (value, view) ->
                val selected = value == selectedAvatar
                view.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (selected) primary else outline
                )
                view.strokeWidth = (if (selected) 3f else 1f) * density
                view.alpha = if (selected) 1f else 0.7f
            }
        }

        avatarValues.forEachIndexed { index, value ->
            val view = ShapeableImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = spacing
                }
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(size / 2f)
                    .build()
                contentDescription = context.getString(
                    R.string.profile_avatar_preset_description,
                    index + 1
                )
                setOnClickListener {
                    // Choosing a built-in picture abandons a photograph that was
                    // picked but not saved; the profile keeps what it had.
                    pendingPhoto = null
                    selectedAvatar = value
                    refreshAvatars()
                    photoSection?.refresh(null)
                }
            }
            FamilyAvatarRenderer.bind(view, value)
            avatarViews += view
            avatarRow.addView(view)
        }
        refreshAvatars()

        val section = if (canChoosePhoto) {
            AvatarPhotoSection.create(
                context = context,
                storedAvatarValue = initialAvatar,
                onPick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemove = {
                    // Taking the photograph away returns the profile to a built-in
                    // picture, which the caller removes from the server on save.
                    pendingPhoto = null
                    selectedAvatar = null
                    refreshAvatars()
                    photoSection?.refresh(null)
                }
            ).also { photoSection = it }
        } else {
            null
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (8 * density).toInt(),
                (20 * density).toInt(),
                0
            )
            addView(nameInput)
            if (section != null) addView(section.view)
            addView(avatarLabel)
            addView(avatarScroll)
        }

        val created = AlertDialog.Builder(context)
            .setTitle(R.string.profile_switch_edit_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        created.setOnShowListener {
            created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        R.string.profile_switch_validation_name,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                // Confirm is disabled while the change is stored: a photograph
                // is uploaded first, and pressing twice would start a second
                // upload of the same picture.
                val confirm = created.getButton(AlertDialog.BUTTON_POSITIVE)
                confirm.isEnabled = false
                val photo = pendingPhoto
                val choice = if (photo != null) {
                    AvatarChoice.Photo(photo)
                } else {
                    AvatarChoice.BuiltIn(selectedAvatar)
                }
                onSave(name, choice) { stored ->
                    if (stored) {
                        created.dismiss()
                    } else {
                        // The change did not happen, so the editor stays open
                        // with the person's choices intact.
                        confirm.isEnabled = true
                    }
                }
            }
        }
        created.show()
    }

}
