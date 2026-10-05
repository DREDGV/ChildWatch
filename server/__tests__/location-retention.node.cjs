// Run directly with Node; .node.cjs keeps this isolated smoke outside Jest discovery.
// Real SQLite and the production retention module; nothing here touches a live database.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const retention = require('../services/LocationRetention');

const DAY = 24 * 60 * 60 * 1000;
const MINUTE = 60 * 1000;

async function memoryDatabase() {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  // The three tables as production creates them (DatabaseManager, routes/location.js,
  // services/LocationMotionStore.js). Only the columns the policy touches are used.
  await db.run(`CREATE TABLE locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, latitude REAL NOT NULL,
    longitude REAL NOT NULL, accuracy REAL NOT NULL, timestamp INTEGER NOT NULL,
    created_at INTEGER DEFAULT (strftime('%s', 'now')))`);
  await db.run(`CREATE TABLE parent_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id TEXT NOT NULL, latitude REAL NOT NULL,
    longitude REAL NOT NULL, accuracy REAL, timestamp INTEGER NOT NULL, battery INTEGER,
    speed REAL, bearing REAL, created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000))`);
  await db.run(`CREATE TABLE location_motion (
    device_id TEXT NOT NULL, timestamp INTEGER NOT NULL, latitude REAL NOT NULL,
    longitude REAL NOT NULL, speed REAL, speed_accuracy REAL,
    PRIMARY KEY(device_id,timestamp,latitude,longitude))`);
  return db;
}

async function count(db, table) {
  const row = await db.get(`SELECT COUNT(*) AS n FROM ${table}`);
  return Number(row.n);
}

async function childFix(db, timestamp) {
  await db.run('INSERT INTO locations (device_id, latitude, longitude, accuracy, timestamp) VALUES (?,?,?,?,?)',
    ['child', 55, 83, 10, timestamp]);
}

test('one time window replaces the count cap and the 48-hour motion window', async () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);
  const db = await memoryDatabase();
  try {
    const fresh = now - DAY;
    const inside = now - 80 * DAY;       // older than any 20 000-row cap, inside the window
    const boundary = retention.cutoffFor(now); // exactly 90 days: still kept
    const expired = now - 91 * DAY;

    for (const ts of [fresh, inside, boundary, expired]) await childFix(db, ts);
    for (const [parentId, ts] of [['parent', fresh], ['parent', inside], ['parent', expired]]) {
      await db.run('INSERT INTO parent_locations (parent_id, latitude, longitude, accuracy, timestamp) VALUES (?,?,?,?,?)',
        [parentId, 55, 83, 10, ts]);
    }
    for (const ts of [fresh, inside, boundary, expired]) {
      await db.run('INSERT INTO location_motion VALUES (?,?,?,?,?,?)', ['child', ts, 55, 83, 1.5, 0.5]);
    }

    const result = await retention.prune(db, now);

    assert.equal(result.cutoff, boundary);
    assert.equal(result.removed.locations, 1, 'only the 91-day-old child fix goes');
    assert.equal(result.removed.parent_locations, 1, 'only the 91-day-old parent fix goes');
    assert.equal(result.removed.location_motion, 1, 'motion follows the same window, not 48 hours');
    assert.equal(await count(db, 'locations'), 3);
    assert.equal(await count(db, 'parent_locations'), 2);
    assert.equal(await count(db, 'location_motion'), 3);

    // The point the old rule would have destroyed: a parent fix 80 days old is
    // older than the newest 20 000 rows but inside the agreed window.
    const kept = await db.all('SELECT timestamp FROM parent_locations ORDER BY timestamp');
    assert.deepEqual(kept.map(row => row.timestamp), [inside, fresh]);

    // Idempotent: a second sweep on the same clock removes nothing.
    const again = await retention.prune(db, now);
    assert.deepEqual(again.removed, { locations: 0, parent_locations: 0, location_motion: 0 });
  } finally {
    db.db.close();
  }
});

