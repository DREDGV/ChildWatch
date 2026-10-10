const ChatPresenceService = require('../services/ChatPresenceService');
const ChatConversationService = require('../services/ChatConversationService');

describe('conversation foreground leases', () => {
  let now, allowed, devices, chat, presence;
  beforeEach(() => {
    now = 0; allowed = true;
    devices = [{ deviceId: 'a', memberId: 'one', isActive: 1 },
      { deviceId: 'b', memberId: 'two', isActive: 1 },
      { deviceId: 'c', memberId: 'two', isActive: 1 }];
    chat = {
      resolveConversationActor: async (deviceId, conversationId) => {
        if (!allowed || !devices.some(d => d.deviceId === deviceId && d.isActive))
          throw new ChatConversationService.Error(403, 'DENIED', 'Denied');
        return { deviceId, memberId: devices.find(d => d.deviceId === deviceId).memberId,
          familyId: 'family', conversation: { id: conversationId } };
      },
      dbManager: { getFamilyMembers: async () => [], getFamilyDevices: async () => devices },
      conversationMembers: async () => [{ memberId: 'one' }, { memberId: 'two' }],
    };
    presence = new ChatPresenceService(chat, { clock: () => now, wallClock: () => 1_000_000 + now,
      deviceConnected: id => id === 'a' });
  });
  const peer = async () => (await presence.snapshot('a', 'room')).participants[1];
  test('a connected transport is not proof of an open chat', async () => {
    const state = await presence.snapshot('a', 'room');
    expect(state.participants[0]).toMatchObject({ deviceConnected: true, chatOpen: false });
    expect(await peer()).toMatchObject({ deviceConnected: false, chatOpen: false });
  });
  test('expires at 45 seconds independently of wall time and restarts unknown', async () => {
    const lease = await presence.renew('b', 'room');
    presence.wallClock = () => -999_999_999;
    now = 44_999; expect((await peer()).chatOpen).toBe(true);
    now = 45_000; expect((await peer()).chatOpen).toBe(false);
    await expect(presence.renew('b', 'room', lease.sessionId)).rejects.toMatchObject({ status: 409 });
    const restarted = new ChatPresenceService(chat);
    expect((await restarted.snapshot('a', 'room')).participants[1]).toMatchObject({ chatOpen: false, deviceConnected: null });
  });
  test('late leave of one screen does not close another screen or phone', async () => {
    const old = await presence.renew('b', 'room');
    const fresh = await presence.renew('b', 'room');
    await presence.leave('b', 'room', old.sessionId);
    expect((await peer()).chatOpen).toBe(true);
    const other = await presence.renew('c', 'room');
    await presence.leave('b', 'room', fresh.sessionId);
    expect((await peer()).chatOpen).toBe(true);
    await presence.leave('c', 'room', other.sessionId);
    expect((await peer()).chatOpen).toBe(false);
  });
  test('a token is bound to authenticated device, member and conversation', async () => {
    const token = (await presence.renew('b', 'room')).sessionId;
    await expect(presence.renew('a', 'room', token)).rejects.toMatchObject({ status: 409 });
    await expect(presence.renew('b', 'different-room', token)).rejects.toMatchObject({ status: 409 });
    await presence.leave('a', 'room', token);
    expect((await peer()).chatOpen).toBe(true);
    devices[1].memberId = 'one';
    await expect(presence.renew('b', 'room', token)).rejects.toMatchObject({ status: 409 });
  });
  test('revoked access cannot renew or read, and unbound phones do not represent members', async () => {
    const token = (await presence.renew('b', 'room')).sessionId;
    allowed = false;
    await expect(presence.snapshot('a', 'room')).rejects.toMatchObject({ status: 403 });
    await expect(presence.renew('b', 'room', token)).rejects.toMatchObject({ status: 403 });
    allowed = true; devices[1].isActive = 0;
    expect((await peer()).chatOpen).toBe(false);
  });
  test('repeated registration is bounded and expired leases free capacity', async () => {
    for (let i = 0; i < 8; i++) await presence.renew('b', 'room');
    await expect(presence.renew('b', 'room')).rejects.toMatchObject({ status: 429 });
    now = 45_000;
    await expect(presence.renew('b', 'room')).resolves.toHaveProperty('sessionId');
  });
});
