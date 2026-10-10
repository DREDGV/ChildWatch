const { randomUUID } = require('crypto');
const { performance } = require('perf_hooks');
const ChatConversationService = require('./ChatConversationService');

// Foreground leases are deliberately volatile: restart means unknown, not online.
class ChatPresenceService {
  constructor(chat, options = {}) {
    this.chat = chat;
    this.clock = options.clock || (() => performance.now());
    this.wallClock = options.wallClock || Date.now;
    this.deviceConnected = options.deviceConnected || (() => null);
    this.sessions = new Map();
  }

  prune() {
    const now = this.clock();
    for (const [id, entry] of this.sessions) {
      if (now - entry.updated >= ChatPresenceService.TTL_MS || now < entry.updated) {
        this.sessions.delete(id);
      }
    }
  }

  async renew(deviceId, conversationId, sessionId = null) {
    const actor = await this.chat.resolveConversationActor(deviceId, conversationId);
    this.prune();
    let entry;
    if (sessionId != null) {
      entry = this.sessions.get(sessionId);
      if (!entry || entry.deviceId !== actor.deviceId || entry.conversationId !== actor.conversation.id ||
          entry.memberId !== actor.memberId) {
        throw new ChatConversationService.Error(409, 'CHAT_PRESENCE_EXPIRED', 'Presence session expired');
      }
    } else {
      if (this.sessions.size >= 10_000 || [...this.sessions.values()].filter(s => s.deviceId === actor.deviceId).length >= 8) {
        throw new ChatConversationService.Error(429, 'CHAT_PRESENCE_LIMIT', 'Too many foreground sessions');
      }
      sessionId = randomUUID();
      entry = { deviceId: actor.deviceId, memberId: actor.memberId, conversationId: actor.conversation.id };
    }
    entry.updated = this.clock();
    this.sessions.set(sessionId, entry);
    return { conversationId: actor.conversation.id, sessionId, expiresInMs: ChatPresenceService.TTL_MS };
  }

  async leave(deviceId, conversationId, sessionId) {
    const actor = await this.chat.resolveConversationActor(deviceId, conversationId);
    const entry = this.sessions.get(sessionId);
    // A late leave can only close its own lease, never a newer screen's session.
    if (entry?.deviceId === actor.deviceId && entry.conversationId === actor.conversation.id) {
      this.sessions.delete(sessionId);
    }
    return { conversationId: actor.conversation.id };
  }

  async snapshot(deviceId, conversationId) {
    const actor = await this.chat.resolveConversationActor(deviceId, conversationId);
    const familyMembers = await this.chat.dbManager.getFamilyMembers(actor.familyId);
    const members = await this.chat.conversationMembers(actor.conversation, familyMembers);
    const devices = await this.chat.dbManager.getFamilyDevices(actor.familyId);
    this.prune();
    const participants = members.map(member => {
      const ownDevices = devices.filter(d => d.memberId === member.memberId && d.isActive === 1);
      const open = ownDevices.some(d => [...this.sessions.values()].some(s =>
        s.deviceId === d.deviceId && s.memberId === member.memberId && s.conversationId === actor.conversation.id));
      const connection = ownDevices.map(d => this.deviceConnected(d.deviceId));
      return { ...member, chatOpen: open,
        deviceConnected: open || connection.some(c => c === true) ? true :
          connection.length > 0 && connection.every(c => c === false) ? false : null };
    });
    return { conversationId: actor.conversation.id, actorMemberId: actor.memberId,
      observedAt: this.wallClock(), expiresInMs: ChatPresenceService.TTL_MS, participants };
  }
}
ChatPresenceService.TTL_MS = 45_000;
module.exports = ChatPresenceService;
