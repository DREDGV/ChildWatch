/*
 * Builds a second test database whose ONLY problem is the orphaned chat seats.
 *
 * The first reproduction leaves other broken references behind on purpose (they are
 * what a real tidy-up leaves), which is useful for proving the cleanup reports them
 * honestly. This one starts from a clean family database and adds nothing but the
 * orphans, so the same cleanup can be shown ending with a database that reports no
 * foreign-key violations at all.
 *
 * Run: node server/scripts/reproduce-orphan-chat-members-clean.js <output.db>
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

async function main() {
  const output = process.argv[2];
  if (!output) {
    console.error("usage: node server/scripts/reproduce-orphan-chat-members-clean.js <output.db>");
    process.exitCode = 2;
    return;
  }
  const resolved = path.resolve(output);
  if (fs.existsSync(resolved)) fs.unlinkSync(resolved);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });

  const db = new DatabaseManager(resolved);
  await db.initialize();

  const parentDeviceId = "parent-clean-0001";
  const childDeviceId = "child-clean-0001";
  for (const [deviceId, name] of [
    [parentDeviceId, "Clean parent"],
    [childDeviceId, "Clean child"],
  ]) {
    await db.registerDevice(deviceId, {
      device_name: name,
      device_type: "android",
      app_version: "8.0.0",
    });
  }
  await db.upsertDeviceLink({
    parentDeviceId,
    childDeviceId,
    parentDisplayName: "Clean parent",
    childDisplayName: "Clean child",
    createdBy: "orphan-reproduction",
  });

  const [family] = await db.getFamiliesForDevice(childDeviceId);
  const conversation = await db.ensureFamilyConversation(family.id);
  const devices = await db.getFamilyDevices(family.id);
  const parentMemberId = devices.find((device) => device.deviceId === parentDeviceId).memberId;

  await db.insertChatMessageV2({
    conversationId: conversation.id,
    senderMemberId: parentMemberId,
    clientMessageId: "clean-message-1",
    text: "Сообщение остаётся",
    serverCreatedAt: Date.now(),
  });
  await db.close();

  // Only the seats are added here: the people they name never existed in this
  // database, so no other table references them and the only violation is the seat.
  const raw = await openRaw(resolved);
  const now = Date.now();
  const phantomSeats = [
    `member_${"a".repeat(24)}`,
    `member_${"b".repeat(24)}`,
    `member_${"c".repeat(24)}`,
  ];
  for (const memberId of phantomSeats) {
    await runRaw(
      raw,
      `INSERT INTO chat_conversation_members (
         conversation_id, member_id, is_active, joined_at, left_at, created_at, updated_at
       ) VALUES (?, ?, 1, ?, NULL, ?, ?)`,
      [conversation.id, memberId, now, now, now]
    );
  }
  const seats = await allRaw(
    raw,
    `SELECT COUNT(*) AS total FROM chat_conversation_members cm
     LEFT JOIN family_members fm ON fm.id = cm.member_id
     WHERE fm.id IS NULL`
  );
  const integrity = await allRaw(raw, "PRAGMA integrity_check");
  const violations = await allRaw(raw, "PRAGMA foreign_key_check");
  await closeRaw(raw);

  console.log(`created: ${resolved}`);
  console.log(`family: ${family.id}`);
  console.log(`conversation: ${conversation.id}`);
  console.log(`phantom seats added: ${Number(seats[0]?.total) || 0}`);
  console.log(`PRAGMA integrity_check: ${integrity[0]?.integrity_check}`);
  console.log(`PRAGMA foreign_key_check before cleanup: ${violations.length} violation(s)`);
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
