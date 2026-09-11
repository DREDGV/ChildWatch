package ru.example.childwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import ru.example.childwatch.designsystem.AvatarPresetCatalog
import ru.example.childwatch.R

data class FamilyAvatarPreset(val storageValue: String, @DrawableRes val drawableRes: Int)

/** Renders one stored avatar value consistently in every parent feature. */
object FamilyAvatarRenderer {
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

    fun bind(view: ImageView, avatarValue: String?, @DrawableRes fallbackRes: Int = R.drawable.avatar_family_mint) {
        view.imageTintList = null
        val value = avatarValue?.trim().orEmpty()
        AvatarPresetCatalog.createDrawable(view.context, value)?.let { view.setImageDrawable(it); return }
        preset(value)?.let { view.setImageResource(it.drawableRes); return }
        legacyPreset(value)?.let { view.setImageResource(it.drawableRes); return }
        if (value.isNotBlank()) {
            runCatching {
                view.setImageDrawable(null)
                view.setImageURI(Uri.parse(value))
                checkNotNull(view.drawable)
            }.onSuccess { return }
        }
        view.setImageResource(fallbackRes)
    }

    fun drawable(context: Context, avatarValue: String?, @DrawableRes fallbackRes: Int): Drawable? {
        val value = avatarValue?.trim().orEmpty()
        AvatarPresetCatalog.createDrawable(context, value)?.let { return it }
        preset(value)?.let { return ContextCompat.getDrawable(context, it.drawableRes) }
        legacyPreset(value)?.let { return ContextCompat.getDrawable(context, it.drawableRes) }
        if (value.isNotBlank()) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(value))?.use { stream ->
                    Drawable.createFromStream(stream, value)
                }
            }.getOrNull()?.let { return it }
        }
        return ContextCompat.getDrawable(context, fallbackRes)
    }

    fun isPreset(value: String?): Boolean = AvatarPresetCatalog.isPreset(value) || legacyPreset(value.orEmpty()) != null

    private fun preset(value: String): FamilyAvatarPreset? = presets.firstOrNull { it.storageValue == value }

    private fun legacyPreset(value: String): FamilyAvatarPreset? =
        legacyPresets.firstOrNull { it.storageValue == value }
}
