#!/usr/bin/env node
/*
 * Removes chat seats whose person no longer exists.
 *
 * `chat_conversation_members.member_id` references `family_members (id)`, and the
 * live database contains rows whose parent is gone. They are not file corruption:
 * `PRAGMA integrity_check` reports `ok` for them. They are leftovers from family
 * tidying done by hand, where people were removed from `family_members` and their
 * seats in conversations were not removed with them.
 *
 * Those rows are invisible in the applications, because every read joins
 * `family_members` and a seat with no person drops out of the join. They are
 * still paid for on every read of a conversation, they inflate the member lists
 * that are not joined, and they make any migration that demands a clean
 * `PRAGMA foreign_key_check` fail over damage it did not cause.
 *
 * What this script does, and what it deliberately does not:
 *
 * - dry run by default; the database is opened READ ONLY and nothing is written
 *   without an explicit `--apply`;
 * - it removes ONLY `chat_conversation_members` rows whose `member_id` has no row
 *   in `family_members`. A seat whose person still exists is never touched, not
 *   even when that person is deactivated — the row is history, not rubbish;
 * - it never deletes a `chat_messages_v2` row, a `chat_conversations` row, or
 *   anything else. Messages stay, and a message whose author no longer exists
 *   keeps its stored display name;
 * - it refuses to run against a path that looks like the live server, because the
 *   owner applies this himself on the server, after a backup. A copy always needs
 *   the same command with a copy path;
 * - the foreign-key violations it did NOT cause are counted and named before and
 *   after, so a database that still has other broken references is reported
 *   honestly instead of being declared clean.
 *
 * Run:
 *   node server/scripts/cleanup-orphan-chat-members.js <copy-of-the-database.db>
 *   node server/scripts/cleanup-orphan-chat-members.js <copy-of-the-database.db> --apply
 */

const fs = require("fs");
const path = require("path");
const sqlite3 = require("sqlite3");

const USAGE =
  "usage: node server/scripts/cleanup-orphan-chat-members.js <database.db> [--apply]";

/*
 * Paths that belong to the live server. The owner runs the cleanup there himself,
 * with a backup in hand; this script is for copies only. Refusing here is the whole
 * point: a mistyped path cannot reach production by accident.
 */
const FORBIDDEN_PATH_MARKERS = [
  "/var/www/",
  "/home/adminuser/childwatch",
  "/opt/childwatch",
];

function parseArguments(argv) {
  const args = { file: "", apply: false, help: false, error: null };
  for (const raw of argv) {
    const value = String(raw || "").trim();
    if (!value) continue;
    if (value === "--apply") {
      args.apply = true;
      continue;
    }
    if (value === "--help" || value === "-h") {
      args.help = true;
      continue;
    }
    if (value.startsWith("--")) {
      args.error = `unknown option: ${value}`;
      return args;
    }
    if (args.file) {
      args.error = `only one database path is accepted (got "${args.file}" and "${value}")`;
      return args;
    }
    args.file = value;
  }
  return args;
}

function normalizeForComparison(filePath) {
  return String(filePath || "")
    .replace(/\\/g, "/")
    .trim()
    .toLowerCase();
}

function forbiddenMarkerIn(filePath) {
  const comparable = normalizeForComparison(filePath);
  return FORBIDDEN_PATH_MARKERS.find((marker) => comparable.includes(marker)) || null;
}

function openDatabase(file, mode) {
  const flags = mode === "readonly" ? sqlite3.OPEN_READONLY : sqlite3.OPEN_READWRITE;
  return new Promise((resolve, reject) => {
    const db = new sqlite3.Database(file, flags, (error) =>
      error ? reject(error) : resolve(db)
    );
  });
}

function closeDatabase(db) {
  return new Promise((resolve) => db.close(() => resolve()));
}

function run(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.run(sql, params, function onDone(error) {
      if (error) reject(error);
      else resolve({ changes: Number(this.changes) || 0 });
    });
  });
}

function all(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.all(sql, params, (error, rows) => (error ? reject(error) : resolve(rows || [])));
  });
}

function get(db, sql, params = []) {
  return new Promise((resolve, reject) => {
    db.get(sql, params, (error, row) => (error ? reject(error) : resolve(row)));
  });
}

