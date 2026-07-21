const ChatConversationService = require("./ChatConversationService");

const EVENTS = Object.freeze({
  SUBSCRIBE: "chat_v2:subscribe",
  SUBSCRIBED: "chat_v2:subscribed",
  UNSUBSCRIBE: "chat_v2:unsubscribe",
  UNSUBSCRIBED: "chat_v2:unsubscribed",
  SEND: "chat_v2:send",
  ACCEPTED: "chat_v2:accepted",
  MESSAGE: "chat_v2:message",
  RECEIPT: "chat_v2:receipt",
  RECEIPT_UPDATED: "chat_v2:receipt_updated",
  ERROR: "chat_v2:error",
});

class ChatV2SocketService {
  constructor(io, chatService) {
    if (!io?.sockets?.sockets || !chatService) {
      throw new Error("Chat v2 sockets require Socket.IO and chat services");
    }

    this.io = io;
    this.chatService = chatService;
    this.conversationSockets = new Map();
    this.socketConversations = new Map();
    this.deviceSockets = new Map();
    this.socketDevices = new Map();
    this.registeredSockets = new WeakSet();
  }

  normalizeId(value) {
    return typeof value === "string" ? value.trim() : "";
  }

  requireAuthenticatedDeviceId(socket) {
    const deviceId = this.normalizeId(socket?.authenticatedDeviceId);
    if (socket?.authMode !== "authenticated" || !deviceId) {
      throw new ChatConversationService.Error(
        401,
        "CHAT_V2_AUTH_REQUIRED",
        "Authenticated WebSocket session is required"
      );
    }
    return deviceId;
  }

  toErrorPayload(error, context = {}) {
    const known = error instanceof ChatConversationService.Error;
    return {
      success: false,
      operation: context.operation || "unknown",
      ...(context.conversationId
        ? { conversationId: context.conversationId }
        : {}),
      ...(context.clientMessageId
        ? { clientMessageId: context.clientMessageId }
        : {}),
      code: known ? error.code : "CHAT_V2_INTERNAL_ERROR",
      message: known ? error.message : "Chat request failed",
    };
  }

  acknowledge(acknowledgement, payload) {
    if (typeof acknowledgement !== "function") return;
    try {
      acknowledgement(payload);
    } catch (error) {
      console.error("[chat-v2] Acknowledgement callback failed:", error);
    }
  }

  emitResponse(socket, eventName, payload, acknowledgement) {
    if (socket?.connected !== false) {
      try {
        socket.emit(eventName, payload);
      } catch (error) {
        console.error(`[chat-v2] Failed to emit ${eventName}:`, error);
      }
    }
    this.acknowledge(acknowledgement, payload);
  }

  emitError(socket, error, context, acknowledgement) {
    const payload = this.toErrorPayload(error, context);
    if (!(error instanceof ChatConversationService.Error)) {
      console.error("[chat-v2] WebSocket request failed:", error);
    }
    this.emitResponse(socket, EVENTS.ERROR, payload, acknowledgement);
    return payload;
  }

  bindCurrentDeviceSocket(socket, deviceId) {
    const previousSocketId = this.deviceSockets.get(deviceId);
    if (previousSocketId && previousSocketId !== socket.id) {
      // Android reconnects can leave the previous Socket.IO transport alive
      // until its timeout expires. It must not remain a chat subscriber: a
      // family with many stale transports otherwise performed dozens of
      // authorization queries before every message broadcast.
      this.removeSocketId(previousSocketId);
    }
    this.deviceSockets.set(deviceId, socket.id);
    this.socketDevices.set(socket.id, deviceId);
  }

  addSubscription(socket, conversationId, deviceId = null) {
    if (deviceId) this.bindCurrentDeviceSocket(socket, deviceId);
    this.removeSubscription(socket, conversationId);

    let socketIds = this.conversationSockets.get(conversationId);
    if (!socketIds) {
      socketIds = new Set();
      this.conversationSockets.set(conversationId, socketIds);
    }
    socketIds.add(socket.id);

    let conversationIds = this.socketConversations.get(socket.id);
    if (!conversationIds) {
      conversationIds = new Set();
      this.socketConversations.set(socket.id, conversationIds);
    }
    conversationIds.add(conversationId);
  }