test('older days collapse to one fix per minute, the newest week keeps every fix', async () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);
  const db = await memoryDatabase();
  try {
    // Five fixes five seconds apart inside one minute, in four age regions.
    const region = (daysAgo) => Math.floor((now - daysAgo * DAY) / MINUTE) * MINUTE;
    const regions = { fresh: region(3), older: region(10), old: region(45), ancient: region(100) };
    for (const base of Object.values(regions)) {
      for (let index = 0; index < 5; index++) await childFix(db, base + index * 5000);
    }
    // Motion is written from the same uploads and follows the same buckets.
    const motionBase = regions.older;
    for (let index = 0; index < 5; index++) {
      await db.run('INSERT INTO location_motion VALUES (?,?,?,?,?,?)', ['child', motionBase + index * 5000, 55, 83, 1.5, 0.5]);
    }

    // Thinning walks forward SLICES_PER_SWEEP days per sweep, so the first sweeps
    // only touch the oldest edge; catch up until the cursor reaches the raw week.
    let thinnedTotal = 0;
    for (let sweep = 0; sweep < 25; sweep++) thinnedTotal += (await retention.prune(db, now)).thinned.locations;
    assert.ok(thinnedTotal > 0, 'thinning actually removed something');

    const inRegion = async (base) => {
      const row = await db.get('SELECT COUNT(*) AS n FROM locations WHERE timestamp >= ? AND timestamp < ?', [base, base + MINUTE]);
      return Number(row.n);
    };
    assert.equal(await inRegion(regions.fresh), 5, 'the newest week keeps every fix');
    assert.equal(await inRegion(regions.older), 1, 'a 10-day-old minute collapses to one fix');
    assert.equal(await inRegion(regions.old), 1, 'a 45-day-old minute collapses to one fix');
    assert.equal(await inRegion(regions.ancient), 0, 'beyond the window nothing survives');
    assert.equal(await count(db, 'location_motion'), 1, 'motion collapses on the same buckets');

    // Thinning is idempotent: nothing left to collapse.
    const settled = await retention.prune(db, now);
    assert.equal(settled.thinned.locations, 0);
  } finally {
    db.db.close();
  }
});

test('a motion row whose fix was thinned away does not linger for ninety days', async () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);
  const db = await memoryDatabase();
  try {
    const base = Math.floor((now - 20 * DAY) / MINUTE) * MINUTE;
    // The position table keeps the earliest fix of the minute; motion was only
    // written for a later fix of that same minute, so after thinning that row is
    // matchable by nothing — `attach` looks motion up by the exact fix.
    await childFix(db, base);
    await childFix(db, base + 5000);
    await db.run('INSERT INTO location_motion VALUES (?,?,?,?,?,?)', ['child', base + 5000, 55, 83, 1.5, 0.5]);
    assert.equal(await count(db, 'location_motion'), 1);

    for (let sweep = 0; sweep < 25; sweep++) await retention.prune(db, now);

    assert.equal(await count(db, 'locations'), 1, 'the earliest fix of the minute stays');
    const kept = await db.get('SELECT timestamp FROM locations');
    assert.equal(kept.timestamp, base);
    assert.equal(await count(db, 'location_motion'), 0, 'the unmatched motion row is removed, not kept');
  } finally {
    db.db.close();
  }
});

test('a sweep removes more than one batch and stops at the batch boundary', async () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);
  const db = await memoryDatabase();
  try {
    const expired = now - 200 * DAY;
    const rows = retention.SWEEP_BATCH + 7;
    await db.run('BEGIN');
    for (let index = 0; index < rows; index++) await childFix(db, expired + index);
    await db.run('COMMIT');
    assert.equal(await count(db, 'locations'), rows);

    const result = await retention.prune(db, now);
    assert.equal(result.removed.locations, rows, 'the loop keeps going past one batch');
    assert.equal(await count(db, 'locations'), 0);
  } finally {
    db.db.close();
  }
});

test('acceptance follows the same window, in seconds or milliseconds', () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);

  assert.equal(retention.acceptanceOf(now - DAY, now).accepted, true);
  assert.equal(retention.acceptanceOf(retention.cutoffFor(now), now).accepted, true, 'the boundary is inside');
  assert.equal(retention.acceptanceOf(now - 91 * DAY, now).code, 'LOCATION_TOO_OLD');
  assert.equal(retention.acceptanceOf(now + 60 * 60 * 1000, now).code, 'LOCATION_TIMESTAMP_FUTURE');
  assert.equal(retention.acceptanceOf('not-a-number', now).code, 'LOCATION_TIMESTAMP_INVALID');

  // Seconds and milliseconds both appear on the wire; the decision is the same.
  const seconds = Math.round((now - DAY) / 1000);
  const fromSeconds = retention.acceptanceOf(seconds, now);
  assert.equal(fromSeconds.accepted, true);
  assert.equal(fromSeconds.timestamp, Math.round(seconds * 1000));
});

test('the scheduled sweep runs once per interval and retries after a failure', async () => {
  const now = Date.UTC(2026, 9, 5, 12, 0, 0);
  const db = await memoryDatabase();
  try {
    await childFix(db, now - 200 * DAY);

    const first = await retention.pruneIfDue(db, now);
    assert.equal(first.swept, true);
    assert.equal(first.removed.locations, 1);

    const early = await retention.pruneIfDue(db, now + 60 * 1000);
    assert.equal(early.swept, false, 'the interval is owned by the module, not the caller');

    await childFix(db, now - 200 * DAY);
    const later = await retention.pruneIfDue(db, now + retention.SWEEP_INTERVAL_MS);
    assert.equal(later.swept, true);
    assert.equal(later.removed.locations, 1);

    // A failure must not consume the interval: the next caller tries again.
    const failing = { run: async () => { throw new Error('database is busy'); } };
    await assert.rejects(retention.pruneIfDue(failing, now));
    await assert.rejects(retention.pruneIfDue(failing, now + 1000));
  } finally {
    db.db.close();
  }
});
