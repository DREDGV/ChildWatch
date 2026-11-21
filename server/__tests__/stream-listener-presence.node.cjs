const { test } = require('node:test');
const assert = require('node:assert/strict');
const CommandManager = require('../managers/CommandManager');
const express = require('express');
const streaming = require('../routes/streaming');

test('the listener must receive audio recently and still belong to the current session', () => {
  const manager = Object.create(CommandManager.prototype);
  const session = { listeners: new Set(['parent-a', 'parent-b']), startTime: 100 };
  manager.streamingSessions = new Map([['child', session]]);
  assert.deepEqual(manager.recentStreamingListeners('child', 110), []);
  manager.noteAudioDelivery('child', 'stranger', 110);
  manager.noteAudioDelivery('child', 'parent-a', 110);
  manager.noteAudioDelivery('child', 'parent-b', 111);
  assert.deepEqual(manager.recentStreamingListeners('child', 112), ['parent-a', 'parent-b']);
  session.listeners.delete('parent-a');
  assert.deepEqual(manager.recentStreamingListeners('child', 112), ['parent-b']);
  assert.deepEqual(manager.recentStreamingListeners('child', 8112), []);
  manager.streamingSessions.set('child', { ...session, startTime: 200 });
  assert.deepEqual(manager.recentStreamingListeners('child', 210), []);
});

test('status names recent listeners, limits names to the viewing adult\'s family and marks stale delivery', async t => {
  let listeners = [];
  const memberships = {
    alice: { familyId: 'f', actorMemberId: 'alice', actorDisplayName: 'Алиса' },
    alice2: { familyId: 'f', actorMemberId: 'alice', actorDisplayName: 'Алиса' },
    another: { familyId: 'other', actorMemberId: 'another', actorDisplayName: 'Другой взрослый' },
    revoked: { familyId: 'f', actorMemberId: 'revoked', actorDisplayName: 'Отозванный' }
  };
  const session = { startTime: Date.now(), ownerDisplayName: 'Алиса', ownerParentId: 'alice', chunks: 1 };
  streaming.init({
    recentStreamingListeners: () => listeners,
    getSessionInfo: () => session,
  }, {
    all: async (_sql, ids) => ids.includes('child') ? [{ role: 'CHILD' }] : [],
    get: async (_sql, ids) => ({ total: 1, revoked: Number(ids.includes('revoked')) }),
    getSharedFamilyMembership: async (actor, target) => target === 'child' && memberships[actor]
      ? { actorRole: 'PARENT', targetRole: 'CHILD', targetMemberId: 'child', ...memberships[actor] } : null,
    getFamilyPermission: async () => ({ allowed: 1 })
  }, {
    isChildConnected: () => true,
    hasActiveListener: () => true,
    getStats: () => ({ totalConnections: 3 })
  });
  const app = express();
  app.use((req, _res, next) => { req.deviceId = req.get('x-fixture-device'); next(); });
  app.use(streaming);
  const server = await new Promise(resolve => {
    const instance = app.listen(0, '127.0.0.1', () => resolve(instance));
  });
  t.after(() => new Promise(resolve => server.close(resolve)));
  async function status(viewer) {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/status/child`, {
      headers: { 'x-fixture-device': viewer }
    });
    return { http: response.status, presence: (await response.json()).listeningPresence };
  }
  assert.deepEqual(await status('child'), { http: 200, presence: { state: 'WAITING', names: [], unknownListener: false } });
  listeners = ['alice', 'alice2', 'another', 'revoked'];
  assert.deepEqual((await status('child')).presence,
    { state: 'ACTIVE', names: ['Алиса', 'Другой взрослый'], unknownListener: true });
  assert.deepEqual((await status('alice')).presence,
    { state: 'ACTIVE', names: ['Алиса'], unknownListener: true });
  assert.equal((await status('stranger')).http, 403);
});
