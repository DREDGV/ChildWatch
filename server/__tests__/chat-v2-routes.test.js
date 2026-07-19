const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const createChatV2Routes = require("../routes/chat-v2");

jest.setTimeout(30_000);

function requestJson(server, { method = "GET", path, deviceId, body }) {
  const address = server.address();
  const encodedBody = body === undefined ? null : JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        method,
        path,
        headers: {
          "x-test-device-id": deviceId,
          ...(encodedBody === null
            ? {}
            : {
                "content-type": "application/json",
                "content-length": Buffer.byteLength(encodedBody),
              }),
        },
      },
      (response) => {
        let responseBody = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          responseBody += chunk;
        });
        response.on("end", () => {
          resolve({
            status: response.statusCode,
            body: responseBody ? JSON.parse(responseBody) : null,
          });
        });
      }
    );
    request.on("error", reject);
    if (encodedBody !== null) request.write(encodedBody);
    request.end();
  });
}

async function registerFamily(db, suffix, { thirdMember = false } = {}) {
  const parentDeviceId = `chat-parent-${suffix}-0001`;
  const childDeviceId = `chat-child-${suffix}-0001`;
  const thirdDeviceId = `chat-parent-${suffix}-0002`;

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
    createdBy: "chat-v2-route-test",
  });

  if (thirdMember) {
    await db.registerDevice(thirdDeviceId, {
      device_name: `Second parent ${suffix}`,
      device_type: "android",
      app_version: "8.0.0",
    });
    await db.upsertDeviceLink({
      parentDeviceId: thirdDeviceId,
      childDeviceId,
      parentDisplayName: `Second parent ${suffix}`,
      childDisplayName: `Child ${suffix}`,
      createdBy: "chat-v2-route-test",
    });
  }

  const [family] = await db.getFamiliesForDevice(parentDeviceId);
  const membershipFor = async (deviceId) =>
    db.getFamilyDeviceMembership(family.id, deviceId);
  return {
    family,
    parentDeviceId,
    parentMembership: await membershipFor(parentDeviceId),
    childDeviceId,
    childMembership: await membershipFor(childDeviceId),
    thirdDeviceId: thirdMember ? thirdDeviceId : null,
    thirdMembership: thirdMember ? await membershipFor(thirdDeviceId) : null,
  };
}

