const DatabaseManager = require("../database/DatabaseManager");

/**
 * A person has read what they wrote.
 *
 * The sender's own read pointer used to stay behind their own messages, so every
 * counter derived from it treated those messages as unread. Two messages sent to
 * a child put a badge of two beside that child's name in the sender's own
 * conversation list - which is what the owner reported.
 */
describe("the sender's own read pointer", () => {
  const dadDevice = "device_dad_phone_0001";
  const levaDevice = "child_device_0001";

  let db;
  let family;
  let dadMember;
  let levaMember;
  let conversation;
  let logSpy;
  let warnSpy;

  async function send(sequenceText, senderMemberId, senderDeviceId, clientMessageId) {
    return db.insertChatMessageV2({
      conversationId: conversation.id,
      senderMemberId,
      senderDeviceId,
      clientMessageId,
      text: sequenceText,
    });
  }

  async function memberState(memberId) {
    return db.get(
      `SELECT last_read_sequence AS readPointer, last_delivered_sequence AS deliveredPointer
       FROM chat_conversation_members
       WHERE conversation_id = ? AND member_id = ?`,
      [conversation.id, memberId]
    );
  }

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});

    db = new DatabaseManager(":memory:");
    await db.initialize();
    for (const [deviceId, name] of [
      [dadDevice, "Samsung отца"],
      [levaDevice, "moto ребёнка"],
    ]) {
      await db.registerDevice(deviceId, {
        device_name: name,
        device_type: "android",
        app_version: "7.3.0",
      });
    }

    const created = await db.createExplicitFamilyForDevice({
      deviceId: dadDevice,
      familyName: "Семья",
      displayName: "Папа",
      role: "PARENT",
    });
    family = created.family;
    dadMember = created.member;

    levaMember = await db.createStableScopedId("member", family.id, levaDevice);
    await db.run(
      `INSERT INTO family_members (id, family_id, display_name, role, is_active, created_at, updated_at)
       VALUES (?, ?, ?, 'CHILD', 1, ?, ?)`,
      [levaMember, family.id, "Лёва", Date.now(), Date.now()]
    );
    await db.attachFamilyDeviceToMember({
      familyId: family.id,
      memberId: levaMember,
      deviceId: levaDevice,
      displayName: "moto ребёнка",
      platform: "android",
    });

    conversation = await db.createDirectConversation({
      familyId: family.id,
      memberIds: [dadMember.id, levaMember],
      createdByMemberId: dadMember.id,
    });
  });

  afterEach(async () => {
    logSpy.mockRestore();
    warnSpy.mockRestore();
    await db.close();
  });

  test("advances to the message just written", async () => {
    await send("Привет", dadMember.id, dadDevice, "client-1");
    const afterFirst = await memberState(dadMember.id);
    expect(afterFirst.readPointer).toBe(1);
    expect(afterFirst.deliveredPointer).toBe(1);

    await send("Как дела?", dadMember.id, dadDevice, "client-2");
    const afterSecond = await memberState(dadMember.id);
    expect(afterSecond.readPointer).toBe(2);
  });

  test("the peer's pointer stays behind, so the badge belongs to the peer", async () => {
    await send("Привет", dadMember.id, dadDevice, "client-3");
    await send("Как дела?", dadMember.id, dadDevice, "client-4");

    const dad = await memberState(dadMember.id);
    const leva = await memberState(levaMember);

    // Dad wrote them, so dad has nothing unread; Leva has both.
    expect(dad.readPointer).toBe(2);
    expect(leva.readPointer).toBe(0);
  });

  test("a conversation list shows no unread for the sender", async () => {
    await send("Привет", dadMember.id, dadDevice, "client-5");
    await send("Как дела?", dadMember.id, dadDevice, "client-6");

    const dadList = await db.listChatConversationsForMember(dadMember.id);
    const levaList = await db.listChatConversationsForMember(levaMember);

    const dadRow = dadList.find((row) => row.id === conversation.id);
    const levaRow = levaList.find((row) => row.id === conversation.id);
    expect(dadRow.unreadCount).toBe(0);
    expect(levaRow.unreadCount).toBe(2);

    // And the quantity a client derives from the pointers agrees.
    expect(Number(dadRow.nextSequence) - Number(dadRow.lastReadSequence)).toBe(0);
  });

  test("the peer writing afterwards still leaves the sender at zero", async () => {
    await send("Привет", dadMember.id, dadDevice, "client-7");
    await send("Привет", levaMember, levaDevice, "client-8");

    const dad = await memberState(dadMember.id);
    // Dad's own message is behind his pointer; Leva's is not.
    expect(dad.readPointer).toBe(1);

    const dadList = await db.listChatConversationsForMember(dadMember.id);
    const dadRow = dadList.find((row) => row.id === conversation.id);
    expect(dadRow.unreadCount).toBe(1);
  });
});
