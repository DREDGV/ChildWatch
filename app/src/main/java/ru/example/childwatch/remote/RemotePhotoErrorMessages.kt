package ru.example.childwatch.remote

import android.content.Context
import ru.example.childwatch.R

data class RemotePhotoUiError(
    val status: String,
    val message: String,
    val title: String? = null,
    val actionable: Boolean = false
)

/** Converts stable ChildDevice camera error codes into useful parent-facing instructions. */
object RemotePhotoErrorMessages {
    fun resolve(context: Context, rawError: String): RemotePhotoUiError {
        val normalized = rawError.trim().lowercase()
        return when {
            normalized.contains("background_camera_restricted") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_background_restricted_status),
                title = context.getString(R.string.remote_camera_background_restricted_title),
                message = context.getString(R.string.remote_camera_background_restricted_message),
                actionable = true
            )

            normalized.contains("camera_permission_denied") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_permission_denied_status),
                title = context.getString(R.string.remote_camera_permission_denied_title),
                message = context.getString(R.string.remote_camera_permission_denied_message),
                actionable = true
            )

            else -> {
                val fallback = context.getString(R.string.remote_camera_error_format, rawError)
                RemotePhotoUiError(status = fallback, message = fallback)
            }
        }
    }
}
