const DatabaseManager = require("../database/DatabaseManager");

jest.setTimeout(30_000);

describe("large legacy database startup", () => {
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

  test("does not rebuild 2,099 links that already have a valid family projection", async () => {
    const familyId = "family-large-startup";
    const parentDeviceId = "parent-large-startup";
    const parentMemberId = "member-parent-large-startup";
    const childCount = 2_099;
    const legacyMessageCount = 205;

    await db.withTransaction(async () => {
      await db.run(
        `INSERT INTO families (id, name, is_active)
         VALUES (?, 'Large family', 1)`,
        [familyId]
      );
      await db.run(
        `INSERT INTO devices (
           device_id, device_name, device_type, app_version, is_active
         ) VALUES (?, 'Parent', 'android', '7.3.0', 1)`,
        [parentDeviceId]
      );
      await db.run(
        `WITH RECURSIVE sequence(value) AS (
           SELECT 1
           UNION ALL
           SELECT value + 1 FROM sequence WHERE value < ?
         )
         INSERT INTO devices (
           device_id, device_name, device_type, app_version, is_active
         )
         SELECT
           'child-large-' || value,
           'Child ' || value,
           'android',
           '7.3.0',
           1
         FROM sequence`,
        [childCount]
      );
      await db.run(
        `INSERT INTO family_members (
           id, family_id, display_name, role, is_active
         ) VALUES (?, ?, 'Parent', 'PARENT', 1)`,
        [parentMemberId, familyId]
      );
      await db.run(
        `INSERT INTO family_devices (
           id, family_id, member_id, device_id, display_name,
           member_binding_source, is_active
         ) VALUES (?, ?, ?, ?, 'Parent', 'LEGACY_BOOTSTRAP', 1)`,
        ["family-device-parent-large", familyId, parentMemberId, parentDeviceId]
      );
      await db.run(
        `WITH RECURSIVE sequence(value) AS (
           SELECT 1
           UNION ALL
           SELECT value + 1 FROM sequence WHERE value < ?
         )
         INSERT INTO family_members (
           id, family_id, display_name, role, is_active
         )
         SELECT
           'member-child-' || value,
           ?,
           'Child ' || value,
           'CHILD',
           1
         FROM sequence`,
        [childCount, familyId]
      );
      await db.run(
        `WITH RECURSIVE sequence(value) AS (
           SELECT 1
           UNION ALL
           SELECT value + 1 FROM sequence WHERE value < ?
         )
         INSERT INTO family_devices (
           id, family_id, member_id, device_id, display_name,
           member_binding_source, is_active
         )
         SELECT
           'family-device-child-' || value,
           ?,
           'member-child-' || value,
           'child-large-' || value,
           'Child ' || value,
           'LEGACY_BOOTSTRAP',
           1
         FROM sequence`,
        [childCount, familyId]
      );
      await db.run(
        `WITH RECURSIVE sequence(value) AS (
           SELECT 1
           UNION ALL
           SELECT value + 1 FROM sequence WHERE value < ?
         )
         INSERT INTO device_links (
           parent_device_id, child_device_id, is_active
         )
         SELECT ?, 'child-large-' || value, 1
         FROM sequence`,
        [childCount, parentDeviceId]
      );
    });

    const rebuildSpy = jest.spyOn(db, "performFamilyBootstrap");
    const bootstrapStartedAt = Date.now();
    const bootstrap = await db.bootstrapFamiliesFromDeviceLinks();
    const bootstrapDurationMs = Date.now() - bootstrapStartedAt;

    expect(bootstrap).toMatchObject({
      skipped: true,
      activeLinks: childCount,
    });
    expect(rebuildSpy).not.toHaveBeenCalled();
    expect(bootstrapDurationMs).toBeLessThan(5_000);

    const conversationStartedAt = Date.now();
    const conversation = await db.ensureFamilyConversation(familyId);
    const conversationDurationMs = Date.now() - conversationStartedAt;
    const membership = await db.get(
      `SELECT COUNT(*) AS count
       FROM chat_conversation_members
       WHERE conversation_id = ? AND is_active = 1`,
      [conversation.id]
    );

    expect(membership.count).toBe(childCount + 1);
    expect(conversationDurationMs).toBeLessThan(5_000);

    await db.run(
      `WITH RECURSIVE sequence(value) AS (
         SELECT 1
         UNION ALL
         SELECT value + 1 FROM sequence WHERE value < ?
       )
       INSERT INTO chat_messages_v2 (
         id,
         conversation_id,
         sequence,
         sender_member_id,
         sender_display_name_snapshot,
         client_message_id,
         text,
         server_created_at,
         legacy_message_id,
         created_at
       )
       SELECT
         'legacy-pathological-message-' || value,
         ?,
         value,
         ?,
         'Parent',
         'legacy-pathological-client-' || value,
         'Legacy text ' || value,
         ?,
         value,
         ?
       FROM sequence`,
      [
        legacyMessageCount,
        conversation.id,
        parentMemberId,
        Date.now(),
        Date.now(),
      ]
    );
    await db.run(
      `INSERT INTO chat_message_receipts (
         message_id, recipient_member_id, created_at, updated_at
       )
       SELECT message.id, member.member_id, ?, ?
       FROM chat_messages_v2 message
       JOIN chat_conversation_members member
         ON member.conversation_id = message.conversation_id
       WHERE message.conversation_id = ?
         AND message.legacy_message_id IS NOT NULL
         AND member.member_id <> ?`,
      [Date.now(), Date.now(), conversation.id, parentMemberId]
    );
    await db.run(
      `DELETE FROM schema_migrations
       WHERE name = 'chat_legacy_receipt_cleanup_v1'`
    );

    const cleanupStartedAt = Date.now();
    await expect(db.cleanupPathologicalLegacyReceipts()).resolves.toBe(
      childCount * legacyMessageCount
    );
    expect(Date.now() - cleanupStartedAt).toBeLessThan(10_000);
    await expect(
      db.get(
        `SELECT COUNT(*) AS count
         FROM chat_message_receipts
         WHERE message_id LIKE 'legacy-pathological-message-%'`
      )
    ).resolves.toEqual({ count: 0 });
    rebuildSpy.mockRestore();
  });
});
