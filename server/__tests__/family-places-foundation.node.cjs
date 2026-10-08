// Execute directly with Node: real SQLite plus production DatabaseManager transactions.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const FamilyPlacesService = require('../services/FamilyPlacesService');

async function fixture(t) {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  t.after(() => new Promise((resolve, reject) => db.db.close(error => error ? reject(error) : resolve())));
  let now = 1_800_000_000_000;
  t.mock.method(Date, 'now', () => now);
  const members = [{ id: 'c', role: 'CHILD' }, { id: 'a', role: 'PARENT' }, { id: 'b', role: 'GUARDIAN' }];
  const phones = { 'child-phone': 'c', 'replacement-phone': 'c', 'phone-a': 'a', 'phone-b': 'b' };
  db.getFamilyMembers = async family => family === 'f' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(row => row.id === phones[device]);
    return member ? [{ familyId: 'f', memberId: member.id, memberRole: member.role, memberDisplayName: member.id }] : [];
  };
  await db.run(`CREATE TABLE family_permissions (id TEXT PRIMARY KEY, family_id TEXT NOT NULL,
    actor_member_id TEXT NOT NULL, target_member_id TEXT NOT NULL, feature TEXT NOT NULL,
    allowed INTEGER NOT NULL, UNIQUE(family_id, actor_member_id, target_member_id, feature))`);
  for (const owner of ['a', 'b']) for (const feature of ['LOCATION', 'LOCATION_HISTORY'])
    await db.run('INSERT INTO family_permissions VALUES (?,?,?,?,?,?)', [randomUUID(), 'f', owner, 'c', feature, 1]);
  let revoked = false;
  function service() {
    const instance = new FamilyPlacesService(db);
    instance.access.isDeviceRevoked = async () => revoked;
    return instance;
  }
  const initial = service();
  const a = await initial.actor('phone-a', 'f'), b = await initial.actor('phone-b', 'f');
  const input = extra => ({ requestId: randomUUID(), targetMemberId: 'c', name: 'School',
    latitude: 0, longitude: 0, radius: 200, onEnter: true, onExit: true, ...extra });
  const sample = (metres, at, accuracy = 10) => ({ latitude: metres / 111194.9266, longitude: 0, timestamp: at, accuracy });
  async function fix(instance, metres, elapsed, accuracy = 10) {
    now = 1_800_000_000_000 + elapsed;
    await instance.onLocation('child-phone', sample(metres, now, accuracy));
  }
  async function rawFix(instance, overrides, elapsed, device = 'child-phone') {
    now = 1_800_000_000_000 + elapsed;
    await instance.onLocation(device, { ...sample(0, now), ...overrides });
  }
  const state = id => db.get('SELECT * FROM family_place_watches WHERE id=?', [id]);
  const events = () => db.all('SELECT * FROM family_place_events ORDER BY id');
  return { db, initial, service, a, b, input, fix, rawFix, sample, state, events, members, phones,
    setRevoked: value => { revoked = value; } };
}

test('SQLite transition persists candidate across service restart and ignores duplicate/out-of-order packets', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  assert.equal((await f.events()).length, 0);
  await f.fix(f.initial, 0, 1000);
  const pending = await f.state(id);
  assert.equal(pending.candidate_count, 1);
  const restarted = f.service();
  await f.fix(restarted, 0, 31_000);
  let rows = await f.events();
  assert.equal(rows.length, 1);
  assert.equal(rows[0].transition, 'ENTER');
  assert.equal(rows[0].measured_at, 1_800_000_031_000);
  assert.equal(rows[0].policy_version, 2);
  assert.equal(JSON.parse(rows[0].evidence_json).confirmationCount, 2);
  assert.equal((await f.state(id)).candidate_since, null);
  const committed = await f.state(id);
  await f.fix(restarted, 0, 31_000);
  await f.fix(restarted, 400, 30_000);
  assert.deepEqual(await f.state(id), committed);
  assert.equal((await f.events()).length, 1);
});

