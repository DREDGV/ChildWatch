const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const FamilyPlacesService = require('../services/FamilyPlacesService');
const NOW = 1_800_000_000_000;
const DAY = 86400000;

async function fixture(t) {
  t.mock.method(Date, 'now', () => NOW);
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  t.after(() => new Promise((resolve, reject) => db.db.close(error => error ? reject(error) : resolve())));
  const members = [{ id: 'a', role: 'PARENT' }, { id: 'b', role: 'GUARDIAN' },
    { id: 'c', role: 'CHILD' }, { id: 'd', role: 'CHILD' }];
  const phones = { 'phone-a': 'a', 'phone-b': 'b', 'child-phone': 'c' };
  db.getFamilyMembers = async family => family === 'f' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(row => row.id === phones[device]);
    return member ? [{ familyId: 'f', memberId: member.id, memberRole: member.role }] : [];
  };
  await db.run(`CREATE TABLE family_permissions (id TEXT PRIMARY KEY, family_id TEXT NOT NULL,
    actor_member_id TEXT NOT NULL,target_member_id TEXT NOT NULL,feature TEXT NOT NULL,allowed INTEGER NOT NULL,
    UNIQUE(family_id,actor_member_id,target_member_id,feature))`);
  for (const owner of ['a', 'b']) for (const target of ['c', 'd']) for (const feature of ['LOCATION', 'LOCATION_HISTORY'])
    await db.run('INSERT INTO family_permissions VALUES (?,?,?,?,?,?)', [randomUUID(), 'f', owner, target, feature, 1]);
  const service = FamilyPlacesService.forDatabase(db);
  service.access.isDeviceRevoked = async () => false;
  await service.ensure();
  const a = await service.actor('phone-a', 'f'), b = await service.actor('phone-b', 'f');
  const watch = actor => service.create(actor, { requestId: randomUUID(), targetMemberId: 'c', name: 'Old school name',
    latitude: 0, longitude: 0, radius: 200, onEnter: true, onExit: true });
  async function append({ owner = 'a', target = 'c', family = 'f', watchId = randomUUID(),
    measuredAt = NOW, createdAt = NOW, personName = 'Saved child', placeName = 'Saved school' } = {}) {
    const row = await db.run(`INSERT INTO family_place_events
      (watch_id,family_id,owner_member_id,target_member_id,device_id,person_name,place_name,transition,measured_at,created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?)`, [watchId, family, owner, target, 'child-phone', personName, placeName, 'ENTER', measuredAt, createdAt]);
    return row.id;
  }
  return { db, service, a, b, phones, watch, append };
}

test('journal scopes owner, target and family and returns saved event snapshots after pause/delete', async t => {
  const f = await fixture(t);
  const paused = await f.watch(f.a), deleted = await f.watch(f.a);
  const own1 = await f.append({ watchId: paused, measuredAt: NOW - 2000 });
  const own2 = await f.append({ watchId: deleted, measuredAt: NOW - 1000 });
  await f.append({ owner: 'b' });
  await f.append({ target: 'd' });
  await f.append({ family: 'other-family' });
  await f.service.toggle(f.a, paused, false);
  await f.service.remove(f.a, deleted);
  const rowsBefore = await f.db.all('SELECT * FROM family_place_events ORDER BY id');
  const stateBefore = await f.db.all('SELECT * FROM family_place_watches ORDER BY id');
  const result = await f.service.history(f.a, 'c');
  assert.deepEqual(result.events.map(row => row.id), [own2, own1]);
  assert.equal(result.success, true);
  assert.equal(result.familyId, 'f');
  assert.equal(result.ownerMemberId, 'a');
  assert.equal(result.targetMemberId, 'c');
  assert.equal(result.retentionDays, 30);
  assert.equal(result.snapshot, own2);
  assert.equal(result.hasMore, false);
  assert.equal(result.nextBefore, null);
  assert.ok(result.events.every(row => row.person_name === 'Saved child' && row.place_name === 'Saved school'));
  assert.deepEqual(await f.db.all('SELECT * FROM family_place_events ORDER BY id'), rowsBefore);
  assert.deepEqual(await f.db.all('SELECT * FROM family_place_watches ORDER BY id'), stateBefore);
  assert.equal((await f.service.history(f.b, 'c')).events.length, 1);
  assert.equal((await f.service.history(f.a, 'd')).events.length, 1);
});

test('journal pages use one stable id snapshot while new events arrive and never repeat rows', async t => {
  const f = await fixture(t);
  for (let index = 0; index < 7; index++) await f.append();
  const first = await f.service.history(f.a, 'c', { limit: 3 });
  assert.deepEqual(first.events.map(row => row.id), [7, 6, 5]);
  assert.equal(first.snapshot, 7);
  assert.equal(first.nextBefore, 5);
  assert.equal(first.hasMore, true);
  const inserted = await f.append();
  assert.equal(inserted, 8);
  const second = await f.service.history(f.a, 'c', { limit: 3, snapshot: first.snapshot, before: first.nextBefore });
  assert.deepEqual(second.events.map(row => row.id), [4, 3, 2]);
  assert.equal(second.snapshot, 7);
  assert.equal(second.nextBefore, 2);
  const third = await f.service.history(f.a, 'c', { limit: 3, snapshot: first.snapshot, before: second.nextBefore });
  assert.deepEqual(third.events.map(row => row.id), [1]);
  assert.equal(third.hasMore, false);
  assert.equal(third.nextBefore, null);
  assert.equal(new Set([...first.events, ...second.events, ...third.events].map(row => row.id)).size, 7);
  assert.equal((await f.service.history(f.a, 'c')).events[0].id, inserted);
  assert.deepEqual((await f.service.history(f.a, 'c', { snapshot: 7, limit: 3 })).events.map(row => row.id), [7, 6, 5]);
});

