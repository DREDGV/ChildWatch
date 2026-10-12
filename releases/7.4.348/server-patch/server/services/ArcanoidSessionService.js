'use strict';
const {randomUUID} = require('node:crypto');
const {performance} = require('node:perf_hooks');
const Games = require('./FamilyGameService');
const Rules = require('./ArcanoidRules');
const STEP_MS = 1000 / 60, STEP_SECONDS = 1 / 60, PADDLE_SPEED = 2200;
const fail = (status, code) => { throw new Games.Error(status, code); };

/** Single-process cooperative simulation. Coordinates, collisions and scoring belong to the server. */
class ArcanoidSessionService {
  constructor(games, {clock = () => performance.now(), wallClock = Date.now,
    countdownMs = 2000, leaseMs = 12000, maxRooms = 100} = {}) {
    this.games = games; this.db = games.db; this.clock = clock; this.wallClock = wallClock;
    this.countdownMs = countdownMs; this.leaseMs = leaseMs; this.maxRooms = maxRooms;
    this.rooms = new Map(); this.chain = Promise.resolve(); this.pulsing = false;
  }
  serial(work) { const result = this.chain.then(work); this.chain = result.catch(() => {}); return result; }
  async ensure() {
    await this.games.ensure();
    if (!this.schema) this.schema = this.db.run(`CREATE TABLE IF NOT EXISTS family_arcanoid_sessions (
      game_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, epoch TEXT NOT NULL,
      revision INTEGER NOT NULL, tick INTEGER NOT NULL, updated_at INTEGER NOT NULL)`)
      .catch(error => { this.schema = null; throw error; });
    return this.schema;
  }
  async authorize(actor, id) {
    const result = await this.games.authorize(actor, id);
    if (result.row.type !== 'ARKANOID_COOP') fail(400, 'ARKANOID_WRONG_GAME');
    if (!['SETUP', 'PLAYING', 'WON', 'LOST'].includes(result.row.status)) fail(409, 'GAME_CLOSED');
    return result;
  }
  async room(actor, id) {
    await this.ensure(); const {row} = await this.authorize(actor, id);
    let room = this.rooms.get(id); if (room) return room;
    if (this.rooms.size >= this.maxRooms) fail(429, 'ARKANOID_ROOM_LIMIT');
    const saved = await this.db.get('SELECT * FROM family_arcanoid_sessions WHERE game_id=?', [id]);
    let engine;
    try { engine = Rules.restore(JSON.parse(saved ? saved.snapshot : row.board)); } catch { engine = null; }
    if (!engine || (saved && (!Number.isSafeInteger(saved.tick) || saved.tick < 0))) fail(500, 'ARKANOID_CHECKPOINT_INVALID');
    const terminal = engine.state === 'GAME_OVER' || Rules.campaignComplete(engine);
    room = {id, engine, epoch: randomUUID(), revision: (saved?.revision || 0) + 1, tick: saved?.tick || 0,
      phase: terminal ? 'FINISHED' : 'PAUSED',
      reason: terminal ? null : engine.state === 'WON' ? 'LEVEL_COMPLETE' : saved ? 'SERVER_RESTART' : 'WAITING_PLAYERS',
      members: [row.host_member_id, row.guest_member_id], connections: [null, null], ready: [false, false],
      targets: [...Rules.view(engine).paddles], sequences: [0, 0], receipts: [new Map(), new Map()],
      moveQuota: [{at: this.clock(), count: 0}, {at: this.clock(), count: 0}],
      due: null, checkpointAt: this.clock(), broadcastAt: this.clock(), authorizedAt: -Infinity};
    this.rooms.set(id, room);
    try { await this.checkpoint(room); } catch (error) { this.rooms.delete(id); throw error; }
    return room;
  }
  state(room) {
    return {protocolVersion: 1, gameId: room.id, epoch: room.epoch, revision: room.revision,
      phase: room.phase, reason: room.reason, ready: [...room.ready], connected: room.connections.map(Boolean),
      countdownMs: room.phase === 'COUNTDOWN' ? Math.max(0, room.due - this.clock()) : 0,
      arcanoid: {...Rules.view(room.engine), tick: room.tick}};
  }
  publish(room) {
    const state = this.state(room); room.broadcastAt = this.clock();
    for (const connection of room.connections) if (connection) connection.send(state);
    return state;
  }
  async checkpoint(room) {
    room.checkpointPending = true; const saved = JSON.stringify(Rules.snapshot(room.engine));
    await this.db.withTransaction(async () => {
      await this.db.run(`INSERT INTO family_arcanoid_sessions(game_id,snapshot,epoch,revision,tick,updated_at)
        VALUES (?,?,?,?,?,?) ON CONFLICT(game_id) DO UPDATE SET snapshot=excluded.snapshot,
        epoch=excluded.epoch,revision=excluded.revision,tick=excluded.tick,updated_at=excluded.updated_at`,
      [room.id, saved, room.epoch, room.revision, room.tick, this.wallClock()]);
      const status = room.phase === 'FINISHED' ? room.engine.state === 'WON' ? 'WON' : 'LOST' : 'PLAYING';
      await this.db.run(`UPDATE family_games SET board=?,status=?,version=version+1,updated_at=?
        WHERE id=? AND status IN ('SETUP','PLAYING')`, [saved, status, this.wallClock(), room.id]);
    });
    room.checkpointAt = this.clock(); room.checkpointPending = false; room.errorFrozen = false;
  }
  freeze(room, reason) {
    if (room.phase === 'FINISHED') return;
    Rules.pause(room.engine); room.phase = 'PAUSED';
    room.reason = room.engine.state === 'WON' && !Rules.campaignComplete(room.engine) ? 'LEVEL_COMPLETE' : reason;
    room.due = null; room.ready = [false, false]; room.targets = [...Rules.view(room.engine).paddles];
    room.sequences = [0, 0]; room.receipts = [new Map(), new Map()];
    room.moveQuota = [{at: this.clock(), count: 0}, {at: this.clock(), count: 0}];
    room.epoch = randomUUID(); room.revision++; room.checkpointPending = true;
  }
  async pause(room, reason) {
    if (room.phase === 'FINISHED') return;
    this.freeze(room, reason); await this.checkpoint(room); this.publish(room);
  }
  join(actor, id, token, send, validate = null) {
    return this.serial(async () => {
      if (typeof token !== 'string' || !token || typeof send !== 'function') fail(400, 'ARKANOID_INVALID_CONNECTION');
      const room = await this.room(actor, id), player = room.members.indexOf(actor.memberId);
      if (player < 0) fail(404, 'GAME_NOT_FOUND');
      const previous = room.connections[player];
      if (previous?.token === token) { previous.lastSeen = this.clock(); return {player, ...this.state(room)}; }
      if (previous) { room.connections[player] = null; previous.send({gameId: id, code: 'ARKANOID_CONNECTION_REPLACED'}); }
      await this.pause(room, previous ? 'CONNECTION_REPLACED' : 'PLAYER_JOINED');
      room.connections[player] = {actor, token, send, validate, lastSeen: this.clock()};
      room.revision++; this.publish(room); return {player, ...this.state(room)};
    });
  }
  async connection(actor, id, token) {
    const room = await this.room(actor, id), player = room.members.indexOf(actor.memberId), connection = room.connections[player];
    if (!connection || connection.token !== token || connection.actor.authenticatedDeviceId !== actor.authenticatedDeviceId)
      fail(409, 'ARKANOID_CONNECTION_CHANGED');
    connection.lastSeen = this.clock(); return {room, player};
  }
  command(actor, id, token, input) {
    return this.serial(async () => {
      const {room, player} = await this.connection(actor, id, token);
      if (!input || input.epoch !== room.epoch) fail(409, 'ARKANOID_EPOCH_CHANGED');
      if (input.action === 'PING') return this.state(room);
      if (input.action === 'PAUSE') { await this.pause(room, 'PLAYER_PAUSED'); return this.state(room); }
      if (input.action === 'READY') {
        if (room.ready[player]) return this.state(room);
        if (room.phase !== 'PAUSED') fail(409, 'ARKANOID_NOT_PAUSED');
        room.ready[player] = true; room.revision++;
        if (room.ready.every(Boolean) && room.connections.every(Boolean)) {
          if (room.engine.state === 'WON' && !Rules.nextLevel(room.engine)) fail(409, 'ARKANOID_CAMPAIGN_COMPLETE');
          room.targets = [...Rules.view(room.engine).paddles];
          room.phase = 'COUNTDOWN'; room.reason = null; room.due = this.clock() + this.countdownMs;
        }
        await this.checkpoint(room); return this.publish(room);
      }
      if (input.action !== 'MOVE' || !Number.isSafeInteger(input.sequence) || input.sequence < 1
          || typeof input.position !== 'number' || !Number.isFinite(input.position) || input.position < 0 || input.position > 1)
        fail(400, 'ARKANOID_INVALID_INPUT');
      const receipt = room.receipts[player].get(input.sequence);
      if (receipt !== undefined) {
        if (receipt !== input.position) fail(409, 'ARKANOID_SEQUENCE_REUSED');
        return {replayed: true, sequence: input.sequence, ...this.state(room)};
      }
      if (room.phase !== 'PLAYING') fail(409, 'ARKANOID_NOT_PLAYING');
      if (input.sequence !== room.sequences[player] + 1) fail(409, 'ARKANOID_SEQUENCE_CONFLICT');
      const quota = room.moveQuota[player], now = this.clock();
      if (now - quota.at >= 1000) { quota.at = now; quota.count = 0; }
      if (quota.count >= 25) fail(429, 'ARKANOID_MOVE_RATE');
      quota.count++; room.targets[player] = Math.max(95, Math.min(905, input.position * 1000));
      room.sequences[player] = input.sequence; room.receipts[player].set(input.sequence, input.position);
      if (room.receipts[player].size > 32) room.receipts[player].delete(room.receipts[player].keys().next().value);
      return {replayed: false, sequence: input.sequence, ...this.state(room)};
    });
  }
  leave(token) {
    return this.serial(async () => {
      for (const room of this.rooms.values()) {
        const player = room.connections.findIndex(connection => connection?.token === token);
        if (player < 0) continue;
        room.connections[player] = null; await this.pause(room, 'PLAYER_DISCONNECTED');
      }
    });
  }
  pulse() {
    if (this.pulsing) return Promise.resolve(); this.pulsing = true;
    return this.serial(async () => {
      for (const room of this.rooms.values()) {
        const recheck = this.clock() - room.authorizedAt >= 250; let pauseReason = null;
        for (let player = 0; player < 2; player++) {
          const connection = room.connections[player]; if (!connection) continue;
          let reason = this.clock() - connection.lastSeen > this.leaseMs ? 'PLAYER_DISCONNECTED' : null;
          if (!reason && recheck) try {
            if (connection.validate && !await connection.validate()) fail(401, 'GAME_AUTH_REQUIRED');
            await this.authorize(connection.actor, room.id);
          } catch (error) {
            if (!(error instanceof Games.Error)) throw error;
            reason = 'ACCESS_CHANGED'; connection.send({gameId: room.id, code: error.code});
          }
          if (reason) { room.connections[player] = null; pauseReason = reason; }
        }
        if (recheck) room.authorizedAt = this.clock();
        if (pauseReason) await this.pause(room, pauseReason);
        if (room.checkpointPending) { await this.checkpoint(room); this.publish(room); }
        if (!['COUNTDOWN','PLAYING'].includes(room.phase)) {
          if (!room.connections.some(Boolean) && !room.checkpointPending) this.rooms.delete(room.id);
          continue;
        }
        const now = this.clock(); if (now < room.due) continue;
        if (now - room.due > 250) { await this.pause(room, 'SERVER_DELAY'); continue; }
        if (room.phase === 'COUNTDOWN') {
          Rules.launchOrResume(room.engine); room.phase = 'PLAYING'; room.revision++;
          room.due = now + STEP_MS; this.publish(room); continue;
        }
        let boundary = false;
        while (room.phase === 'PLAYING' && now + 1e-7 >= room.due) {
          const before = Rules.view(room.engine);
          for (let player = 0; player < 2; player++) {
            const delta = room.targets[player] - before.paddles[player];
            Rules.movePaddle(room.engine, player, before.paddles[player] + Math.max(-PADDLE_SPEED * STEP_SECONDS, Math.min(PADDLE_SPEED * STEP_SECONDS, delta)));
          }
          Rules.update(room.engine, STEP_SECONDS); room.tick++; room.revision++; room.due += STEP_MS;
          const after = Rules.view(room.engine);
          if (room.engine.state === 'GAME_OVER' || Rules.campaignComplete(room.engine)) {
            room.phase = 'FINISHED'; room.reason = null; room.due = null;
            room.ready = [false,false]; room.targets = [...after.paddles]; boundary = true;
          } else if (room.engine.state === 'WON' || after.lives < before.lives) {
            await this.pause(room, room.engine.state === 'WON' ? 'LEVEL_COMPLETE' : 'LIFE_LOST'); boundary = true;
          }
        }
        if (room.phase === 'FINISHED' || now - room.checkpointAt >= 2000) await this.checkpoint(room);
        if (boundary || now - room.broadcastAt + 1e-7 >= 50) this.publish(room);
      }
    }).finally(() => { this.pulsing = false; });
  }
  failSafe() {
    return this.serial(async () => {
      for (const room of this.rooms.values()) {
        if (room.phase === 'FINISHED' || room.errorFrozen) continue;
        this.freeze(room, 'SERVER_ERROR'); room.errorFrozen = true; this.publish(room);
      }
    });
  }
  async close() {
    await this.serial(async () => {
      for (const room of this.rooms.values()) {
        if (room.phase !== 'FINISHED') await this.pause(room, 'SERVER_STOPPED');
        else if (room.checkpointPending) await this.checkpoint(room);
      }
      this.rooms.clear();
    });
  }
}
module.exports = ArcanoidSessionService;
