package ru.example.parentwatch.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R

/*
 * How an installation ended.
 *
 * This class is declared in the manifest, and that is the whole point of it:
 * installing an update replaces the application, which kills this process. The screen
 * that asked for the installation no longer exists by the time the answer comes back,
 * so nothing registered from a running application could receive it. A receiver
 * declared in the manifest is started for that one message instead, which is why the
 * outcome survives the replacement.
 *
 * Two outcomes are ordinary and must not be dressed up as errors:
 *
 * - the installation succeeded, which is what the person asked for;
 * - the person refused it in the system's own window, which is a decision, not a
 *   fault. Android reports a refusal as a failure, so it is recognised by the status
 *   the installer sends with it.
 *
 * Everything else is written down so the next launch can show a readable sentence.
 * Writing that note is checked: a child cannot be asked what an error message said,
 * so an installation that quietly did nothing would simply never be noticed.
 */
class UpdateResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateResultReceiver"

        /** The installer session this result belongs to, so it can be ended. */
        const val EXTRA_SESSION_ID = "ru.example.parentwatch.update.SESSION_ID"

        /** Set on the screen's own intent when a notification opened it. */
        const val EXTRA_RESUME_CONFIRMATION = "ru.example.parentwatch.update.RESUME_CONFIRMATION"

        private const val NOTIFICATION_CHANNEL_ID = "update_notifications"
        private const val NOTIFICATION_ID = 6100

        /**
         * Where the system puts the confirmation it wants shown.
         *
         * Written out by hand because `PackageInstaller.EXTRA_INTENT` is not part of
         * the public API — it is hidden from the SDK, so the constant cannot be
         * referenced and the name is the only thing available. Its value is fixed by
         * the platform and cannot change without breaking every installer client.
         */
        private const val EXTRA_INTENT = "android.content.pm.extra.INTENT"

        /**
         * The message Android sends when the person closed the confirmation.
         *
         * It is matched **alongside** the status code and never instead of it:
         * deciding what to tell a person from a piece of prose is how a rewritten
         * message silently changes the advice. This one only distinguishes two
         * outcomes that are both already failures, and treats one of them as the
         * normal answer it actually is.
         */
        private const val MESSAGE_USER_ACTION_REQUIRED = "user action"

        @Volatile
        private var runningContext: Context? = null

        /** Called by a screen so the confirmation can be opened while it is visible. */
        fun attach(context: Context) {
            runningContext = context.applicationContext
        }

        fun detach() {
            runningContext = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        // A message WITHOUT a status is the system asking for the confirmation to be
        // shown, not a result.
        //
        // This is what made the update repeat for ever. Android announces "user
        // action required" by sending this receiver a message that carries no status
        // at all, and only the identifier of the session. Reading the status with a
        // default turned that request into "the installation failed", so the
        // confirmation window was thrown away, the person saw nothing, and the
        // application offered the same update again. The system log said it plainly:
        // "status of session: pending, User action required".
        if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            Log.i(TAG, "The installer is asking for the confirmation to be shown")
            onConfirmationNeeded(context, intent)
            return
        }

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        Log.i(TAG, "Install result: status=$status session=$sessionId message=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> onInstalled(context)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> onConfirmationNeeded(context, intent)
            else -> onRefused(context, sessionId, statusMessage, status)
        }
    }

    /**
     * The package is on the phone.
     *
     * The install may already have been finished by the system after this process was
     * restarted, so the file is deleted unconditionally rather than by looking for a
     * running screen.
     */
    private fun onInstalled(context: Context) {
        Log.i(TAG, "The update was installed")
        deleteDownloadedPackages(context)
        val preferences = UpdatePreferences(context)
        // A note left by an earlier failed attempt must not be shown as the outcome of
        // the attempt that worked.
        preferences.clearPendingFailureNote()
    }

    /**
     * The system is asking the person to confirm.
     *
     * A current Android version does not let an application open a window from the
     * background, so when a screen is running the confirmation is handed to it and
     * opened from there. When nothing is running — the application was closed while
     * the download finished — a notification opens the application, and the
     * confirmation is shown as the screen resumes. Either way the person sees the
     * system's own window and decides.
     */
    private fun onConfirmationNeeded(context: Context, intent: Intent) {
        val confirmation = parcelableIntent(intent, EXTRA_INTENT)

        if (confirmation == null) {
            // Nothing to show. This is the one case where the package cannot be offered
            // at all, and the person has to be told why.
            noteFailure(context, context.getString(R.string.update_error_confirmation_missing))
            return
        }

        UpdateManager.publishPendingConfirmation(confirmation)

        if (runningContext != null) {
            // A screen is running, so hand it over: it may open the installer's window
            // directly, without a notification standing in between.
            Handler(Looper.getMainLooper()).post {
                UpdateManager.deliverPendingConfirmation()
            }
        } else {
            notifyToContinue(context)
        }
    }

    /**
     * The installation did not happen.
     *
     * A refusal by the person is a normal outcome: they were shown what would be
     * installed and said no. It is not reported, it is not written down, and the
     * downloaded package is removed, because the offer is over.
     */
    private fun onRefused(
        context: Context,
        sessionId: Int,
        statusMessage: String,
        status: Int
    ) {
        // The session is ended in every outcome except success. Android caps how many
        // live installer sessions may exist at once, and one left behind makes the
        // next attempt fail too.
        UpdateInstaller(context).abandonSession(sessionId)

        if (status == PackageInstaller.STATUS_FAILURE_ABORTED ||
            statusMessage.contains(MESSAGE_USER_ACTION_REQUIRED, ignoreCase = true)
        ) {
            Log.i(TAG, "The person did not confirm the installation")
            deleteDownloadedPackages(context)
            return
        }

        Log.e(TAG, "The installation failed: status=$status message=$statusMessage")
        deleteDownloadedPackages(context)
        noteFailure(
            context,
            context.getString(R.string.update_error_install_failed, statusMessage.ifBlank {
                context.getString(R.string.update_error_install_unknown)
            })
        )
    }

    /**
     * Writes the sentence the next launch shows, and complains if it cannot.
     *
     * A failure to store the note is logged rather than swallowed: silently losing it
     * would leave this phone with no explanation of an installation that did not
     * happen, and nobody who could report it.
     */
    private fun noteFailure(context: Context, note: String) {
        if (!UpdateManager.rememberFailure(context, note)) {
            Log.e(TAG, "The failure note could not be stored and will not be shown: $note")
        }
    }

    /**
     * Removes downloaded packages.
     *
     * The outcome is unknown to whichever process runs this, because the application
     * may have been replaced in between, so the cache is swept rather than a named
     * file being deleted.
     */
    private fun deleteDownloadedPackages(context: Context) {
        try {
            val directory = java.io.File(context.cacheDir, "updates")
            if (!directory.isDirectory) return
            directory.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "Could not delete ${file.name}")
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not clear the update directory", error)
        }
    }

    /**
     * A notification that opens this application so the confirmation can be shown.
     *
     * Used only when no screen is running. The confirmation itself is not launched
     * from here by force: an application in the background is not allowed to open a
     * window, and an attempt to do it anyway would fail silently, leaving the person
     * with nothing at all.
     */
    private fun notifyToContinue(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            ensureChannel(context, manager)

            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_RESUME_CONFIRMATION, true)
            }
            val pending = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
            )

            val notification: Notification = NotificationCompat.Builder(
                context,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.update_notification_text))
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: Throwable) {
            // The confirmation is still held for the next resume, so the person is not
            // left without a way to continue.
            Log.w(TAG, "Could not post the notification that continues the installation", error)
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    /** Reads a nested intent the way the running Android version requires. */
    private fun parcelableIntent(intent: Intent, key: String): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key) as? Intent
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the confirmation the system supplied", error)
            null
        }
    }

    private fun mutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }
}
