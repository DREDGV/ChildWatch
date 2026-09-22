/**
 * Application updates over the air.
 *
 * Every build used to be installed by hand: connect the phone by cable, build,
 * copy the file, install it. On the phone that watches a child that is not even
 * possible without holding the device, so fixes waited and the versions on the
 * two phones drifted apart.
 *
 * This serves one manifest describing what is published, and the files it names.
 * The manifest is the single source of truth: a client compares its own version
 * code with the one here, and downloads the file this manifest names — not a file
 * it guessed at.
 *
 * Two things this endpoint deliberately does NOT do: it does not decide that an
 * update is safe (that needs the signature, which only the device can check
 * against the application already installed), and it does not install anything.
 * Installation stays a visible action for the person holding the phone.
 */

const express = require("express");
const router = express.Router();
const fs = require("fs");
const path = require("path");

/**
 * Where released files live.
 *
 * Deliberately outside the application directory: released builds are not source
 * and must not be wiped by deploying a new version of the server. Set
 * CW_UPDATE_DIR to place them elsewhere.
 */
const UPDATE_DIRECTORY = path.resolve(
  process.env.CW_UPDATE_DIR || path.join(__dirname, "..", "..", "updates")
);

/** The manifest written by the release step. */
const MANIFEST_FILE = path.join(UPDATE_DIRECTORY, "manifest.json");

/**
 * The manifest must never be cached.
 *
 * A cached answer is a functional bug, not a performance detail: it can hide a
 * release that has just been published, or keep announcing one that the phone has
 * already installed. The file is a few hundred bytes and is fetched once in a
 * while, so there is nothing to gain by caching it.
 */
const MANIFEST_CACHE_CONTROL = "no-store";

/**
 * Reads the manifest from disk on every request.
 *
 * Caching it in memory would mean a published release stays invisible until the
 * server is restarted, which is exactly the kind of silent delay this feature
 * exists to remove. The file is small and read rarely.
 */
function readManifest() {
  if (!fs.existsSync(MANIFEST_FILE)) {
    return { missing: true };
  }
  const raw = fs.readFileSync(MANIFEST_FILE, "utf8");
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed !== "object" || !parsed.apps) {
    throw new Error("manifest has no apps section");
  }
  return parsed;
}

/**
 * Answers with what is published.
 *
 * No authentication: a phone must be able to learn that an update exists before
 * anybody signs in, and the answer holds nothing private — a version number, a
 * checksum and the name of a file that is downloadable by anybody anyway.
 */
router.get("/manifest", (req, res) => {
  try {
    const manifest = readManifest();
    if (manifest.missing) {
      return res.status(404).json({
        error: "No release has been published",
        code: "UPDATE_MANIFEST_MISSING",
      });
    }

    // Each entry is answered with a ready-to-use address as well as the file name.
    //
    // A client that builds the address itself has to agree with the server on two
    // things forever: the route and the file name. The ruvia project made exactly
    // that mistake — its client hardcodes the file name and never reads the one in
    // the announcement, so renaming it would have to be done in three places with
    // nothing to catch a disagreement. Handing over the address leaves one place.
    const withUrls = {
      ...manifest,
      apps: Object.fromEntries(
        Object.entries(manifest.apps || {}).map(([key, app]) => [
          key,
          app && app.file
            ? { ...app, url: `${req.baseUrl}/files/${app.file}` }
            : app,
        ])
      ),
    };

    res.set("Cache-Control", MANIFEST_CACHE_CONTROL);
    res.set("X-Content-Type-Options", "nosniff");
    return res.json(withUrls);
  } catch (error) {
    return res.status(500).json({
      error: "The release manifest could not be read",
      code: "UPDATE_MANIFEST_UNREADABLE",
    });
  }
});

/**
 * Serves a published package.
 *
 * The name is checked against a fixed shape rather than merely joined onto a
 * directory: a request naming `../` must not be able to reach anything outside
 * the release directory, and the same rule keeps the route from becoming a way to
 * read arbitrary files.
 */
router.get("/files/:fileName", (req, res) => {
  const fileName = String(req.params.fileName || "");
  if (!/^[A-Za-z0-9._-]+\.apk$/.test(fileName) || fileName.includes("..")) {
    return res.status(400).json({
      error: "Not a release file name",
      code: "UPDATE_FILE_NAME_INVALID",
    });
  }

  const resolved = path.resolve(path.join(UPDATE_DIRECTORY, fileName));
  if (!resolved.startsWith(UPDATE_DIRECTORY + path.sep)) {
    return res.status(400).json({
      error: "Not a release file name",
      code: "UPDATE_FILE_NAME_INVALID",
    });
  }

  if (!fs.existsSync(resolved)) {
    return res.status(404).json({
      error: "That release file is not published",
      code: "UPDATE_FILE_MISSING",
    });
  }

  // The Android installer needs this type, and a browser must download rather
  // than try to display it.
  res.set("Content-Type", "application/vnd.android.package-archive");
  res.set("Content-Disposition", `attachment; filename="${fileName}"`);
  // Released files never change once published, so they may be cached; a rebuild
  // produces a new name.
  res.set("Cache-Control", "public, max-age=86400, immutable");
  res.set("X-Content-Type-Options", "nosniff");
  return res.sendFile(resolved);
});

module.exports = router;
module.exports.UPDATE_DIRECTORY = UPDATE_DIRECTORY;
module.exports.MANIFEST_FILE = MANIFEST_FILE;
module.exports.readManifest = readManifest;