test('history requires explicit target, both current permissions and freshly matched adult identity', async t => {
  const f = await fixture(t);
  await f.append();
  for (const target of [undefined, '', '   ', 1]) await assert.rejects(f.service.history(f.a, target), { status: 400 });
  await assert.rejects(f.service.history(f.a, 'absent-child'), { status: 403 });
  const child = await f.service.actor('child-phone', 'f');
  await assert.rejects(f.service.history(child, 'c'), { status: 403 });
  for (const feature of ['LOCATION', 'LOCATION_HISTORY']) {
    await f.db.run('UPDATE family_permissions SET allowed=0 WHERE actor_member_id=? AND target_member_id=? AND feature=?', ['a', 'c', feature]);
    await assert.rejects(f.service.history(f.a, 'c'), { status: 403 });
    await f.db.run('UPDATE family_permissions SET allowed=1 WHERE actor_member_id=? AND target_member_id=? AND feature=?', ['a', 'c', feature]);
  }
  f.phones['phone-a'] = 'b';
  await assert.rejects(f.service.history(f.a, 'c'), { status: 403 });
});

test('30-day retention uses creation time and preserves capture time for display', async t => {
  const f = await fixture(t);
  const exact = await f.append({ createdAt: NOW - 30 * DAY, measuredAt: NOW - 30 * DAY - 5000 });
  const delayed = await f.append({ createdAt: NOW, measuredAt: NOW - 31 * DAY });
  const expired = await f.append({ createdAt: NOW - 30 * DAY - 1, measuredAt: NOW });
  const result = await f.service.history(f.a, 'c');
  assert.deepEqual(result.events.map(row => row.id), [delayed, exact]);
  assert.equal(result.events[0].measured_at, NOW - 31 * DAY);
  assert.equal(result.snapshot, expired);
  assert.equal((await f.db.all('SELECT id FROM family_place_events')).length, 3, 'read-only history must not delete retained SQL rows');
});

test('empty snapshot retries, safe integer bounds, strict paging and maximum page size', async t => {
  const f = await fixture(t);
  const empty = await f.service.history(f.a, 'c');
  assert.deepEqual(empty.events, []);
  assert.equal(empty.snapshot, 0);
  assert.equal(empty.nextBefore, null);
  assert.equal((await f.service.history(f.a, 'c', { snapshot: 0 })).snapshot, 0);
  for (const options of [null, [], { limit: 0 }, { limit: 51 }, { limit: 1.5 }, { limit: '3' },
    { snapshot: -1 }, { snapshot: 1.5 }, { snapshot: '1' }, { snapshot: Number.MAX_SAFE_INTEGER + 1 },
    { before: 1 }, { before: 0, snapshot: 1 }, { before: 2, snapshot: 1 }, { before: 1, snapshot: 0 },
    { before: Number.MAX_SAFE_INTEGER + 1, snapshot: Number.MAX_SAFE_INTEGER }])
    await assert.rejects(f.service.history(f.a, 'c', options), { status: 400 });
  assert.equal((await f.service.history(f.a, 'c', { before: Number.MAX_SAFE_INTEGER, snapshot: Number.MAX_SAFE_INTEGER })).events.length, 0);
  for (let index = 0; index < 51; index++) await f.append();
  assert.equal((await f.service.history(f.a, 'c')).events.length, 30);
  const max = await f.service.history(f.a, 'c', { limit: 50 });
  assert.equal(max.events.length, 50);
  assert.equal(max.hasMore, true);
});

test('GET /history validates scalar decimal query fields and returns service contract', async t => {
  const f = await fixture(t);
  await f.append();
  const router = require('../routes/family-places');
  router.init(f.db);
  const middleware = router.stack.find(layer => !layer.route && layer.handle.length === 3).handle;
  const handler = router.stack.find(layer => layer.route?.path === '/history').route.stack[0].handle;
  const errorHandler = router.stack.find(layer => !layer.route && layer.handle.length === 4).handle;
  async function request(query = {}, deviceId = 'phone-a') {
    let status = 200, body, allowed = false;
    const req = { deviceId, query: { familyId: 'f', actorMemberId: 'a', targetMemberId: 'c', ...query } };
    const res = { status(value) { status = value; return this; }, json(value) { body = value; return this; } };
    const fail = error => errorHandler(error, req, res, () => {});
    await middleware(req, res, error => { if (error) fail(error); else allowed = true; });
    if (allowed) await handler(req, res, fail);
    return { status, body };
  }
  const valid = await request({ limit: '1' });
  assert.equal(valid.status, 200);
  assert.equal(valid.body.success, true);
  assert.equal(valid.body.events.length, 1);
  for (const query of [{ targetMemberId: '' }, { targetMemberId: ['c'] }, { limit: '1.2' },
    { limit: ['1'] }, { snapshot: '' }, { snapshot: '1e3' }, { before: '2', snapshot: '1' }])
    assert.equal((await request(query)).status, 400);
  assert.equal((await request({}, null)).status, 401);
  assert.equal((await request({ actorMemberId: 'b' })).status, 403);
});
