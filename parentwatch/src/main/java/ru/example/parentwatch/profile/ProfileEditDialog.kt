package ru.example.parentwatch.profile

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
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
 */
object ProfileEditDialog {

    /**
     * Shows the editor.
     *
     * @param initial the profile being edited, or null to create one
     * @param currentAvatarKey avatar currently resolved for this device
     * @param onSave receives the chosen name and avatar; the caller persists them
     */
    fun show(
        activity: Activity,
        initial: ChildDeviceProfile?,
        currentAvatarKey: String?,
        onSave: (name: String, avatarKey: String?) -> Unit
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
                    selectedAvatar = value
                    refreshAvatars()
                }
            }
            FamilyAvatarRenderer.bind(view, value)
            avatarViews += view
            avatarRow.addView(view)
        }
        refreshAvatars()

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
            addView(avatarScroll)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_switch_edit_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

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
                onSave(name, selectedAvatar)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
