const DatabaseManager = require("../database/DatabaseManager");
const ChatConversationService = require("../services/ChatConversationService");

jest.setTimeout(30_000);

/**
 * Editing and deleting messages.
 *
 * Two different deletions have to stay distinct: withdrawing a message removes it
 * for everyone, while "delete for me" must hide it on one device only and leave
 * it untouched for the other participants.
 */
async function registerFamily(db, suffix) {
  const parentDeviceId = `parent-edit-${suffix}-0001`;
  const childDeviceId = `child-edit-${suffix}-0001`;

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
    createdBy: "edit-test",
  });

  const [family] = await db.getFamiliesForDevice(childDeviceId);
  const conversation = await db.ensureFamilyConversation(family.id);
  return { family, conversation, parentDeviceId, childDeviceId };
}

describe("editing and deleting chat messages", () => {
  let db;
  let service;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    service = new ChatConversationService(db);
  });

  afterEach(async () => {
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  async function sendMessage(deviceId, conversationId, text, clientMessageId) {
    const result = await service.sendMessage(deviceId, conversationId, {
      clientMessageId,
      text,
      clientSentAt: Date.now(),
    });
    return result.message;
  }

  test("the author can rewrite their own message and it stays in place", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "a"
    );
    const original = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Опечятка",
      "client-edit-1"
    );

    const edited = await service.editMessage(
      parentDeviceId,
      conversation.id,
      original.messageId,
      { text: "Исправлено" }
    );

    expect(edited.message.text).toBe("Исправлено");
    expect(edited.message.editedAt).toBeTruthy();
    // The position in the conversation must not change on edit.
    expect(edited.message.serverSequence).toBe(original.serverSequence);

    const page = await service.getMessages(childDeviceId, conversation.id);
    expect(page.messages).toHaveLength(1);
    expect(page.messages[0].text).toBe("Исправлено");
    expect(page.messages[0].editedAt).toBeTruthy();
  });

  test("only the author may edit", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "b"
    );
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Моё сообщение",
      "client-edit-2"
    );

    await expect(
      service.editMessage(childDeviceId, conversation.id, message.messageId, {
        text: "Чужое",
      })
    ).rejects.toMatchObject({ code: "MESSAGE_NOT_OWNED" });
  });

  test("empty text is refused", async () => {
    const { conversation, parentDeviceId } = await registerFamily(db, "c");
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Текст",
      "client-edit-3"
    );

    await expect(
      service.editMessage(parentDeviceId, conversation.id, message.messageId, {
        text: "   ",
      })
    ).rejects.toMatchObject({ code: "INVALID_MESSAGE_TEXT" });
  });

  test("over-long text is refused instead of crashing", async () => {
    const { conversation, parentDeviceId } = await registerFamily(db, "d");
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Текст",
      "client-edit-4"
    );

    const tooLong = "я".repeat(20 * 1024);
    await expect(
      service.editMessage(parentDeviceId, conversation.id, message.messageId, {
        text: tooLong,
      })
    ).rejects.toMatchObject({ code: "MESSAGE_TEXT_TOO_LARGE" });
  });

  test("withdrawing for everyone hides the text but keeps the place", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "e"
    );
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Секрет",
      "client-edit-5"
    );

    const result = await service.deleteMessage(
      parentDeviceId,
      conversation.id,
      message.messageId,
      { forEveryone: true }
    );
    expect(result.forEveryone).toBe(true);

    for (const deviceId of [parentDeviceId, childDeviceId]) {
      const page = await service.getMessages(deviceId, conversation.id);
      expect(page.messages).toHaveLength(1);
      expect(page.messages[0].text).toBe("");
      expect(page.messages[0].deletedAt).toBeTruthy();
    }
  });

  test("only the author may withdraw for everyone", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "f"
    );
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Не удаляй",
      "client-edit-6"
    );

    await expect(
      service.deleteMessage(childDeviceId, conversation.id, message.messageId, {
        forEveryone: true,
      })
    ).rejects.toMatchObject({ code: "MESSAGE_NOT_OWNED" });

    const page = await service.getMessages(childDeviceId, conversation.id);
    expect(page.messages[0].text).toBe("Не удаляй");
  });

  test("deleting for me hides it on one device and not on the other", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "g"
    );
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Только для меня",
      "client-edit-7"
    );

    await service.deleteMessage(childDeviceId, conversation.id, message.messageId);

    const childPage = await service.getMessages(childDeviceId, conversation.id);
    expect(childPage.messages).toHaveLength(0);

    const parentPage = await service.getMessages(parentDeviceId, conversation.id);
    expect(parentPage.messages).toHaveLength(1);
    expect(parentPage.messages[0].text).toBe("Только для меня");
    expect(parentPage.messages[0].deletedAt).toBeFalsy();
  });

  test("deleting for me is idempotent", async () => {
    const { conversation, parentDeviceId, childDeviceId } = await registerFamily(
      db,
      "h"
    );
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Повтор",
      "client-edit-8"
    );

    await service.deleteMessage(childDeviceId, conversation.id, message.messageId);
    await service.deleteMessage(childDeviceId, conversation.id, message.messageId);

    const childPage = await service.getMessages(childDeviceId, conversation.id);
    expect(childPage.messages).toHaveLength(0);
  });

  test("a withdrawn message cannot be edited", async () => {
    const { conversation, parentDeviceId } = await registerFamily(db, "i");
    const message = await sendMessage(
      parentDeviceId,
      conversation.id,
      "Удалили",
      "client-edit-9"
    );
    await service.deleteMessage(
      parentDeviceId,
      conversation.id,
      message.messageId,
      { forEveryone: true }
    );

    await expect(
      service.editMessage(parentDeviceId, conversation.id, message.messageId, {
        text: "Верни",
      })
    ).rejects.toMatchObject({ code: "MESSAGE_DELETED" });
  });

  test("an unknown message is reported as not found", async () => {
    const { conversation, parentDeviceId } = await registerFamily(db, "j");

    await expect(
      service.editMessage(parentDeviceId, conversation.id, "msg_missing", {
        text: "Ничего",
      })
    ).rejects.toMatchObject({ code: "MESSAGE_NOT_FOUND" });
  });
});
