package ru.example.childwatch.update

import org.json.JSONObject

/**
 * What the server says is published for one application.
 *
 * Everything here comes from one answer, and that matters: the version, the
 * checksum and the address of the file are read together, so a file can never be
 * judged against a version that came from somewhere else.
 */
data class UpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileName: String,
    val url: String,
    val signingCertSha256: String,
    val notes: String
)

/**
 * Reads the release manifest.
 *
 * The manifest is the single source of truth about what has been published, so
 * nothing is guessed at here: an entry is used only when it names this very
 * application and carries everything needed to fetch and check the file. An
 * entry that does not describe this application is ignored rather than
 * questioned — the same manifest legitimately describes the other application of
 * the family, and the parent application has no business installing that one.
 */
object UpdateManifest {

    /** The only manifest layout this code understands. */
    const val SCHEMA = 1

    /**
     * The published file name is required to be a plain name.
     *
     * The name is used to build a path inside the cache directory, so accepting
     * anything the server sends would let a malformed answer write outside it.
     * The shape is the same one the server itself enforces on its file route.
     */
    private val FILE_NAME_PATTERN = Regex("^[A-Za-z0-9._-]+\\.apk$")

    /**
     * Parses the answer for [packageName], or answers null.
     *
     * Null means "there is nothing usable here", and the caller treats every such
     * case the same way: the check simply did not succeed. That is deliberate —
     * an unknown schema or a foreign package name must not be reported to the
     * person as an error, because both are expected states of a shared server.
     */
    fun parse(raw: String, packageName: String): UpdateRelease? {
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return null
        }

        // A manifest from a newer schema may describe versions in a way this code
        // would misread; ignoring it is safer than guessing.
        if (root.optInt("schema", -1) != SCHEMA) return null

        val apps = root.optJSONObject("apps") ?: return null
        val entry = findEntry(apps, packageName) ?: return null

        if (!entry.optString("packageName").equals(packageName, ignoreCase = true)) return null

        // The version is compared as a whole number. The version name is carried
        // along for nothing but display.
        val versionCode = if (entry.has("versionCode")) entry.optInt("versionCode", 0) else 0
        if (versionCode <= 0) return null

        val fileName = entry.optString("file").trim()
        if (!FILE_NAME_PATTERN.matches(fileName)) return null

        val sha256 = entry.optString("sha256").trim().lowercase()
        if (!SHA256_PATTERN.matches(sha256)) return null

        // The address is taken from the manifest rather than assembled here: the
        // server owns the route, and building it in two places is how the two
        // places come to disagree.
        val url = entry.optString("url").trim()
        if (url.isEmpty()) return null

        val sizeBytes = if (entry.has("sizeBytes")) entry.optLong("sizeBytes", 0L) else 0L

        return UpdateRelease(
            versionCode = versionCode,
            versionName = entry.optString("versionName").trim(),
            sizeBytes = sizeBytes,
            sha256 = sha256,
            fileName = fileName,
            url = url,
            signingCertSha256 = entry.optString("signingCertSha256").trim().lowercase(),
            notes = entry.optString("notes").trim()
        )
    }

    /**
     * The entry for this application.
     *
     * The key is not trusted on its own: a manifest that files the parent
     * application under the wrong key must not lead to the wrong file being
     * installed, so the key is only a hint and the package name inside the entry
     * is what decides.
     */
    private fun findEntry(apps: JSONObject, packageName: String): JSONObject? {
        apps.optJSONObject(keyFor(packageName))?.let { return it }

        for (key in apps.keys()) {
            val candidate = apps.optJSONObject(key) ?: continue
            if (candidate.optString("packageName").equals(packageName, ignoreCase = true)) {
                return candidate
            }
        }
        return null
    }

    private fun keyFor(packageName: String): String {
        return if (packageName.endsWith(".parentwatch")) "child" else "parent"
    }

    private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
}
