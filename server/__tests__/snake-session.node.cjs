const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const Games = require('../services/FamilyGameService');
const Sessions = require('../services/SnakeSessionService');
const SocketService = require('../services/SnakeSocketService');

async function fixture() {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', e => e ? reject(e) : resolve(connection));
  });
  const members = [{ id: 'host', role: 'PARENT' }, { id: 'guest', role: 'CHILD' }, { id: 'other', role: 'GUARDIAN' }];
  const bindings = { a: 'host', b: 'guest', b2: 'guest', c: 'other' }, revoked = new Set();
  db.getFamilyMembers = async family => family === 'family' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(m => m.id === bindings[device]);
    return member ? [{ familyId: 'family', memberId: member.id, memberRole: member.role }] : [];
  };
  const games = new Games(db, { clock: () => 1000000 });
  games.access.isDeviceRevoked = async device => revoked.has(device);
  const actors = { host: await games.actor('a', 'family'), guest: await games.actor('b', 'family'),
    guest2: await games.actor('b2', 'family'), other: await games.actor('c', 'family') };
  let now = 0;
  const options = { clock: () => now, wallClock: () => 1000000 + now };
  const sessions = new Sessions(games, options);
  let game = (await games.create(actors.host, { gameId: randomUUID(), type: 'SNAKE_COOP', targetMemberId: 'guest' })).game;
  const invited = game;
  game = (await games.action(actors.guest, game.id, { actionId: randomUUID(), version: game.version, action: 'ACCEPT' })).game;
  const sent = [[], []];
  const join = (player, token = player ? 'b' : 'a', actor = player ? actors.guest : actors.host) =>
    sessions.join(actor, game.id, token, state => sent[player].push(state));
  const command = (player, action, extra = {}) => sessions.command(player ? actors.guest : actors.host,
    game.id, player ? 'b' : 'a', { epoch: sessions.rooms.get(game.id).epoch, action, ...extra });
  const start = async () => { await join(0); await join(1); await command(0, 'READY'); await command(1, 'READY'); now += 2000; await sessions.pulse(); };
  return { db, games, actors, sessions, options, game, invited, sent, revoked, bindings, members, join, command, start,
    advance: ms => { now += ms; }, close: () => new Promise((resolve, reject) => db.db.close(e => e ? reject(e) : resolve())) };
}

test('two players share countdown and authoritative ticks; no movement before both ready', async () => {
  const f = await fixture(); try {
    await f.join(0); await f.command(0, 'READY'); f.advance(2000); await f.sessions.pulse();
    assert.equal(f.sessions.state(f.sessions.rooms.get(f.game.id)).snake.tick, '0');
    await f.join(1); assert.deepEqual(f.sessions.rooms.get(f.game.id).ready, [false, false]);
    await f.command(0, 'READY'); await f.command(1, 'READY');
    assert.equal(f.sent[0].at(-1).phase, 'COUNTDOWN');
    const repeat = await f.command(1, 'READY'); assert.equal(repeat.phase, 'COUNTDOWN');
    f.advance(2000); await f.sessions.pulse();
    assert.equal(f.sent[0].at(-1).phase, 'PLAYING');
    const a = await f.command(0, 'INPUT', { sequence: 1, targetTick: 1, direction: 'UP' });
    assert.equal(a.replayed, false);
    await f.command(1, 'INPUT', { sequence: 1, targetTick: 1, direction: 'DOWN' });
    f.advance(180); await f.sessions.pulse();
    assert.deepEqual(f.sent[0].at(-1), f.sent[1].at(-1));
    assert.equal(f.sent[0].at(-1).snake.tick, '1');
    assert.equal(f.sent[0].at(-1).snake.players[0].body[0], 145);
    assert.equal(f.sent[0].at(-1).snake.players[1].body[0], 374);
  } finally { await f.close(); }
});

test('input retry applies once; altered sequence, late/future/reverse and queue floods rejected', async () => {
  const f = await fixture(); try {
    await f.start(); const input = { sequence: 1, targetTick: 1, direction: 'UP' };
    await f.command(0, 'INPUT', input); assert.equal((await f.command(0, 'INPUT', input)).replayed, true);
    await assert.rejects(f.command(0, 'INPUT', { ...input, direction: 'DOWN' }), { code: 'SNAKE_SEQUENCE_REUSED' });
    await assert.rejects(f.command(0, 'INPUT', { sequence: 3, targetTick: 2, direction: 'LEFT' }), { code: 'SNAKE_SEQUENCE_CONFLICT' });
    await assert.rejects(f.command(0, 'INPUT', { sequence: 2, targetTick: 4, direction: 'LEFT' }), { code: 'SNAKE_TICK_WINDOW' });
    await assert.rejects(f.command(0, 'INPUT', { sequence: 2, targetTick: 2, direction: 'DOWN' }), { code: 'SNAKE_INVALID_TURN' });
    await f.command(0, 'INPUT', { sequence: 2, targetTick: 2, direction: 'LEFT' });
    await assert.rejects(f.command(0, 'INPUT', { sequence: 3, targetTick: 3, direction: 'DOWN' }), { code: 'SNAKE_INPUT_QUEUE' });
    f.advance(180); await f.sessions.pulse();
    assert.equal((await f.command(0, 'INPUT', input)).replayed, true);
    f.advance(180); await f.sessions.pulse();
    assert.equal(f.sent[0].at(-1).snake.players[0].body[0], 144);
    await assert.rejects(f.command(1, 'INPUT', { sequence: 1, targetTick: 2, direction: 'UP' }), { code: 'SNAKE_TICK_WINDOW' });
  } finally { await f.close(); }
});

