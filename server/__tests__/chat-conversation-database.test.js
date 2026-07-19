const DatabaseManager = require("../database/DatabaseManager");

jest.setTimeout(30_000);

async function registerFamily(db, suffix, { secondParent = false } = {}) {
  const parentDeviceId = `parent-chat-${suffix}-0001`;
  const secondParentDeviceId = `parent-chat-${suffix}-0002`;
  const childDeviceId = `child-chat-${suffix}-0001`;

  await db.registerDevice(parentDeviceId, {
    device_name: `Parent ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(childDeviceId, {
    device_name: `Child ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId,
    childDeviceId,
    parentDisplayName: `Parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "chat-test",
  });

  if (secondParent) {
    await db.registerDevice(secondParentDeviceId, {
      device_name: `Second parent ${suffix}`,
      device_type: "android",
      app_version: "8.0.0",
    });
    await db.upsertDeviceLink({
      parentDeviceId: secondParentDeviceId,
      childDeviceId,
      parentDisplayName: `Second parent ${suffix}`,
      childDisplayName: `Child ${suffix}`,
      createdBy: "chat-test",
    });
  }

  const [family] = await db.getFamiliesForDevice(childDeviceId);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(
    devices.map((device) => [device.deviceId, device.memberId])
  );
  const conversation = await db.ensureFamilyConversation(family.id);
  return {
    family,
    conversation,
    parentDeviceId,
    secondParentDeviceId: secondParent ? secondParentDeviceId : null,
    childDeviceId,
    memberByDevice,
  };
}

describe("chat conversation database foundation", () => {
  let db;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
  });

  afterEach(async () => {
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("creates additive schema and idempotently backfills 360 ordered legacy messages", async () => {
    const context = await registerFamily(db, "bulk", { secondParent: true });
    const expectedTables = [
      "chat_conversations",
      "chat_conversation_members",
      "chat_messages_v2",
      "chat_message_receipts",
      "chat_legacy_threads",
      "schema_migrations",
    ];
    const schemaRows = await db.all(
      `SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name`
    );
    const schemaNames = new Set(schemaRows.map((row) => row.name));
    expectedTables.forEach((name) => expect(schemaNames.has(name)).toBe(true));

    const familyEmoji = "👨‍👩‍👧‍👦 👍🏽 🏳️‍🌈 🇷🇺";
    let ambiguousLegacyId = null;
    await db.withTransaction(async () => {
      for (let index = 0; index < 360; index += 1) {
        const fromChild = index % 3 === 0;
        const isAmbiguousParent = index === 43;
        const result = await db.saveChatMessage(context.childDeviceId, {
          sender: fromChild ? "child" : "parent",
          senderDeviceId: fromChild
            ? context.childDeviceId
            : isAmbiguousParent
              ? null
              : context.parentDeviceId,
          senderDisplayName: isAmbiguousParent
            ? `Семья ${familyEmoji}`
            : null,
          message:
            index === 177
              ? `Unicode survives exactly ${familyEmoji}`
              : `Legacy message ${String(index).padStart(3, "0")}`,
          timestamp: 1_780_000_000_000 + index,
          id: `legacy-client-${String(index).padStart(3, "0")}`,
          delivered: index % 2 === 0 ? 1 : 0,
        });
        if (isAmbiguousParent) ambiguousLegacyId = result.id;
      }
    });

    const firstRun = await db.migrateLegacyChatToConversations();
    expect(firstRun).toMatchObject({
      totalLegacyRows: 360,
      imported: 360,
      alreadyImported: 0,
    });
    expect(firstRun.ambiguousAuthors).toBeGreaterThanOrEqual(1);

    const conversation = await db.getChatConversationById(
      context.conversation.id
    );
    expect(conversation.nextSequence).toBe(360);
    const aggregate = await db.get(
      `SELECT COUNT(*) AS count, MIN(sequence) AS minimum, MAX(sequence) AS maximum
       FROM chat_messages_v2
       WHERE conversation_id = ?`,
      [context.conversation.id]
    );
    expect(aggregate).toEqual({ count: 360, minimum: 1, maximum: 360 });

    const emojiRow = await db.get(
      `SELECT text FROM chat_messages_v2 WHERE legacy_message_id = ?`,
      [178]
    );
    expect(emojiRow.text).toBe(`Unicode survives exactly ${familyEmoji}`);
    const ambiguous = await db.get(
      `SELECT sender_member_id, sender_display_name_snapshot
       FROM chat_messages_v2
       WHERE legacy_message_id = ?`,
      [ambiguousLegacyId]
    );
    expect(ambiguous.sender_member_id).toBeNull();
    expect(ambiguous.sender_display_name_snapshot).toBe(`Семья ${familyEmoji}`);

    const paged = [];
    let beforeSequence = null;
    let hasMore = true;
    while (hasMore) {
      const page = await db.getChatMessagesV2Page(context.conversation.id, {
        beforeSequence,
        limit: 47,
      });
      paged.push(...page.messages);
      beforeSequence = page.nextBeforeSequence;
      hasMore = page.hasMore;
    }
    expect(paged).toHaveLength(360);
    expect(new Set(paged.map((message) => message.id)).size).toBe(360);
    expect(paged.map((message) => message.sequence).sort((a, b) => a - b)).toEqual(
      Array.from({ length: 360 }, (_, index) => index + 1)
    );

    const secondRun = await db.migrateLegacyChatToConversations();
    expect(secondRun).toMatchObject({
      totalLegacyRows: 360,
      imported: 0,
      alreadyImported: 360,
    });
    await expect(
      db.getChatConversationById(context.conversation.id)
    ).resolves.toMatchObject({ nextSequence: 360 });
    await expect(
      db.get(`SELECT COUNT(*) AS count FROM chat_messages_v2`)
    ).resolves.toEqual({ count: 360 });
    const ledger = await db.get(
      `SELECT applied_at, last_run_at, details_json
       FROM schema_migrations
       WHERE name = 'chat_conversations_v1'`
    );
    expect(ledger.applied_at).toBeGreaterThan(0);
    expect(JSON.parse(ledger.details_json)).toMatchObject({
      imported: 0,
      alreadyImported: 360,
    });
  });

  test("deduplicates v2 sends without consuming sequence or regressing receipts", async () => {
    const context = await registerFamily(db, "dedup");
    const senderMemberId = context.memberByDevice.get(context.parentDeviceId);
    const recipientMemberId = context.memberByDevice.get(context.childDeviceId);
    const preservedText = "  Первый текст 👍🏽\n";
    const first = await db.insertChatMessageV2({
      conversationId: context.conversation.id,
      senderMemberId,
      senderDeviceId: context.parentDeviceId,
      clientMessageId: "client-message-dedup-0001",
      text: preservedText,
      clientSentAt: 1_780_100_000_000,
    });
    expect(first).toMatchObject({ created: true, deduplicated: false });
    expect(first.message.sequence).toBe(1);

    const receiptState = await db.advanceChatMemberReceipt({
      conversationId: context.conversation.id,
      memberId: recipientMemberId,
      deliveredThroughSequence: 1,
      readThroughSequence: 1,
      deviceId: context.childDeviceId,
    });
    expect(receiptState).toMatchObject({
      lastDeliveredSequence: 1,
      lastReadSequence: 1,
    });

    const duplicate = await db.insertChatMessageV2({
      conversationId: context.conversation.id,
      senderMemberId,
      senderDeviceId: context.parentDeviceId,
      clientMessageId: "client-message-dedup-0001",
      text: "Этот повтор не должен перезаписать исходный текст",
      clientSentAt: 1_780_100_999_999,
    });
    expect(duplicate).toMatchObject({ created: false, deduplicated: true });
    expect(duplicate.message).toMatchObject({
      id: first.message.id,
      sequence: 1,
      text: preservedText,
      clientSentAt: 1_780_100_000_000,
    });
    await expect(
      db.getChatConversationById(context.conversation.id)
    ).resolves.toMatchObject({ nextSequence: 1 });
    const [receipt] = await db.getChatMessageReceipts(first.message.id);
    expect(receipt).toMatchObject({
      recipientMemberId,
      deliveredByDeviceId: context.childDeviceId,
      readByDeviceId: context.childDeviceId,
    });
    expect(receipt.deliveredAt).toBeGreaterThan(0);
    expect(receipt.readAt).toBeGreaterThan(0);

    const legacyId = "legacy-status-does-not-regress";
    await db.saveChatMessage(context.childDeviceId, {
      sender: "parent",
      senderDeviceId: context.parentDeviceId,
      message: "Original legacy text",
      timestamp: 1_780_101_000_000,
      id: legacyId,
      delivered: 1,
    });
    await db.markMessageAsReadByClientId(legacyId);
    await db.saveChatMessage(context.childDeviceId, {
      sender: "parent",
      senderDeviceId: context.parentDeviceId,
      message: "Retry must not overwrite",
      timestamp: 1_780_101_999_999,
      id: legacyId,
      delivered: 0,
    });
    await expect(
      db.get(
        `SELECT message, timestamp, delivered, is_read
         FROM chat_messages
         WHERE client_message_id = ?`,
        [legacyId]
      )
    ).resolves.toEqual({
      message: "Original legacy text",
      timestamp: 1_780_101_000_000,
      delivered: 1,
      is_read: 1,
    });
  });

  test("excludes stale provisional devices from family-chat receipts", async () => {
    const context = await registerFamily(db, "stale-receipts");
    const parentMemberId = context.memberByDevice.get(context.parentDeviceId);
    const childMemberId = context.memberByDevice.get(context.childDeviceId);

    await db.run(
      `UPDATE devices
       SET created_at = 1, updated_at = 1
       WHERE device_id = ?`,
      [context.childDeviceId]
    );
    await db.ensureFamilyConversation(context.family.id);

    const first = await db.insertChatMessageV2({
      conversationId: context.conversation.id,
      senderMemberId: parentMemberId,
      senderDeviceId: context.parentDeviceId,
      clientMessageId: "stale-receipts-0001",
      text: "Do not fan out to an obsolete provisional identity",
    });
    await expect(db.getChatMessageReceipts(first.message.id)).resolves.toEqual(
      []
    );
    await expect(
      db.get(
        `SELECT is_active AS isActive
         FROM chat_conversation_members
         WHERE conversation_id = ? AND member_id = ?`,
        [context.conversation.id, childMemberId]
      )
    ).resolves.toEqual({ isActive: 0 });

    await db.run(
      `UPDATE family_devices
       SET member_binding_source = 'EXPLICIT'
       WHERE family_id = ? AND member_id = ?`,
      [context.family.id, childMemberId]
    );
    await db.ensureFamilyConversation(context.family.id);

    const second = await db.insertChatMessageV2({
      conversationId: context.conversation.id,
      senderMemberId: parentMemberId,
      senderDeviceId: context.parentDeviceId,
      clientMessageId: "stale-receipts-0002",
      text: "Explicit family members remain eligible",
    });
    await expect(db.getChatMessageReceipts(second.message.id)).resolves.toEqual([
      expect.objectContaining({ recipientMemberId: childMemberId }),
    ]);
  });

  test("creates one stable direct conversation visible only to its members", async () => {
    const context = await registerFamily(db, "direct", { secondParent: true });
    const firstParentMemberId = context.memberByDevice.get(context.parentDeviceId);
    const secondParentMemberId = context.memberByDevice.get(
      context.secondParentDeviceId
    );
    const childMemberId = context.memberByDevice.get(context.childDeviceId);
    const direct = await db.createDirectConversation({
      familyId: context.family.id,
      memberIds: [childMemberId, firstParentMemberId],
      createdByMemberId: firstParentMemberId,
    });
    const repeated = await db.createDirectConversation({
      familyId: context.family.id,
      memberIds: [firstParentMemberId, childMemberId],
      createdByMemberId: childMemberId,
    });
    expect(repeated.id).toBe(direct.id);

    const familySend = await db.insertChatMessageV2({
      conversationId: context.conversation.id,
      senderMemberId: firstParentMemberId,
      senderDeviceId: context.parentDeviceId,
      clientMessageId: "direct-client-id-conflict-0001",
      text: "Family message",
    });
    expect(familySend.created).toBe(true);
    await expect(
      db.insertChatMessageV2({
        conversationId: direct.id,
        senderMemberId: firstParentMemberId,
        senderDeviceId: context.parentDeviceId,
        clientMessageId: "direct-client-id-conflict-0001",
        text: "Must not deduplicate across conversations",
      })
    ).rejects.toThrow("already used in another conversation");
    await expect(db.getChatConversationById(direct.id)).resolves.toMatchObject({
      nextSequence: 0,
    });

    await expect(
      db.getChatConversationForMember(direct.id, firstParentMemberId)
    ).resolves.toMatchObject({ id: direct.id, type: "DIRECT" });
    await expect(
      db.getChatConversationForMember(direct.id, childMemberId)
    ).resolves.toMatchObject({ id: direct.id, type: "DIRECT" });
    await expect(
      db.getChatConversationForMember(direct.id, secondParentMemberId)
    ).resolves.toBeUndefined();
    const outsiderList = await db.listChatConversationsForMember(
      secondParentMemberId
    );
    expect(outsiderList.some((conversation) => conversation.id === direct.id)).toBe(
      false
    );
  });
});
