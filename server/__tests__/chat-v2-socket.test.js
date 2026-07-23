const ChatConversationService = require("../services/ChatConversationService");
const ChatV2SocketService = require("../services/ChatV2SocketService");
const WebSocketManager = require("../managers/WebSocketManager");

class FakeSocket {
  constructor(id, deviceId, authMode = "authenticated") {
    this.id = id;
    this.authenticatedDeviceId = deviceId;
    this.authMode = authMode;
    this.connected = true;
    this.handlers = new Map();
    this.emit = jest.fn();
  }

  on(eventName, handler) {
    const handlers = this.handlers.get(eventName) || [];
    handlers.push(handler);
    this.handlers.set(eventName, handlers);
  }

  async trigger(eventName, payload, acknowledgement) {
    const handlers = this.handlers.get(eventName) || [];
    const results = [];
    for (const handler of handlers) {
      results.push(await handler(payload, acknowledgement));
    }
    return results;
  }

  payloads(eventName) {
    return this.emit.mock.calls
      .filter(([name]) => name === eventName)
      .map(([, payload]) => payload);
  }
}

function accessDenied() {
  return new ChatConversationService.Error(
    403,
    "CONVERSATION_ACCESS_DENIED",
    "Device is not a participant in this conversation"
  );
}

function createFakeChatService() {
  const members = {
    "parent-device": { memberId: "parent-member", memberRole: "GUARDIAN" },
    "child-device": { memberId: "child-member", memberRole: "CHILD" },
    "third-device": { memberId: "third-member", memberRole: "GUARDIAN" },
  };
  const conversations = {
    "family-conversation": {
      id: "family-conversation",
      familyId: "family-1",
      type: "FAMILY",
      devices: new Set(Object.keys(members)),
    },
    "direct-conversation": {
      id: "direct-conversation",
      familyId: "family-1",
      type: "DIRECT",
      devices: new Set(["parent-device", "child-device"]),
    },
  };
  const messages = new Map();
  const nextSequence = new Map();
  const service = {
    failSend: false,
    resolveConversationActor: jest.fn(async (deviceId, conversationId) => {
      const conversation = conversations[conversationId];
      const member = members[deviceId];
      if (!conversation || !member || !conversation.devices.has(deviceId)) {
        throw accessDenied();
      }
      return {
        deviceId,
        ...member,
        familyId: conversation.familyId,
        conversation,
      };
    }),
    sendMessage: jest.fn(async (deviceId, conversationId, payload) => {
      if (service.failSend) throw new Error("database unavailable");
      const actor = await service.resolveConversationActor(
        deviceId,
        conversationId
      );
      const key = `${conversationId}\u0000${deviceId}\u0000${payload.clientMessageId}`;
      const existing = messages.get(key);
      if (existing) {
        return {
          created: false,
          deduplicated: true,
          message: existing,
        };
      }
      const sequence = (nextSequence.get(conversationId) || 0) + 1;
      nextSequence.set(conversationId, sequence);
      const message = {
        messageId: `message-${sequence}`,
        clientMessageId: payload.clientMessageId,
        conversationId,
        serverSequence: sequence,
        senderMemberId: actor.memberId,
        senderDeviceId: deviceId,
        senderRole: actor.memberRole,
        text: payload.text,
        clientSentAt: payload.clientSentAt,
        serverCreatedAt: 1_800_000_000_000 + sequence,
        deliveryState: "ACCEPTED",
        receipts: [],
      };
      messages.set(key, message);
      return { created: true, deduplicated: false, message };
    }),
    advanceReceipt: jest.fn(async (deviceId, conversationId, payload) => {
      const actor = await service.resolveConversationActor(
        deviceId,
        conversationId
      );
      return {
        receipt: {
          conversationId,
          memberId: actor.memberId,
          deliveredThroughSequence:
            payload.deliveredThroughSequence === undefined
              ? 0
              : payload.deliveredThroughSequence,
          readThroughSequence:
            payload.readThroughSequence === undefined
              ? 0
              : payload.readThroughSequence,
        },
      };
    }),
  };
  return service;
}

