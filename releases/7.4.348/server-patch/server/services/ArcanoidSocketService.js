'use strict';
const {randomUUID} = require('node:crypto');
const Games = require('./FamilyGameService');

/** Shares only the authenticated namespace, never the monitoring events or Snake room state. */
class ArcanoidSocketService {
  constructor(namespace, sessions, {authenticate = null} = {}) {
    this.namespace = namespace; this.sessions = sessions; this.authenticate = authenticate;
    this.onConnection = socket => this.register(socket);
  }
  start() {
    if (this.timer) return this;
    this.namespace.on('connection', this.onConnection);
    this.timer = setInterval(() => this.sessions.pulse().catch(error => {
      const now = this.sessions.clock();
      if (this.lastErrorLog === undefined || now - this.lastErrorLog >= 1000) {
        console.error('Arcanoid simulation frozen after error:', error.message); this.lastErrorLog = now;
      }
      this.sessions.failSafe().catch(failure => console.error('Arcanoid pause failed:', failure.message));
    }), 8);
    this.timer.unref(); return this;
  }
  register(socket) {
    const token = randomUUID(); let actor = null, gameId = null, pending = false;
    let quotaAt = 0, quota = 0, moveQuota = 0;
    const validate = async () => {
      if (!this.authenticate) return socket.authMode === 'authenticated' && Boolean(socket.authenticatedDeviceId);
      return new Promise(resolve => this.authenticate(socket, error => resolve(!error)));
    };
    const respond = (ack, payload) => { if (typeof ack === 'function') ack(payload); };
    const execute = work => async (input, ack) => {
      const now = this.sessions.clock();
      if (now - quotaAt >= 1000) { quotaAt = now; quota = 0; moveQuota = 0; }
      if (++quota > 35 || (input?.action === 'MOVE' && ++moveQuota > 25) || pending)
        return respond(ack, {success: false, code: 'ARKANOID_BUSY'});
      let encoded;
      try { encoded = JSON.stringify(input); } catch { encoded = null; }
      if (!input || typeof input !== 'object' || Array.isArray(input) || !encoded || encoded.length > 2048)
        return respond(ack, {success: false, code: 'ARKANOID_INVALID_INPUT'});
      pending = true;
      try {
        if (!await validate() || socket.authMode !== 'authenticated' || !socket.authenticatedDeviceId)
          throw new Games.Error(401, 'GAME_AUTH_REQUIRED');
        respond(ack, {success: true, ...await work(input)});
      } catch (error) {
        respond(ack, {success: false, code: error instanceof Games.Error ? error.code : 'ARKANOID_UNAVAILABLE'});
        if (!(error instanceof Games.Error)) console.error('Arcanoid socket failed:', error.message);
      } finally { pending = false; }
    };
    socket.on('arkanoid:join', execute(async input => {
      if (gameId) await this.sessions.leave(token);
      actor = await this.sessions.games.actor(socket.authenticatedDeviceId, input.familyId);
      if (input.actorMemberId !== actor.memberId) throw new Games.Error(403, 'GAME_CONTEXT_CHANGED');
      const result = await this.sessions.join(actor, input.gameId, token, state => {
        if (socket.connected !== false) socket.emit('arkanoid:state', state);
      }, async () => await validate() && socket.authenticatedDeviceId === actor.authenticatedDeviceId);
      gameId = input.gameId;
      if (socket.connected === false) { await this.sessions.leave(token); gameId = null; }
      return result;
    }));
    socket.on('arkanoid:command', execute(async input => {
      if (!actor || !gameId || input.gameId !== gameId) throw new Games.Error(409, 'ARKANOID_NOT_JOINED');
      if (socket.authenticatedDeviceId !== actor.authenticatedDeviceId) throw new Games.Error(403, 'GAME_CONTEXT_CHANGED');
      return this.sessions.command(actor, gameId, token, input);
    }));
    socket.on('arkanoid:leave', execute(async () => {
      await this.sessions.leave(token); gameId = null; actor = null; return {};
    }));
    socket.on('disconnect', () => this.sessions.leave(token).catch(error => console.error('Arcanoid disconnect checkpoint failed:', error.message)));
  }
  async close() {
    clearInterval(this.timer); this.timer = null;
    if (typeof this.namespace.off === 'function') this.namespace.off('connection', this.onConnection);
    await this.sessions.close();
  }
}
module.exports = ArcanoidSocketService;