describe("chat v2 HTTP API", () => {
  let db;
  let server;
  let primary;
  let secondary;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    primary = await registerFamily(db, "primary", { thirdMember: true });
    secondary = await registerFamily(db, "secondary");

    const app = express();
    app.use(express.json({ limit: "64kb" }));
    app.use((req, res, next) => {
      req.deviceId = req.headers["x-test-device-id"];
      next();
    });
    app.use("/api/chat/v2", createChatV2Routes(db));
    server = http.createServer(app);
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  async function familyConversation(context, deviceId = context.parentDeviceId) {
    const response = await requestJson(server, {
      path: "/api/chat/v2/conversations",
      deviceId,
    });
    expect(response.status).toBe(200);
    const conversation = response.body.conversations.find(
      (conversation) => conversation.type === "FAMILY"
    );
    expect(conversation).toMatchObject({
      conversationId: expect.any(String),
      familyId: context.family.id,
      lastSequence: expect.any(Number),
      members: expect.arrayContaining([
        expect.objectContaining({
          memberId: context.parentMembership.memberId,
          displayName: expect.any(String),
        }),
      ]),
    });
    expect(conversation).not.toHaveProperty("id");
    expect(conversation).not.toHaveProperty("nextSequence");
    return conversation;
  }

  async function sendMessage(
    context,
    conversationId,
    clientMessageId,
    text,
    deviceId = context.parentDeviceId
  ) {
    return requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversationId}/messages`,
      deviceId,
      body: { clientMessageId, text, clientSentAt: 1_783_000_000_000 },
    });
  }

  test("denies cross-family conversation access and direct-chat creation", async () => {
    const foreignConversation = await familyConversation(secondary);
    const read = await requestJson(server, {
      path: `/api/chat/v2/conversations/${foreignConversation.conversationId}/messages`,
      deviceId: primary.parentDeviceId,
    });
    expect(read).toEqual({
      status: 403,
      body: {
        error: "Device is not a participant in this conversation",
        code: "CONVERSATION_ACCESS_DENIED",
      },
    });

    const direct = await requestJson(server, {
      method: "POST",
      path: "/api/chat/v2/conversations/direct",
      deviceId: primary.parentDeviceId,
      body: {
        targetMemberId: secondary.childMembership.memberId,
        familyId: secondary.family.id,
        memberId: secondary.parentMembership.memberId,
      },
    });
    expect(direct).toEqual({
      status: 403,
      body: {
        error: "Target member is not available for a direct conversation",
        code: "DIRECT_TARGET_NOT_AVAILABLE",
      },
    });
  });

  test("keeps a direct conversation private from a third family member", async () => {
    const created = await requestJson(server, {
      method: "POST",
      path: "/api/chat/v2/conversations/direct",
      deviceId: primary.parentDeviceId,
      body: { targetMemberId: primary.childMembership.memberId },
    });
    expect(created.status).toBe(201);
    expect(created.body.conversation.type).toBe("DIRECT");
    expect(created.body.conversation.title).toBe("Child primary");
    expect(created.body.conversation.members).toHaveLength(2);
    expect(created.body.conversation).not.toHaveProperty("id");
    expect(created.body.conversation).not.toHaveProperty("nextSequence");

    const denied = await requestJson(server, {
      path: `/api/chat/v2/conversations/${created.body.conversation.conversationId}/messages`,
      deviceId: primary.thirdDeviceId,
    });
    expect(denied.body.code).toBe("CONVERSATION_ACCESS_DENIED");
    expect(denied.status).toBe(403);

    const outsiderList = await requestJson(server, {
      path: "/api/chat/v2/conversations",
      deviceId: primary.thirdDeviceId,
    });
    expect(
      outsiderList.body.conversations.some(
        (conversation) =>
          conversation.conversationId ===
          created.body.conversation.conversationId
      )
    ).toBe(false);
  });

  test("deduplicates clientMessageId and derives the sender from device auth", async () => {
    const conversation = await familyConversation(primary);
    const body = {
      clientMessageId: "http-idempotent-message-0001",
      text: "One durable message",
      clientSentAt: 1_783_100_000_000,
      familyId: secondary.family.id,
      memberId: primary.childMembership.memberId,
      sender: "child",
    };
    const first = await requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages`,
      deviceId: primary.parentDeviceId,
      body,
    });
    const retry = await requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages`,
      deviceId: primary.parentDeviceId,
      body: { ...body, text: "A retry must not overwrite the original" },
    });

    expect(first.status).toBe(201);
    expect(first.body).toMatchObject({
      created: true,
      deduplicated: false,
      message: {
        senderMemberId: primary.parentMembership.memberId,
        senderRole: primary.parentMembership.memberRole,
        serverSequence: 1,
        text: body.text,
      },
    });
    expect(retry.status).toBe(200);
    expect(retry.body).toMatchObject({
      created: false,
      deduplicated: true,
      message: {
        messageId: first.body.message.messageId,
        serverSequence: first.body.message.serverSequence,
        text: body.text,
      },
    });
    expect(first.body.message).not.toHaveProperty("id");
    expect(first.body.message).not.toHaveProperty("sequence");
    expect(first.body.message).not.toHaveProperty("senderRoleSnapshot");
    expect(first.body.message).not.toHaveProperty("senderDeviceId");

    const page = await requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages`,
      deviceId: primary.childDeviceId,
    });
    expect(page.body.messages).toHaveLength(1);
  });

  test("preserves original Unicode, whitespace and ZWJ emoji without truncation", async () => {
    const conversation = await familyConversation(primary);
    const text = "  Семья 👨‍👩‍👧‍👦 ❤️ 👍🏽 e\u0301\n";
    const sent = await sendMessage(
      primary,
      conversation.conversationId,
      "unicode-zwj-message-0001",
      text
    );
    expect(sent.status).toBe(201);
    expect(Buffer.from(sent.body.message.text, "utf8")).toEqual(
      Buffer.from(text, "utf8")
    );

    const page = await requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages`,
      deviceId: primary.childDeviceId,
    });
    expect(page.body.messages[0].text).toBe(text);

    const exactLimitText = "😀".repeat(4096);
    const exactLimit = await sendMessage(
      primary,
      conversation.conversationId,
      "unicode-exact-limit-0001",
      exactLimitText
    );
    expect(exactLimit.status).toBe(201);
    expect(Buffer.byteLength(exactLimit.body.message.text, "utf8")).toBe(
      16 * 1024
    );

    const oversized = await sendMessage(
      primary,
      conversation.conversationId,
      "unicode-too-large-0001",
      `${exactLimitText}a`
    );
    expect(oversized).toEqual({
      status: 413,
      body: {
        error: "Message text exceeds 16 KiB",
        code: "MESSAGE_TEXT_TOO_LARGE",
      },
    });
  });

  test("pages backwards by exclusive server sequence", async () => {
    const conversation = await familyConversation(primary);
    for (let index = 1; index <= 5; index += 1) {
      const sent = await sendMessage(
        primary,
        conversation.conversationId,
        `paging-message-${index}`,
        `Message ${index}`
      );
      expect(sent.status).toBe(201);
    }

    const latest = await requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages?limit=2`,
      deviceId: primary.childDeviceId,
    });
    expect(latest.body).toMatchObject({
      hasMore: true,
      nextBeforeSequence: 4,
    });
    expect(
      latest.body.messages.map((message) => message.serverSequence)
    ).toEqual([4, 5]);

    const middle = await requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages?limit=2&beforeSequence=4`,
      deviceId: primary.childDeviceId,
    });
    expect(
      middle.body.messages.map((message) => message.serverSequence)
    ).toEqual([2, 3]);
    expect(middle.body).toMatchObject({
      hasMore: true,
      nextBeforeSequence: 2,
    });
  });

  test("advances delivery and read receipts monotonically", async () => {
    const conversation = await familyConversation(primary);
    for (let index = 1; index <= 3; index += 1) {
      await sendMessage(
        primary,
        conversation.conversationId,
        `receipt-message-${index}`,
        `Receipt ${index}`
      );
    }

    const advance = await requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversation.conversationId}/receipts`,
      deviceId: primary.childDeviceId,
      body: { deliveredThroughSequence: 3, readThroughSequence: 2 },
    });
    expect(advance.body.receipt).toMatchObject({
      memberId: primary.childMembership.memberId,
      deliveredThroughSequence: 3,
      readThroughSequence: 2,
    });

    const stale = await requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversation.conversationId}/receipts`,
      deviceId: primary.childDeviceId,
      body: { deliveredThroughSequence: 1, readThroughSequence: 1 },
    });
    expect(stale.body.receipt).toMatchObject({
      deliveredThroughSequence: 3,
      readThroughSequence: 2,
    });

    const readLatest = await requestJson(server, {
      method: "POST",
      path: `/api/chat/v2/conversations/${conversation.conversationId}/receipts`,
      deviceId: primary.childDeviceId,
      body: { readThroughSequence: 3 },
    });
    expect(readLatest.body.receipt).toMatchObject({
      deliveredThroughSequence: 3,
      readThroughSequence: 3,
    });

    const page = await requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation.conversationId}/messages?limit=1`,
      deviceId: primary.childDeviceId,
    });
    const recipientReceipt = page.body.messages[0].receipts.find(
      (receipt) =>
        receipt.recipientMemberId === primary.childMembership.memberId
    );
    expect(recipientReceipt.deliveredAt).toBeGreaterThan(0);
    expect(recipientReceipt.readAt).toBeGreaterThan(0);
    expect(recipientReceipt).not.toHaveProperty("deliveredByDeviceId");
    expect(recipientReceipt).not.toHaveProperty("readByDeviceId");
  });

  test("returns a stable conflict when a client id is reused in another conversation", async () => {
    const family = await familyConversation(primary);
    const direct = await requestJson(server, {
      method: "POST",
      path: "/api/chat/v2/conversations/direct",
      deviceId: primary.parentDeviceId,
      body: { targetMemberId: primary.childMembership.memberId },
    });
    const clientMessageId = "cross-conversation-conflict-0001";
    const familySend = await sendMessage(
      primary,
      family.conversationId,
      clientMessageId,
      "Family message"
    );
    expect(familySend.status).toBe(201);

    const conflict = await sendMessage(
      primary,
      direct.body.conversation.conversationId,
      clientMessageId,
      "Direct message"
    );
    expect(conflict).toEqual({
      status: 409,
      body: {
        error: "clientMessageId is already used in another conversation",
        code: "CLIENT_MESSAGE_ID_CONFLICT",
      },
    });
  });
});
