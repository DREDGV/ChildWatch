const catalog = require("../services/AvatarPresetCatalog");
const { detectImageType } = require("../routes/avatars");

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

  /**
   * A person may use their own photograph instead of a built-in picture. The
   * stored value is a path on this server, and it is shown to every member of the
   * family, so what it is allowed to be matters.
   */
  describe("a picture the person uploaded", () => {
    const validPath = `/avatars/${"a".repeat(32)}.jpg`;

    test("is accepted", () => {
      expect(catalog.isValidAvatarKey(validPath)).toBe(true);
      expect(catalog.isUploadedAvatar(validPath)).toBe(true);
      expect(catalog.isUploadedAvatar("preset:corgi")).toBe(false);
    });

    test("refuses a path that leaves the picture directory", () => {
      // A stored value must never be able to name a file outside /avatars.
      expect(catalog.isValidAvatarKey("/avatars/../../etc/passwd")).toBe(false);
      expect(catalog.isValidAvatarKey("/avatars/..%2f..%2fetc/passwd")).toBe(false);
      expect(catalog.isValidAvatarKey("/avatars/sub/dir/" + "a".repeat(32) + ".jpg")).toBe(false);
    });

    test("refuses an address on another server", () => {
      // Otherwise a profile could point every family member's device at a third
      // party's website, and the family would follow the server's move too.
      expect(catalog.isValidAvatarKey("https://example.invalid/x.png")).toBe(false);
      expect(catalog.isValidAvatarKey("//example.invalid/x.png")).toBe(false);
      expect(catalog.isValidAvatarKey("javascript:alert(1)")).toBe(false);
      expect(catalog.isValidAvatarKey("data:image/png;base64,AAAA")).toBe(false);
    });

    test("refuses a format that could carry script", () => {
      expect(catalog.isValidAvatarKey(`/avatars/${"a".repeat(32)}.svg`)).toBe(false);
      expect(catalog.isValidAvatarKey(`/avatars/${"a".repeat(32)}.html`)).toBe(false);
      expect(catalog.isValidAvatarKey(`/avatars/${"a".repeat(32)}.js`)).toBe(false);
    });

    test("refuses a name this server would never have written", () => {
      expect(catalog.isValidAvatarKey("/avatars/short.jpg")).toBe(false);
      expect(catalog.isValidAvatarKey(`/avatars/${"z".repeat(32)}.jpg`)).toBe(false);
      expect(catalog.isValidAvatarKey(`/avatars/${"a".repeat(31)}.jpg`)).toBe(false);
    });
  });

  /**
   * The uploader states the format; these bytes are the file itself. A file that
   * claims to be a picture but is not must not be stored and served back to the
   * whole family.
   */
  describe("recognising a picture by its content", () => {
    test("recognises the accepted formats", () => {
      const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0]);
      const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
      const webp = Buffer.concat([
        Buffer.from("RIFF", "ascii"),
        Buffer.from([0, 0, 0, 0]),
        Buffer.from("WEBP", "ascii"),
      ]);
      expect(detectImageType(jpeg)).toBe("image/jpeg");
      expect(detectImageType(png)).toBe("image/png");
      expect(detectImageType(webp)).toBe("image/webp");
    });

    test("refuses a file that only claims to be a picture", () => {
      // An executable, a script and an SVG all start with text, and none is an
      // image however the upload is labelled.
      const script = Buffer.from("<?php system($_GET['c']); ?>", "utf8");
      const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"></svg>', "utf8");
      const empty = Buffer.alloc(0);
      expect(detectImageType(script)).toBeNull();
      expect(detectImageType(svg)).toBeNull();
      expect(detectImageType(empty)).toBeNull();
      expect(detectImageType(null)).toBeNull();
    });

    test("refuses a file too short to be any picture", () => {
      expect(detectImageType(Buffer.from([0xff, 0xd8, 0xff]))).toBeNull();
    });
  });
});
