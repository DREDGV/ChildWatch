package ru.example.childwatch.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.example.childwatch.BuildConfig
import ru.example.childwatch.network.NetworkClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Finding out that an update exists, and getting it ready to install.
 *
 * Three rules shape everything here, and each of them is the answer to a mistake
 * made by the project this design was taken from:
 *
 * 1. The time of the check is recorded **only after a check that succeeded**. That
 *    project wrote the timestamp before making the request, so one moment without a
 *    network meant a full day of silence with nothing to retry. Here a failure
 *    changes nothing at all and the next opportunity tries again.
 * 2. The version is compared as a **whole number**. That project's check matched a
 *    hardcoded `-alpha` shape in the version string, so the day it published a
 *    release that was no longer an alpha every installed copy quietly stopped
 *    seeing updates. The version name is never parsed here.
 * 3. A failed download **passes through `Throwable`**, so no failure of any kind
 *    can leave a partly written package behind or a progress bar on screen.
 *
 * Nothing in this class shows anything to the person. A failed check is a log line
 * and a retry; only a successful check that found a newer version is worth
 * mentioning, and that is the screen's business.
 */
class UpdateManager(
    private val context: Context,
    private val packageName: String = context.packageName,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE
) {

    private val preferences = UpdatePreferences(context)

    /**
     * The address the last successful check used.
     *
     * Held so the published file is fetched from the very host the manifest came from,
     * rather than from a second reading of the server setting that could disagree.
     */
    @Volatile
    private var serverBaseUrl: String? = null

    /** True when a successful check happened recently, so asking now is wasteful. */
    fun checkedRecently(now: Long = System.currentTimeMillis()): Boolean {
        val last = preferences.lastSuccessfulCheckAt()
        return last > 0L && now - last < CHECK_INTERVAL_MS
    }

    /**
     * Asks what has been published.
     *
     * [serverBase] is the address the caller resolved from the one setting the
     * application already keeps its server address in. It is passed in and remembered,
     * so the file is later fetched from the same host the manifest came from — this
     * class never reads that setting by itself.
     *
     * Answers null when there is nothing to tell the person: no network, a server
     * that does not answer, a manifest this code cannot read, or — most often —
     * the installed version already being current. The caller cannot tell those
     * apart, and should not: none of them deserves a message.
     */
    suspend fun checkForUpdate(serverBase: String): UpdateRelease? {
        if (checkedRecently()) {
            Log.d(TAG, "An update was checked for less than a day ago; skipping")
            return null
        }
        val base = serverBase.trim().trimEnd('/')
        if (base.isBlank()) {
            Log.d(TAG, "The update check is skipped: no server address is configured")
            return null
        }
        serverBaseUrl = base

        val raw = NetworkClient(context).fetchUpdateManifest(base).getOrElse { error ->
            // Deliberately no timestamp here. One failed attempt must not buy a day
            // of silence: the next launch asks again.
            Log.w(TAG, "Update check failed and will be retried later", error)
            return null
        }

        val release = UpdateManifest.parse(raw, packageName) ?: run {
            // The answer arrived but describes no release this application can act
            // on. That is a normal state — the manifest is shared with the other
            // application of the family — so it is not an error and, more
            // importantly, it is not a failure of the check.
            Log.d(TAG, "The manifest describes nothing usable for $packageName")
            preferences.recordSuccessfulCheck(System.currentTimeMillis())
            return null
        }

        // From this point the manifest was read successfully and names this very
        // application, so the wait until the next check starts now. This is the one
        // and only place the timestamp moves.
        preferences.recordSuccessfulCheck(System.currentTimeMillis())

        if (release.versionCode <= currentVersionCode) {
            Log.d(
                TAG,
                "Already current: installed ${currentVersionCode}, published ${release.versionCode}"
            )
            return null
        }

        Log.i(
            TAG,
            "Update available: ${release.versionName} (${release.versionCode}) " +
                "over installed ${currentVersionCode}"
        )
        return release
    }

    /**
     * Downloads the published package and checks it before returning.
     *
     * The order matters: the checksum is computed **while the bytes are being
     * written**, so a wrong or truncated file is known to be wrong before anything
     * is offered to the person. A package that fails any check is deleted here and
     * never reaches the installer.
     *
     * [onProgress] is called with the number of bytes written so far, on the
     * download thread, and is deliberately given a count rather than a percentage:
     * the size in the manifest is the only total available, and a caller that wants
     * a bar can decide for itself what to do with the count.
     */
    suspend fun downloadAndVerify(
        release: UpdateRelease,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): DownloadResult = withContext(Dispatchers.IO) {
        var target: File? = null
        try {
            if (isCancelled()) {
                return@withContext DownloadResult.Failure(
                    DownloadFailure.CANCELLED,
                    retryable = true
                )
            }

            if (release.sizeBytes <= 0L) {
                return@withContext DownloadResult.Failure(
                    DownloadFailure.SIZE_NOT_ANNOUNCED,
                    retryable = false
                )
            }
            if (release.sizeBytes > MAX_PACKAGE_BYTES) {
                return@withContext DownloadResult.Failure(
                    DownloadFailure.SIZE_UNREASONABLE,
                    retryable = false
                )
            }

            // The connection is no longer a reason to refuse.
            //
            // This used to stop on any metered connection to avoid spending somebody's
            // data allowance. In practice that made the update impossible for anyone
            // without Wi-Fi, and the person had already pressed "update" and seen the
            // size - they made the decision. The size is shown in the confirmation,
            // and the connection only has to work.
            if (!hasUsableConnection()) {
                return@withContext DownloadResult.Failure(
                    DownloadFailure.NO_CONNECTION,
                    retryable = true
                )
            }

            // The application's own cache directory, never the shared Downloads
            // folder: a partly written package there would be visible to every
            // other application and would survive this application's own cleanup.
            val file = safeCacheFile(release.fileName)
            deleteQuietly(file)
            target = file

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L

            java.net.URL(resolveDownloadUrl(release.url)).openConnection().apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            throw DownloadCancelled()
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > release.sizeBytes) {
                            // More bytes than announced: the manifest does not
                            // describe this file, so it is not this file.
                            throw SizeExceeded()
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onProgress(written)
                    }
                    output.flush()
                }
            }

            if (written != release.sizeBytes) {
                deleteQuietly(file)
                target = null
                return@withContext DownloadResult.Failure(
                    DownloadFailure.SIZE_MISMATCH,
                    retryable = true
                )
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(release.sha256, ignoreCase = true)) {
                // A checksum mismatch is the one failure that must never offer
                // "try again": either the download was altered on the way or the
                // manifest and the file are not the same release, and repeating the
                // same request cannot change either.
                Log.e(
                    TAG,
                    "Checksum mismatch: the manifest announced ${release.sha256}, " +
                        "the file is $actualHash; the file was deleted"
                )
                deleteQuietly(file)
                target = null
                return@withContext DownloadResult.Failure(
                    DownloadFailure.CHECKSUM_MISMATCH,
                    retryable = false
                )
            }

            when (val verdict = ApkVerifier.verify(file, context, packageName, release)) {
                ApkVerification.Accept -> DownloadResult.Success(file)
                ApkVerification.PackageNameMismatch,
                ApkVerification.VersionCodeMismatch,
                ApkVerification.NotNewer,
                ApkVerification.SignatureMismatch -> {
                    Log.e(TAG, "The downloaded package was refused: $verdict; deleting it")
                    deleteQuietly(file)
                    target = null
                    DownloadResult.Failure(
                        DownloadFailure.PACKAGE_REFUSED,
                        retryable = false,
                        detail = verdict
                    )
                }
            }
        } catch (cancelled: DownloadCancelled) {
            // The person stopped it. Nothing is wrong, and nothing is reported.
            target?.let { deleteQuietly(it) }
            DownloadResult.Failure(DownloadFailure.CANCELLED, retryable = true)
        } catch (error: Throwable) {
            // Deliberately Throwable, not Exception: a download interrupted by an
            // OutOfMemoryError or any other failure must still leave no file
            // behind on the phone.
            Log.w(TAG, "The update download did not finish", error)
            target?.let { deleteQuietly(it) }
            DownloadResult.Failure(DownloadFailure.DOWNLOAD_FAILED, retryable = true)
        }
    }

    /**
     * Whether the connection is free to use without asking.
     *
     * An update is tens of megabytes, which is a real cost on a phone plan, so the
     * download waits for a connection that is not metered and says so when it does.
     * An unknown connection is treated as metered: the cautious answer costs a
     * person a moment, the permissive one can cost them money.
     */
    /**
     * Whether there is a connection that actually reaches the internet.
     *
     * Being metered is deliberately not part of this: see where it is used for why.
     * An unknown answer is treated as no connection, which only delays the attempt.
     */
    private fun hasUsableConnection(): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not tell whether the connection is usable", error)
            false
        }
    }

    /** The manifest may announce a path; it is joined onto the server the check used. */
    private fun resolveDownloadUrl(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = serverBaseUrl ?: throw IOException("Server URL is not configured")
        return base.trimEnd('/') + "/" + trimmed.trimStart('/')
    }
    /**
     * A file inside this application's cache directory.
     *
     * The name was already required to be a plain file name when the manifest was
     * read; the containment check is repeated here so that this class stays safe on
     * its own if it is ever given a name from somewhere else.
     */
    private fun safeCacheFile(fileName: String): File {
        val directory = File(context.cacheDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the update directory")
        }
        val file = File(directory, fileName)
        val root = directory.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(root)) {
            throw IOException("Refusing to write outside the update directory")
        }
        return file
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete ${file.name}")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not delete ${file.name}", error)
        }
    }

    /** Keeps the notice closed for this version until a newer one is published. */
    fun dismiss(versionCode: Int) {
        preferences.dismissVersion(versionCode)
    }

    fun wasDismissed(versionCode: Int): Boolean =
        preferences.dismissedVersionCode() == versionCode

    /** Reads the once-only note left by an install that ended while nothing was on screen. */
    fun consumePendingFailureNote(): String? = preferences.consumePendingFailureNote()

    /** True when this version may be offered on this screen right now. */
    fun shouldOffer(release: UpdateRelease): Boolean = !wasDismissed(release.versionCode)

    private class DownloadCancelled : IOException("The download was cancelled")

    private class SizeExceeded : IOException("The download exceeded the announced size")

    companion object {
        private const val TAG = "UpdateManager"

        /** How often the server is asked at most. */
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /**
         * The largest package this application will accept.
         *
         * A released build is tens of megabytes. A manifest naming something far
         * larger is either a mistake or an attempt to fill the phone's storage, and
         * neither is worth starting a download for.
         */
        private const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L

        /** 64 KiB: large enough to stream quickly, small enough to cancel promptly. */
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Stores a failure that happened while nothing was on screen.
         *
         * The installation broadcast arrives when the application has just been
         * replaced, so the message has to survive until a screen can show it.
         */
        fun rememberFailure(context: Context, note: String): Boolean =
            UpdatePreferences(context).writePendingFailureNote(note)

        /**
         * The confirmation the system installer produced, waiting for a screen.
         *
         * A current Android version does not let an application open a window from
         * the background, so the confirmation is handed to whatever screen is
         * running instead of being launched blindly by the receiver. When no screen
         * is running the receiver posts a notification, and the screen picks the
         * confirmation up from here as it resumes.
         */
        @Volatile
        private var pendingConfirmation: Intent? = null

        @Volatile
        private var confirmationSink: ((Intent) -> Unit)? = null

        fun publishPendingConfirmation(intent: Intent) {
            pendingConfirmation = intent
        }

        fun takePendingConfirmation(): Intent? {
            val intent = pendingConfirmation
            pendingConfirmation = null
            return intent
        }

        /** Registers the screen that may open the installer's window right now. */
        fun attachSink(sink: ((Intent) -> Unit)?) {
            confirmationSink = sink
        }

        /**
         * Hands the waiting confirmation to the running screen, or leaves it for the
         * next resume.
         */
        fun deliverPendingConfirmation() {
            val intent = takePendingConfirmation() ?: return
            val sink = confirmationSink
            if (sink == null) {
                pendingConfirmation = intent
                return
            }
            sink(intent)
        }
    }
}

/** Why a download did not produce an installable file. */
enum class DownloadFailure {
    CANCELLED,
    NO_CONNECTION,
    SIZE_NOT_ANNOUNCED,
    SIZE_UNREASONABLE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    PACKAGE_REFUSED,
    DOWNLOAD_FAILED
}

/** The outcome of a download. */
sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()

    /**
     * [retryable] decides what the person is offered. A checksum mismatch never
     * offers "try again": the same request would produce the same mismatch, and
     * offering it would be advice that cannot work.
     */
    data class Failure(
        val reason: DownloadFailure,
        val retryable: Boolean,
        val detail: ApkVerification? = null
    ) : DownloadResult()
}