  removeSubscription(socket, conversationId) {
    const normalizedConversationId = this.normalizeId(conversationId);
    if (!socket?.id || !normalizedConversationId) return false;

    const socketIds = this.conversationSockets.get(normalizedConversationId);
    const removed = socketIds?.delete(socket.id) === true;
    if (socketIds?.size === 0) {
      this.conversationSockets.delete(normalizedConversationId);
    }

    const conversationIds = this.socketConversations.get(socket.id);
    conversationIds?.delete(normalizedConversationId);
    if (conversationIds?.size === 0) {
      this.socketConversations.delete(socket.id);
    }
    return removed;
  }

  removeSocket(socket) {
    if (!socket?.id) return;
    this.removeSocketId(socket.id);
  }

  removeSocketId(socketId) {
    if (!socketId) return;
    const conversationIds = Array.from(
      this.socketConversations.get(socketId) || []
    );
    for (const conversationId of conversationIds) {
      const socketIds = this.conversationSockets.get(conversationId);
      socketIds?.delete(socketId);
      if (socketIds?.size === 0) {
        this.conversationSockets.delete(conversationId);
      }
    }
    this.socketConversations.delete(socketId);
    const deviceId = this.socketDevices.get(socketId);
    if (deviceId && this.deviceSockets.get(deviceId) === socketId) {
      this.deviceSockets.delete(deviceId);
    }
    this.socketDevices.delete(socketId);
  }

  async getAuthorizedSubscribers(conversationId) {
    const socketIds = Array.from(
      this.conversationSockets.get(conversationId) || []
    );
    const authorized = await Promise.all(socketIds.map(async (socketId) => {
      const socket = this.io.sockets.sockets.get(socketId);
      if (!socket || socket.connected === false) {
        this.removeSocketId(socketId);
        return null;
      }

      try {
        const deviceId = this.requireAuthenticatedDeviceId(socket);
        await this.chatService.resolveConversationActor(
          deviceId,
          conversationId
        );
        if (
          this.conversationSockets.get(conversationId)?.has(socket.id) &&
          socket.connected !== false
        ) {
          return socket;
        }
      } catch (_error) {
        // Membership may be revoked after subscription. Evict silently so a
        // stale socket can never receive FAMILY or DIRECT conversation data.
        this.removeSubscription(socket, conversationId);
      }
      return null;
    }));

    if (this.conversationSockets.get(conversationId)?.size === 0) {
      this.conversationSockets.delete(conversationId);
    }
    return authorized.filter(Boolean);
  }

  async broadcast(conversationId, eventName, payload) {
    const sockets = await this.getAuthorizedSubscribers(conversationId);
    const deliveredSocketIds = new Set();
    for (const socket of sockets) {
      try {
        socket.emit(eventName, payload);
        deliveredSocketIds.add(socket.id);
      } catch (error) {
        console.error(
          `[chat-v2] Failed to emit ${eventName} to ${socket.id}:`,
          error
        );
      }
    }
    return deliveredSocketIds;
  }

  async handleSubscribe(socket, raw, acknowledgement) {
    const requestedConversationId = this.normalizeId(raw?.conversationId);
    const context = {
      operation: "subscribe",
      conversationId: requestedConversationId,
    };
    try {
      const deviceId = this.requireAuthenticatedDeviceId(socket);
      const actor = await this.chatService.resolveConversationActor(
        deviceId,
        requestedConversationId
      );
      const conversationId = this.normalizeId(actor?.conversation?.id);
      if (!conversationId) {
        throw new Error("Resolved chat conversation has no id");
      }

      this.addSubscription(socket, conversationId, deviceId);
      const payload = {
        success: true,
        conversationId,
        type: actor.conversation.type,
      };
      this.emitResponse(socket, EVENTS.SUBSCRIBED, payload, acknowledgement);
      return payload;
    } catch (error) {
      return this.emitError(socket, error, context, acknowledgement);
    }
  }

  handleUnsubscribe(socket, raw, acknowledgement) {
    const conversationId = this.normalizeId(raw?.conversationId);
    const context = { operation: "unsubscribe", conversationId };
    try {
      this.requireAuthenticatedDeviceId(socket);
      if (!conversationId) {
        throw new ChatConversationService.Error(
          400,
          "INVALID_CONVERSATION_ID",
          "Invalid conversation id"
        );
      }
      this.removeSubscription(socket, conversationId);
      const payload = { success: true, conversationId };
      this.emitResponse(socket, EVENTS.UNSUBSCRIBED, payload, acknowledgement);
      return payload;
    } catch (error) {
      return this.emitError(socket, error, context, acknowledgement);
    }
  }

