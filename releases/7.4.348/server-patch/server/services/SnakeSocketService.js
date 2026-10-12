const { randomUUID } = require('node:crypto');
const FamilyGameService = require('./FamilyGameService');

/** Dedicated namespace: mandatory token auth; no audio registries or monitoring events. */
class SnakeSocketService {
  constructor(namespace, sessions, { authenticate = null } = {}) {
    this.namespace = namespace; this.sessions = sessions; this.authenticate = authenticate;
  }
  start() {
    if (this.timer) return this;
    this.namespace.on('connection', socket => this.register(socket));
    this.timer = setInterval(() => this.sessions.pulse().catch(error => {
      console.error('Snake simulation paused after error:', error.message);
      this.sessions.close().catch(e => console.error('Snake checkpoint failed:', e.message));
    }), 50);
    this.timer.unref(); return this;
  }
  register(socket) {
    const token = randomUUID(); let actor = null, gameId = null, pending = false;
    let quotaAt = 0, quota = 0;
    const validate = async () => {
      if (!this.authenticate) return socket.authMode === 'authenticated' && Boolean(socket.authenticatedDeviceId);
      return new Promise(resolve => this.authenticate(socket, error => resolve(!error)));
    };
    const respond = (ack, payload) => { if (typeof ack === 'function') ack(payload); };
    const execute = work => async (input, ack) => {
      const now = this.sessions.clock();
      if (now - quotaAt >= 1000) { quotaAt = now; quota = 0; }
      if (++quota > 20 || pending) return respond(ack, { success: false, code: 'SNAKE_BUSY' });
      if (!input || typeof input !== 'object' || JSON.stringify(input).length > 2048)
        return respond(ack, { success: false, code: 'SNAKE_INVALID_INPUT' });
      pending = true;
      try {
        if (!await validate() || socket.authMode !== 'authenticated' || !socket.authenticatedDeviceId)
          throw new FamilyGameService.Error(401, 'GAME_AUTH_REQUIRED');
        respond(ack, { success: true, ...await work(input) });
      } catch (error) {
        respond(ack, { success: false, code: error instanceof FamilyGameService.Error ? error.code : 'SNAKE_UNAVAILABLE' });
        if (!(error instanceof FamilyGameService.Error)) console.error('Snake socket failed:', error.message);
      } finally { pending = false; }
    };
    socket.on('snake:join', execute(async input => {
      if (gameId) await this.sessions.leave(token);
      actor = await this.sessions.games.actor(socket.authenticatedDeviceId, input.familyId);
      if (input.actorMemberId !== actor.memberId) throw new FamilyGameService.Error(403, 'GAME_CONTEXT_CHANGED');
      const result = await this.sessions.join(actor, input.gameId, token, snapshot => {
        if (socket.connected !== false) socket.emit('snake:state', snapshot);
      }, async () => await validate() && socket.authenticatedDeviceId === actor.authenticatedDeviceId);
      gameId = input.gameId;
      // Disconnect can arrive while identity/database work is awaiting.
      if (socket.connected === false) { await this.sessions.leave(token); gameId = null; }
      return result;
    }));
    socket.on('snake:command', execute(async input => {
      if (!actor || !gameId || input.gameId !== gameId) throw new FamilyGameService.Error(409, 'SNAKE_NOT_JOINED');
      if (socket.authenticatedDeviceId !== actor.authenticatedDeviceId) throw new FamilyGameService.Error(403, 'GAME_CONTEXT_CHANGED');
      return this.sessions.command(actor, gameId, token, input);
    }));
    socket.on('snake:leave', execute(async () => {
      await this.sessions.leave(token); gameId = null; actor = null; return {};
    }));
    socket.on('disconnect', () => this.sessions.leave(token).catch(error => console.error('Snake disconnect failed:', error.message)));
  }
  async close() { clearInterval(this.timer); await this.sessions.close(); }
}
module.exports = SnakeSocketService;
