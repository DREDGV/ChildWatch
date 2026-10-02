// Run directly with Node; .node.cjs keeps this isolated smoke outside Jest discovery.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const FamilyPickupService = require('../services/FamilyPickupService');

test('pickup SQLite transactions, retry, handoff, expiry and access gates', async () => {
  // Real SQL and the production transaction queue; family authority is a fixture.
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  const members = [{ id: 'c', role: 'CHILD' }, { id: 'a', role: 'PARENT' }, { id: 'b', role: 'GUARDIAN' }];
  let allowed = true, revoked = false, replacement = false;
  db.getFamilyMembers = async () => members;
  db.getFamilyPermission = async () => ({ allowed: allowed ? 1 : 0 });
  db.getFamilyIdentityMembershipsForDevice = async id => {
    const member = members.find(m => m.id === id);
    return member ? [{ familyId: 'f', memberId: replacement ? 'other' : member.id, memberRole: member.role }] : [];
  };
  const service = new FamilyPickupService(db);
  service.access.isDeviceRevoked = async () => revoked;
  const child = await service.actor('c', 'f'), a = await service.actor('a', 'f'), b = await service.actor('b', 'f');
  const point = () => ({ requestId: randomUUID(), latitude: 55, longitude: 83, place: 'School gate', note: '', pointConfirmed: true });
  const action = (version, kind) => ({ actionId: randomUUID(), version, action: kind });
  try {
    const input = point();
    let row = await service.create(child, input);
    assert.equal((await service.create(child, input)).id, row.id);
    await assert.rejects(service.create(child, { ...input, place: 'Other gate' }), { code: 'PICKUP_ID_REUSED' });
    await assert.rejects(service.create(child, point()), { code: 'PICKUP_ALREADY_ACTIVE' });
    const claims = [action(row.version, 'ACCEPT'), action(row.version, 'ACCEPT')];
    const results = await Promise.allSettled([service.action(a, row.id, claims[0]), service.action(b, row.id, claims[1])]);
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    const winner = results[0].status === 'fulfilled' ? 0 : 1;
    const owner = winner === 0 ? a : b, other = winner === 0 ? b : a;
    row = results[winner].value;
    assert.equal((await service.action(owner, row.id, claims[winner])).version, row.version);
    await assert.rejects(service.action(other, row.id, action(row.version, 'CANCEL')), { code: 'PICKUP_ACCESS_DENIED' });
    row = await service.action(owner, row.id, action(row.version, 'RELEASE'));
    assert.equal(row.status, 'REQUESTED'); assert.equal(row.adultMemberId, null);
    row = await service.action(other, row.id, action(row.version, 'ACCEPT'));
    row = await service.action(other, row.id, action(row.version, 'DEPART'));
    row = await service.action(other, row.id, action(row.version, 'ARRIVE'));
    row = await service.action(child, row.id, action(row.version, 'CONFIRM'));
    assert.equal(row.status, 'HANDOFF');
    row = await service.action(other, row.id, action(row.version, 'CONFIRM'));
    assert.equal(row.status, 'COMPLETED');
    await assert.rejects(service.action(other, row.id, action(row.version, 'ACCEPT')), { code: 'PICKUP_CLOSED' });
    row = await service.create(child, point());
    allowed = false;
    assert.equal((await service.list(a)).requests.length, 0);
    await assert.rejects(service.action(a, row.id, action(row.version, 'ACCEPT')), { code: 'PICKUP_ACCESS_DENIED' });
    allowed = true; revoked = true;
    await assert.rejects(service.action(a, row.id, action(row.version, 'ACCEPT')), { code: 'PICKUP_ACCESS_DENIED' });
    revoked = false; replacement = true;
    await assert.rejects(service.action(a, row.id, action(row.version, 'ACCEPT')), { code: 'PICKUP_CONTEXT_CHANGED' });
    replacement = false;
    await db.run('UPDATE family_pickups SET expires_at=? WHERE id=?', [Date.now() - 1, row.id]);
    const restarted = new FamilyPickupService(db);
    const rows = (await restarted.list(child)).requests;
    assert.equal(rows.find(r => r.id === row.id).status, 'EXPIRED');
    await assert.rejects(service.action(a, row.id, action(row.version + 1, 'ACCEPT')), { code: 'PICKUP_CLOSED' });
  } finally { await new Promise((resolve, reject) => db.db.close(error => error ? reject(error) : resolve())); }
});
