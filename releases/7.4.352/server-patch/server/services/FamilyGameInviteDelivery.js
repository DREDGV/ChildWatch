/** Invalidation only: names and game state are fetched through the authenticated API. */
class FamilyGameInviteDelivery {
  constructor(io, games) { this.io = io; this.games = games; }

  async changed(game) {
    if (!game?.familyId || !game.host?.memberId || !game.guest?.memberId) return;
    const sockets = Array.from(this.io.sockets.sockets.values());
    const allowed = new Map();
    for (const socket of sockets) {
      const device = socket.authenticatedDeviceId;
      if (!socket.connected || socket.authMode !== 'authenticated' || !device) continue;
      if (!allowed.has(device)) {
        try {
          const actor = await this.games.actor(device, game.familyId);
          allowed.set(device, [game.host.memberId, game.guest.memberId].includes(actor.memberId));
        } catch (_) { allowed.set(device, false); }
      }
      if (allowed.get(device) && socket.connected)
        socket.emit('family_games:changed', { familyId: game.familyId });
    }
  }
}
module.exports = FamilyGameInviteDelivery;
