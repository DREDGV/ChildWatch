const catalog = require("../services/AvatarPresetCatalog");

/**
 * The invite screen offers every preset the Android design system can render.
 * The server previously accepted only the original six, so choosing any of the
 * newer avatars failed with "Unsupported profile avatar" and the invitation
 * could not be created at all. These tests pin the complete list and the
 * strictness of the check.
 */
describe("portable avatar catalog", () => {
  const designSystemPresets = [
    "corgi", "dinosaur", "robot", "cactus", "penguin",
    "astronaut", "donut", "cat", "pizza", "unicorn",
    "monster", "mug", "avocado", "panda", "rocket",
    "alien", "shark", "burger", "chick", "frog",
    "llama", "sloth", "controller", "pineapple", "cloud",
  ];
  const legacyPresets = ["sky", "mint", "sun", "coral", "lilac", "ocean"];

  test("accepts every preset the client can render", () => {
    for (const name of [...designSystemPresets, ...legacyPresets]) {
      expect(catalog.isValidAvatarKey(`preset:${name}`)).toBe(true);
    }
  });

  test("keeps the legacy presets for installed clients", () => {
    for (const name of legacyPresets) {
      expect(catalog.AVATAR_PRESET_SET.has(name)).toBe(true);
    }
  });

  test("refuses anything that is not an exact preset key", () => {
    const invalid = [
      "preset:",
      "preset:fox",
      "preset:corgi ",
      "preset: corgi",
      "corgi",
      "content://local/photo",
      "http://example.com/a.png",
      "",
      null,
      undefined,
      42,
      {},
    ];
    for (const value of invalid) {
      expect(catalog.isValidAvatarKey(value)).toBe(false);
    }
  });

  test("the pattern is anchored on both ends", () => {
    expect(catalog.AVATAR_PATTERN.source.startsWith("^")).toBe(true);
    expect(catalog.AVATAR_PATTERN.source.endsWith("$")).toBe(true);
    // A value that merely contains a valid key must not pass.
    expect(catalog.isValidAvatarKey("prefix preset:corgi")).toBe(false);
    expect(catalog.isValidAvatarKey("preset:corgi extra")).toBe(false);
  });

  test("both server entry points share this one list", () => {
    // families.js and FamilyOnboardingService must not keep private copies.
    const fs = require("fs");
    const path = require("path");
    const familiesSource = fs.readFileSync(
      path.join(__dirname, "..", "routes", "families.js"),
      "utf8"
    );
    const onboardingSource = fs.readFileSync(
      path.join(__dirname, "..", "services", "FamilyOnboardingService.js"),
      "utf8"
    );
    expect(familiesSource).toContain('require("../services/AvatarPresetCatalog")');
    expect(onboardingSource).toContain('require("./AvatarPresetCatalog")');
    // Neither file may hardcode its own preset list again.
    expect(familiesSource).not.toMatch(/preset:\(sky\|mint/);
    expect(onboardingSource).not.toMatch(/preset:\(sky\|mint/);
  });

  /**
   * Members created automatically used to be stored with no picture at all, so
   * those people appeared as empty circles while everybody else had an avatar.
   * A default is now derived from the member id.
   */
  describe("default avatar for a member who has not chosen one", () => {
    test("always returns a preset the server accepts", () => {
      const ids = [
        "member_1786b0036d3273f1e7265ace",
        "member_abcafe9bf38e55a45c2259f0",
        "member_eec972d2697bcf17190ba3c0",
        "member_5bcff583c11d7e305ccac877",
      ];
      for (const id of ids) {
        const key = catalog.defaultAvatarKeyFor(id);
        expect(catalog.isValidAvatarKey(key)).toBe(true);
      }
    });

    test("gives the same person the same avatar every time", () => {
      // A value drawn at random would change on every restart and re-seed, which
      // would make faces flicker between people.
      const id = "member_6f1d7a4e4a275b480dc71ef7";
      expect(catalog.defaultAvatarKeyFor(id)).toBe(catalog.defaultAvatarKeyFor(id));
    });

    test("spreads different people across different avatars", () => {
      const keys = new Set();
      for (let index = 0; index < 60; index += 1) {
        keys.add(catalog.defaultAvatarKeyFor(`member_seed_${index}`));
      }
      // Sixty people must not all look alike; a handful of presets is too few.
      expect(keys.size).toBeGreaterThan(5);
    });

    test("still answers when there is nothing to derive from", () => {
      const key = catalog.defaultAvatarKeyFor("");
      expect(catalog.isValidAvatarKey(key)).toBe(true);
      expect(catalog.isValidAvatarKey(catalog.defaultAvatarKeyFor(null))).toBe(true);
    });
  });
});
