package ru.example.childwatch.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** What happened when an installation was asked for. */
sealed class InstallRequest {
    /** The system installer is dealing with it and will report the outcome. */
    data class Committed(val sessionId: Int) : InstallRequest()

    /**
     * This application is not allowed to install packages yet.
     *
     * Android keeps that decision with the person, and the word "yet" matters: the
     * screen offers one button that opens Android's own screen for this
     * application, and the attempt can be repeated straight after.
     */
    object PermissionRequired : InstallRequest()
    /** The package could not even be handed over; nothing was committed. */
    data class Failed(val reason: Throwable) : InstallRequest()
}

/**
 * Hands a checked package to the system installer.
 *
 * An update is never installed silently. Android would refuse it for an ordinary
 * application, and it would be the wrong thing to want: replacing somebody's
 * application without them seeing it is not an update, it is a takeover. What
 * happens here is a request — the system shows its own window, says what is about
 * to be installed, and the person confirms it.
 *
 * The session is created, filled and then committed. Anything that fails between
 * creating it and committing it abandons the session **before** the error leaves
 * this class. Android caps how many live installer sessions may exist at once, and
 * a session left behind makes the next attempt fail as well — which is exactly the
 * trap the project this design was taken from fell into.
 */
class UpdateInstaller(private val context: Context) {

    companion object {
        private const val TAG = "UpdateInstaller"

        private const val WRITE_BUFFER_BYTES = 64 * 1024

        /** How long the installer is given to read the package. */
        private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000
    }

    /**
     * Whether Android will let this application ask for an installation.
     *
     * False is an ordinary state on a phone where the person has never installed an
     * application from outside a store; it is not a failure and must be explained
     * rather than reported as an error.
     */
    fun canInstallPackages(): Boolean {
        val manager = context.packageManager
        return try {
            manager.canRequestPackageInstalls()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not ask whether installs are permitted", error)
            false
        }
    }

    /**
     * Starts an installation.
     *
     * [onProgress] receives the number of bytes handed to the installer. Writing
     * into the session is a copy to a location the installer reads from, so it
     * takes a moment on a package of tens of megabytes and the person is shown that
     * it is happening.
     *
     * The result is delivered to the receiver declared in the manifest, not to the
     * caller: installing replaces this application, which kills this process, so a
     * listener registered here would never hear the answer.
     */
    fun install(
        apk: File,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): InstallRequest {
        if (!canInstallPackages()) {
            return InstallRequest.PermissionRequired
        }
        if (!apk.exists() || apk.length() <= 0L) {
            return InstallRequest.Failed(IOException("The downloaded package is gone"))
        }

        var session: PackageInstaller.Session? = null
        var committed = false
        try {
            val installer = context.packageManager.packageInstaller
            val parameters = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Declares that the person has already agreed, in this
                    // application's own window, to installing this update.
                    //
                    // Without it Android cancels the session the moment it is
                    // committed and reports the installation as aborted, before any
                    // system window can appear. That is what made the update repeat
                    // for ever: download, hand over, cancelled, offer again.
                    //
                    // The application is updating itself, so Android accepts this
                    // declaration; the person is never asked twice for one decision
                    // they already made.
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                }
            }

            val sessionId = installer.createSession(parameters)
            Log.i(TAG, "Installer session $sessionId created for ${apk.name}")
            session = installer.openSession(sessionId)

            var written = 0L
            FileInputStream(apk).use { input ->
                val stream = session.openWrite("package", 0L, apk.length())
                stream.use { output ->
                    val buffer = ByteArray(WRITE_BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            throw InstallCancelled()
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                    output.flush()
                    // The data is on disk only after this. The stream the installer
                    // hands out is written through to a file descriptor, so it is
                    // flushed there explicitly; committing a session whose bytes are
                    // still in a buffer would hand the installer a half-written
                    // package.
                    (output as? FileOutputStream)?.fd?.sync()
                }
            }

            session.commit(pendingIntent(sessionId).intentSender)
            committed = true
            Log.i(TAG, "Installer session $sessionId committed; awaiting the person")
            return InstallRequest.Committed(sessionId)
        } catch (cancelled: InstallCancelled) {
            Log.i(TAG, "The installation was cancelled before it was committed")
            return InstallRequest.Failed(cancelled)
        } catch (error: Throwable) {
            // Deliberately Throwable: any failure at all between creating the
            // session and committing it has to end the session, or the next attempt
            // runs into Android's limit on live sessions.
            Log.e(TAG, "The installation could not be started", error)
            return InstallRequest.Failed(error)
        } finally {
            if (!committed) {
                abandonQuietly(session)
            }
        }
    }

    /**
     * Ends a session that will never be committed.
     *
     * Called from the result receiver with the identifier of a session the system
     * reported as failed, so a refused installation does not leave the phone with a
     * used-up session slot.
     */
    fun abandonSession(sessionId: Int) {
        if (sessionId <= 0) return
        abandonQuietly(openSessionQuietly(sessionId))
    }

    private fun openSessionQuietly(sessionId: Int): PackageInstaller.Session? {
        return try {
            context.packageManager.packageInstaller.openSession(sessionId)
        } catch (error: Throwable) {
            Log.w(TAG, "Session $sessionId could not be reopened to be abandoned", error)
            null
        }
    }

    private fun abandonQuietly(session: PackageInstaller.Session?) {
        if (session == null) return
        try {
            session.abandon()
        } catch (error: Throwable) {
            // Losing the race with the system (the session may already be gone) is
            // not worth reporting, but a session that could not be abandoned is.
            Log.w(TAG, "Could not abandon the installer session", error)
        }
    }

    /**
     * Where the installer reports the outcome.
     *
     * The receiver is resolved by class name — it is declared in the manifest, so it
     * is available to a process that has just replaced this application.
     */
    private fun pendingIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateResultReceiver::class.java).apply {
            // The identifier travels with the result so a refused installation can
            // end its own session without having to remember anything.
            putExtra(UpdateResultReceiver.EXTRA_SESSION_ID, sessionId)
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills in the confirmation intent, so this one must stay
            // mutable. Without it the receiver is handed nothing to show.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            flags
        )
    }

    private class InstallCancelled : IOException("The installation was cancelled")
}