describe("chat v2 WebSocket protocol", () => {
  let sockets;
  let io;
  let chatService;
  let socketService;
  let parent;
  let child;
  let third;
  let errorSpy;
  let logSpy;
  let previousRequireWsAuth;

  beforeEach(() => {
    previousRequireWsAuth = process.env.CW_REQUIRE_WS_AUTH;
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    sockets = new Map();
    io = { sockets: { sockets } };
    chatService = createFakeChatService();
    socketService = new ChatV2SocketService(io, chatService);
    parent = new FakeSocket("parent-socket", "parent-device");
    child = new FakeSocket("child-socket", "child-device");
    third = new FakeSocket("third-socket", "third-device");
    for (const socket of [parent, child, third]) {
      sockets.set(socket.id, socket);
      socketService.registerSocket(socket);
    }
  });

  afterEach(() => {
    if (previousRequireWsAuth === undefined) {
      delete process.env.CW_REQUIRE_WS_AUTH;
    } else {
      process.env.CW_REQUIRE_WS_AUTH = previousRequireWsAuth;
    }
    errorSpy.mockRestore();
    logSpy.mockRestore();
  });

  async function subscribe(socket, conversationId) {
    await socket.trigger("chat_v2:subscribe", { conversationId });
  }

  function clearOutgoing(...targetSockets) {
    for (const socket of targetSockets) socket.emit.mockClear();
  }

  test("rejects legacy sockets even when compatibility mode is enabled", async () => {
    process.env.CW_REQUIRE_WS_AUTH = "0";
    const legacy = new FakeSocket(
      "legacy-socket",
      "parent-device",
      "legacy"
    );
    sockets.set(legacy.id, legacy);
    socketService.registerSocket(legacy);

    await subscribe(legacy, "family-conversation");

    expect(legacy.payloads("chat_v2:error")).toEqual([
      expect.objectContaining({
        operation: "subscribe",
        code: "CHAT_V2_AUTH_REQUIRED",
      }),
    ]);
    expect(chatService.resolveConversationActor).not.toHaveBeenCalled();
  });

  test("derives sender identity only from the authenticated socket", async () => {
    await subscribe(parent, "family-conversation");
    clearOutgoing(parent);

    await parent.trigger("chat_v2:send", {
      conversationId: "family-conversation",
      clientMessageId: "client-message-1",
      text: "Hello family",
      clientSentAt: 1_800_000_000_000,
      senderDeviceId: "child-device",
      senderMemberId: "child-member",
      familyId: "other-family",
      senderRole: "CHILD",
    });

    expect(chatService.sendMessage).toHaveBeenCalledWith(
      "parent-device",
      "family-conversation",
      {
        clientMessageId: "client-message-1",
        text: "Hello family",
        clientSentAt: 1_800_000_000_000,
      }
    );
    expect(parent.payloads("chat_v2:accepted")[0]).toMatchObject({
      success: true,
      clientMessageId: "client-message-1",
      created: true,
    });
    expect(parent.payloads("chat_v2:message")[0].message).toMatchObject({
      senderDeviceId: "parent-device",
      senderMemberId: "parent-member",
    });
  });

  test("does not let a nonparticipant subscribe or send", async () => {
    await subscribe(third, "direct-conversation");
    await third.trigger("chat_v2:send", {
      conversationId: "direct-conversation",
      clientMessageId: "forbidden-message",
      text: "Should not pass",
      clientSentAt: 1_800_000_000_000,
    });

    expect(third.payloads("chat_v2:error")).toEqual([
      expect.objectContaining({
        operation: "subscribe",
        code: "CONVERSATION_ACCESS_DENIED",
      }),
      expect.objectContaining({
        operation: "send",
        code: "CONVERSATION_ACCESS_DENIED",
      }),
    ]);
    expect(third.payloads("chat_v2:subscribed")).toHaveLength(0);
    expect(third.payloads("chat_v2:accepted")).toHaveLength(0);
  });

  test("never leaks a direct message to a third family member", async () => {
    await subscribe(parent, "direct-conversation");
    await subscribe(child, "direct-conversation");
    await subscribe(third, "direct-conversation");
    clearOutgoing(parent, child, third);

    await parent.trigger("chat_v2:send", {
      conversationId: "direct-conversation",
      clientMessageId: "private-message",
      text: "Private",
      clientSentAt: 1_800_000_000_000,
    });

    expect(parent.payloads("chat_v2:message")).toHaveLength(1);
    expect(child.payloads("chat_v2:message")).toHaveLength(1);
    expect(third.payloads("chat_v2:message")).toHaveLength(0);
  });

  test("replaces a stale chat transport for the same authenticated device", async () => {
    const reconnectedParent = new FakeSocket(
      "parent-socket-reconnected",
      "parent-device"
    );
    sockets.set(reconnectedParent.id, reconnectedParent);
    socketService.registerSocket(reconnectedParent);

    await subscribe(parent, "family-conversation");
    await subscribe(child, "family-conversation");
    await subscribe(reconnectedParent, "family-conversation");
    clearOutgoing(parent, child, reconnectedParent);

    await reconnectedParent.trigger("chat_v2:send", {
      conversationId: "family-conversation",
      clientMessageId: "after-transport-reconnect",
      text: "Current transport only",
      clientSentAt: 1_800_000_000_000,
    });

    expect(parent.payloads("chat_v2:message")).toHaveLength(0);
    expect(reconnectedParent.payloads("chat_v2:message")).toHaveLength(1);
    expect(child.payloads("chat_v2:message")).toHaveLength(1);
  });

  test("subscribes an authenticated device immediately after connection", async () => {
    const autoChatService = createFakeChatService();
    autoChatService.listConversations = jest.fn(async (deviceId) => ({
      conversations:
        deviceId === "parent-device"
          ? [
              { conversationId: "family-conversation" },
              { conversationId: "direct-conversation" },
            ]
          : [],
    }));
    const autoSocketService = new ChatV2SocketService(io, autoChatService);
    const connectedParent = new FakeSocket(
      "auto-parent-socket",
      "parent-device"
    );
    sockets.set(connectedParent.id, connectedParent);

    autoSocketService.registerSocket(connectedParent);
    await new Promise((resolve) => setImmediate(resolve));

    expect(
      autoSocketService.conversationSockets
        .get("family-conversation")
        ?.has(connectedParent.id)
    ).toBe(true);
    expect(
      autoSocketService.conversationSockets
        .get("direct-conversation")
        ?.has(connectedParent.id)
    ).toBe(true);
  });

  test("does not accept or broadcast a message when durable storage fails", async () => {
    await subscribe(parent, "family-conversation");
    await subscribe(child, "family-conversation");
    clearOutgoing(parent, child);
    chatService.failSend = true;

    await parent.trigger("chat_v2:send", {
      conversationId: "family-conversation",
      clientMessageId: "failed-message",
      text: "Not durable",
      clientSentAt: 1_800_000_000_000,
    });

    expect(parent.payloads("chat_v2:error")).toEqual([
      expect.objectContaining({ code: "CHAT_V2_INTERNAL_ERROR" }),
    ]);
    expect(parent.payloads("chat_v2:accepted")).toHaveLength(0);
    expect(parent.payloads("chat_v2:message")).toHaveLength(0);
    expect(child.payloads("chat_v2:message")).toHaveLength(0);
  });

  test("acknowledges an idempotent retry without rebroadcasting", async () => {
    await subscribe(parent, "family-conversation");
    await subscribe(child, "family-conversation");
    clearOutgoing(parent, child);
    const payload = {
      conversationId: "family-conversation",
      clientMessageId: "idempotent-message",
      text: "Only once",
      clientSentAt: 1_800_000_000_000,
    };

    await parent.trigger("chat_v2:send", payload);
    await parent.trigger("chat_v2:send", payload);

    expect(parent.payloads("chat_v2:accepted")).toEqual([
      expect.objectContaining({ created: true, deduplicated: false }),
      expect.objectContaining({ created: false, deduplicated: true }),
    ]);
    expect(parent.payloads("chat_v2:message")).toHaveLength(1);
    expect(child.payloads("chat_v2:message")).toHaveLength(1);
  });

  test("broadcasts a server-derived receipt to current participants", async () => {
    await subscribe(parent, "direct-conversation");
    await subscribe(child, "direct-conversation");
    clearOutgoing(parent, child);

    await child.trigger("chat_v2:receipt", {
      conversationId: "direct-conversation",
      memberId: "parent-member",
      deliveredThroughSequence: 7,
      readThroughSequence: 6,
    });

    expect(chatService.advanceReceipt).toHaveBeenCalledWith(
      "child-device",
      "direct-conversation",
      { deliveredThroughSequence: 7, readThroughSequence: 6 }
    );
    for (const socket of [parent, child]) {
      expect(socket.payloads("chat_v2:receipt_updated")[0]).toMatchObject({
        conversationId: "direct-conversation",
        receipt: {
          memberId: "child-member",
          deliveredThroughSequence: 7,
          readThroughSequence: 6,
        },
      });
    }
  });

  test("returns receipt_updated to an authorized sender before subscription", async () => {
    await subscribe(parent, "direct-conversation");
    clearOutgoing(parent, child);

    await child.trigger("chat_v2:receipt", {
      conversationId: "direct-conversation",
      deliveredThroughSequence: 3,
    });

    expect(parent.payloads("chat_v2:receipt_updated")).toHaveLength(1);
    expect(child.payloads("chat_v2:receipt_updated")).toEqual([
      expect.objectContaining({
        conversationId: "direct-conversation",
        receipt: expect.objectContaining({ memberId: "child-member" }),
      }),
    ]);
  });

  test("removes all subscriptions on disconnect", async () => {
    await subscribe(parent, "family-conversation");
    await subscribe(parent, "direct-conversation");
    parent.connected = false;

    await parent.trigger("disconnect");

    expect(socketService.socketConversations.has(parent.id)).toBe(false);
    expect(
      socketService.conversationSockets
        .get("family-conversation")
        ?.has(parent.id) || false
    ).toBe(false);
    expect(
      socketService.conversationSockets
        .get("direct-conversation")
        ?.has(parent.id) || false
    ).toBe(false);
  });

  test("WebSocketManager registers v2 alongside legacy chat handlers", () => {
    const connectionHandlers = [];
    const managerIo = {
      sockets: { sockets: new Map() },
      on: jest.fn((eventName, handler) => {
        if (eventName === "connection") connectionHandlers.push(handler);
      }),
    };
    const manager = new WebSocketManager(managerIo);
    const bridge = { registerSocket: jest.fn() };
    manager.setChatV2SocketService(bridge);
    manager.initialize();
    const socket = new FakeSocket("integrated-socket", "parent-device");

    for (const handler of connectionHandlers) handler(socket);

    expect(bridge.registerSocket).toHaveBeenCalledWith(socket);
    expect(socket.handlers.has("chat_message")).toBe(true);
    expect(socket.handlers.has("chat_message_status")).toBe(true);
  });
});
