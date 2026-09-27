#!/usr/bin/env node
/*
 * Checks group conversations against a real database file.
 *
 * The same checks live in `__tests__/chat-group-conversation.test.js`, and that is
 * where they belong: it is the suite that runs with everything else. This script
 * exists because the suite needs worker processes, and in some environments those
 * are not available - so the migration's most dangerous property, that it keeps
 * every conversation it found, can still be checked directly.
 *
 * Two separate temporary databases are used, and that is deliberate. The migration
 * check has to put the table back the way it was before groups existed, and a
 * database that already contains a group cannot be reshaped that way - the old
 * constraint refuses the row, which is exactly what the check is about.
 *
 * Run: node scripts/verify-group-conversations.js
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const DatabaseManager = require("../database/DatabaseManager");

const failures = [];

function check(description, condition, detail = "") {
  const verdict = condition ? "ok  " : "FAIL";
  console.log(`  [${verdict}] ${description}${detail ? ` - ${detail}` : ""}`);
  if (!condition) failures.push(description);
}

function makeTemporaryDatabase(prefix) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  return {
    file: path.join(directory, "childwatch.db"),
    cleanup: () => fs.rmSync(directory, { recursive: true, force: true }),
  };
}

async function registerFamily(db) {
  const parentOne = "verify-parent-0001";
  const parentTwo = "verify-parent-0002";
  const child = "verify-child-0001";

  await db.registerDevice(parentOne, {
    device_name: "Первый",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(parentTwo, {
    device_name: "Второй",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(child, {
    device_name: "Ребёнок",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentOne,
    childDeviceId: child,
    parentDisplayName: "Первый",
    childDisplayName: "Ребёнок",
    createdBy: "verify-group-conversations",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentTwo,
    childDeviceId: child,
    parentDisplayName: "Второй",
    childDisplayName: "Ребёнок",
    createdBy: "verify-group-conversations",
  });

  const [family] = await db.getFamiliesForDevice(child);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(
    devices.map((device) => [device.deviceId, device.memberId])
  );
  return { family, memberByDevice };
}

/** Rebuilds `chat_conversations` the way it looked before groups were allowed. */
async function restoreOldConversationSchema(db) {
  await db.run("PRAGMA foreign_keys = OFF");
  try {
    await db.run(
      "CREATE TABLE chat_conversations_old_shape AS SELECT * FROM chat_conversations"
    );
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
       FROM chat_conversations_old_shape`
    );
    await db.run("DROP TABLE chat_conversations_old_shape");
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

async function checkGroupsAndMembership() {
  console.log("\n1. a group holds exactly the members that were chosen");
  const temporary = makeTemporaryDatabase("cw-group-verify-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  try {
    // Only the manager's own startup chatter is muted, never this script's report.
    console.log = quiet;
    await db.initialize();
    console.log = realLog;
    const { family, memberByDevice } = await registerFamily(db);
    const first = memberByDevice.get("verify-parent-0001");
    const second = memberByDevice.get("verify-parent-0002");
    const third = memberByDevice.get("verify-child-0001");

    const group = await db.createGroupConversation({
      familyId: family.id,
      title: "Школа",
      memberIds: [first, second],
      createdByMemberId: first,
    });
    check("the conversation is a GROUP", group.type === "GROUP", group.type);
    check("the name is kept", group.title === "Школа", group.title);
    const members = await db.getGroupConversationMemberIds(group.id);
    check(
      "exactly two members, not the whole family",
      members.length === 2 && members.includes(first) && members.includes(second),
      `${members.length}`
    );
    check(
      "the creator administers it",
      (await db.getGroupAdminMemberId(group.id, first)) === first
    );

    console.log("\n2. refusals");
    let refusedSmall = false;
    try {
      await db.createGroupConversation({
        familyId: family.id,
        title: "Один",
        memberIds: [first],
        createdByMemberId: first,
      });
    } catch (error) {
      refusedSmall = /at least two members/.test(error.message);
    }
    check("a one-person group is refused", refusedSmall);

    let refusedStranger = false;
    try {
      await db.createGroupConversation({
        familyId: family.id,
        title: "Чужие",
        memberIds: [first, "member_other_family"],
        createdByMemberId: first,
      });
    } catch (error) {
      refusedStranger = /must belong to the family/.test(error.message);
    }
    check("a member of another family is refused", refusedStranger);

    const familyConversation = await db.ensureFamilyConversation(family.id);
    let refusedFamily = false;
    try {
      await db.addGroupConversationMembers(familyConversation.id, [third]);
    } catch (error) {
      refusedFamily = /chosen membership/.test(error.message);
    }
    check("the family chat refuses a chosen membership", refusedFamily);

    const direct = await db.createDirectConversation({
      familyId: family.id,
      memberIds: [first, second],
      createdByMemberId: first,
    });
    let refusedDirect = false;
    try {
      await db.addGroupConversationMembers(direct.id, [third]);
    } catch (error) {
      refusedDirect = /chosen membership/.test(error.message);
    }
    check("a direct chat refuses a chosen membership", refusedDirect);

    console.log("\n3. membership can change");
    check(
      "a third member is added",
      (await db.addGroupConversationMembers(group.id, [third])).length === 3
    );
    check(
      "adding twice does not duplicate a seat",
      (await db.addGroupConversationMembers(group.id, [third])).length === 3
    );
    const afterRemoval = await db.removeGroupConversationMember(group.id, second);
    check(
      "one member is taken out",
      afterRemoval.length === 2 && !afterRemoval.includes(second),
      `${afterRemoval.length}`
    );
    check(
      "the administrator is still the creator",
      (await db.getGroupAdminMemberId(group.id, first)) === first
    );
  } finally {
    console.log = realLog;
    await db.close();
    temporary.cleanup();
  }
}

async function checkMigration() {
  console.log("\n4. the migration on a database that already has conversations");
  const temporary = makeTemporaryDatabase("cw-group-migration-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  try {
    console.log = quiet;
    await db.initialize();
    console.log = realLog;
    const { family, memberByDevice } = await registerFamily(db);
    const first = memberByDevice.get("verify-parent-0001");
    const second = memberByDevice.get("verify-parent-0002");
    const familyConversation = await db.ensureFamilyConversation(family.id);

    await restoreOldConversationSchema(db);
    const before = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");

    let oldSchemaRefusedGroup = false;
    try {
      await db.run(
        `INSERT INTO chat_conversations (
           id, family_id, type, title, next_sequence, is_active, created_at, updated_at
         ) VALUES ('chat_conversation_probe', ?, 'GROUP', 'Проба', 0, 1, 1, 1)`,
        [family.id]
      );
    } catch (error) {
      oldSchemaRefusedGroup = /CHECK constraint failed/.test(error.message);
    }
    check(
      "the old schema really refuses a GROUP, so the migration is not decoration",
      oldSchemaRefusedGroup
    );

    console.log = quiet;
    let result;
    let migrationError = null;
    try {
      result = await db.ensureChatConversationGroupType();
    } catch (error) {
      migrationError = error;
    }
    console.log = realLog;
    check(
      "the migration completed",
      !migrationError,
      migrationError ? migrationError.message : ""
    );
    if (migrationError) return;

    check(
      "it moved every conversation",
      result.movedRows === Number(before.total),
      `${result.movedRows} of ${before.total}`
    );
    const after = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    check("no conversation was lost", Number(after.total) === Number(before.total));
    const restored = await db.getChatConversationById(familyConversation.id);
    check("the family conversation is still readable", restored?.type === "FAMILY");
    check(
      "its membership still resolves",
      Boolean(await db.getChatConversationForMember(familyConversation.id, first))
    );
    check(
      "no broken references were left behind",
      (await db.all("PRAGMA foreign_key_check")).length === 0
    );

    const group = await db.createGroupConversation({
      familyId: family.id,
      title: "После обновления",
      memberIds: [first, second],
      createdByMemberId: first,
    });
    check("a group can be created afterwards", group.type === "GROUP");

    console.log = quiet;
    const secondRun = await db.ensureChatConversationGroupType();
    console.log = realLog;
    check(
      "running the migration again does nothing",
      secondRun.skipped === "already applied",
      secondRun.skipped || ""
    );

    const finalCount = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    check(
      "the final count is the original plus the new group",
      Number(finalCount.total) === Number(before.total) + 1,
      `${finalCount.total}`
    );
  } finally {
    console.log = realLog;
    await db.close();
    temporary.cleanup();
  }
}

async function main() {
  // Each check mutes the manager's own startup chatter around itself; nothing here
  // may mute the report.
  await checkGroupsAndMembership();
  await checkMigration();

  console.log("");
  if (failures.length) {
    console.log(`FAILED: ${failures.length} check(s)`);
    failures.forEach((failure) => console.log(`  - ${failure}`));
    process.exitCode = 1;
    return;
  }
  console.log("All checks passed.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