  async handleSend(socket, raw, acknowledgement) {
    const conversationId = this.normalizeId(raw?.conversationId);
    const clientMessageId = this.normalizeId(raw?.clientMessageId);
    const context = {
      operation: "send",
      conversationId,
      clientMessageId,
    };

    try {
      const deviceId = this.requireAuthenticatedDeviceId(socket);
      // Pass only protocol fields. Claimed sender/member/family identities in
      // the client payload are deliberately ignored.
      const result = await this.chatService.sendMessage(
        deviceId,
        conversationId,
        {
          clientMessageId: raw?.clientMessageId,
          text: raw?.text,
          clientSentAt: raw?.clientSentAt,
        }
      );
      const canonicalConversationId = this.normalizeId(
        result?.message?.conversationId
      );
      if (!canonicalConversationId) {
        throw new Error("Stored chat message has no conversation id");
      }

      const accepted = {
        success: true,
        conversationId: canonicalConversationId,
        clientMessageId: result.message.clientMessageId,
        messageId: result.message.messageId,
        serverSequence: result.message.serverSequence,
        created: result.created === true,
        deduplicated: result.deduplicated === true,
        deliveryState: result.message.deliveryState || "ACCEPTED",
        message: result.message,
      };
      this.emitResponse(socket, EVENTS.ACCEPTED, accepted, acknowledgement);

      // An idempotent retry acknowledges the already-durable row without
      // rebroadcasting it. Clients can recover missed rows through paging.
      if (result.created === true) {
        await this.broadcast(canonicalConversationId, EVENTS.MESSAGE, {
          conversationId: canonicalConversationId,
          message: result.message,
        });
      }
      return accepted;
    } catch (error) {
      return this.emitError(socket, error, context, acknowledgement);
    }
  }

  async handleReceipt(socket, raw, acknowledgement) {
    const conversationId = this.normalizeId(raw?.conversationId);
    const context = { operation: "receipt", conversationId };

    try {
      const deviceId = this.requireAuthenticatedDeviceId(socket);
      // The member is resolved from the authenticated device on every call.
      const result = await this.chatService.advanceReceipt(
        deviceId,
        conversationId,
        {
          deliveredThroughSequence: raw?.deliveredThroughSequence,
          readThroughSequence: raw?.readThroughSequence,
        }
      );
      const canonicalConversationId = this.normalizeId(
        result?.receipt?.conversationId
      );
      if (!canonicalConversationId) {
        throw new Error("Updated chat receipt has no conversation id");
      }

      const payload = {
        success: true,
        conversationId: canonicalConversationId,
        receipt: result.receipt,
      };
      const deliveredSocketIds = await this.broadcast(
        canonicalConversationId,
        EVENTS.RECEIPT_UPDATED,
        payload
      );
      if (!deliveredSocketIds.has(socket.id)) {
        this.emitResponse(
          socket,
          EVENTS.RECEIPT_UPDATED,
          payload,
          acknowledgement
        );
      } else {
        this.acknowledge(acknowledgement, payload);
      }
      return payload;
    } catch (error) {
      return this.emitError(socket, error, context, acknowledgement);
    }
  }

  registerSocket(socket) {
    if (!socket?.id || typeof socket.on !== "function") return false;
    if (this.registeredSockets.has(socket)) return true;
    this.registeredSockets.add(socket);

    socket.on(EVENTS.SUBSCRIBE, (raw, acknowledgement) => {
      return this.handleSubscribe(socket, raw, acknowledgement);
    });
    socket.on(EVENTS.UNSUBSCRIBE, (raw, acknowledgement) => {
      return this.handleUnsubscribe(socket, raw, acknowledgement);
    });
    socket.on(EVENTS.SEND, (raw, acknowledgement) => {
      return this.handleSend(socket, raw, acknowledgement);
    });
    socket.on(EVENTS.RECEIPT, (raw, acknowledgement) => {
      return this.handleReceipt(socket, raw, acknowledgement);
    });
    socket.on("disconnect", () => {
      this.removeSocket(socket);
    });
    return true;
  }
}

ChatV2SocketService.EVENTS = EVENTS;

module.exports = ChatV2SocketService;