test('real SQLite fault after event insert rolls back event and state, then retry commits exactly once', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  const before = await f.state(id);
  // An actual trigger aborts the statement after the preceding INSERT executed in this transaction.
  await f.db.run(`CREATE TRIGGER injected_place_state_failure BEFORE UPDATE ON family_place_watches
    WHEN NEW.observation_reason='confirmed_transition'
    BEGIN SELECT RAISE(ABORT,'injected_state_fault'); END`);
  await assert.rejects(f.fix(f.initial, 0, 31_000), /injected_state_fault/);
  assert.equal((await f.events()).length, 0);
  assert.deepEqual(await f.state(id), before);
  await f.db.run('DROP TRIGGER injected_place_state_failure');
  await f.fix(f.initial, 0, 31_000);
  await f.fix(f.initial, 0, 31_000);
  assert.equal((await f.events()).length, 1);
  assert.equal((await f.state(id)).side, 'INSIDE');
});

test('permission revoke/grant without a GPS fix resets only that owner and prevents synthetic ENTER', async t => {
  const f = await fixture(t);
  const aId = await f.initial.create(f.a, f.input());
  const bId = await f.initial.create(f.b, f.input());
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  const bBefore = await f.state(bId);
  await f.db.run("UPDATE family_permissions SET allowed=0 WHERE actor_member_id='a' AND feature='LOCATION'");
  assert.equal((await f.state(aId)).side, null);
  assert.equal((await f.state(aId)).candidate, null);
  assert.equal((await f.state(aId)).last_certain_timestamp, 0);
  assert.deepEqual(await f.state(bId), bBefore);
  assert.equal((await f.initial.list(f.a)).length, 0);
  await f.db.run("UPDATE family_permissions SET allowed=1 WHERE actor_member_id='a' AND feature='LOCATION'");
  assert.equal((await f.state(aId)).last_timestamp, 0);
  await f.fix(f.initial, 0, 31_000);
  const rows = await f.events();
  assert.equal(rows.length, 1);
  assert.equal(rows[0].owner_member_id, 'b');
  assert.equal((await f.state(aId)).side, 'INSIDE');
  assert.equal((await f.state(aId)).observation_reason, 'initial_baseline');
});

test('permission DELETE and INSERT reset persisted presence; same-value UPDATE does not erase evidence', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  const before = await f.state(id);
  await f.db.run("UPDATE family_permissions SET allowed=1 WHERE actor_member_id='a' AND feature='LOCATION_HISTORY'");
  assert.deepEqual(await f.state(id), before);
  await f.db.run("DELETE FROM family_permissions WHERE actor_member_id='a' AND feature='LOCATION_HISTORY'");
  assert.equal((await f.state(id)).candidate, null);
  assert.equal((await f.initial.list(f.a)).length, 0);
  await f.db.run('INSERT INTO family_permissions VALUES (?,?,?,?,?,?)', [randomUUID(), 'f', 'a', 'c', 'LOCATION_HISTORY', 1]);
  await f.fix(f.initial, 0, 31_000);
  assert.equal((await f.events()).length, 0);
  assert.equal((await f.state(id)).side, 'INSIDE');
});

test('owners cannot list, replay, modify or remove another owners watches', async t => {
  const f = await fixture(t);
  const aId = await f.initial.create(f.a, f.input());
  const bId = await f.initial.create(f.b, f.input());
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  await f.fix(f.initial, 0, 31_000);
  assert.deepEqual((await f.initial.list(f.a)).map(row => row.id), [aId]);
  const replay = await f.initial.events(f.a, 0);
  assert.equal(replay.familyId, 'f');
  assert.equal(replay.ownerMemberId, 'a');
  assert.equal(replay.events.length, 1);
  assert.equal(replay.events[0].watch_id, aId);
  await assert.rejects(f.initial.toggle(f.a, bId, false), { status: 403 });
  await assert.rejects(f.initial.update(f.a, bId, f.input()), { status: 403 });
  await f.initial.remove(f.a, bId);
  assert.ok(await f.state(bId));
  const child = await f.initial.actor('child-phone', 'f');
  await assert.rejects(f.initial.list(child), { status: 403 });
  f.setRevoked(true);
  await assert.rejects(f.initial.events(f.a, 0), { status: 403 });
});

