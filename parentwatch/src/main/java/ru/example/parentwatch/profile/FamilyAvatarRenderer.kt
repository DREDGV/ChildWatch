package ru.example.parentwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import ru.example.childwatch.designsystem.AvatarPresetCatalog
import ru.example.childwatch.designsystem.LetterAvatarFactory
import ru.example.parentwatch.R

/**
 * Renders the same stored family avatar keys as ParentMonitor.
 *
 * Pictures come from the shared preset sheet in the design-system module, so
 * both applications show identical avatars. A stored value that is not a preset
 * is treated as a device-local image URI.
 */
object FamilyAvatarRenderer {

    /** A selectable avatar: the stored value and a stand-in for static previews. */
    data class FamilyAvatarPreset(val storageValue: String, @DrawableRes val placeholderRes: Int)

    /**
     * The avatars offered for choosing.
     *
     * These are the stored values themselves, so a picker built from this list
     * shows each avatar's own picture. Reading [presets] instead gives the stored
     * value with a stand-in image, which is why a picker built that way showed the
     * same picture several times.
     */
    fun selectableValues(): List<String> = presets.map { it.storageValue }

    /**
     * Every choice offered in the pickers. Kept identical to the server's
     * accepted list: a preset offered here but refused by the server would make
     * saving a profile fail.
     */
    val presets: List<FamilyAvatarPreset> = listOf(
        "corgi", "dinosaur", "robot", "cactus", "penguin", "astronaut", "donut",
        "cat", "pizza", "unicorn", "monster", "mug", "avocado", "panda", "rocket",
        "alien", "shark", "burger", "chick", "frog", "llama", "sloth", "controller",
        "pineapple", "cloud"
    ).map { name -> FamilyAvatarPreset("preset:$name", R.drawable.ic_brand_childdevice) }

    /**
     * Renders [avatarValue] when it is a known picture, otherwise a letter
     * avatar for [displayName].
     *
     * The letter fallback replaces the previous blank-silhouette/app-icon
     * fallback: a row of identical silhouettes told the user nothing, while a
     * coloured initial identifies the person and matches their other screens.
     */
    fun bind(
        view: ImageView,
        avatarValue: String?,
        displayName: String? = null,
        @DrawableRes fallbackRes: Int = R.drawable.ic_brand_childdevice
    ) {
        view.imageTintList = null
        val drawable = drawable(view.context, avatarValue)
            ?: LetterAvatarFactory.create(view.context, displayName)
        if (drawable != null) {
            view.setImageDrawable(drawable)
        } else {
            view.setImageResource(fallbackRes)
        }
    }

    /**
     * Drawable for a stored avatar value, or null when nothing can be rendered.
     * Used by rows that build their content programmatically, such as chat.
     */
    fun drawable(context: Context, avatarValue: String?): Drawable? {
        val value = avatarValue?.trim().orEmpty()
        if (value.isBlank()) return null
        AvatarPresetCatalog.createDrawable(context, value)?.let { return it }
        // Anything else is expected to be a device-local image URI.
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(value))?.use { stream ->
                Drawable.createFromStream(stream, value)
            }
        }.getOrNull()
    }

    fun isPreset(value: String?): Boolean {
        val normalized = value?.trim().orEmpty()
        return normalized.isNotBlank() && AvatarPresetCatalog.isPreset(normalized)
    }

    /**
     * The offered preset standing for a stored value, or null when there is no picture.
     *
     * A person whose picture is one of the six old colour-named values has to see that choice
     * highlighted in the picker, otherwise saving the dialog would move them to another picture
     * without their asking for it.
     */
    fun selectedValueFor(storedValue: String?): String? {
        val normalized = storedValue?.trim().orEmpty()
        return AvatarPresetCatalog.offeredPresetFor(normalized.takeIf { it.isNotBlank() })
    }
}
