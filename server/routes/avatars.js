/**
 * Profile picture uploads.
 *
 * Until now a profile picture could only be one of the built-in presets: the
 * server accepted nothing but `preset:<name>`, so a person's own photograph was
 * refused and every member of the family looked like a cartoon animal.
 *
 * This accepts an uploaded picture and returns the value to store in the profile.
 * The value is a path served from the public directory, so it reaches every
 * device in the family the same way, and it is deliberately not an absolute URL:
 * the address of the server is not baked into the stored profile, so the family
 * keeps working if the server moves.
 *
 * The picture is checked by its content, not by what the uploader claims. A file
 * that merely calls itself an image is refused, because a stored file is later
 * served back to every member of the family.
 */

const express = require("express");
const router = express.Router();
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");

/** Where uploaded pictures live, relative to the server root. */
const AVATAR_PUBLIC_PREFIX = "/avatars";
const AVATAR_DIRECTORY = path.join(__dirname, "..", "public", "avatars");

/** A picture larger than this is refused before it is written anywhere. */
const MAX_AVATAR_BYTES = 5 * 1024 * 1024;

/**
 * The image formats that may be stored.
 *
 * Deliberately a short list with no SVG: a stored picture is served back to every
 * member of the family, and SVG is a document format that can carry script.
 */
const ALLOWED_IMAGE_TYPES = Object.freeze({
  "image/jpeg": ".jpg",
  "image/png": ".png",
  "image/webp": ".webp",
});

/**
 * Recognises a format from the first bytes of the file.
 *
 * The declared content type is a claim by the uploader; these bytes are the file
 * itself. A mismatch means the upload is not what it says it is.
 */
function detectImageType(buffer) {
  if (!buffer || buffer.length < 12) return null;
  if (buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return "image/jpeg";
  }
  const pngSignature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (pngSignature.every((byte, index) => buffer[index] === byte)) {
    return "image/png";
  }
  if (
    buffer.toString("ascii", 0, 4) === "RIFF" &&
    buffer.toString("ascii", 8, 12) === "WEBP"
  ) {
    return "image/webp";
  }
  return null;
}

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_AVATAR_BYTES, files: 1 },
});

/** Creates the storage directory on first use rather than at import time. */
function ensureAvatarDirectory() {
  fs.mkdirSync(AVATAR_DIRECTORY, { recursive: true });
}

/**
 * Stores the picture and answers with the value to save in the profile.
 *
 * The name is derived from random bytes, so two people uploading the same
 * photograph still get separate files and one person's picture can never be
 * reached by guessing another's.
 *
 * Authentication is applied when this router is mounted, as with every other
 * route in this server.
 */
router.post("/avatar", upload.single("avatar"), async (req, res) => {
  try {
    if (!req.file || !req.file.buffer) {
      return res.status(400).json({
        error: "No picture was sent",
        code: "AVATAR_FILE_MISSING",
      });
    }

    const detected = detectImageType(req.file.buffer);
    if (!detected || !ALLOWED_IMAGE_TYPES[detected]) {
      return res.status(400).json({
        error: "Only a JPEG, PNG or WebP picture is accepted",
        code: "AVATAR_UNSUPPORTED_IMAGE",
      });
    }

    ensureAvatarDirectory();
    const fileName = `${crypto.randomBytes(16).toString("hex")}${
      ALLOWED_IMAGE_TYPES[detected]
    }`;
    fs.writeFileSync(path.join(AVATAR_DIRECTORY, fileName), req.file.buffer);

    const value = `${AVATAR_PUBLIC_PREFIX}/${fileName}`;
    return res.json({
      success: true,
      // The value a client stores in the profile.
      avatarValue: value,
      // A ready-to-use address, for a client that wants to show it at once.
      publicPath: value,
      bytes: req.file.buffer.length,
      contentType: detected,
    });
  } catch (error) {
    return res.status(500).json({
      error: "The picture could not be stored",
      code: "AVATAR_STORE_FAILED",
    });
  }
});

/**
 * Removes a picture this device uploaded earlier.
 *
 * Called when a person replaces their own picture or goes back to a built-in
 * one. Only a file inside the avatar directory is ever touched, so a request
 * naming some other path cannot delete anything else on the server.
 */
router.delete("/avatar", async (req, res) => {
  const requested = String(req.query.path || "").trim();

  if (!requested.startsWith(`${AVATAR_PUBLIC_PREFIX}/`)) {
    return res.status(400).json({
      error: "Not an uploaded picture",
      code: "AVATAR_NOT_UPLOADED",
    });
  }

  // Only the file name is used, so no sequence of "../" can escape the directory.
  const fileName = path.basename(requested);
  if (!/^[a-f0-9]{32}\.(jpg|png|webp)$/.test(fileName)) {
    return res.status(400).json({
      error: "Not an uploaded picture",
      code: "AVATAR_NOT_UPLOADED",
    });
  }

  const resolved = path.resolve(path.join(AVATAR_DIRECTORY, fileName));
  if (!resolved.startsWith(path.resolve(AVATAR_DIRECTORY) + path.sep)) {
    return res.status(400).json({
      error: "Not an uploaded picture",
      code: "AVATAR_NOT_UPLOADED",
    });
  }

  try {
    if (fs.existsSync(resolved)) fs.unlinkSync(resolved);
    return res.json({ success: true, removed: fileName });
  } catch (error) {
    return res.status(500).json({
      error: "The picture could not be removed",
      code: "AVATAR_REMOVE_FAILED",
    });
  }
});

module.exports = router;
module.exports.AVATAR_PUBLIC_PREFIX = AVATAR_PUBLIC_PREFIX;
module.exports.AVATAR_DIRECTORY = AVATAR_DIRECTORY;
module.exports.MAX_AVATAR_BYTES = MAX_AVATAR_BYTES;
module.exports.detectImageType = detectImageType;