test('disconnect clears inputs and changes epoch; stale disconnect cannot evict replacement', async () => {
  const f = await fixture(); try {
    await f.start(); const oldEpoch = f.sessions.rooms.get(f.game.id).epoch;
    await f.command(0, 'INPUT', { sequence: 1, targetTick: 1, direction: 'UP' });
    await f.sessions.leave('b'); const room = f.sessions.rooms.get(f.game.id);
    assert.equal(room.phase, 'PAUSED'); assert.notEqual(room.epoch, oldEpoch); assert.deepEqual(room.inputs, [[], []]);
    f.advance(4000); await f.sessions.pulse(); assert.equal(room.engine.ticks, 0n);
    await assert.rejects(f.sessions.command(f.actors.host, f.game.id, 'a', { epoch: oldEpoch, action: 'READY' }), { code: 'SNAKE_EPOCH_CHANGED' });
    await f.join(1, 'replacement', f.actors.guest2); await f.sessions.leave('b');
    assert.equal(room.connections[1].token, 'replacement');
    await f.command(0, 'READY');
    await f.sessions.command(f.actors.guest2, f.game.id, 'replacement', { epoch: room.epoch, action: 'READY' });
    f.advance(2000); await f.sessions.pulse(); f.advance(180); await f.sessions.pulse();
    assert.equal(room.engine.ticks, 1n); assert.equal(room.engine.players[0].body[0], 166);
  } finally { await f.close(); }
});

test('lease loss, foreground pause and server stall pause globally without catch-up or defeat', async () => {
  for (const kind of ['lease', 'pause', 'stall']) {
    const f = await fixture(); try {
      await f.start(); const room = f.sessions.rooms.get(f.game.id);
      if (kind === 'pause') await f.command(0, 'PAUSE');
      else { f.advance(kind === 'lease' ? 12001 : 1000); await f.sessions.pulse(); }
      assert.equal(room.phase, 'PAUSED'); assert.equal(room.engine.ticks, 0n);
      assert.equal(room.reason, kind === 'pause' ? 'PLAYER_PAUSED' : kind === 'lease' ? 'PLAYER_DISCONNECTED' : 'SERVER_DELAY');
      assert.deepEqual(room.ready, [false, false]);
    } finally { await f.close(); }
  }
});

test('revocation/context change/outsider and closed invitation never receive a playable stream', async () => {
  const f = await fixture(); try {
    await assert.rejects(f.sessions.join(f.actors.other, f.game.id, 'x', () => {}), { status: 404 });
    await f.start(); f.revoked.add('b'); f.advance(250); await f.sessions.pulse();
    const room = f.sessions.rooms.get(f.game.id); assert.equal(room.phase, 'PAUSED'); assert.equal(room.connections[1], null);
    await assert.rejects(f.command(1, 'READY'), { status: 403 });
    f.revoked.clear(); f.bindings.b = 'other'; await assert.rejects(f.join(1), { code: 'GAME_CONTEXT_CHANGED' });
    const game = await f.games.get(f.actors.host, f.game.id);
    await f.games.action(f.actors.host, f.game.id, { actionId: randomUUID(), version: game.version, action: 'RESIGN' });
    await assert.rejects(f.command(0, 'READY'), { code: 'GAME_CLOSED' });
    f.advance(250); await f.sessions.pulse(); assert.equal(room.connections[0], null);
    assert.equal((await f.games.get(f.actors.host, f.game.id)).status, 'RESIGNED');
  } finally { await f.close(); }
});

test('batched checkpoint recovers after restart paused with a new epoch and no unacknowledged inputs', async () => {
  const f = await fixture(); try {
    await f.start(); f.advance(180); await f.sessions.pulse(); await f.command(0, 'PAUSE');
    const old = f.sessions.rooms.get(f.game.id);
    await f.command(0, 'READY'); await f.command(1, 'READY'); f.advance(2000); await f.sessions.pulse();
    const restarted = new Sessions(f.games, f.options);
    const state = await restarted.join(f.actors.host, f.game.id, 'new-a', () => {});
    assert.equal(state.phase, 'PAUSED'); assert.notEqual(state.epoch, old.epoch);
    assert.equal(state.snake.tick, '1'); assert.deepEqual(state.ready, [false, false]);
    assert.deepEqual(state.snake.players, f.sessions.state(old).snake.players);
  } finally { await f.close(); }
});

