package ru.example.childwatch.profile

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import ru.example.childwatch.R

data class FamilyAvatarPreset(val storageValue: String, @DrawableRes val drawableRes: Int)

/** Renders one stored avatar value consistently in every parent feature. */
object FamilyAvatarRenderer {
    val presets = listOf(
        FamilyAvatarPreset("preset:sky", R.drawable.avatar_family_sky),
        FamilyAvatarPreset("preset:mint", R.drawable.avatar_family_mint),
        FamilyAvatarPreset("preset:sun", R.drawable.avatar_family_sun),
        FamilyAvatarPreset("preset:coral", R.drawable.avatar_family_coral),
        FamilyAvatarPreset("preset:lilac", R.drawable.avatar_family_lilac),
        FamilyAvatarPreset("preset:ocean", R.drawable.avatar_family_ocean)
    )

    fun bind(view: ImageView, avatarValue: String?, @DrawableRes fallbackRes: Int = R.drawable.avatar_family_mint) {
        view.imageTintList = null
        val value = avatarValue?.trim().orEmpty()
        preset(value)?.let { view.setImageResource(it.drawableRes); return }
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
        preset(value)?.let { return ContextCompat.getDrawable(context, it.drawableRes) }
        if (value.isNotBlank()) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(value))?.use { stream ->
                    Drawable.createFromStream(stream, value)
                }
            }.getOrNull()?.let { return it }
        }
        return ContextCompat.getDrawable(context, fallbackRes)
    }

    fun isPreset(value: String?): Boolean = preset(value.orEmpty()) != null

    private fun preset(value: String): FamilyAvatarPreset? = presets.firstOrNull { it.storageValue == value }
}