async function count(db, table) {
  const row = await get(db, `SELECT COUNT(*) AS total FROM ${table}`);
  return Number(row?.total) || 0;
}

async function tableExists(db, table) {
  const row = await get(
    db,
    `SELECT 1 AS found FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1`,
    [table]
  );
  return Boolean(row);
}

async function integrityCheck(db) {
  const row = await get(db, "PRAGMA integrity_check");
  return String(row?.integrity_check ?? "(no result)");
}

/**
 * Every broken reference in the file, grouped by the table that holds it.
 *
 * Grouped rather than counted so the ones this script cannot fix stay visible by
 * name: a database with a violation in `family_permissions` is not a clean one,
 * and claiming otherwise would be the dishonest result the report exists to avoid.
 */
async function foreignKeyViolations(db) {
  const rows = await all(db, "PRAGMA foreign_key_check");
  const byTable = new Map();
  for (const row of rows) {
    const table = row.table || "?";
    if (!byTable.has(table)) {
      byTable.set(table, { count: 0, parents: new Set(), sampleRows: [] });
    }
    const entry = byTable.get(table);
    entry.count += 1;
    if (row.parent) entry.parents.add(row.parent);
    if (entry.sampleRows.length < 3) entry.sampleRows.push(Number(row.rowid));
  }
  return { total: rows.length, byTable };
}

function printForeignKeys(label, report) {
  console.log(`${label}: ${report.total} violation(s)`);
  if (!report.total) {
    console.log("  (none)");
    return;
  }
  const sorted = [...report.byTable.entries()].sort(
    (left, right) => right[1].count - left[1].count
  );
  for (const [table, entry] of sorted) {
    console.log(
      `  ${table}: ${entry.count} row(s), missing parent table(s): ${
        [...entry.parents].join(", ") || "?"
      }, sample rowid(s): ${entry.sampleRows.join(", ")}`
    );
  }
}

function violationKey(row) {
  return `${row.table || "?"}|${row.rowid}|${row.parent || "?"}|${row.fkid}`;
}

/**
 * Every chat seat whose person is not in `family_members`.
 *
 * The `LEFT JOIN ... IS NULL` is the whole point: it is the only join that finds a
 * missing parent. Every read in the server uses an inner join, which is why these
 * rows are never seen and never fixed by the application itself.
 */
async function selectOrphans(db) {
  return all(
    db,
    `SELECT
       cm.conversation_id AS conversationId,
       cm.member_id AS memberId,
       cm.is_active AS isActive,
       cm.joined_at AS joinedAt,
       cm.left_at AS leftAt,
       cm.created_at AS createdAt,
       c.type AS conversationType,
       c.family_id AS familyId,
       c.title AS conversationTitle,
       c.is_active AS conversationIsActive,
       (SELECT COUNT(*) FROM chat_conversation_members all_cm
         WHERE all_cm.conversation_id = cm.conversation_id) AS conversationMemberRows,
       (SELECT COUNT(*) FROM chat_messages_v2 m
         WHERE m.conversation_id = cm.conversation_id) AS conversationMessageRows
     FROM chat_conversation_members cm
     LEFT JOIN chat_conversations c ON c.id = cm.conversation_id
     LEFT JOIN family_members fm ON fm.id = cm.member_id
     WHERE fm.id IS NULL
     ORDER BY cm.conversation_id ASC, cm.joined_at ASC, cm.member_id ASC`
  );
}

function groupByConversation(orphans) {
  const groups = new Map();
  for (const row of orphans) {
    const key = row.conversationId;
    if (!groups.has(key)) {
      groups.set(key, {
        conversationId: row.conversationId,
        conversationType: row.conversationType || "(conversation row is missing too)",
        familyId: row.familyId || "(none)",
        conversationTitle: row.conversationTitle || "(untitled)",
        conversationIsActive: row.conversationIsActive,
        conversationMemberRows: Number(row.conversationMemberRows) || 0,
        conversationMessageRows: Number(row.conversationMessageRows) || 0,
        rows: [],
      });
    }
    groups.get(key).rows.push(row);
  }
  return [...groups.values()];
}

function formatTime(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return "-";
  const milliseconds = number > 1e12 ? number : number * 1000;
  return new Date(milliseconds).toISOString();
}

