package ru.example.childwatch.profile

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import ru.example.childwatch.R

/**
 * One person's edited profile, as the editor hands it over.
 *
 * A picture the person uploaded is carried by [avatarKey] as the path the server
 * returned, so the caller has one value to store whatever the person chose. The
 * local [photoUri] is only for showing it here, and is never sent anywhere: the
 * caller must not store a picture that exists on one phone alone.
 */
data class ProfileEditResult(
    val name: String,
    val avatarKey: String?,
    val photoUri: android.net.Uri? = null
)

/**
 * The editor for this device's own name and picture.
 *
 * It shows only what a person actually changes — the name and the picture. The
 * address of the server and the device identifiers used to fill this dialog as
 * well, which is noise for anyone who is not diagnosing a connection, so they are
 * no longer part of it.
 *
 * The change is published to the family by the caller: the name and picture live
 * on the server too, so saving only on the phone would be undone by the next
 * synchronisation.
 */
object ProfileEditDialog {

    /**
     * Shows the editor and waits until it is closed.
     *
     * It is a suspend function because the editor itself performs the upload of a
     * chosen picture: the picture has to be on the server before the caller can
     * store it in the profile. The dialog is closed only when the person saves or
     * cancels, so a picture still being sent is never abandoned half way.
     *
     * @param initialName the name to start from, empty to create a new one
     * @param currentAvatarKey avatar currently resolved for this device, if any
     * @param photoSession sends a picture chosen from this phone; when it is absent
     *        the editor offers only the built-in avatars
     * @param onProfileChanged receives the name and the picture to store
     */
    suspend fun show(
        activity: ComponentActivity,
        initialName: String?,
        currentAvatarKey: String?,
        photoSession: ProfilePhotoSession? = null,
        onProfileChanged: (ProfileEditResult) -> Unit
    ) {
        val context: Context = activity
        val density = context.resources.displayMetrics.density

        val nameInput = android.widget.EditText(context).apply {
            hint = context.getString(R.string.profile_switch_name_hint)
            setText(initialName.orEmpty())
            setSingleLine()
            setSelection(text.length)
        }

        // A picture may already be stored on the server; it is selected from the
        // start, otherwise saving would replace the person's own photograph with
        // whichever preset happened to look selected.
        var selectedAvatar = currentAvatarKey?.trim()
            ?.takeIf { it.isNotBlank() && it != PENDING_PHOTO_VALUE }
        // A picture being sent to the server is displayed from this phone, because
        // it is not on the server yet.
        var pendingPhotoUri: android.net.Uri? = null
        var isUploadingPhoto = false

        /** The value the profile held before the picture was replaced. */
        val replacedAvatar = selectedAvatar?.takeIf { FamilyAvatarRenderer.isUploadedValue(it) }

        // The tile that opens the phone's picture chooser and shows the result.
        val photoPreview = ShapeableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(PHOTO_PREVIEW_SIZE_DP, PHOTO_PREVIEW_SIZE_DP)
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(PHOTO_PREVIEW_SIZE_DP / 2f)
                .build()
            contentDescription = context.getString(R.string.profile_avatar_photo_button)
        }
        val photoLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.profile_avatar_photo_button)
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        val photoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
            addView(photoPreview)
            addView(photoLabel)
        }

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

        val avatarValues = FamilyAvatarRenderer.selectableValues()
        val avatarViews = mutableListOf<ShapeableImageView>()
        val size = (52 * density).toInt()
        val spacing = (8 * density).toInt()

        /** Marks exactly one choice, whichever it is. */
        fun markSelected(selected: Boolean, view: ShapeableImageView, selectedWidth: Float = 3f) {
            val outline = ContextCompat.getColor(context, R.color.cw_color_outline_variant)
            view.strokeColor = ColorStateList.valueOf(
                if (selected) ContextCompat.getColor(context, R.color.cw_color_primary) else outline
            )
            view.strokeWidth = (if (selected) selectedWidth else 1f) * density
            view.alpha = if (selected) 1f else 0.7f
        }

        /** Shows the photograph tile: the stored picture, the chosen one, or the button glyph. */
        fun renderPhotoTile() {
            when {
                pendingPhotoUri != null ->
                    FamilyAvatarRenderer.bind(photoPreview, pendingPhotoUri.toString())

                FamilyAvatarRenderer.isUploadedValue(selectedAvatar) ->
                    FamilyAvatarRenderer.bind(photoPreview, selectedAvatar)

                // Nothing chosen yet: the outline itself is the invitation.
                else -> photoPreview.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.cw_ic_avatar_photo)
                )
            }
        }

        /**
         * Draws the whole choice: the photograph tile and every preset.
         *
         * Called after every change rather than after a choice, because going back
         * from a photograph to a built-in avatar has to clear the photograph too.
         */
        fun refreshAvatars() {
            val photoSelected = selectedAvatar == PENDING_PHOTO_VALUE ||
                FamilyAvatarRenderer.isUploadedValue(selectedAvatar)
            markSelected(photoSelected, photoPreview, selectedWidth = PHOTO_STROKE_DP)
            // A picture on its way to the server is dimmed, which is the only
            // progress the person needs for an upload they can see happening. The
            // label follows the tile, and both come back to full strength after.
            photoLabel.alpha = photoPreview.alpha
            if (isUploadingPhoto) {
                photoPreview.alpha = UPLOADING_ALPHA
                photoLabel.alpha = UPLOADING_ALPHA
            }
            avatarValues.zip(avatarViews).forEach { (value, view) ->
                markSelected(value == selectedAvatar, view)
            }
        }

        fun describeFailure(reason: ProfilePhotoFailure): String = context.getString(
            when (reason) {
                ProfilePhotoFailure.FILE_TOO_LARGE -> R.string.profile_avatar_photo_too_large
                ProfilePhotoFailure.UNSUPPORTED_FORMAT -> R.string.profile_avatar_photo_unsupported
                ProfilePhotoFailure.UNREADABLE -> R.string.profile_avatar_photo_unreadable
                ProfilePhotoFailure.REJECTED -> R.string.profile_avatar_photo_failed
            }
        )

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
                    // Back to a built-in avatar: the photograph on this phone is no
                    // longer the choice, so it must not be shown or published.
                    pendingPhotoUri = null
                    selectedAvatar = value
                    refreshAvatars()
                    renderPhotoTile()
                }
            }
            // The real artwork comes from the shared sheet; binding the preset list
            // directly would show six repeated placeholder images.
            FamilyAvatarRenderer.bind(view, value)
            avatarViews += view
            avatarRow.addView(view)
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
            addView(avatarLabel)
            addView(photoRow)
            addView(avatarScroll)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_switch_edit_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        /**
         * Completed once the dialog is gone.
         *
         * The chooser is opened by an activity the person can leave at any moment,
         * so the picture is only drawn again while the editor is still on screen.
         */
        val closed = CompletableDeferred<Unit>()
        var isOpen = true

        // Opens the phone's picture chooser. The launcher belongs to the host
        // activity; when the activity registered none this does nothing, and the
        // editor keeps working with the built-in avatars alone.
        photoRow.setOnClickListener {
            val session = photoSession ?: return@setOnClickListener
            if (isUploadingPhoto) return@setOnClickListener
            it.isEnabled = false
            pendingPhotoUri = null
            isUploadingPhoto = true
            refreshAvatars()
            activity.lifecycleScope.launch {
                try {
                    val picked = ProfileImagePicker.pick()
                    // The person may have left the editor while the chooser was open;
                    // a picture for a dialog that is gone is not worth sending.
                    if (picked == null || !isOpen) return@launch
                    // Shown at once from this phone, so the person sees their own
                    // choice without waiting for the server.
                    pendingPhotoUri = picked
                    selectedAvatar = PENDING_PHOTO_VALUE
                    renderPhotoTile()
                    refreshAvatars()

                    when (val result = session.upload(picked)) {
                        is ProfilePhotoResult.Stored -> {
                            // The value the server returned is what the profile keeps,
                            // and the tile draws it from there.
                            selectedAvatar = result.avatarValue
                            pendingPhotoUri = null
                            refreshAvatars()
                            renderPhotoTile()
                            // Kept in the profile straight away rather than on save:
                            // the picture is already on the server, and leaving it
                            // unpublished would show the photograph on this phone only.
                            if (isOpen) {
                                onProfileChanged(
                                    ProfileEditResult(
                                        name = nameInput.text?.toString()?.trim().orEmpty(),
                                        avatarKey = result.avatarValue
                                    )
                                )
                            }
                        }

                        is ProfilePhotoResult.Failed -> {
                            // The choice never reached the family, so the editor goes
                            // back to what the profile actually holds instead of
                            // showing a picture that lives on this phone alone.
                            pendingPhotoUri = null
                            selectedAvatar = replacedAvatar
                            refreshAvatars()
                            renderPhotoTile()
                            if (isOpen) {
                                android.widget.Toast.makeText(
                                    context,
                                    describeFailure(result.reason),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } finally {
                    isUploadingPhoto = false
                    it.isEnabled = true
                }
            }
        }

        refreshAvatars()
        renderPhotoTile()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        R.string.profile_switch_validation_name,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (isUploadingPhoto) {
                    // Saving now would publish the previous picture and then the new
                    // one, so the person is asked to wait for the upload instead.
                    android.widget.Toast.makeText(
                        context,
                        R.string.profile_avatar_photo_uploading,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val value = selectedAvatar?.takeIf { it != PENDING_PHOTO_VALUE }
                // A picture replaced outright is removed once the profile has moved
                // on; the profile change matters more than tidying the file.
                if (replacedAvatar != null && replacedAvatar != value) {
                    photoSession?.deleteReplacedPicture(replacedAvatar)
                }
                onProfileChanged(
                    ProfileEditResult(name = name, avatarKey = value, photoUri = pendingPhotoUri)
                )
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            isOpen = false
            closed.complete(Unit)
        }
        dialog.show()

        // The caller stores the profile when the editor closes, so this waits for it
        // rather than returning while the person is still choosing.
        closed.await()
    }

    /**
     * Says that the choice is a photograph rather than a preset, while the picture
     * is being sent. The value that is finally stored is always the one the server
     * returned; this never reaches a profile.
     */
    private const val PENDING_PHOTO_VALUE = "photo-pending"

    private const val PHOTO_PREVIEW_SIZE_DP = 52
    private const val PHOTO_STROKE_DP = 1.5f
    private const val UPLOADING_ALPHA = 0.5f
}
