const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const sqlite3 = require('sqlite3');
const Reader = require('../services/DeviceStatusReadService');

async function fixture() {
  const conn = new sqlite3.Database(':memory:');
  const db = {
    run: (sql, params = []) => new Promise((resolve, reject) => conn.run(sql, params, e => e ? reject(e) : resolve())),
    get: (sql, params = []) => new Promise((resolve, reject) => conn.get(sql, params, (e, row) => e ? reject(e) : resolve(row))),
    all: (sql, params = []) => new Promise((resolve, reject) => conn.all(sql, params, (e, rows) => e ? reject(e) : resolve(rows))),
    withTransaction: async work => { await db.run('BEGIN'); try { const result = await work(); await db.run('COMMIT'); return result; }
      catch (e) { await db.run('ROLLBACK'); throw e; } },
    getFamilyIdentityMembershipsForDevice: id => db.all(`SELECT fd.family_id AS familyId,
      fm.id AS memberId,fm.role AS memberRole FROM family_devices fd
      JOIN family_members fm ON fm.id=fd.member_id AND fm.family_id=fd.family_id
      JOIN families f ON f.id=fd.family_id WHERE fd.device_id=? AND fd.is_active=1
      AND fm.is_active=1 AND f.is_active=1`, [id]),
    getFamilyPermission: p => db.get(`SELECT allowed FROM family_permissions WHERE family_id=?
      AND actor_member_id=? AND target_member_id=? AND feature=?`, [p.familyId,p.actorMemberId,p.targetMemberId,p.feature]),
    getDeviceStatusHistory: async (id, limit) => (await db.all('SELECT data FROM statuses WHERE device_id=? ORDER BY timestamp DESC LIMIT ?', [id,limit])).map(r => JSON.parse(r.data)),
    getLatestDeviceStatus: async id => (await db.getDeviceStatusHistory(id,1))[0] || null,
    close: () => new Promise(resolve => conn.close(resolve)),
  };
  for (const sql of [
    'CREATE TABLE devices(device_id TEXT,is_active INTEGER)',
    'CREATE TABLE families(id TEXT,is_active INTEGER)',
    'CREATE TABLE family_members(id TEXT,family_id TEXT,role TEXT,is_active INTEGER)',
    'CREATE TABLE family_devices(device_id TEXT,family_id TEXT,member_id TEXT,is_active INTEGER)',
    'CREATE TABLE family_permissions(family_id TEXT,actor_member_id TEXT,target_member_id TEXT,feature TEXT,allowed INTEGER)',
    'CREATE TABLE device_links(parent_device_id TEXT,child_device_id TEXT,is_active INTEGER)',
    'CREATE TABLE statuses(device_id TEXT,timestamp INTEGER,data TEXT)',
    "INSERT INTO families VALUES('f',1),('foreign',1)",
    "INSERT INTO family_members VALUES('adult','f','PARENT',1),('child','f','CHILD',1),('other','foreign','CHILD',1)",
    "INSERT INTO family_devices VALUES('parent','f','adult',1),('device_0123456789abcdef','f','child',1),('stranger','foreign','other',1)",
    "INSERT INTO devices VALUES('parent',1),('device_0123456789abcdef',1),('stranger',1)",
    "INSERT INTO family_permissions VALUES('f','adult','child','APP_USAGE',1)",
  ]) await db.run(sql);
  const sample = { batteryLevel: 49, timestamp: 10, currentAppName: 'Private', currentAppPackage: 'private.app',
    recentApps: ['private'], raw: { batteryLevel:49,currentApp:{ packageName:'private.app' },recentApps:['private'],
      dailyUsage:{ private:30 },appUsageCollectedAt:9,appUsageStale:false,usagePermissionGranted:true } };
  await db.run('INSERT INTO statuses VALUES(?,?,?)', ['device_0123456789abcdef',10,JSON.stringify(sample)]);
  await db.run('INSERT INTO statuses VALUES(?,?,?)', ['0123456789abcdef',20,JSON.stringify({...sample,timestamp:20})]);
  return { db, reader: new Reader(db), sample };
}
const request = extra => ({ callerDeviceId:'parent',targetDeviceId:'0123456789abcdef',...extra });
test('foreign target denied, including omission of purpose', async () => {
  const {db,reader}=await fixture(); try { assert.equal((await reader.read(request({targetDeviceId:'stranger'}))).allowed,false); } finally {await db.close();}
});
test('explicit usage permission, alias merge newest and global history limit', async () => {
  const {db,reader}=await fixture(); try {
    assert.equal((await reader.read(request({purpose:'app_usage',familyId:'f',actorMemberId:'adult'}))).status.timestamp,20);
    const r=await reader.read(request({history:true,limit:1,purpose:'app_usage'})); assert.equal(r.statuses.length,1);assert.equal(r.statuses[0].timestamp,20);
  } finally {await db.close();}
});
test('revoked usage denies purpose and generic reads redact without mutating stored data', async () => {
  const {db,reader}=await fixture(); try {
    await db.run('UPDATE family_permissions SET allowed=0');
    assert.equal((await reader.read(request({purpose:'app_usage'}))).code,'APP_USAGE_PERMISSION_DENIED');
    const r=await reader.read(request({history:true})); assert.equal(r.allowed,true);
    for(const s of r.statuses) {assert.equal(s.batteryLevel,49);assert.equal(s.raw.batteryLevel,49);
      for(const key of ['currentAppName','currentAppPackage','recentApps']) assert.equal(key in s,false);
      for(const key of ['currentApp','recentApps','dailyUsage','appUsageCollectedAt','appUsageStale','usagePermissionGranted']) assert.equal(key in s.raw,false);}
    assert.equal((await db.getLatestDeviceStatus('0123456789abcdef')).raw.dailyUsage.private,30);
    assert.equal(Reader.redact({...r.statuses[0],recentApps:['restored']},false).recentApps,undefined);
  } finally {await db.close();}
});
test('scope changes and revoked membership do not fall back to legacy link', async () => {
  const {db,reader}=await fixture(); try {
    assert.equal((await reader.read(request({familyId:'foreign',actorMemberId:'adult'}))).allowed,false);
    assert.equal((await reader.read(request({familyId:'f',actorMemberId:'wrong'}))).allowed,false);
    await db.run("INSERT INTO device_links VALUES('parent','0123456789abcdef',1)");
    await db.run("UPDATE family_devices SET is_active=0 WHERE member_id='child'");
    assert.equal((await reader.read(request({}))).allowed,false);
  } finally {await db.close();}
});
test('self aliases may read own usage; revoked device denied', async () => {
  const {db,reader}=await fixture(); try {
    assert.equal((await reader.read(request({callerDeviceId:'device_0123456789abcdef',purpose:'app_usage'}))).usageAllowed,true);
    await db.run("UPDATE devices SET is_active=0 WHERE device_id='device_0123456789abcdef'");
    assert.equal((await reader.read(request({}))).allowed,false);
  } finally {await db.close();}
});
test('legacy active link permits battery but never remote usage; deactivated link denies', async () => {
  const {db,reader}=await fixture(); try {
    await db.run("INSERT INTO device_links VALUES('legacy-adult','legacy-child',1)");
    const req={callerDeviceId:'legacy-adult',targetDeviceId:'legacy-child'};
    assert.equal((await reader.read(req)).allowed,true);
    assert.equal((await reader.read({...req,purpose:'app_usage'})).allowed,false);
    await db.run('UPDATE device_links SET is_active=0'); assert.equal((await reader.read(req)).allowed,false);
  } finally {await db.close();}
});
test('role restriction: same-family child cannot read another member usage', async () => {
  const {db,reader}=await fixture(); try {
    await db.run("UPDATE family_members SET role='CHILD' WHERE id='adult'");
    assert.equal((await reader.read(request({purpose:'app_usage'}))).allowed,false);
    assert.equal((await reader.read(request({}))).usageAllowed,false);
  } finally {await db.close();}
});
test('both real route handlers delegate authorization and redact after enrichment', () => {
  const source=fs.readFileSync(require.resolve('../index.js'),'utf8');
  for(const route of ['/api/device/status/history/:deviceId','/api/device/status/:deviceId?']) {
    const section=source.slice(source.indexOf('"'+route+'"'),source.indexOf('\n);',source.indexOf('"'+route+'"')));
    assert.match(section,/new StatusReader\(dbManager\)\.read/);
    assert.match(section,/if \(!read.allowed\) return res.status\(403\)/);
    assert.match(section,/StatusReader.redact\(enrichDeviceStatus/);
  }
});