function printOrphanGroups(groups, title) {
  console.log("");
  console.log(`=== ${title} ===`);
  console.log(`${groups.length} conversation(s) hold orphaned seats`);
  const listedPerConversation = 12;
  for (const group of groups) {
    console.log("");
    console.log(
      `conversation ${group.conversationId}  type=${group.conversationType}  family=${group.familyId}` +
        `  active=${group.conversationIsActive}  title="${group.conversationTitle}"`
    );
    console.log(
      `  seats in this conversation now: ${group.conversationMemberRows} (of which orphaned: ${group.rows.length})` +
        `, messages in it: ${group.conversationMessageRows} (none of which are touched)`
    );
    const shown = group.rows.slice(0, listedPerConversation);
    for (const row of shown) {
      console.log(
        `  - member_id=${row.memberId}  missing from family_members` +
          `  is_active=${row.isActive}  joined_at=${row.joinedAt} (${formatTime(row.joinedAt)})` +
          `  left_at=${row.leftAt ?? "-"}`
      );
    }
    if (group.rows.length > shown.length) {
      console.log(`  ... and ${group.rows.length - shown.length} more in this conversation`);
    }
  }
}

/**
 * How old the leftovers are against the people that do exist.
 *
 * This is what tells the cause apart: leftovers older than every living person are
 * history, while rows younger than a living person mean something wrote a seat whose
 * person never arrived.
 */
async function describeAge(db, orphans) {
  const oldestOrphan = orphans.reduce(
    (oldest, row) => Math.min(oldest, Number(row.joinedAt) || Number.MAX_SAFE_INTEGER),
    Number.MAX_SAFE_INTEGER
  );
  const youngestOrphan = orphans.reduce((youngest, row) => Math.max(youngest, Number(row.joinedAt) || 0), 0);
  const members = await get(
    db,
    `SELECT COUNT(*) AS total,
            MIN(created_at) AS oldestCreatedAt,
            MAX(created_at) AS newestCreatedAt
     FROM family_members`
  );
  console.log("");
  console.log("=== age of the leftovers ===");
  console.log(
    `orphaned seats: joined_at from ${Number.isFinite(oldestOrphan) ? oldestOrphan : "-"} (${formatTime(oldestOrphan)})` +
      ` to ${youngestOrphan} (${formatTime(youngestOrphan)})`
  );
  console.log(
    `family_members that do exist: ${Number(members?.total) || 0}` +
      `, created_at from ${members?.oldestCreatedAt ?? "-"} (${formatTime(members?.oldestCreatedAt)})` +
      ` to ${members?.newestCreatedAt ?? "-"} (${formatTime(members?.newestCreatedAt)})`
  );
  // Enough of the identifier to recognise the shape, and a few living identifiers
  // beside three of the leftovers: a leftover that follows the same scheme as a
  // living person came from the same code and was later deleted, while an identifier
  // of another shape means something else wrote it.
  const livingIds = await all(db, `SELECT id FROM family_members ORDER BY id LIMIT 3`);
  const orphanIds = orphans.slice(0, 3).map((row) => row.memberId);
  console.log(`  living ids (first 3): ${livingIds.map((row) => row.id).join(", ") || "(none)"}`);
  console.log(`  orphaned ids (first 3): ${orphanIds.join(", ")}`);
}

async function snapshot(db) {
  const tables = {};
  for (const table of [
    "chat_conversation_members",
    "chat_conversations",
    "chat_messages_v2",
    "family_members",
    "families",
  ]) {
    tables[table] = (await tableExists(db, table)) ? await count(db, table) : null;
  }
  return {
    tables,
    integrity: await integrityCheck(db),
    foreignKeys: await foreignKeyViolations(db),
    foreignKeyRows: await all(db, "PRAGMA foreign_key_check"),
  };
}

