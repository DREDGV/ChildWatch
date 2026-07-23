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
            normalized.contains("background_camera_restricted") ||
                normalized.contains("camera_background_restricted") ||
                normalized.contains("camera restricted") ||
                normalized.contains("background camera") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_background_restricted_status),
                title = context.getString(R.string.remote_camera_background_restricted_title),
                message = context.getString(R.string.remote_camera_background_restricted_message),
                actionable = true
            )

            normalized.contains("camera_permission_denied") ||
                normalized.contains("camera permission denied") ||
                normalized.contains("permission not granted") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_permission_denied_status),
                title = context.getString(R.string.remote_camera_permission_denied_title),
                message = context.getString(R.string.remote_camera_permission_denied_message),
                actionable = true
            )

            normalized.contains("child device not connected") ||
                normalized.contains("child device disconnected") ||
                normalized.contains("child socket not available") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_device_offline_status),
                message = context.getString(R.string.remote_camera_device_offline_message)
            )

            normalized.contains("camera_in_use") ||
                normalized.contains("camera in use") ||
                normalized.contains("max_cameras_in_use") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_device_camera_busy),
                message = context.getString(R.string.remote_camera_device_camera_busy_message)
            )

            normalized.contains("photo_target_mismatch") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_profile_mismatch_status),
                title = context.getString(R.string.remote_camera_profile_mismatch_title),
                message = context.getString(R.string.remote_camera_profile_mismatch_message),
                actionable = true
            )

            normalized.contains("photo_service_start_failed") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_service_start_failed),
                message = context.getString(R.string.remote_camera_service_start_failed_message)
            )

            normalized.contains("photo_request_timeout") -> RemotePhotoUiError(
                status = context.getString(R.string.remote_camera_request_timeout),
                message = context.getString(R.string.remote_camera_no_response)
            )

            else -> {
                val fallback = context.getString(R.string.remote_camera_error_format, rawError)
                RemotePhotoUiError(status = fallback, message = fallback)
            }
        }
    }
}
