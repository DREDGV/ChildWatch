#!/usr/bin/env node
/*
 * Reports the foreign-key violations a database already has.
 *
 * `PRAGMA integrity_check` says the file is structurally sound; it says nothing about
 * rows that point at a parent which is not there. Those are the rows a rebuild is
 * blamed for, so any check that compares before and after has to be able to tell
 * them apart from ones it caused itself.
 *
 * Run: node scripts/foreign-key-report.js <path-to-copy.db>
 */

const fs = require("fs");
const sqlite3 = require("sqlite3");

function openRaw(file) {
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(
      file,
      sqlite3.OPEN_READONLY,
      (error) => (error ? reject(error) : resolve(db))
    );
  });
}

function rawAll(db, sql) {
  return new Promise((resolve, reject) => {
    db.all(sql, (error, rows) => (error ? reject(error) : resolve(rows)));
  });
}

async function main() {
  const file = process.argv[2];
  if (!file || !fs.existsSync(file)) {
    console.error("usage: node scripts/foreign-key-report.js <copy.db>");
    process.exitCode = 2;
    return;
  }

  const db = await openRaw(file);
  const violations = await rawAll(db, "PRAGMA foreign_key_check");
  const byTable = new Map();
  for (const violation of violations) {
    const table = violation.table || "?";
    if (!byTable.has(table)) {
      byTable.set(table, { count: 0, parents: new Set() });
    }
    const entry = byTable.get(table);
    entry.count += 1;
    if (violation.parent) entry.parents.add(violation.parent);
  }

  console.log(`database: ${file}`);
  console.log(`foreign-key violations: ${violations.length}`);
  if (!violations.length) {
    console.log("(none: every row that points at a parent finds it)");
  } else {
    console.log("");
    for (const [table, entry] of [...byTable.entries()].sort(
      (left, right) => right[1].count - left[1].count
    )) {
      console.log(
        `  ${table}: ${entry.count}  -> missing parent(s): ${[...entry.parents].join(", ") || "?"}`
      );
    }
  }

  // The tables this project's chat lives in, counted directly, so the numbers can be
  // compared with what the violations above are about.
  for (const table of [
    "chat_conversations",
    "chat_conversation_members",
    "chat_messages_v2",
    "chat_legacy_threads",
  ]) {
    const exists = await rawAll(
      db,
      `SELECT name FROM sqlite_master WHERE type = 'table' AND name = '${table}'`
    );
    if (!exists.length) {
      console.log(`  ${table}: (table does not exist)`);
      continue;
    }
    const row = (await rawAll(db, `SELECT COUNT(*) AS total FROM ${table}`))[0];
    console.log(`  ${table}: ${row.total} row(s)`);
  }

  await new Promise((resolve) => db.close(() => resolve()));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