test('expired socket credentials stop the stream within the authorization interval', async () => {
  const f = await fixture(); try {
    let valid = true;
    await f.sessions.join(f.actors.host, f.game.id, 'a', state => f.sent[0].push(state), async () => valid);
    await f.join(1); await f.command(0, 'READY'); await f.command(1, 'READY');
    f.advance(2000); await f.sessions.pulse(); valid = false;
    f.advance(250); await f.sessions.pulse();
    const room = f.sessions.rooms.get(f.game.id);
    assert.equal(room.connections[0], null); assert.equal(room.phase, 'PAUSED');
    assert.equal(f.sent[0].at(-1).code, 'GAME_AUTH_REQUIRED');
    assert.equal(f.sent[1].at(-1).reason, 'ACCESS_CHANGED');
  } finally { await f.close(); }
});

test('paused checkpoint failure is retryable and room capacity is bounded', async () => {
  const f = await fixture(); try {
    await f.start(); const run = f.db.run.bind(f.db); let rejected = false;
    f.db.run = async (sql, args) => {
      if (!rejected && sql.includes('INSERT INTO family_snake_sessions')) { rejected = true; throw new Error('pause checkpoint failed once'); }
      return run(sql, args);
    };
    await assert.rejects(f.command(0, 'PAUSE'), /pause checkpoint failed once/);
    const room = f.sessions.rooms.get(f.game.id); assert.equal(room.phase, 'PAUSED');
    await f.sessions.pulse(); assert.equal(room.checkpointPending, false);
    assert.equal(f.sent[1].at(-1).phase, 'PAUSED');
    const limited = new Sessions(f.games, { ...f.options, maxRooms: 0 });
    await assert.rejects(limited.join(f.actors.host, f.game.id, 'x', () => {}), { code: 'SNAKE_ROOM_LIMIT' });
  } finally { await f.close(); }
});

test('failed terminal SQLite checkpoint is retried; restart retains result', async () => {
  const f = await fixture(); try {
    await f.start(); const room = f.sessions.rooms.get(f.game.id);
    // Head x=5, moving right: collision at the fifteenth tick. Checkpoint batches are independent.
    for (let i = 0; i < 14; i++) { f.advance(180); await f.command(0, 'PING'); await f.command(1, 'PING'); await f.sessions.pulse(); }
    const run = f.db.run.bind(f.db); let rejected = false;
    f.db.run = async (sql, args) => {
      if (!rejected && sql.includes('INSERT INTO family_snake_sessions')) { rejected = true; throw new Error('write failed once'); }
      return run(sql, args);
    };
    f.advance(180); await assert.rejects(f.sessions.pulse(), /write failed once/);
    assert.equal(room.phase, 'FINISHED'); assert.equal(room.checkpointPending, true);
    await f.sessions.pulse(); assert.equal(room.checkpointPending, false);
    assert.equal((await f.games.get(f.actors.host, f.game.id)).status, 'LOST');
    const state = await new Sessions(f.games, f.options).join(f.actors.host, f.game.id, 'new', () => {});
    assert.equal(state.phase, 'FINISHED'); assert.equal(state.snake.state, 'GAME_OVER');
  } finally { await f.close(); }
});

test('socket handler rejects unauthenticated and wrong-member joins and sends no private snapshot', async () => {
  const f = await fixture(); try {
    const socket = { connected: true, handlers: {}, messages: [], authMode: 'legacy',
      on(event, handler) { this.handlers[event] = handler; }, emit(event, payload) { this.messages.push({ event, payload }); } };
    new SocketService({}, f.sessions).register(socket);
    const call = async input => { let result; await socket.handlers['snake:join'](input, value => { result = value; }); return result; };
    assert.equal((await call({ familyId: 'family', actorMemberId: 'host', gameId: f.game.id })).code, 'GAME_AUTH_REQUIRED');
    socket.authMode = 'authenticated'; socket.authenticatedDeviceId = 'a';
    assert.equal((await call({ familyId: 'family', actorMemberId: 'guest', gameId: f.game.id })).code, 'GAME_CONTEXT_CHANGED');
    assert.equal(socket.messages.length, 0);
    assert.equal((await call({ familyId: 'family', actorMemberId: 'host', gameId: f.game.id })).success, true);
    assert.equal(socket.messages.at(-1).event, 'snake:state');
    socket.connected = false; await socket.handlers.disconnect(); await f.sessions.chain;
    assert.equal(f.sessions.rooms.get(f.game.id).connections[0], null);
  } finally { await f.close(); }
});
