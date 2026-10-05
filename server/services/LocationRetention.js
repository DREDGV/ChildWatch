// One retention policy for measured fixes and the motion values that belong to them.
//
// Before this module the server held three unrelated rules:
//   * `parent_locations` kept the newest 20 000 rows per parent — a COUNT, so the
//     real window depended on the upload rate. At one fix per five seconds that is
//     about 27.8 hours, not the agreed three months, and the neighbour comment
//     still said "keep last 1000";
//   * `location_motion` (speed/accuracy of an exact fix) kept 48 hours;
//   * `locations` — the child's own fixes — was never pruned at all.
//
// The agreed contract is one window for everything that is a measured fix: three
// months of positions and three months of the motion values that belong to them.
// A window in TIME also survives a change of upload cadence, which a row count
// cannot: the same 20 000 rows mean a different period on every device.
//
// Inside that window the resolution drops with age — the owner's decision of
// 05.10.2026: the newest 7 days keep every fix, older days keep one fix per
// minute. At one fix per five seconds that is ~1.56 million rows per device and
// table for three months raw, against ~0.24 million thinned (7 days raw =
// 120 960 rows, 83 days of minutes = 119 520 rows). The detailed week is the part
// that is actually replayed (today's route, the way to school, the evening); an
// older day only has to answer "roughly where was the child then", and nothing on
// a screen reads further back than 31 days today.
//
// Acceptance of a late fix follows the same window. A device that was offline may
// deliver a backlog, and anything inside the window is legitimate history, so the
// window itself is the acceptance boundary. A fix from outside it is refused with
// a code instead of being written and immediately deleted by the sweep: it would
// otherwise resurrect rows that were already pruned and skew the history a parent
// is reading at that moment.

const DAY_MS = 24 * 60 * 60 * 1000;

/** Positions and their motion values are kept this long. */
const RETENTION_DAYS = 90;
/** The newest days keep every fix; older days keep one fix per bucket. */
const RAW_DAYS = 7;
/** The bucket an older day collapses to. */
const BUCKET_MS = 60 * 1000;
/** One thinning slice, and how many slices one sweep may take. */
const SLICE_MS = DAY_MS;
const SLICES_PER_SWEEP = 4;
/** How far into the future a fix may claim to be before it is refused. */
const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;
/** The scheduled sweep runs at most this often, whatever calls it. */
const SWEEP_INTERVAL_MS = 60 * 60 * 1000;
/** Rows removed per statement, so a sweep never holds a long write lock. */
const SWEEP_BATCH = 5000;

/**
 * Tables that hold measured fixes. `key` is what the bucket applies to: two
 * children may share a minute, and so may two parents.
 */
const FIX_TABLES = Object.freeze([
  { table: 'locations', column: 'timestamp', key: 'device_id' },
  { table: 'parent_locations', column: 'timestamp', key: 'parent_id' },
  { table: 'location_motion', column: 'timestamp', key: 'device_id' },
]);

/** Seconds and milliseconds both arrive on the wire; the stores keep milliseconds. */
function normalizeTimestamp(value) {
  const raw = Number(value);
  if (!Number.isFinite(raw) || raw <= 0) return null;
  return Math.round(raw < 100000000000 ? raw * 1000 : raw);
}

/** The oldest instant that is still inside the window. */
function cutoffFor(now = Date.now()) {
  return now - RETENTION_DAYS * DAY_MS;
}

/** The oldest instant that still keeps every fix. */
function rawCutoffFor(now = Date.now()) {
  return now - RAW_DAYS * DAY_MS;
}

/**
 * Decide whether a fix may be written or read back as history.
 * @param value - the timestamp as it arrived (seconds or milliseconds).
 * @param now - the clock used for the decision, injectable for tests.
 * @returns {{accepted: boolean, code: string|null, timestamp: number|null}}
 */
function acceptanceOf(value, now = Date.now()) {
  const timestamp = normalizeTimestamp(value);
  if (timestamp === null) return { accepted: false, code: 'LOCATION_TIMESTAMP_INVALID', timestamp: null };
  if (timestamp > now + FUTURE_TOLERANCE_MS) return { accepted: false, code: 'LOCATION_TIMESTAMP_FUTURE', timestamp };
  if (timestamp < cutoffFor(now)) return { accepted: false, code: 'LOCATION_TOO_OLD', timestamp };
  return { accepted: true, code: null, timestamp };
}

// `locations` carries (device_id, timestamp) but no index on `timestamp` alone, and
// the sweep asks by time only. The other two tables already have theirs; creating
// them again is a no-op.
const indexed = new WeakMap();
async function ensureIndexes(db) {
  if (!indexed.has(db)) {
    indexed.set(db, (async () => {
      await db.run('CREATE INDEX IF NOT EXISTS idx_locations_timestamp ON locations (timestamp)');
      await db.run('CREATE INDEX IF NOT EXISTS idx_parent_locations_timestamp ON parent_locations (timestamp)');
      await db.run('CREATE INDEX IF NOT EXISTS idx_location_motion_time ON location_motion (timestamp)');
    })().catch(error => { indexed.delete(db); throw error; }));
  }
  return indexed.get(db);
}

/** Delete one table's expired rows in bounded batches. */
async function pruneTable(db, table, column, cutoff) {
  let removed = 0;
  for (;;) {
    const result = await db.run(
      `DELETE FROM ${table} WHERE rowid IN (SELECT rowid FROM ${table} WHERE ${column} < ? LIMIT ?)`,
      [cutoff, SWEEP_BATCH]
    );
    const changes = Number((result && result.changes) || 0);
    removed += changes;
    if (changes < SWEEP_BATCH) return removed;
  }
}

