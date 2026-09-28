/*
 * Builds a database that reproduces the live orphan problem locally.
 *
 * The live copy could not be downloaded (port 22 is blocked from this network), so the
 * situation is reproduced instead of guessed at: a family is registered through the
 * same helpers a test uses, members and a message exist, and then two family_members
 * rows are removed with raw SQL while foreign-key enforcement is off — which is what
 * the hand-run family tidy-up on the live server did. The chat seats of those people
 * are left behind, exactly as they are in the live database.
 *
 * Run: node server/scripts/reproduce-orphan-chat-members.js <output.db>
 */

const fs = require("fs");
const path = require("path");
const sqlite3 = require("sqlite3");
const DatabaseManager = require("../database/DatabaseManager");

function runRaw(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.run(sql, params, function onDone(error) {
      if (error) reject(error);
      else resolve(Number(this.changes) || 0);
    });
  });
}

function allRaw(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.all(sql, params, (error, rows) => (error ? reject(error) : resolve(rows || [])));
  });
}

function openRaw(file) {
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(file, (error) => (error ? reject(error) : resolve(db)));
  });
}

function closeRaw(db) {
  return new Promise((resolve) => db.close(() => resolve()));
}

/**
 * Registers one family through the production helpers, the way
 * `server/__tests__/chat-group-settings.test.js` does.
 */
async function registerFamily(db, suffix) {
  const firstParentDeviceId = `parent-local-${suffix}-0001`;
  const childDeviceId = `child-local-${suffix}-0001`;
  const secondChildDeviceId = `child-local-${suffix}-0002`;

  for (const [deviceId, name] of [
    [firstParentDeviceId, `Local parent ${suffix}`],
    [childDeviceId, `Local child ${suffix}`],
    [secondChildDeviceId, `Local second child ${suffix}`],
  ]) {
    await db.registerDevice(deviceId, {
      device_name: name,
      device_type: "android",
      app_version: "8.0.0",
    });
  }

  for (const child of [childDeviceId, secondChildDeviceId]) {
    await db.upsertDeviceLink({
      parentDeviceId: firstParentDeviceId,
      childDeviceId: child,
      parentDisplayName: `Local parent ${suffix}`,
      childDisplayName: `Local child ${suffix}`,
      createdBy: "orphan-reproduction",
    });
  }

  const [family] = await db.getFamiliesForDevice(childDeviceId);
  const conversation = await db.ensureFamilyConversation(family.id);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(devices.map((device) => [device.deviceId, device.memberId]));
  return { family, conversation, firstParentDeviceId, childDeviceId, secondChildDeviceId, memberByDevice };
}

async function main() {
  const output = process.argv[2];
  if (!output) {
    console.error("usage: node server/scripts/reproduce-orphan-chat-members.js <output.db>");
    process.exitCode = 2;
    return;
  }
  const resolved = path.resolve(output);
  if (fs.existsSync(resolved)) fs.unlinkSync(resolved);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });

  const db = new DatabaseManager(resolved);
  await db.initialize();

  const { family, conversation, firstParentDeviceId, childDeviceId, memberByDevice } =
    await registerFamily(db, "a");
  const second = await registerFamily(db, "b");

  const parentMemberId = memberByDevice.get(firstParentDeviceId);
  const childMemberId = memberByDevice.get(childDeviceId);

  // A message, so the test database also proves that messages survive the cleanup.
  await db.insertChatMessageV2({
    conversationId: conversation.id,
    senderMemberId: parentMemberId,
    clientMessageId: "repro-message-1",
    text: "Сообщение из воспроизведения",
    serverCreatedAt: Date.now(),
  });
  // The second family needs its own author: a member of one family cannot write in
  // another family's conversation.
  const secondSenderMemberId = second.memberByDevice.get(second.firstParentDeviceId);
  await db.insertChatMessageV2({
    conversationId: second.conversation.id,
    senderMemberId: secondSenderMemberId,
    clientMessageId: "repro-message-2",
    text: "Второе сообщение",
    serverCreatedAt: Date.now(),
  });

  await db.close();

  // The tidy-up that caused this on the live server: people removed with raw SQL,
  // their chat seats left alone. Foreign-key enforcement is switched off for the
  // deletion because the live tidy-up ran outside the application's own connection.
  const raw = await openRaw(resolved);
  await runRaw(raw, "PRAGMA foreign_keys = OFF");
  const removedMembers = await runRaw(
    raw,
    "DELETE FROM family_members WHERE id IN (?, ?)",
    [parentMemberId, childMemberId]
  );
  const orphanSeats = await allRaw(
    raw,
    `SELECT COUNT(*) AS total
     FROM chat_conversation_members cm
     LEFT JOIN family_members fm ON fm.id = cm.member_id
     WHERE fm.id IS NULL`
  );
  const integrity = await allRaw(raw, "PRAGMA integrity_check");
  const violations = await allRaw(raw, "PRAGMA foreign_key_check");
  await runRaw(raw, "PRAGMA foreign_keys = ON");
  await closeRaw(raw);

  console.log(`created: ${resolved}`);
  console.log(`family: ${family.id}`);
  console.log(`conversation: ${conversation.id}`);
  console.log(`family_members rows deleted: ${removedMembers}`);
  console.log(`chat seats left behind: ${Number(orphanSeats[0]?.total) || 0}`);
  console.log(`PRAGMA integrity_check: ${integrity[0]?.integrity_check}`);
  console.log(`PRAGMA foreign_key_check: ${violations.length} violation(s)`);
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
