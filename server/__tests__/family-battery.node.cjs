const { test } = require('node:test');
const assert = require('node:assert/strict');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const battery = require('../services/FamilyBatterySnapshot');
const now = 1_800_000_000_000;

test('family endpoint joins charge after LOCATION authorization and keeps GPS on telemetry failure', async () => {
  const route = require('../routes/location');
  let allowed = 1, member = true, failBattery = false, batteryReads = 0;
  const capture = Date.now() - 5000;
  const db = {
    run: async () => {}, all: async () => [],
    getFamilyIdentityMembershipsForDevice: async () => member ? [{familyId:'family', memberId:'parent'}] : [],
    getFamilyMembers: async () => [{id:'child', displayName:'Child', role:'CHILD'}],
    getFamilyDevices: async () => [{memberId:'child', deviceId:'child-phone'}],
    getFamilyPermission: async () => ({allowed}),
    get: async (sql, ids) => {
      if (sql.includes('FROM locations')) return {latitude:55, longitude:83, accuracy:10, timestamp:capture};
      if (sql.includes('FROM device_status')) {
        batteryReads++;
        assert.deepEqual(ids, ['child-phone']);
        if (failBattery) throw new Error('fixture telemetry failure');
        return {device_id:'child-phone', battery_level:40, is_charging:0, timestamp:capture - 5000};
      }
      return null;
    }
  };
  route.init(db);
  const handler = route.stack.find(layer => layer.route?.path === '/family/latest').route.stack[0].handle;
  async function request(deviceId = 'parent-phone') {
    let body, code = 200;
    const res = {status(value) {code=value; return this;}, json(value) {body=value; return this;}};
    await handler({query:{familyId:'family'}, deviceId}, res);
    return {code, body};
  }
  const success = await request();
  assert.equal(success.body.locations[0].battery.level, 40);
  assert.equal(success.body.locations[0].battery.timestamp, capture - 5000);
  assert.equal(success.body.locations[0].timestamp, capture);
  batteryReads = 0; allowed = 0;
  assert.deepEqual((await request()).body.locations, []);
  assert.equal(batteryReads, 0);
  member = false;
  assert.equal((await request()).code, 403);
  assert.equal((await request(null)).code, 401);
  assert.equal(batteryReads, 0);
  member = true; allowed = 1; failBattery = true;
  const failedTelemetry = await request();
  assert.equal(failedTelemetry.code, 200);
  assert.equal(failedTelemetry.body.locations[0].battery, null);
  assert.equal(failedTelemetry.body.locations[0].timestamp, capture);
});

test('battery reads the selected device and both historical spellings, not newer sibling data', async () => {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  try {
    await db.run('CREATE TABLE device_status(device_id TEXT, battery_level INTEGER, is_charging INTEGER, timestamp INTEGER)');
    await db.run('INSERT INTO device_status VALUES(?,?,?,?)', ['device_child', 0, 1, now - 60_000]);
    await db.run('INSERT INTO device_status VALUES(?,?,?,?)', ['sibling', 99, 0, now]);
    const snapshot = await battery.read(db, 'child', ['child', 'device_child'], now);
    assert.deepEqual(snapshot, {deviceId: 'child', level: 0, isCharging: true, timestamp: now - 60_000});
    assert.equal(await battery.read(db, 'other', ['other'], now), null);
    await db.run('INSERT INTO device_status VALUES(?,?,?,?)', ['child', null, 0, now]);
    assert.equal(await battery.read(db, 'child', ['child', 'device_child'], now), null);
  } finally { await db.close(); }
});

test('capture validation preserves stale time and rejects unknown, fractions, future, wrong device', async () => {
  for (const level of [null, -1, 101, 3.5, '50']) {
    assert.equal(await battery.read({get: async () => ({device_id:'child', battery_level:level, timestamp:now})}, 'child', ['child'], now), null);
  }
  assert.equal(await battery.read({get: async () => ({device_id:'parent', battery_level:50, timestamp:now})}, 'child', ['child'], now), null);
  for (const timestamp of [null, 0, now + 1]) {
    assert.equal(await battery.read({get: async () => ({device_id:'child', battery_level:50, timestamp})}, 'child', ['child'], now), null);
  }
  const old = now - 2 * 60 * 60_000;
  assert.deepEqual(await battery.read({get: async () => ({device_id:'child', battery_level:100, is_charging:2, timestamp:old / 1000})}, 'child', ['child'], now),
    {deviceId:'child', level:100, isCharging:null, timestamp:old});
});
