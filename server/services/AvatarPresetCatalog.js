/**
 * Portable family avatar keys.
 *
 * Single source of truth for every server-side avatar check. Keeping two
 * separate copies of this list is what allowed the invite screen to offer
 * avatars the server then refused with "Unsupported profile avatar".
 *
 * The Android design system renders 25 presets; the original six remain
 * accepted because installed clients and already-stored profiles use them.
 */
const LEGACY_AVATAR_PRESETS = ["sky", "mint", "sun", "coral", "lilac", "ocean"];

const DESIGN_SYSTEM_AVATAR_PRESETS = [
  "corgi",
  "dinosaur",
  "robot",
  "cactus",
  "penguin",
  "astronaut",
  "donut",
  "cat",
  "pizza",
  "unicorn",
  "monster",
  "mug",
  "avocado",
  "panda",
  "rocket",
  "alien",
  "shark",
  "burger",
  "chick",
  "frog",
  "llama",
  "sloth",
  "controller",
  "pineapple",
  "cloud",
];

const AVATAR_PRESETS = Object.freeze([
  ...LEGACY_AVATAR_PRESETS,
  ...DESIGN_SYSTEM_AVATAR_PRESETS,
]);

const AVATAR_PRESET_SET = new Set(AVATAR_PRESETS);

/** `preset:<name>` for any supported preset, and nothing else. */
const AVATAR_PATTERN = new RegExp(`^preset:(${AVATAR_PRESETS.join("|")})$`);

/**
 * A picture the person uploaded, as returned by the upload endpoint.
 *
 * The value is a path on this server, not an absolute address: storing the
 * server's own hostname in a profile would break every member's picture the day
 * the server moves, and would let a profile point at somebody else's website.
 * The shape is fixed — a directory, then thirty-two hexadecimal characters and a
 * known extension — so a stored value can never name a path outside the avatar
 * directory, and can never be something other than a picture this server wrote.
 */
const UPLOADED_AVATAR_PATTERN = /^\/avatars\/[a-f0-9]{32}\.(jpg|png|webp)$/;

/**
 * Strict check: a supported preset, or a picture this server stored.
 *
 * It deliberately does not trim. Callers that accept user input normalize it
 * first, so trimming here would let a padded value pass one check and be stored
 * in a different shape than another check expects.
 */
function isValidAvatarKey(value) {
  if (typeof value !== "string" || value === "") return false;
  if (UPLOADED_AVATAR_PATTERN.test(value)) return true;
  return AVATAR_PATTERN.test(value);
}

/** Whether the value is a picture the person uploaded rather than a preset. */
function isUploadedAvatar(value) {
  return typeof value === "string" && UPLOADED_AVATAR_PATTERN.test(value);
}

/**
 * A preset to show for a member who has not chosen one.
 *
 * Members created automatically — when a phone first connects, or when an
 * invitation carries no picture — were stored with no avatar at all, so those
 * people appeared as blank circles while everybody else had a picture. This gives
 * every member a face without asking anything of them.
 *
 * The choice is derived from the member id, not drawn at random: the same person
 * always receives the same avatar, so it stays put across restarts and re-seeds.
 * It is a default, not a decision — as soon as the person picks their own picture
 * this value is replaced and never consulted again.
 */
function defaultAvatarKeyFor(seed) {
  const text = typeof seed === "string" ? seed.trim() : "";
  if (!text) return `preset:${AVATAR_PRESETS[0]}`;
  // FNV-1a: small, stable, and spreads similar ids across the whole list.
  let hash = 0x811c9dc5;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return `preset:${AVATAR_PRESETS[hash % AVATAR_PRESETS.length]}`;
}

module.exports = {
  LEGACY_AVATAR_PRESETS,
  DESIGN_SYSTEM_AVATAR_PRESETS,
  AVATAR_PRESETS,
  AVATAR_PRESET_SET,
  AVATAR_PATTERN,
  UPLOADED_AVATAR_PATTERN,
  isValidAvatarKey,
  isUploadedAvatar,
  defaultAvatarKeyFor,
};
