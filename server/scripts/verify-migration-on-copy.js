#!/usr/bin/env node
/*
 * Rehearses the migrations on a copy of the server's own database.
 *
 * Opening the copy runs everything the server would run on its next start, which is
 * the point: a migration that is only ever tried on an empty database has not been
 * tried at all. The file passed in is modified, so it must be a copy - the script
 * refuses to touch anything that looks like the live path.
 *
 * Run: node scripts/verify-migration-on-copy.js <path-to-copy.db>
 */

const fs = require("fs");
const sqlite3 = require("sqlite3");
const DatabaseManager = require("../database/DatabaseManager");

const failures = [];

function check(description, condition, detail = "") {
  const verdict = condition ? "ok  " : "FAIL";
  console.log(`  [${verdict}] ${description}${detail ? ` - ${detail}` : ""}`);
  if (!condition) failures.push(description);
}

function openRaw(file, mode) {
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(file, mode, (error) =>
      error ? reject(error) : resolve(db)
    );
  });
}

function rawGet(db, sql) {
  return new Promise((resolve, reject) => {
    db.get(sql, (error, row) => (error ? reject(error) : resolve(row)));
  });
}

function rawAll(db, sql) {
  return new Promise((resolve, reject) => {
    db.all(sql, (error, rows) => (error ? reject(error) : resolve(rows)));
  });
}

function closeRaw(db) {
  return new Promise((resolve) => db.close(() => resolve()));
}

async function main() {
  const file = process.argv[2];
  if (!file) {
    console.error("usage: node scripts/verify-migration-on-copy.js <copy.db>");
    process.exitCode = 2;
    return;
  }
  if (!fs.existsSync(file)) {
    console.error(`no such file: ${file}`);
    process.exitCode = 2;
    return;
  }
  if (/\/var\/www\/|\/home\/adminuser\/childwatch\//.test(file)) {
    console.error(
      `refusing to run against ${file}: this script modifies the database it is given`
    );
    process.exitCode = 2;
    return;
  }

  console.log(`copy under test: ${file}`);
  console.log(`size: ${(fs.statSync(file).size / 1024 / 1024).toFixed(1)} MB`);

  const raw = await openRaw(file, sqlite3.OPEN_READONLY);
  const before = {
    conversations: Number(
      (await rawGet(raw, "SELECT COUNT(*) AS total FROM chat_conversations"))?.total
    ),
    messages: Number(
      (await rawGet(raw, "SELECT COUNT(*) AS total FROM chat_messages_v2"))?.total
    ),
    members: Number(
      (await rawGet(raw, "SELECT COUNT(*) AS total FROM chat_conversation_members"))
        ?.total
    ),
    families: Number(
      (await rawGet(raw, "SELECT COUNT(*) AS total FROM families"))?.total
    ),
    devices: Number(
      (await rawGet(raw, "SELECT COUNT(*) AS total FROM devices"))?.total
    ),
  };
  const tableBefore = (
    await rawGet(
      raw,
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chat_conversations'"
    )
  )?.sql;
  const integrityBefore = await rawGet(raw, "PRAGMA integrity_check");
  const brokenBefore = (await rawAll(raw, "PRAGMA foreign_key_check")).map(
    (violation) => `${violation.table}|${violation.rowid}|${violation.parent}|${violation.fkid}`
  );
  await closeRaw(raw);

  console.log("\nbefore:");
  console.log(`  conversations ${before.conversations}, messages ${before.messages}, members ${before.members}`);
  console.log(`  families ${before.families}, devices ${before.devices}`);
  console.log(`  broken references already present: ${brokenBefore.length}`);
  check(
    "the copy is readable and intact before anything runs",
    integrityBefore?.integrity_check === "ok",
    integrityBefore?.integrity_check
  );
  check(
    "the copy still has the old conversation kinds",
    !String(tableBefore || "").includes("'GROUP'")
  );

  console.log("\nopening the copy, which runs the migrations the server would run:");
  const started = Date.now();
  const db = new DatabaseManager(file);
  let openError = null;
  try {
    await db.initialize();
  } catch (error) {
    openError = error;
  }
  const seconds = ((Date.now() - started) / 1000).toFixed(1);
  check("opening it did not fail", !openError, openError ? openError.message : `${seconds}s`);
  if (openError) {
    process.exitCode = 1;
    return;
  }

  const after = {
    conversations: Number(
      (await db.get("SELECT COUNT(*) AS total FROM chat_conversations"))?.total
    ),
    messages: Number(
      (await db.get("SELECT COUNT(*) AS total FROM chat_messages_v2"))?.total
    ),
    members: Number(
      (await db.get("SELECT COUNT(*) AS total FROM chat_conversation_members"))?.total
    ),
    families: Number((await db.get("SELECT COUNT(*) AS total FROM families"))?.total),
    devices: Number((await db.get("SELECT COUNT(*) AS total FROM devices"))?.total),
  };
  const tableAfter = (
    await db.get(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chat_conversations'"
    )
  )?.sql;
  const applied = await db.get(
    "SELECT name, details_json FROM schema_migrations WHERE name = 'chat_group_conversation_type_v1'"
  );
  const violations = await db.all("PRAGMA foreign_key_check");
  const introduced = violations.filter(
    (violation) =>
      !brokenBefore.includes(
        `${violation.table}|${violation.rowid}|${violation.parent}|${violation.fkid}`
      )
  );
  const integrityAfter = await db.get("PRAGMA integrity_check");

  console.log("\nafter:");
  for (const key of Object.keys(before)) {
    check(
      `${key} unchanged`,
      before[key] === after[key],
      `${before[key]} -> ${after[key]}`
    );
  }
  check(
    "the table now allows GROUP",
    String(tableAfter || "").includes("'GROUP'")
  );
  check("the migration recorded itself", Boolean(applied), applied?.name || "");
  check(
    "the migration introduced no broken reference",
    introduced.length === 0,
    `${introduced.length} new, ${violations.length} in total, ${brokenBefore.length} were already there`
  );
  check(
    "the database is still intact",
    integrityAfter?.integrity_check === "ok",
    integrityAfter?.integrity_check
  );

  // The conversations are not just counted, they are read the way the chat does.
  const sample = await db.get(
    `SELECT id, family_id AS familyId, type FROM chat_conversations ORDER BY updated_at DESC LIMIT 1`
  );
  if (sample) {
    const scoped = await db.get(
      `SELECT 1 AS found FROM chat_conversation_members WHERE conversation_id = ? LIMIT 1`,
      [sample.id]
    );
    check(
      "the most recent conversation still resolves with its members",
      Boolean(scoped),
      `${sample.type} ${sample.id.slice(0, 24)}...`
    );
  }

  await db.close();

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
