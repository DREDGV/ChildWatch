package ru.example.parentwatch.profile

import android.net.Uri
import android.widget.ImageView
import androidx.annotation.DrawableRes
import ru.example.childwatch.designsystem.AvatarPresetCatalog
import ru.example.parentwatch.R

/** Renders the same stored family avatar keys as ParentMonitor. */
object FamilyAvatarRenderer {
    fun bind(
        view: ImageView,
        avatarValue: String?,
        @DrawableRes fallbackRes: Int = R.drawable.ic_brand_childdevice
    ) {
        view.imageTintList = null
        val value = avatarValue?.trim().orEmpty()
        AvatarPresetCatalog.createDrawable(view.context, value)?.let {
            view.setImageDrawable(it)
            return
        }
        if (value.isNotBlank()) {
            runCatching {
                view.setImageDrawable(null)
                view.setImageURI(Uri.parse(value))
                checkNotNull(view.drawable)
            }.onSuccess { return }
        }
        view.setImageResource(fallbackRes)
    }
}
