package ru.example.childwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import ru.example.childwatch.designsystem.AvatarPresetCatalog
import ru.example.childwatch.designsystem.LetterAvatarFactory
import ru.example.childwatch.R

data class FamilyAvatarPreset(val storageValue: String, @DrawableRes val drawableRes: Int)

/** Renders one stored avatar value consistently in every parent feature. */
object FamilyAvatarRenderer {
    /**
     * The avatars offered for choosing, in the order they are shown.
     *
     * Held as plain values rather than as drawable resources: the artwork comes
     * from the shared sheet through [drawable]. An earlier version associated each
     * value with one of six legacy images, so a picker built from that list showed
     * six repeated pictures instead of the twenty-five actual avatars.
     */
    fun selectableValues(): List<String> = AvatarPresetCatalog.keys()

    private val legacyPresets = listOf(
        FamilyAvatarPreset("preset:sky", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:mint", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:sun", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:coral", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:lilac", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:ocean", R.drawable.avatar_family_ocean)
    )

    val presets = listOf(
        FamilyAvatarPreset("preset:corgi", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:dinosaur", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:robot", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:cactus", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:penguin", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:astronaut", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:donut", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:cat", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:pizza", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:unicorn", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:monster", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:mug", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:avocado", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:panda", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:rocket", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:alien", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:shark", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:burger", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:chick", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:frog", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:llama", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:sloth", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:controller", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:pineapple", R.drawable.avatar_family_ocean),
        FamilyAvatarPreset("preset:cloud", R.drawable.avatar_family_sky)
    )

    /**
     * Renders [avatarValue] when it resolves to a picture, otherwise a letter
     * avatar for [displayName].
     *
     * The letter fallback replaces the previous behaviour of drawing one of the
     * legacy preset images (or a blank silhouette) for every person, which is
     * why chat rows looked like a row of identical figures.
     */
    fun bind(
        view: ImageView,
        avatarValue: String?,
        displayName: String? = null,
        @DrawableRes fallbackRes: Int = R.drawable.avatar_family_mint
    ) {
        view.imageTintList = null
        drawable(view.context, avatarValue)?.let { view.setImageDrawable(it); return }
        view.setImageDrawable(LetterAvatarFactory.create(view.context, displayName))
    }

    /** Preset picture for a stored value, or null when the value is not one. */
    fun drawable(context: Context, avatarValue: String?): Drawable? {
        val value = avatarValue?.trim().orEmpty()
        if (value.isBlank()) return null
        AvatarPresetCatalog.createDrawable(context, value)?.let { return it }
        legacyPreset(value)?.let { return ContextCompat.getDrawable(context, it.drawableRes) }
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(value))?.use { stream ->
                Drawable.createFromStream(stream, value)
            }
        }.getOrNull()
    }

    fun isPreset(value: String?): Boolean = AvatarPresetCatalog.isPreset(value) || legacyPreset(value.orEmpty()) != null

    private fun preset(value: String): FamilyAvatarPreset? = presets.firstOrNull { it.storageValue == value }

    private fun legacyPreset(value: String): FamilyAvatarPreset? =
        legacyPresets.firstOrNull { it.storageValue == value }
}
