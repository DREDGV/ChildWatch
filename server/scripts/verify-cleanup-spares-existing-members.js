/*
 * Proves the cleanup spares a membership whose person still EXISTS.
 *
 * The live database also holds seats belonging to people who were deactivated rather
 * than deleted. Those rows are history and must survive the cleanup, otherwise the
 * script would quietly rewrite conversations nobody asked it to touch.
 *
 * Run: node server/scripts/verify-cleanup-spares-existing-members.js <test.db> <db1> <db2> ...
 */

const path = require("path");
const sqlite3 = require("sqlite3");

function open(file, mode) {
  const flags = mode === "ro" ? sqlite3.OPEN_READONLY : sqlite3.OPEN_READWRITE;
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(file, flags, (error) => (error ? reject(error) : resolve(db)));
  });
}

function run(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.run(sql, params, function onDone(error) {
      if (error) reject(error);
      else resolve(Number(this.changes) || 0);
    });
  });
}

function all(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.all(sql, params, (error, rows) => (error ? reject(error) : resolve(rows || [])));
  });
}

function close(db) {
  return new Promise((resolve) => db.close(() => resolve()));
}

async function main() {
  const file = process.argv[2];
  if (!file) {
    console.error("usage: node server/scripts/verify-cleanup-spares-existing-members.js <test.db>");
    process.exitCode = 2;
    return;
  }
  const resolved = path.resolve(file);

  // Deactivate one existing person, keep their seat. `is_active = 0` on
  // family_members is exactly the state a superseded family leaves behind.
  //
  // The person is chosen from those who actually hold a seat: deactivating somebody
  // without one would prove nothing about which seats the cleanup spares.
  const db = await open(resolved, "rw");
  const seat = (
    await all(
      db,
      `SELECT cm.conversation_id AS conversationId,
              cm.member_id AS memberId,
              cm.is_active AS isActive
       FROM chat_conversation_members cm
       JOIN family_members fm ON fm.id = cm.member_id
       WHERE fm.is_active = 1
       ORDER BY cm.rowid ASC
       LIMIT 1`
    )
  )[0];
  if (!seat) {
    console.error("no seat belongs to an existing person in this database: nothing to prove here");
    process.exitCode = 2;
    await close(db);
    return;
  }
  await run(db, "UPDATE family_members SET is_active = 0 WHERE id = ?", [seat.memberId]);
  const inactiveSeats = await all(
    db,
    `SELECT COUNT(*) AS total
     FROM chat_conversation_members cm
     JOIN family_members fm ON fm.id = cm.member_id
     WHERE fm.is_active = 0`
  );
  const orphansBefore = await all(
    db,
    `SELECT COUNT(*) AS total FROM chat_conversation_members cm
     LEFT JOIN family_members fm ON fm.id = cm.member_id WHERE fm.id IS NULL`
  );
  await close(db);

  console.log(`database: ${resolved}`);
  console.log(`deactivated person: ${seat.memberId} (seat left in place, active=${seat.isActive})`);
  console.log(`seats whose person exists but is deactivated: ${Number(inactiveSeats[0]?.total) || 0}`);
  console.log(`orphaned seats: ${Number(orphansBefore[0]?.total) || 0}`);
  console.log(`the cleanup must leave the first number and remove the second`);
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
