package ru.example.parentwatch.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Why a downloaded package was accepted or refused. */
enum class ApkVerification {
    Accept,
    PackageNameMismatch,
    VersionCodeMismatch,
    NotNewer,
    SignatureMismatch
}

/**
 * Checks a downloaded package before the person is asked to install it.
 *
 * This phone belongs to a child and is the one nobody can reach with a cable, so
 * "an update that installs itself" is not a convenience here — it is the only way a
 * fix ever arrives. That makes the checks below more important, not less: the file
 * has to be shown to be the published build and nothing else before anybody is
 * asked to trust it.
 *
 * Android would refuse a package that is not signed by the key of the installed
 * application, but it does so with a system error message that explains nothing.
 * Checking here turns "the installation failed" into a refusal this application can
 * explain, and it means a package that was swapped on the way is never offered at
 * all.
 *
 * Three things are confirmed, and all three are needed:
 *
 * - the package name is this application's, so an update cannot replace a different
 *   application;
 * - the version code is exactly the announced one and higher than the installed
 *   one, so the file matches the manifest that described it;
 * - the signing certificate is byte-for-byte the certificate of the application
 *   already on the phone.
 *
 * Certificates are compared as the certificate's own bytes, not as the signature
 * objects the framework hands out: those are objects whose equality is neither
 * promised nor useful, while the encoded certificate is exactly the thing that must
 * match — and it is what the manifest's own fingerprint is computed over.
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    fun verify(
        apk: File,
        context: Context,
        packageName: String,
        release: UpdateRelease
    ): ApkVerification {
        val manager = context.packageManager

        val archived = try {
            packageInfoFromArchive(manager, apk.absolutePath)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the downloaded package", error)
            null
        } ?: return ApkVerification.PackageNameMismatch

        val archivedName = archived.packageName ?: return ApkVerification.PackageNameMismatch
        if (!archivedName.equals(packageName, ignoreCase = true)) {
            Log.e(TAG, "Downloaded package is $archivedName, not $packageName")
            return ApkVerification.PackageNameMismatch
        }

        val archivedCode = archived.longVersionCodeCompat()
        if (archivedCode != release.versionCode.toLong()) {
            Log.e(
                TAG,
                "Downloaded package has version code $archivedCode, " +
                    "the manifest announced ${release.versionCode}"
            )
            return ApkVerification.VersionCodeMismatch
        }

        val installed = try {
            packageInfo(manager, packageName)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the installed package", error)
            null
        } ?: return ApkVerification.NotNewer

        if (archivedCode <= installed.longVersionCodeCompat()) {
            Log.e(
                TAG,
                "Downloaded package ($archivedCode) is not newer than the installed one " +
                    "(${installed.longVersionCodeCompat()})"
            )
            return ApkVerification.NotNewer
        }

        val downloadedCertificate = certificateBytes(archived)
            ?: return ApkVerification.SignatureMismatch
        val installedCertificate = certificateBytes(installed)
            ?: return ApkVerification.SignatureMismatch

        if (!downloadedCertificate.contentEquals(installedCertificate)) {
            Log.e(TAG, "The downloaded package is signed with a different key")
            return ApkVerification.SignatureMismatch
        }

        // The manifest carries the fingerprint of the key the release step used. When
        // it is present it is checked as well, so a manifest and a file that do not
        // belong together are caught here rather than by the installer.
        if (release.signingCertSha256.length == 64) {
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(downloadedCertificate)
                .joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.signingCertSha256, ignoreCase = true)) {
                Log.e(TAG, "The package does not match the certificate the manifest announced")
                return ApkVerification.SignatureMismatch
            }
        }

        return ApkVerification.Accept
    }

    /**
     * The installed package, asked for the way this Android version expects.
     *
     * `GET_SIGNING_CERTIFICATES` exists from API 28; older versions answer with the
     * older flag, which still returns the certificate that matters.
     */
    private fun packageInfo(
        manager: PackageManager,
        packageName: String
    ): PackageInfo? = manager.getPackageInfo(packageName, signingFlags())

    /** The package described by a file, which is not installed yet. */
    private fun packageInfoFromArchive(
        manager: PackageManager,
        apkPath: String
    ): PackageInfo? = manager.getPackageArchiveInfo(apkPath, signingFlags())

    /**
     * The flag that makes the signing certificate available.
     *
     * `GET_SIGNATURES` is deprecated but is the only one API 26 and 27 understand.
     */
    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    /**
     * The certificate bytes a package was signed with.
     *
     * The comparison is made between the **certificate's own encoded bytes**, which
     * is the only form that can be compared meaningfully: an application is
     * identified by its certificate, and the framework's signature objects are
     * wrappers whose equality is neither promised nor useful.
     *
     * On API 28 and above the signers are read from `signingInfo`, whose byte array
     * is available on every version of that API — the certificate *history* holds
     * objects whose own accessor was added later, and reaching for it would leave
     * this code broken on the very devices it is meant to support.
     */
    private fun certificateBytes(info: PackageInfo?): ByteArray? {
        if (info == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = info.signingInfo?.apkContentsSigners
            if (signers != null && signers.isNotEmpty()) {
                return signers.first().toByteArray()
            }
        }

        @Suppress("DEPRECATION")
        val signatures = info.signatures
        return signatures?.firstOrNull()?.toByteArray()
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
    }
}
