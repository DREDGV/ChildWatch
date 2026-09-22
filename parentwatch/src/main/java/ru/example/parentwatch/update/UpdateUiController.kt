package ru.example.parentwatch.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.example.parentwatch.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything this screen does about updates.
 *
 * The screen only has to hand over a place to put the notice; the rest — the daily
 * check, the download, the verification and the request to the system installer —
 * lives here.
 *
 * On this phone the rules matter for a second reason: it is the device that cannot
 * be plugged in, so nothing here may ever block the screen a child uses or leave a
 * dialog standing in the way of the chat. The check runs off the main thread, a
 * failure is a log line and nothing else, and every progress indicator is dismissed
 * on **any** failure — in a `finally`, so no path leaves a spinner on the screen
 * with nothing behind it.
 */
class UpdateUiController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val noticeContainer: LinearLayout,
    private val serverUrlProvider: () -> String?,
    private val packageName: String = context.packageName
) {

    companion object {
        private const val TAG = "UpdateUiController"
        private const val BYTES_PER_MB = 1024L * 1024L

        /** How often the download progress is redrawn. */
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    }

    private val manager = UpdateManager(context, packageName)
    private val installer = UpdateInstaller(context)
    private val notice = UpdateNoticeView(context)

    /** The release currently being offered, if any. */
    private var offered: UpdateRelease? = null

    /** The download dialog, held so that every path can close it. */
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var downloadJob: Job? = null
    private val downloadCancelled = AtomicBoolean(false)
    private var lastProgressUpdateAt = 0L

    /**
     * Runs the daily check and puts the notice on the screen when there is something
     * to say.
     *
     * Called on start and on every return to the foreground; the daily limit inside
     * [UpdateManager] is what stops that from being a request per resume.
     */
    fun checkAndShowNotice() {
        // Read through the caller's resolver, which is the same session the
        // synchronisation uses. A phone that has not joined a family yet is asked
        // nothing: there is no server to ask.
        val serverUrl = try {
            serverUrlProvider()?.trim()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not resolve the server address", error)
            null
        }

        scope.launch {
            val release = try {
                manager.checkForUpdate(serverUrl.orEmpty())
            } catch (error: Throwable) {
                // The check must never be able to interfere with the screen. Even a
                // failure that is not an Exception ends here as a log line.
                Log.w(TAG, "The update check did not complete", error)
                null
            } ?: return@launch

            if (!manager.shouldOffer(release)) {
                Log.d(TAG, "Version ${release.versionCode} was dismissed by the person")
                return@launch
            }

            showNotice(release)
        }
    }

    /** Puts the notice at the top of the screen's content. */
    private fun showNotice(release: UpdateRelease) {
        if (offered?.versionCode == release.versionCode && notice.isShowing()) return
        offered = release

        if (noticeContainer.indexOfChild(notice.view) < 0) {
            noticeContainer.addView(notice.view, 0)
        }
        notice.show(
            release = release,
            onUpdate = { offerDownload(release) },
            onDismiss = {
                // The notice stays away until a newer release is published. Nothing is
                // asked again on the next resume, which is what "dismissable" has to
                // mean to be worth offering.
                manager.dismiss(release.versionCode)
                notice.hide()
                offered = null
            }
        )
    }

    /** Hides the notice, for a launch that no longer needs it. */
    fun hideNotice() {
        notice.hide()
    }

    /**
     * Starts a download after saying what it will cost.
     *
     * A package of tens of megabytes over a phone plan is a real expense, and on this
     * phone nobody is watching for it, so the size is stated before anything is
     * transferred and a metered connection is not used at all.
     */
    private fun offerDownload(release: UpdateRelease) {
        val size = if (release.sizeBytes > 0L) {
            context.getString(
                R.string.update_download_size,
                release.sizeBytes.toDouble() / BYTES_PER_MB
            )
        } else {
            context.getString(R.string.update_error_size_unknown)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_download_title, release.versionName))
            .setMessage(context.getString(R.string.update_download_message, size))
            .setPositiveButton(context.getString(R.string.update_download_confirm)) { _, _ ->
                startDownload(release)
            }
            .setNegativeButton(context.getString(R.string.update_cancel), null)
            .show()
    }

    /**
     * Downloads, checks and hands the package to the installer.
     *
     * Nothing is offered to the person until every check has passed, and a package
     * that fails any of them has already been deleted by the time the message is
     * written.
     */
    private fun startDownload(release: UpdateRelease) {
        if (downloadJob?.isActive == true) return
        downloadCancelled.set(false)
        notice.setUpdating(true)
        showProgressDialog()

        downloadJob = scope.launch {
            var result: DownloadResult? = null
            try {
                result = manager.downloadAndVerify(
                    release = release,
                    onProgress = { bytes -> publishProgress(bytes, release.sizeBytes) },
                    isCancelled = { downloadCancelled.get() }
                )
            } catch (error: Throwable) {
                // Deliberately Throwable and deliberately still inside the try: the
                // dialog is closed in the finally below whatever happened.
                Log.w(TAG, "The update download ended unexpectedly", error)
            } finally {
                // Catch Throwable, dismiss in finally: no failure of any kind may
                // leave the progress dialog on the screen.
                closeProgressDialog()
                notice.setUpdating(false)
            }

            when (val outcome = result) {
                is DownloadResult.Success -> requestInstall(outcome, release)
                is DownloadResult.Failure -> onDownloadFailure(outcome, release)
                null -> onDownloadFailure(
                    DownloadResult.Failure(DownloadFailure.DOWNLOAD_FAILED, retryable = true),
                    release
                )
            }
        }
    }

    /**
     * Shows how much of the package has arrived.
     *
     * The total comes from the manifest, never from a `Content-Length` header: the
     * server does not send one, and a bar that waits for the file to finish before it
     * can tell how long the file is would be no bar at all. When the manifest
     * announces no size the bar is left indeterminate rather than showing a percentage
     * of nothing.
     */
    private fun publishProgress(bytes: Long, announcedTotal: Long) {
        // Chunks arrive every few milliseconds, so the screen is refreshed a few times
        // a second at most: enough to look alive, few enough that the drawing does not
        // become the slow part.
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdateAt < PROGRESS_UPDATE_INTERVAL_MS && bytes < announcedTotal) {
            return
        }
        lastProgressUpdateAt = now

        scope.launch {
            val bar = progressBar ?: return@launch
            val label = progressText ?: return@launch
            val megabytes = bytes.toDouble() / BYTES_PER_MB

            if (announcedTotal > 0L) {
                bar.isIndeterminate = false
                bar.max = 100
                bar.progress = ((bytes * 100L) / announcedTotal).coerceIn(0L, 100L).toInt()
            } else {
                bar.isIndeterminate = true
            }

            label.text = context.getString(R.string.update_download_progress, megabytes)
        }
    }

    /** A dialog that cannot be dismissed by tapping outside: cancelling is a choice. */
    private fun showProgressDialog() {
        closeProgressDialog()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val label = TextView(context).apply {
            text = context.getString(R.string.update_download_starting)
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        column.addView(label)
        column.addView(bar)

        progressText = label
        progressBar = bar

        progressDialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_download_running_title))
            .setView(column)
            .setCancelable(false)
            .setNegativeButton(context.getString(R.string.update_cancel)) { _, _ ->
                // The download stops at the next chunk and removes what it wrote; the
                // person is told nothing, because stopping is not a failure.
                downloadCancelled.set(true)
            }
            .show()
    }

    private fun closeProgressDialog() {
        try {
            progressDialog?.dismiss()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not close the download dialog", error)
        } finally {
            progressDialog = null
            progressBar = null
            progressText = null
        }
    }

    /**
     * Asks the system installer to install the checked package.
     *
     * The person sees the installer's own window and confirms it there; nothing is
     * installed behind their back, because an application may not install a package
     * without a visible confirmation and should not want to.
     */
    private fun requestInstall(success: DownloadResult.Success, release: UpdateRelease) {
        when (val request = installer.install(success.file)) {
            is InstallRequest.Committed -> {
                Log.i(TAG, "Installation session ${request.sessionId} was committed")
                // The package is deleted by the result receiver, which is the only part
                // of this that outlives the application being replaced.
                UpdateManager.attachSink { intent -> openConfirmation(intent) }
                UpdateManager.deliverPendingConfirmation()
            }
            InstallRequest.PermissionRequired -> showInstallPermissionExplanation()
            is InstallRequest.Failed -> {
                Log.e(TAG, "Could not start the installation", request.reason)
                showFailureMessage(
                    DownloadResult.Failure(DownloadFailure.DOWNLOAD_FAILED, retryable = true),
                    release
                )
            }
        }
    }

    /**
     * Explains, in one sentence, why Android is holding the installation back, and
     * offers the one button that changes it.
     *
     * The screen that opens is Android's own — the application never tries to grant
     * itself anything, and it never pretends the switch is a settings screen of its
     * own. The download is not kept while this happens: the notice stays on screen and
     * the update can be started again once the switch is on.
     */
    private fun showInstallPermissionExplanation() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_permission_title))
            .setMessage(context.getString(R.string.update_permission_message))
            .setPositiveButton(context.getString(R.string.update_permission_open_settings)) { _, _ ->
                openInstallPermissionSettings()
            }
            .setNegativeButton(context.getString(R.string.update_cancel), null)
            .show()
    }

    private fun openInstallPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not open the installation permission screen", error)
            // Not every phone has this screen; saying so is better than a button that
            // appears to do nothing.
            Toast.makeText(
                context,
                context.getString(R.string.update_permission_settings_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Says what went wrong, and offers another attempt only when one could work. */
    private fun onDownloadFailure(failure: DownloadResult.Failure, release: UpdateRelease) {
        if (failure.reason == DownloadFailure.CANCELLED) {
            // The person stopped it. A message about their own decision would be noise.
            Log.i(TAG, "The update download was cancelled by the person")
            return
        }

        Log.w(
            TAG,
            "The update was not prepared: ${failure.reason} (retryable=${failure.retryable})"
        )

        showFailureMessage(failure, release)
    }

    private fun showFailureMessage(failure: DownloadResult.Failure, release: UpdateRelease) {
        if (failure.reason == DownloadFailure.CANCELLED) {
            // The person stopped it. A message about their own decision would be noise,
            // and there is nothing on the screen to dismiss either.
            return
        }

        val message = context.getString(messageFor(failure))

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_error_title))
            .setMessage(message)
            .setNegativeButton(context.getString(R.string.update_close), null)

        // "Try again" is offered only where another attempt could work. A checksum
        // mismatch is the clearest case of one that cannot: the same request would
        // produce the same mismatch.
        if (failure.retryable && failure.reason != DownloadFailure.METERED_CONNECTION) {
            builder.setPositiveButton(context.getString(R.string.update_retry)) { _, _ ->
                startDownload(release)
            }
        }

        builder.show()
    }

    /**
     * The sentence shown for each reason.
     *
     * One sentence per cause, decided by the cause and not by the words in a message:
     * the project this design came from searched the error text for the word
     * "checksum" to decide whether to offer another attempt, so rewording a message
     * there silently changed the advice a person was given.
     */
    private fun messageFor(failure: DownloadResult.Failure): Int {
        return when (failure.reason) {
            DownloadFailure.CHECKSUM_MISMATCH -> R.string.update_error_checksum
            DownloadFailure.SIZE_UNREASONABLE -> R.string.update_error_size_unreasonable
            DownloadFailure.SIZE_NOT_ANNOUNCED -> R.string.update_error_size_unknown
            DownloadFailure.SIZE_MISMATCH -> R.string.update_error_size_mismatch
            DownloadFailure.METERED_CONNECTION -> R.string.update_error_metered
            DownloadFailure.PACKAGE_REFUSED -> when (failure.detail) {
                ApkVerification.PackageNameMismatch -> R.string.update_error_wrong_package
                ApkVerification.VersionCodeMismatch,
                ApkVerification.NotNewer -> R.string.update_error_wrong_version
                ApkVerification.SignatureMismatch -> R.string.update_error_wrong_signature
                else -> R.string.update_error_refused
            }
            DownloadFailure.CANCELLED -> R.string.update_close
            DownloadFailure.DOWNLOAD_FAILED -> R.string.update_error_network
        }
    }

    /**
     * Opens the confirmation the system installer produced.
     *
     * Called while a screen is visible, because that is when an application is allowed
     * to open a window. The receiver holds the confirmation when nothing is on screen
     * and posts a notification instead.
     */
    fun openConfirmation(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not open the installer's confirmation window", error)
        }
    }

    /**
     * Shows the once-only note an installation left behind.
     *
     * Read exactly once: this phone reports what happened to the installation it was
     * asked for, and does not repeat it on every launch afterwards.
     */
    fun showPendingFailureNote() {
        val note = try {
            manager.consumePendingFailureNote()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the note about the last installation", error)
            null
        } ?: return

        Toast.makeText(context, note, Toast.LENGTH_LONG).show()
    }

    /** True when the screen was opened by the notification that continues an install. */
    fun wasOpenedToContinue(intent: Intent?): Boolean =
        intent?.getBooleanExtra(
            UpdateResultReceiver.EXTRA_RESUME_CONFIRMATION,
            false
        ) == true

    /** Releases the screen's claim on the installer's confirmation. */
    fun detach() {
        UpdateManager.attachSink(null)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