function printSnapshot(label, data) {
  console.log("");
  console.log(`=== ${label} ===`);
  for (const [table, total] of Object.entries(data.tables)) {
    console.log(`  ${table}: ${total === null ? "(table does not exist)" : `${total} row(s)`}`);
  }
  console.log(`  PRAGMA integrity_check: ${data.integrity}`);
  printForeignKeys("  PRAGMA foreign_key_check", data.foreignKeys);
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  if (args.help) {
    console.log(USAGE);
    return;
  }
  if (args.error) {
    console.error(args.error);
    console.error(USAGE);
    process.exitCode = 2;
    return;
  }
  if (!args.file) {
    console.error(USAGE);
    process.exitCode = 2;
    return;
  }

  const resolved = path.resolve(args.file);
  const forbidden = forbiddenMarkerIn(resolved);
  if (forbidden) {
    console.error(
      `refusing to run against a path that looks like the live server: "${resolved}" contains "${forbidden}"`
    );
    console.error(
      "run this against a copy; the owner applies it on the server himself, after a backup"
    );
    process.exitCode = 3;
    return;
  }
  if (!fs.existsSync(resolved)) {
    console.error(`database file not found: ${resolved}`);
    process.exitCode = 2;
    return;
  }

  // A read-only handle in a dry run makes "nothing is written" a property of the
  // filesystem rather than a promise in a comment.
  const db = await openDatabase(resolved, args.apply ? "readwrite" : "readonly");
  try {
    if (!(await tableExists(db, "chat_conversation_members"))) {
      console.error(`no chat_conversation_members table in ${resolved}: nothing to clean`);
      process.exitCode = 2;
      return;
    }
    const journalMode = await get(db, "PRAGMA journal_mode");

    console.log(`database: ${resolved}`);
    console.log(`mode: ${args.apply ? "APPLY (the database will be changed)" : "DRY RUN (read only)"}`);
    console.log(`journal_mode: ${journalMode?.journal_mode ?? "?"}`);

    const before = await snapshot(db);
    printSnapshot("before", before);
    const beforeViolations = new Set(before.foreignKeyRows.map(violationKey));

    const orphans = await selectOrphans(db);
    const groups = groupByConversation(orphans);

    if (!orphans.length) {
      console.log("");
      console.log("=== result ===");
      console.log("no orphaned seats: every chat seat's person exists in family_members");
      console.log("nothing to remove");
      return;
    }

    printOrphanGroups(
      groups,
      args.apply ? "orphaned seats to remove" : "orphaned seats that WOULD be removed"
    );
    await describeAge(db, orphans);

    console.log("");
    console.log("=== totals ===");
    console.log(`orphaned seats: ${orphans.length} in ${groups.length} conversation(s)`);
    console.log(
      `seats in chat_conversation_members before: ${before.tables.chat_conversation_members}` +
        `, after: ${(Number(before.tables.chat_conversation_members) || 0) - orphans.length}`
    );
    console.log(
      `chat_messages_v2 rows: ${before.tables.chat_messages_v2} (this script never deletes a message)`
    );
    console.log(
      `chat_conversations rows: ${before.tables.chat_conversations} (this script never deletes a conversation)`
    );

    if (!args.apply) {
      console.log("");
      console.log(
        "DRY RUN: nothing was written. Re-run with --apply on this same copy to remove these rows."
      );
      return;
    }

    const backupPath = `${resolved}.before-orphan-chat-cleanup.bak`;
    if (!fs.existsSync(backupPath)) {
      fs.copyFileSync(resolved, backupPath);
      console.log("");
      console.log(`backup written: ${backupPath}`);
      // A copy of the file alone is not a complete backup of a write-ahead-logging
      // database: the newest committed rows may still live in the -wal file. The
      // owner's own server backup is the one that counts.
      for (const suffix of ["-wal", "-shm"]) {
        if (fs.existsSync(`${resolved}${suffix}`)) {
          console.log(
            `note: ${resolved}${suffix} also exists — a plain file copy does not carry the` +
              ` newest rows; take the server's own backup before applying this on the server`
          );
        }
      }
    } else {
      console.log("");
      console.log(`backup kept from an earlier run: ${backupPath}`);
    }

    // The removal happens exactly once, inside one transaction, and is compared
    // with what was listed above. A mismatch rolls it back rather than leaving a
    // partly cleaned database behind.
    let removed = 0;
    await run(db, "BEGIN IMMEDIATE");
    try {
      const result = await run(
        db,
        `DELETE FROM chat_conversation_members
         WHERE member_id NOT IN (SELECT id FROM family_members)`
      );
      removed = result.changes;
      if (removed !== orphans.length) {
        throw new Error(
          `removed ${removed} row(s) but ${orphans.length} orphaned seat(s) were listed; rolling back`
        );
      }
      await run(db, "COMMIT");
    } catch (error) {
      await run(db, "ROLLBACK").catch(() => {});
      throw error;
    }

    const after = await snapshot(db);
    printSnapshot("after", after);

    // Evidence that the cleanup did what it claims and nothing more.
    console.log("");
    console.log("=== checks ===");
    const removedDelta =
      (Number(before.tables.chat_conversation_members) || 0) -
      (Number(after.tables.chat_conversation_members) || 0);
    console.log(
      `chat_conversation_members ${before.tables.chat_conversation_members} -> ${after.tables.chat_conversation_members}` +
        ` (removed ${removedDelta}, orphaned seats listed ${orphans.length})`
    );
    const messagesKept =
      Number(before.tables.chat_messages_v2) === Number(after.tables.chat_messages_v2);
    console.log(
      `chat_messages_v2 ${before.tables.chat_messages_v2} -> ${after.tables.chat_messages_v2}` +
        ` (${messagesKept ? "unchanged, no message was deleted" : "CHANGED — investigate"})`
    );
    const conversationsKept =
      Number(before.tables.chat_conversations) === Number(after.tables.chat_conversations);
    console.log(
      `chat_conversations ${before.tables.chat_conversations} -> ${after.tables.chat_conversations}` +
        ` (${conversationsKept ? "unchanged, no conversation was deleted" : "CHANGED — investigate"})`
    );

    const remainingMembers = await count(db, "chat_conversation_members");
    const remainingOrphans = await selectOrphans(db);
    console.log(
      `orphaned seats left: ${remainingOrphans.length}` +
        ` (of ${remainingMembers} remaining seats; every one of them has a person)`
    );
    if (remainingOrphans.length) {
      console.log("  this should be 0 — the removal did not cover everything, investigate");
    }

    // Every conversation that had an orphaned seat still exists, and still holds
    // exactly the seats it had minus the leftovers counted above.
    const surviving = await all(
      db,
      `SELECT cm.conversation_id AS conversationId, COUNT(*) AS total
       FROM chat_conversation_members cm
       GROUP BY cm.conversation_id`
    );
    const survivingByConversation = new Map(
      surviving.map((row) => [row.conversationId, Number(row.total) || 0])
    );
    const conversationsKeptTheirSeats = groups.every((group) => {
      const beforeSeats = group.conversationMemberRows;
      const afterSeats = survivingByConversation.get(group.conversationId) || 0;
      return afterSeats === beforeSeats - group.rows.length;
    });
    console.log(
      `conversations that still hold seats: ${survivingByConversation.size}` +
        ` (of ${groups.length} that had orphaned seats)` +
        `; every conversation kept the seats whose person exists: ${conversationsKeptTheirSeats}`
    );

    const afterViolationRows = after.foreignKeyRows;
    const introduced = afterViolationRows.filter((row) => !beforeViolations.has(violationKey(row)));
    console.log(
      `foreign-key violations introduced by this cleanup: ${introduced.length}` +
        (introduced.length ? " — investigate" : " (none)")
    );
    const remainingNotOurs = afterViolationRows.filter((row) => row.table !== "chat_conversation_members");
    const remainingInSeats = afterViolationRows.filter((row) => row.table === "chat_conversation_members");
    console.log("");
    console.log("=== foreign-key violations that were NOT caused by this script ===");
    console.log(
      `before this run: ${before.foreignKeys.total} (${[...before.foreignKeys.byTable.keys()].join(", ") || "none"})`
    );
    console.log(
      `after this run:  ${after.foreignKeys.total}` +
        ` (remaining in chat_conversation_members: ${remainingInSeats.length}, in other tables: ${remainingNotOurs.length})`
    );
    if (after.foreignKeys.total) {
      printForeignKeys("still present (left untouched, they are somebody else's problem)", after.foreignKeys);
      console.log(
        "the database is therefore NOT free of broken references; the rows above are counted and named"
      );
    } else {
      console.log("the database reports no foreign-key violations at all now");
    }

    console.log("");
    console.log(`removed rows: ${removed}`);
  } finally {
    await closeDatabase(db);
  }
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
