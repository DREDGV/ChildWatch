const { randomUUID } = require('node:crypto');
const { performance } = require('node:perf_hooks');
const FamilyGameService = require('./FamilyGameService');
const Snake = require('./SnakeRules');

const fail = (status, code) => { throw new FamilyGameService.Error(status, code); };
const DIRECTIONS = ['UP', 'RIGHT', 'DOWN', 'LEFT'];
const tickNumber = engine => {
  const value = Number(Snake.view(engine).tick);
  if (!Number.isSafeInteger(value) || value < 0) fail(500, 'SNAKE_TICK_OVERFLOW');
  return value;
};

/** Single-process authoritative simulation. Socket identity is never supplied by an input. */
class SnakeSessionService {
  constructor(games, { clock = () => performance.now(), wallClock = Date.now,
    countdownMs = 2000, leaseMs = 12000, maxRooms = 100 } = {}) {
    this.games = games; this.db = games.db; this.clock = clock; this.wallClock = wallClock;
    this.countdownMs = countdownMs; this.leaseMs = leaseMs; this.maxRooms = maxRooms;
    this.rooms = new Map(); this.chain = Promise.resolve(); this.pulsing = false;
  }
  serial(work) {
    const result = this.chain.then(work);
    this.chain = result.catch(() => {}); return result;
  }
  async ensure() {
    await this.games.ensure();
    if (!this.ready) this.ready = this.db.run(`CREATE TABLE IF NOT EXISTS family_snake_sessions (
      game_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, epoch TEXT NOT NULL,
      revision INTEGER NOT NULL, updated_at INTEGER NOT NULL)`)
      .catch(error => { this.ready = null; throw error; });
    return this.ready;
  }
  async authorize(actor, id) {
    const result = await this.games.authorize(actor, id);
    if (result.row.type !== 'SNAKE_COOP') fail(400, 'SNAKE_WRONG_GAME');
    if (!['SETUP', 'PLAYING', 'WON', 'LOST'].includes(result.row.status)) fail(409, 'GAME_CLOSED');
    return result;
  }
  async room(actor, id) {
    await this.ensure();
    const { row } = await this.authorize(actor, id);
    let room = this.rooms.get(id);
    if (room) return room;
    if (this.rooms.size >= this.maxRooms) fail(429, 'SNAKE_ROOM_LIMIT');
    const saved = await this.db.get('SELECT * FROM family_snake_sessions WHERE game_id=?', [id]);
    const engine = saved ? Snake.restore(JSON.parse(saved.snapshot)) : Snake.restore(JSON.parse(row.board));
    if (!engine) fail(500, 'SNAKE_CHECKPOINT_INVALID');
    room = { id, engine, epoch: randomUUID(), revision: (saved?.revision || 0) + 1,
      phase: ['WON', 'LOST'].includes(row.status) ? 'FINISHED' : 'PAUSED', reason: saved ? 'SERVER_RESTART' : 'WAITING_PLAYERS',
      members: [row.host_member_id, row.guest_member_id], connections: [null, null],
      ready: [false, false], inputs: [[], []], receipts: [new Map(), new Map()],
      sequences: [0, 0], due: null, checkpointAt: this.clock(), authorizedAt: -Infinity };
    this.rooms.set(id, room);
    try { await this.checkpoint(room); } catch (error) { this.rooms.delete(id); throw error; }
    return room;
  }
  state(room) {
    return { protocolVersion: 1, gameId: room.id, epoch: room.epoch, revision: room.revision,
      phase: room.phase, reason: room.reason, ready: [...room.ready],
      connected: room.connections.map(Boolean), countdownMs: room.phase === 'COUNTDOWN' ? Math.max(0, room.due - this.clock()) : 0,
      snake: Snake.view(room.engine) };
  }
  publish(room) {
    const payload = this.state(room);
    for (const connection of room.connections) if (connection) connection.send(payload);
    return payload;
  }
  async checkpoint(room) {
    room.checkpointPending = true;
    const snapshot = JSON.stringify(Snake.snapshot(room.engine));
    await this.db.withTransaction(async () => {
      await this.db.run(`INSERT INTO family_snake_sessions(game_id,snapshot,epoch,revision,updated_at)
        VALUES (?,?,?,?,?) ON CONFLICT(game_id) DO UPDATE SET snapshot=excluded.snapshot,
        epoch=excluded.epoch,revision=excluded.revision,updated_at=excluded.updated_at`,
      [room.id, snapshot, room.epoch, room.revision, this.wallClock()]);
      const status = room.phase === 'FINISHED' ? (room.engine.state === 'WON' ? 'WON' : 'LOST') : 'PLAYING';
      await this.db.run(`UPDATE family_games SET board=?,status=?,version=version+1,updated_at=?
        WHERE id=? AND status IN ('SETUP','PLAYING')`, [snapshot, status, this.wallClock(), room.id]);
    });
    room.checkpointAt = this.clock();
    room.checkpointPending = false;
  }
  async pause(room, reason) {
    if (room.phase === 'FINISHED') return;
    Snake.pause(room.engine); room.phase = 'PAUSED'; room.reason = reason; room.due = null;
    room.ready = [false, false]; room.inputs = [[], []]; room.receipts = [new Map(), new Map()];
    room.sequences = [0, 0]; room.epoch = randomUUID(); room.revision++;
    await this.checkpoint(room); this.publish(room);
  }
  join(actor, id, token, send, validate = null) {
    return this.serial(async () => {
      if (typeof token !== 'string' || !token || typeof send !== 'function') fail(400, 'SNAKE_INVALID_CONNECTION');
      const room = await this.room(actor, id), player = room.members.indexOf(actor.memberId);
      const previous = room.connections[player];
      if (previous?.token === token) { previous.lastSeen = this.clock(); return { player, ...this.state(room) }; }
      if (previous) { room.connections[player] = null; previous.send({ gameId: id, code: 'SNAKE_CONNECTION_REPLACED' }); }
      await this.pause(room, previous ? 'CONNECTION_REPLACED' : 'PLAYER_JOINED');
      room.connections[player] = { actor, token, send, validate, lastSeen: this.clock() };
      room.revision++; this.publish(room); return { player, ...this.state(room) };
    });
  }
  async connection(actor, id, token) {
    const room = await this.room(actor, id), player = room.members.indexOf(actor.memberId);
    const connection = room.connections[player];
    if (!connection || connection.token !== token || connection.actor.authenticatedDeviceId !== actor.authenticatedDeviceId)
      fail(409, 'SNAKE_CONNECTION_CHANGED');
    connection.lastSeen = this.clock(); return { room, player };
  }
  command(actor, id, token, input) {
    return this.serial(async () => {
      const { room, player } = await this.connection(actor, id, token);
      if (!input || input.epoch !== room.epoch) fail(409, 'SNAKE_EPOCH_CHANGED');
      if (input.action === 'PING') return this.state(room);
      if (input.action === 'PAUSE') { await this.pause(room, 'PLAYER_PAUSED'); return this.state(room); }
      if (input.action === 'READY') {
        if (room.ready[player]) return this.state(room);
        if (room.phase !== 'PAUSED') fail(409, 'SNAKE_NOT_PAUSED');
        room.ready[player] = true; room.revision++;
        if (room.ready.every(Boolean) && room.connections.every(Boolean)) {
          room.phase = 'COUNTDOWN'; room.reason = null; room.due = this.clock() + this.countdownMs;
        }
        await this.checkpoint(room); return this.publish(room);
      }
      if (input.action !== 'INPUT' || !Number.isSafeInteger(input.sequence) || input.sequence < 1 ||
          !Number.isSafeInteger(input.targetTick) || !DIRECTIONS.includes(input.direction)) fail(400, 'SNAKE_INVALID_INPUT');
      const payload = JSON.stringify([input.targetTick, input.direction]);
      const receipt = room.receipts[player].get(input.sequence);
      if (receipt) {
        if (receipt !== payload) fail(409, 'SNAKE_SEQUENCE_REUSED');
        return { replayed: true, sequence: input.sequence, ...this.state(room) };
      }
      if (room.phase !== 'PLAYING') fail(409, 'SNAKE_NOT_PLAYING');
      if (input.sequence !== room.sequences[player] + 1) fail(409, 'SNAKE_SEQUENCE_CONFLICT');
      const tick = tickNumber(room.engine), queue = room.inputs[player];
      if (input.targetTick <= tick || input.targetTick > tick + 3) fail(409, 'SNAKE_TICK_WINDOW');
      if (queue.length >= 2 || (queue.length && input.targetTick <= queue.at(-1).targetTick)) fail(429, 'SNAKE_INPUT_QUEUE');
      const last = queue.length ? queue.at(-1).direction : Snake.view(room.engine).players[player].direction;
      if (input.direction === last || (DIRECTIONS.indexOf(input.direction) + 2) % 4 === DIRECTIONS.indexOf(last))
        fail(400, 'SNAKE_INVALID_TURN');
      queue.push({ targetTick: input.targetTick, direction: input.direction });
      room.sequences[player] = input.sequence; room.receipts[player].set(input.sequence, payload);
      if (room.receipts[player].size > 32) room.receipts[player].delete(room.receipts[player].keys().next().value);
      return { replayed: false, sequence: input.sequence, ...this.state(room) };
    });
  }
  leave(token) {
    return this.serial(async () => {
      for (const room of this.rooms.values()) {
        const player = room.connections.findIndex(c => c?.token === token);
        if (player < 0) continue;
        room.connections[player] = null; await this.pause(room, 'PLAYER_DISCONNECTED');
      }
    });
  }
  pulse() {
    // A slow database must not accumulate a backlog of scheduled ticks.
    if (this.pulsing) return Promise.resolve(); this.pulsing = true;
    return this.serial(async () => {
      for (const room of this.rooms.values()) {
        const recheck = this.clock() - room.authorizedAt >= 250;
        let pauseReason = null;
        for (let player = 0; player < 2; player++) {
          const c = room.connections[player]; if (!c) continue;
          let reason = this.clock() - c.lastSeen > this.leaseMs ? 'PLAYER_DISCONNECTED' : null;
          if (!reason && recheck) try {
            if (c.validate && !await c.validate()) fail(401, 'GAME_AUTH_REQUIRED');
            await this.authorize(c.actor, room.id);
          } catch (error) {
            if (!(error instanceof FamilyGameService.Error)) throw error;
            reason = 'ACCESS_CHANGED'; c.send({ gameId: room.id, code: error.code });
          }
          if (reason) { room.connections[player] = null; pauseReason = reason; }
        }
        if (recheck) room.authorizedAt = this.clock();
        if (pauseReason) await this.pause(room, pauseReason);
        if (room.checkpointPending) { await this.checkpoint(room); this.publish(room); }
        if (!['PLAYING', 'COUNTDOWN'].includes(room.phase)) {
          if (!room.connections.some(Boolean)) this.rooms.delete(room.id);
          continue;
        }
        const now = this.clock(); if (now < room.due) continue;
        if (now - room.due > 500) { await this.pause(room, 'SERVER_DELAY'); continue; }
        if (room.phase === 'COUNTDOWN') {
          Snake.launchOrResume(room.engine); room.phase = 'PLAYING'; room.revision++;
          room.due = now + Snake.view(room.engine).tickMs; this.publish(room); continue;
        }
        const tick = tickNumber(room.engine) + 1;
        for (let player = 0; player < 2; player++) {
          const queue = room.inputs[player];
          if (queue[0]?.targetTick === tick) Snake.turn(room.engine, player, queue.shift().direction);
        }
        Snake.tick(room.engine); room.revision++;
        room.due += Snake.view(room.engine).tickMs;
        if (['GAME_OVER', 'WON'].includes(room.engine.state)) { room.phase = 'FINISHED'; room.reason = null; }
        if (room.phase === 'FINISHED' || now - room.checkpointAt >= 2000) await this.checkpoint(room);
        this.publish(room);
      }
    }).finally(() => { this.pulsing = false; });
  }
  async close() {
    await this.serial(async () => { for (const room of this.rooms.values()) await this.pause(room, 'SERVER_STOPPED'); this.rooms.clear(); });
  }
}
module.exports = SnakeSessionService;