/**
 * Collapse one table's slice to one fix per bucket per key.
 *
 * The oldest fix of each bucket is the representative in every table, and both
 * `locations` and `location_motion` are written from the same upload, so the kept
 * position keeps its speed whenever that fix carried one.
 */
async function thinTable(db, table, column, key, from, to) {
  let removed = 0;
  for (;;) {
    const result = await db.run(
      `DELETE FROM ${table} WHERE rowid IN (
         SELECT rowid FROM ${table}
          WHERE ${column} >= ? AND ${column} < ?
            AND rowid NOT IN (
              SELECT MIN(rowid) FROM ${table}
               WHERE ${column} >= ? AND ${column} < ?
               GROUP BY ${key}, ${column} / ${BUCKET_MS}
            )
          LIMIT ?
       )`,
      [from, to, from, to, SWEEP_BATCH]
    );
    const changes = Number((result && result.changes) || 0);
    removed += changes;
    if (changes < SWEEP_BATCH) return removed;
  }
}

/**
 * Drop motion rows whose fix no longer exists in `locations`.
 *
 * Thinning keeps the earliest fix of a minute in each table independently, and
 * motion is only written when a fix carried speed, so a bucket's motion
 * representative can differ from its position representative. The leftover row is
 * matchable by nothing (`attach` looks motion up by the exact fix) and would sit
 * in the file until the retention window removed it ninety days later.
 */
async function pruneOrphanMotion(db, from, to) {
  let removed = 0;
  for (;;) {
    const result = await db.run(
      `DELETE FROM location_motion WHERE rowid IN (
         SELECT motion.rowid FROM location_motion motion
          WHERE motion.timestamp >= ? AND motion.timestamp < ?
            AND NOT EXISTS (
              SELECT 1 FROM locations position
               WHERE position.device_id = motion.device_id
                 AND position.timestamp = motion.timestamp
                 AND position.latitude = motion.latitude
                 AND position.longitude = motion.longitude
            )
          LIMIT ?
       )`,
      [from, to, SWEEP_BATCH]
    );
    const changes = Number((result && result.changes) || 0);
    removed += changes;
    if (changes < SWEEP_BATCH) return removed;
  }
}

// Where thinning has reached, per database handle and table. A fresh process
// starts at the retention edge and catches up SLICES_PER_SWEEP days per sweep
// (~15 hours to walk 60 days once); afterwards one sweep only handles the day
// that just aged out of the raw window.
const thinCursor = new WeakMap();
function cursorFor(db, table, now) {
  let perTable = thinCursor.get(db);
  if (perTable === undefined) { perTable = new Map(); thinCursor.set(db, perTable); }
  const known = perTable.get(table);
  const oldest = cutoffFor(now);
  if (known === undefined || known < oldest) return oldest;
  return known;
}

/**
 * Apply the whole policy once: thin what is older than the raw window, then
 * delete what is older than the retention window.
 * @returns {Promise<{cutoff: number, thinned: Object, removed: Object}>}
 */
async function prune(db, now = Date.now()) {
  await ensureIndexes(db);
  const cutoff = cutoffFor(now);
  const thinUntil = rawCutoffFor(now);
  const thinned = {};
  const removed = {};
  const orphans = {};

  // `locations` is processed first on purpose: motion can only be judged orphaned
  // against a position table that already collapsed the same slice.
  for (const { table, column, key } of FIX_TABLES) {
    let cursor = cursorFor(db, table, now);
    let moved = 0;
    let dropped = 0;
    let slices = 0;
    while (cursor < thinUntil && slices < SLICES_PER_SWEEP) {
      const sliceEnd = Math.min(cursor + SLICE_MS, thinUntil);
      moved += await thinTable(db, table, column, key, cursor, sliceEnd);
      if (table === 'location_motion') dropped += await pruneOrphanMotion(db, cursor, sliceEnd);
      cursor = sliceEnd;
      slices++;
    }
    const perTable = thinCursor.get(db);
    perTable.set(table, cursor);
    thinned[table] = moved;
    if (table === 'location_motion') orphans[table] = dropped;
    removed[table] = await pruneTable(db, table, column, cutoff);
  }

  return { cutoff, thinned, removed, orphans };
}

// Sweep bookkeeping per database handle. The timestamp is claimed BEFORE the work
// so two concurrent callers cannot both start a sweep; a failure puts it back, so
// the next caller retries instead of waiting out a window that never ran.
const lastSweep = new WeakMap();
async function pruneIfDue(db, now = Date.now()) {
  const previous = lastSweep.get(db) || 0;
  if (now - previous < SWEEP_INTERVAL_MS) return { swept: false, cutoff: cutoffFor(now), thinned: null, removed: null };
  lastSweep.set(db, now);
  try {
    const result = await prune(db, now);
    return { swept: true, ...result };
  } catch (error) {
    lastSweep.set(db, previous);
    throw error;
  }
}

module.exports = {
  RETENTION_DAYS,
  RAW_DAYS,
  BUCKET_MS,
  SLICE_MS,
  SLICES_PER_SWEEP,
  FUTURE_TOLERANCE_MS,
  SWEEP_INTERVAL_MS,
  SWEEP_BATCH,
  FIX_TABLES,
  normalizeTimestamp,
  cutoffFor,
  rawCutoffFor,
  acceptanceOf,
  prune,
  pruneIfDue,
};