test('SQLite ambiguous measurements cannot conceal offline certainty gap', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  for (let elapsed = 120_000; elapsed <= 720_000; elapsed += 120_000)
    await f.fix(f.initial, 200, elapsed);
  const uncertain = await f.state(id);
  assert.equal(uncertain.side, null);
  assert.equal(uncertain.last_certain_timestamp, 0);
  assert.equal((await f.initial.list(f.a))[0].presence.status, 'UNCERTAIN');
  await f.fix(f.initial, 0, 750_000);
  assert.equal((await f.events()).length, 0);
  assert.equal((await f.state(id)).side, 'INSIDE');
});

test('additive migration preserves existing watches/history and deliberately creates a new baseline', async t => {
  const f = await fixture(t);
  await f.db.run(`CREATE TABLE family_place_watches (id TEXT PRIMARY KEY, family_id TEXT NOT NULL,
    owner_member_id TEXT NOT NULL,target_member_id TEXT NOT NULL,name TEXT NOT NULL,latitude REAL NOT NULL,
    longitude REAL NOT NULL,radius REAL NOT NULL,on_enter INTEGER NOT NULL,on_exit INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,side TEXT,candidate TEXT,candidate_since INTEGER,
    candidate_count INTEGER DEFAULT 0,last_timestamp INTEGER DEFAULT 0,last_device_id TEXT,created_at INTEGER NOT NULL)`);
  await f.db.run(`CREATE TABLE family_place_events (id INTEGER PRIMARY KEY AUTOINCREMENT,watch_id TEXT NOT NULL,
    family_id TEXT NOT NULL,owner_member_id TEXT NOT NULL,target_member_id TEXT NOT NULL,device_id TEXT NOT NULL,
    person_name TEXT NOT NULL,place_name TEXT NOT NULL,transition TEXT NOT NULL,measured_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,UNIQUE(watch_id,measured_at,transition))`);
  const id = randomUUID();
  await f.db.run(`INSERT INTO family_place_watches VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    [id, 'f', 'a', 'c', 'Legacy school', 0, 0, 200, 1, 1, 1, 'OUTSIDE', 'INSIDE', Date.now(), 1, Date.now(), 'child-phone', Date.now()]);
  await f.db.run(`INSERT INTO family_place_events
    (watch_id,family_id,owner_member_id,target_member_id,device_id,person_name,place_name,transition,measured_at,created_at)
    VALUES (?,?,?,?,?,?,?,?,?,?)`, [id, 'f', 'a', 'c', 'child-phone', 'Child', 'Legacy school', 'EXIT', Date.now() - 1000, Date.now()]);
  await f.initial.ensure();
  assert.equal((await f.state(id)).name, 'Legacy school');
  assert.equal((await f.state(id)).last_certain_timestamp, 0);
  assert.equal((await f.initial.list(f.a))[0].presence.status, 'WAITING',
    'legacy candidate has no known confidence timestamp and must await a new baseline');
  assert.equal((await f.events()).length, 1);
  assert.equal((await f.events())[0].policy_version, 1);
  const restarted = f.service();
  await restarted.ensure();
  await f.fix(restarted, 0, 31_000);
  assert.equal((await f.state(id)).side, 'INSIDE');
  assert.equal((await f.events()).length, 1);
  assert.equal((await f.events())[0].place_name, 'Legacy school');
});

test('ingestion rejects malformed coordinates without converting null to a location and cancels pending evidence', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  let elapsed = 1000;
  for (const bad of [{ latitude: null }, { longitude: null }, { latitude: 91 }, { longitude: NaN }]) {
    await f.fix(f.initial, 0, elapsed);
    await f.rawFix(f.initial, bad, elapsed + 1000);
    const row = await f.state(id);
    assert.equal(row.side, 'OUTSIDE');
    assert.equal(row.observation_reason, 'invalid_coordinates');
    assert.equal(row.candidate, null);
    assert.equal(row.last_timestamp, 1_800_000_000_000 + elapsed);
    assert.equal((await f.events()).length, 0);
    elapsed += 3000;
  }
  await f.fix(f.initial, 0, elapsed);
  await f.rawFix(f.initial, { accuracy: null }, elapsed + 1000);
  assert.equal((await f.state(id)).observation_reason, 'poor_accuracy');
  assert.equal((await f.state(id)).candidate, null);
  assert.equal((await f.events()).length, 0);
});

test('pause/resume and place editing discard previous candidate, each requires a new baseline', async t => {
  const f = await fixture(t);
  const input = f.input();
  const id = await f.initial.create(f.a, input);
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  await f.initial.toggle(f.a, id, false);
  const paused = await f.state(id);
  assert.equal(paused.side, null);
  assert.equal((await f.initial.list(f.a))[0].presence.status, 'PAUSED');
  await f.fix(f.initial, 0, 31_000);
  assert.deepEqual(await f.state(id), paused);
  await f.initial.toggle(f.a, id, true);
  await f.fix(f.initial, 0, 40_000);
  assert.equal((await f.state(id)).side, 'INSIDE');
  assert.equal((await f.events()).length, 0);
  await f.fix(f.initial, 400, 41_000);
  await f.initial.update(f.a, id, { ...input, name: 'Updated school', radius: 250 });
  const edited = await f.state(id);
  assert.equal(edited.candidate_since, null);
  assert.equal(edited.last_certain_timestamp, 0);
  await f.fix(f.initial, 400, 80_000);
  assert.equal((await f.state(id)).side, 'OUTSIDE');
  assert.equal((await f.events()).length, 0);
});

test('replacement handset establishes a new persisted baseline without completing the old candidate', async t => {
  const f = await fixture(t);
  const id = await f.initial.create(f.a, f.input());
  await f.fix(f.initial, 400, 0);
  await f.fix(f.initial, 0, 1000);
  await f.rawFix(f.initial, {}, 31_000, 'replacement-phone');
  const row = await f.state(id);
  assert.equal(row.side, 'INSIDE');
  assert.equal(row.last_device_id, 'replacement-phone');
  assert.equal(row.observation_reason, 'device_baseline');
  assert.equal((await f.events()).length, 0);
});

test('membership replacement invalidates a captured actor before create/list/event replay', async t => {
  const f = await fixture(t);
  const captured = f.a;
  f.phones['phone-a'] = 'b';
  assert.equal((await f.initial.actor('phone-a', 'f')).memberId, 'b');
  await assert.rejects(f.initial.create(captured, f.input()), { status: 403 });
  await assert.rejects(f.initial.list(captured), { status: 403 });
  await assert.rejects(f.initial.events(captured, 0), { status: 403 });
  assert.equal((await f.db.get('SELECT COUNT(*) AS count FROM family_place_watches')).count, 0);
});

test('a hidden first page still advances owner cursor and permission replay remains freshly checked', async t => {
  const f = await fixture(t);
  const paused = await f.initial.create(f.a, f.input({ name: 'Paused place' }));
  const deleted = await f.initial.create(f.a, f.input({ name: 'Deleted place' }));
  const visible = await f.initial.create(f.a, f.input({ name: 'Visible place' }));
  // Real fixture events preserve owner authority and production LIMIT/order/cursor behavior.
  for (let index = 1; index <= 101; index++) {
    const watch = index === 101 ? visible : index % 2 ? paused : deleted;
    await f.db.run(`INSERT INTO family_place_events
      (watch_id,family_id,owner_member_id,target_member_id,device_id,person_name,place_name,transition,measured_at,created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?)`, [watch, 'f', 'a', 'c', 'child-phone', 'Child', 'Place', 'ENTER', Date.now() - index, Date.now()]);
  }
  await f.initial.toggle(f.a, paused, false);
  await f.initial.remove(f.a, deleted);
  const first = await f.initial.events(f.a, 0);
  assert.deepEqual(first.events, []);
  assert.equal(first.cursor, 100);
  const next = await f.initial.events(f.a, first.cursor);
  assert.equal(next.cursor, 101);
  assert.equal(next.events.length, 1);
  assert.equal(next.events[0].id, 101);
  assert.equal(next.events[0].watch_id, visible);
  await f.db.run("UPDATE family_permissions SET allowed=0 WHERE actor_member_id='a' AND feature='LOCATION_HISTORY'");
  const revokedReplay = await f.initial.events(f.a, first.cursor);
  assert.deepEqual(revokedReplay.events, []);
  assert.equal(revokedReplay.cursor, 101);
  const afterHidden = await f.initial.events(f.a, revokedReplay.cursor);
  assert.deepEqual(afterHidden.events, []);
  assert.equal(afterHidden.cursor, 101);
});
