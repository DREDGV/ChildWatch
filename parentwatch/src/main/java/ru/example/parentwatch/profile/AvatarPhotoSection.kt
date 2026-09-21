package ru.example.parentwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import ru.example.parentwatch.R

/**
 * The part of a profile editor that offers a photograph from the phone.
 *
 * Two screens edit a profile — the home screen dialog and the settings screen —
 * and both have to offer the same thing in the same shape, so the section is
 * built once here rather than twice. Choosing the picture belongs to the screen,
 * which owns the result launcher; this shows the choice and reports what the
 * person did.
 *
 * Picking uses the system photo picker, which needs no storage permission on any
 * supported version — on Android 14 the storage permissions are no longer
 * available for this purpose at all.
 */
object AvatarPhotoSection {

    /**
     * The section on the screen.
     *
     * It is returned so a caller can draw the current choice after a pick, and
     * read back what the profile already holds.
     */
    class Section internal constructor(
        val view: LinearLayout,
        private val preview: ShapeableImageView,
        private val pickButton: TextView,
        private val removeButton: TextView
    ) {
        /** The picture already in the profile: a path on the server, or null. */
        var storedAvatarValue: String? = null

        /** Draws the current choice: a photograph just picked, a stored one, or none. */
        fun refresh(pickedPhoto: Uri?) {
            val context = view.context
            if (pickedPhoto != null) {
                runCatching { preview.setImageURI(pickedPhoto) }
                pickButton.text = context.getString(R.string.avatar_photo_replace)
                removeButton.visibility = View.VISIBLE
                return
            }

            if (AvatarImageLoader.isUploadedPicture(storedAvatarValue)) {
                FamilyAvatarRenderer.bind(preview, storedAvatarValue)
                pickButton.text = context.getString(R.string.avatar_photo_replace)
                removeButton.visibility = View.GONE
            } else {
                preview.setImageDrawable(null as Drawable?)
                pickButton.text = context.getString(R.string.avatar_photo_pick)
                removeButton.visibility = View.GONE
            }
        }
    }

    /**
     * Builds the section.
     *
     * @param storedAvatarValue the picture already in the profile, so it is shown
     *        where it is and replacing it is a decision rather than an accident
     * @param onPick called when the person asks to choose a photograph
     * @param onRemove called when the person puts the photograph away and goes
     *        back to a built-in picture
     */
    fun create(
        context: Context,
        storedAvatarValue: String?,
        onPick: () -> Unit,
        onRemove: () -> Unit
    ): Section {
        val density = context.resources.displayMetrics.density
        val size = (56 * density).toInt()

        val preview = ShapeableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(size / 2f)
                .build()
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = context.getString(R.string.avatar_photo_preview_description)
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.avatar_photo_section_title)
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.avatar_photo_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.cw_color_on_surface_variant))
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val pickButton = TextView(context).apply {
            text = context.getString(R.string.avatar_photo_pick)
            setTextColor(ContextCompat.getColor(context, R.color.cw_color_primary))
            setPadding(0, (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            isClickable = true
            setOnClickListener { onPick() }
        }
        val removeButton = TextView(context).apply {
            text = context.getString(R.string.avatar_photo_remove)
            setTextColor(ContextCompat.getColor(context, R.color.cw_color_primary))
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            isClickable = true
            visibility = View.GONE
            setOnClickListener { onRemove() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pickButton)
            addView(removeButton)
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), 0, 0, 0)
                    addView(title)
                    addView(hint)
                    addView(buttons)
                }
            )
        }

        return Section(view, preview, pickButton, removeButton).apply {
            this.storedAvatarValue = storedAvatarValue
            refresh(null)
        }
    }
}
