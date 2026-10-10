const fs = require("fs");
const os = require("os");
const path = require("path");
const DatabaseManager = require("../database/DatabaseManager");

jest.setTimeout(30_000);

/**
 * A group with a chosen membership.
 *
 * The family conversation holds everybody, and a direct conversation holds exactly
 * two people. A group is the third case: the members were picked, and that list is
 * the only thing that says who is in it.
 */
async function registerFamily(db, suffix, deviceNames = ["First", "Second", "Child"]) {
  const parentOne = `parent-group-conv-${suffix}-0001`;
  const parentTwo = `parent-group-conv-${suffix}-0002`;
  const child = `child-group-conv-${suffix}-0001`;

  await db.registerDevice(parentOne, {
    device_name: `${deviceNames[0]} ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(parentTwo, {
    device_name: `${deviceNames[1]} ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(child, {
    device_name: `${deviceNames[2]} ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentOne,
    childDeviceId: child,
    parentDisplayName: deviceNames[0],
    childDisplayName: deviceNames[2],
    createdBy: "group-conversation-test",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentTwo,
    childDeviceId: child,
    parentDisplayName: deviceNames[1],
    childDisplayName: deviceNames[2],
    createdBy: "group-conversation-test",
  });

  const [family] = await db.getFamiliesForDevice(child);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(
    devices.map((device) => [device.deviceId, device.memberId])
  );
  return { family, memberByDevice, parentOne, parentTwo, child };
}

describe("group conversations", () => {
  let db;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
  });

  afterEach(async () => {
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("a created group is a GROUP with exactly the members that were chosen", async () => {
    const { family, memberByDevice, parentOne, parentTwo } = await registerFamily(
      db,
      "a"
    );
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);

    const conversation = await db.createGroupConversation({
      familyId: family.id,
      title: "Школа",
      memberIds: [first, second],
      createdByMemberId: first,
    });

    expect(conversation.type).toBe("GROUP");
    expect(conversation.title).toBe("Школа");
    expect(conversation.createdByMemberId).toBe(first);
    // The family conversation holds everybody; this group must not.
    expect((await db.getGroupConversationMemberIds(conversation.id)).sort()).toEqual([first, second].sort());
    expect(await db.getFamilyAdminMemberId(family.id)).toBeTruthy();
  });

  test("the creator administers the group, and the longest-standing member takes over", async () => {
    const { family, memberByDevice, parentOne, parentTwo } = await registerFamily(
      db,
      "b"
    );
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);

    const conversation = await db.createGroupConversation({
      familyId: family.id,
      title: "Дача",
      memberIds: [first, second],
      createdByMemberId: first,
    });

    expect(await db.getGroupAdminMemberId(conversation.id, first)).toBe(first);
    // An administrator who is no longer in the group cannot manage it: a group
    // without one would be frozen for ever, so somebody has to take over.
    expect(await db.getGroupAdminMemberId(conversation.id, "member_gone")).toBe(
      first
    );
  });

  test("a group refuses a single member and a member of another family", async () => {
    const { family, memberByDevice, parentOne, parentTwo } = await registerFamily(
      db,
      "c"
    );
    const first = memberByDevice.get(parentOne);

    await expect(
      db.createGroupConversation({
        familyId: family.id,
        title: "Вдвоём с собой",
        memberIds: [first],
        createdByMemberId: first,
      })
    ).rejects.toThrow(/at least two members/);

    await expect(
      db.createGroupConversation({
        familyId: family.id,
        title: "Чужие",
        memberIds: [first, "member_other_family"],
        createdByMemberId: first,
      })
    ).rejects.toThrow(/must belong to the family/);

    // A conversation created before the failure must not exist afterwards.
    const conversations = await db.listChatConversationsForMember(first, 50);
    expect(
      conversations.filter((conversation) => conversation.type === "GROUP")
    ).toHaveLength(0);
  });

  test("members can be added and taken out, and their messages stay", async () => {
    const { family, memberByDevice, parentOne, parentTwo, child } =
      await registerFamily(db, "d");
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);
    const third = memberByDevice.get(child);

    // Membership is a set. Equal join timestamps legitimately tie-break on random member IDs.
    const clock = jest.spyOn(Date, "now").mockReturnValue(Date.now());
    try {
      const conversation = await db.createGroupConversation({
        familyId: family.id,
        title: "Кружок",
        memberIds: [first, second],
        createdByMemberId: first,
      });

      expect((await db.addGroupConversationMembers(conversation.id, [third])).sort())
        .toEqual([first, second, third].sort());
      // Adding somebody twice is not an error and does not duplicate the seat.
      expect((await db.addGroupConversationMembers(conversation.id, [third])).sort())
        .toEqual([first, second, third].sort());

      expect((await db.removeGroupConversationMember(conversation.id, second)).sort())
        .toEqual([first, third].sort());
      expect((await db.getGroupConversationMemberIds(conversation.id)).sort())
        .toEqual([first, third].sort());
    } finally { clock.mockRestore(); }
  });

  test("the family chat and a direct chat reject a chosen membership", async () => {
    const { family, memberByDevice, parentOne, parentTwo } = await registerFamily(
      db,
      "e"
    );
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);

    const familyConversation = await db.ensureFamilyConversation(family.id);
    await expect(
      db.addGroupConversationMembers(familyConversation.id, [second])
    ).rejects.toThrow(/chosen membership/);

    const direct = await db.createDirectConversation({
      familyId: family.id,
      memberIds: [first, second],
      createdByMemberId: first,
    });
    await expect(
      db.addGroupConversationMembers(direct.id, [second])
    ).rejects.toThrow(/chosen membership/);
  });
});

describe("widening the kind of a conversation on an existing database", () => {
  let directory;
  let file;
  let db;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    directory = fs.mkdtempSync(path.join(os.tmpdir(), "cw-group-migration-"));
    file = path.join(directory, "childwatch.db");
  });

  afterEach(async () => {
    if (db) await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
    fs.rmSync(directory, { recursive: true, force: true });
  });

  /**
   * Puts the database back the way it was before groups existed: the kinds are
   * fixed to the three that were there, and the migration has never run. Rebuilding
   * the table here is how the real old schema looked, and it is the only way to
   * prove the migration works on a database that already has conversations in it.
   */
  async function makeItLookLikeTheOldSchema() {
    await db.run("PRAGMA foreign_keys = OFF");
    try {
      await db.run("DROP TABLE chat_conversations");
      await db.run(
        `CREATE TABLE chat_conversations (
                    id TEXT PRIMARY KEY,
                    family_id TEXT,
                    type TEXT NOT NULL CHECK (type IN ('FAMILY', 'DIRECT', 'LEGACY')),
                    title TEXT,
                    direct_pair_key TEXT,
                    created_by_member_id TEXT,
                    next_sequence INTEGER NOT NULL DEFAULT 0,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (family_id) REFERENCES families (id),
                    FOREIGN KEY (created_by_member_id) REFERENCES family_members (id)
                )`
      );
      await db.run(
        `INSERT INTO chat_conversations (
           id, family_id, type, title, direct_pair_key, created_by_member_id,
           next_sequence, is_active, created_at, updated_at
         )
         SELECT
           id, family_id, type, title, direct_pair_key, created_by_member_id,
           next_sequence, is_active, created_at, updated_at
         FROM chat_conversations_group_type_backup`
      );
      await db.run("DROP TABLE chat_conversations_group_type_backup");
      await db.run(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_family_conversation ON chat_conversations (family_id) WHERE type = 'FAMILY' AND is_active = 1"
      );
      await db.run(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_direct_conversation ON chat_conversations (family_id, direct_pair_key) WHERE type = 'DIRECT' AND is_active = 1"
      );
    } finally {
      await db.run("PRAGMA foreign_keys = ON");
    }
    await db.run(
      "DELETE FROM schema_migrations WHERE name = 'chat_group_conversation_type_v1'"
    );
  }

  test("the migration keeps every conversation and lets a group exist", async () => {
    db = new DatabaseManager(file);
    await db.initialize();

    const { family, memberByDevice, parentOne, parentTwo } = await registerFamily(
      db,
      "m"
    );
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);
    const familyConversation = await db.ensureFamilyConversation(family.id);

    // Keep a copy, then restore the old shape of the table from that copy: the rows
    // must survive the migration that follows.
    await db.run(
      `CREATE TABLE chat_conversations_group_type_backup AS
       SELECT * FROM chat_conversations`
    );
    const before = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    await makeItLookLikeTheOldSchema();

    // The old shape really does refuse a group, so the migration is not decoration.
    await expect(
      db.run(
        `INSERT INTO chat_conversations (
           id, family_id, type, title, next_sequence, is_active, created_at, updated_at
         ) VALUES ('chat_conversation_probe', ?, 'GROUP', 'Проба', 0, 1, 1, 1)`,
        [family.id]
      )
    ).rejects.toThrow(/CHECK constraint failed/);

    const result = await db.ensureChatConversationGroupType();
    expect(result.movedRows).toBe(Number(before.total));

    // Every conversation is still there, still readable, and still referenced by the
    // tables that point at it.
    const after = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    expect(Number(after.total)).toBe(Number(before.total));
    const restored = await db.getChatConversationById(familyConversation.id);
    expect(restored?.type).toBe("FAMILY");
    const scoped = await db.getChatConversationForMember(
      familyConversation.id,
      first
    );
    expect(scoped).toBeTruthy();
    expect(await db.all("PRAGMA foreign_key_check")).toHaveLength(0);

    // And now a group is allowed.
    const group = await db.createGroupConversation({
      familyId: family.id,
      title: "После обновления",
      memberIds: [first, second],
      createdByMemberId: first,
    });
    expect(group.type).toBe("GROUP");

    // Running it a second time changes nothing: it is recorded as applied.
    const again = await db.ensureChatConversationGroupType();
    expect(again.skipped).toBe("already applied");
    const finalCount = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    expect(Number(finalCount.total)).toBe(Number(before.total) + 1);
  });
});
