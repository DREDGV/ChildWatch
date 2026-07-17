/**
 * WebSocketManager - Manages WebSocket connections for real-time audio streaming
 *
 * Architecture:
 * - ParentWatch (child device) connects and sends audio chunks
 * - ChildWatch (parent device) connects and receives audio chunks
 * - Server routes chunks from child -> parent in real time
 */

class WebSocketManager {
  constructor(io, commandManager = null, dbManager = null) {
    this.io = io;
    this.commandManager = commandManager;
    this.dbManager = dbManager;
    this.attentionSignalManager = null;
    this.verboseWsLogs = process.env.CW_VERBOSE_WS_LOGS === "1";
    this.verboseAudioLogs = process.env.CW_VERBOSE_AUDIO_LOGS === "1";

    // Map: deviceId (child) -> socket.id
    this.childSockets = new Map();

    // Neutral exact-device registry. A device may have multiple live sockets
    // during reconnect overlap; exact routing never falls back to another ID.
    // Legacy childSockets/parentSockets remain in place for verified audio paths.
    this.deviceSockets = new Map();

    // Map: deviceId (child) -> parentSocketId
    this.activeStreams = new Map();

    // Map: parentSocketId -> deviceId (child being monitored)
    this.parentSockets = new Map();
    // Map: requestId -> { parentSocketId, deviceId, createdAt }
    this.pendingPhotoRequests = new Map();
    // Map: deviceId (child) -> active photo owner/request
    this.activePhotoRequests = new Map();
    this.PHOTO_REQUEST_TTL_MS = 25 * 1000;
    this.lastMissingParentLogAt = new Map();

    console.log("[ws] WebSocketManager initialized");
  }

  setAttentionSignalManager(attentionSignalManager) {
    this.attentionSignalManager = attentionSignalManager;
  }

  normalizeDeviceId(value) {
    if (value === null || value === undefined) return "";
    return String(value).trim();
  }

  registerDeviceSocket(socket, deviceId) {
    const normalizedDeviceId = this.normalizeDeviceId(deviceId);
    if (!socket?.id || !normalizedDeviceId) return false;

    this.unregisterDeviceSocket(socket);
    let socketIds = this.deviceSockets.get(normalizedDeviceId);
    if (!socketIds) {
      socketIds = new Set();
      this.deviceSockets.set(normalizedDeviceId, socketIds);
    }
    socketIds.add(socket.id);
    socket.exactDeviceId = normalizedDeviceId;
    return true;
  }

  unregisterDeviceSocket(socket) {
    const normalizedDeviceId = this.normalizeDeviceId(socket?.exactDeviceId);
    if (!socket?.id || !normalizedDeviceId) return false;
    const socketIds = this.deviceSockets.get(normalizedDeviceId);
    if (!socketIds) {
      delete socket.exactDeviceId;
      return false;
    }
    const removed = socketIds.delete(socket.id);
    if (socketIds.size === 0) {
      this.deviceSockets.delete(normalizedDeviceId);
    }
    delete socket.exactDeviceId;
    return removed;
  }

  getConnectedSocketIdsForDevice(deviceId) {
    const normalizedDeviceId = this.normalizeDeviceId(deviceId);
    if (!normalizedDeviceId) return [];
    const socketIds = this.deviceSockets.get(normalizedDeviceId);
    if (!socketIds) return [];

    const connected = [];
    for (const socketId of Array.from(socketIds)) {
      const socket = this.io.sockets.sockets.get(socketId);
      if (!socket || !socket.connected) {
        socketIds.delete(socketId);
        continue;
      }
      connected.push(socketId);
    }
    if (socketIds.size === 0) {
      this.deviceSockets.delete(normalizedDeviceId);
    }
    return connected;
  }

  emitToExactDevice(deviceId, eventName, payload) {
    const socketIds = this.getConnectedSocketIdsForDevice(deviceId);
    let delivered = 0;
    for (const socketId of socketIds) {
      const socket = this.io.sockets.sockets.get(socketId);
      if (!socket || !socket.connected) continue;
      socket.emit(eventName, payload);
      delivered += 1;
    }
    return delivered;
  }

  looksLikeSyntheticParentDeviceId(value) {
    const normalized = this.normalizeDeviceId(value);
    if (!normalized) return false;
    if (normalized.startsWith("parent_socket_")) return true;
    return /^parent_(?:\d{6,}|[A-Za-z0-9_-]{8,})$/.test(normalized);
  }

  formatShortId(value) {
    const normalized = this.normalizeDeviceId(value);
    if (!normalized) return "";
    if (normalized.length <= 16) return normalized;
    return `${normalized.slice(0, 8)}...${normalized.slice(-4)}`;
  }

  shouldLogAudioChunk(sequence) {
    if (this.verboseAudioLogs) return true;
    const numericSequence = Number(sequence);
    return Number.isFinite(numericSequence) && numericSequence % 25 === 0;
  }

  shouldLogMissingParent(deviceId) {
    if (this.verboseAudioLogs) return true;
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized) return true;
    const now = Date.now();
    const lastLoggedAt = this.lastMissingParentLogAt.get(normalized) || 0;
    if (now - lastLoggedAt < 30_000) {
      return false;
    }
    this.lastMissingParentLogAt.set(normalized, now);
    return true;
  }

  getParentDisplayLabel(parentDeviceId, fallbackDisplayName = null) {
    const fallback = this.normalizeDeviceId(fallbackDisplayName);
    if (fallback) return fallback;
    const normalizedParentId = this.normalizeDeviceId(parentDeviceId);
    if (!normalizedParentId) return "Родитель";
    return this.formatShortId(normalizedParentId);
  }

  getChatDisplayLabel(senderRole, senderDeviceId, fallbackDisplayName = null) {
    const explicit = this.normalizeDeviceId(fallbackDisplayName);
    if (explicit) return explicit;

    if (senderRole === "parent") {
      return this.getParentDisplayLabel(senderDeviceId, fallbackDisplayName);
    }

    const normalizedSenderId = this.normalizeDeviceId(senderDeviceId);
    return normalizedSenderId ? this.formatShortId(normalizedSenderId) : "Ребенок";
  }

  shouldDeliverChatMessageToTarget(message, targetRole, targetParentDeviceId = null) {
    const normalizedTargetRole = this.normalizeDeviceId(targetRole).toLowerCase();
    const normalizedTargetParentId = this.normalizeDeviceId(targetParentDeviceId);
    const senderRole = this.normalizeDeviceId(message?.sender).toLowerCase();
    const senderDeviceId = this.normalizeDeviceId(message?.sender_device_id);

    if (normalizedTargetRole === "parent") {
      return (
        senderRole === "child" ||
        (senderRole === "parent" &&
          (!normalizedTargetParentId || senderDeviceId !== normalizedTargetParentId))
      );
    }

    if (normalizedTargetRole === "child") {
      return senderRole === "parent";
    }

    return true;
  }

  getConnectedParentSocketIdsForParent(parentDeviceId, childDeviceId = null) {
    const normalizedParentId = this.normalizeDeviceId(parentDeviceId);
    const normalizedChildId = this.normalizeDeviceId(childDeviceId);
    if (!normalizedParentId) return [];

    const result = [];
    for (const [parentSocketId, mappedChildId] of this.parentSockets.entries()) {
      const parentSocket = this.io.sockets.sockets.get(parentSocketId);
      if (!parentSocket || !parentSocket.connected) {
        this.parentSockets.delete(parentSocketId);
        this.removePhotoRequestsForParent(parentSocketId);
        continue;
      }
      if (this.normalizeDeviceId(parentSocket.parentDeviceId) !== normalizedParentId) continue;
      if (
        normalizedChildId &&
        this.normalizeDeviceId(mappedChildId) !== normalizedChildId
      ) {
        continue;
      }
      result.push(parentSocketId);
    }
    return result;
  }

  isPhotoRequestActive(entry, now = Date.now()) {
    if (!entry) return false;
    const createdAt = Number(entry.createdAt || 0);
    if (!Number.isFinite(createdAt) || createdAt <= 0) return false;
    return (now - createdAt) < this.PHOTO_REQUEST_TTL_MS;
  }

  notifyStreamTakeoverRequested(childDeviceId, ownerParentId, requesterParentId, session) {
    const ownerSocketIds = this.getConnectedParentSocketIdsForParent(
      ownerParentId,
      childDeviceId
    );
    if (!ownerSocketIds.length) return;

    const payload = {
      deviceId: childDeviceId,
      ownerParentId: this.normalizeDeviceId(ownerParentId),
      requesterParentId: this.normalizeDeviceId(requesterParentId),
      startedAt: session?.startTime || Date.now(),
      durationMs: session?.durationMs || 0,
      timestamp: Date.now(),
    };

    for (const socketId of ownerSocketIds) {
      const parentSocket = this.io.sockets.sockets.get(socketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("stream_takeover_requested", payload);
      }
    }
  }

  notifyStreamForceReleased(
    childDeviceId,
    releasedSession,
    releasedByType = "child",
    releasedByDisplayName = null
  ) {
    const parentSocketIds = this.getConnectedParentSocketIdsForDevice(childDeviceId);
    if (!parentSocketIds.length) return;

    const payload = {
      deviceId: childDeviceId,
      ownerParentId: this.normalizeDeviceId(releasedSession?.ownerParentId),
      ownerDisplayName: releasedSession?.ownerDisplayName || "",
      releasedByType: this.normalizeDeviceId(releasedByType) || "child",
      releasedByDisplayName: this.normalizeDeviceId(releasedByDisplayName),
      startedAt: releasedSession?.startTime || 0,
      durationMs: releasedSession?.durationMs || 0,
      timestamp: Date.now(),
    };

    for (const socketId of parentSocketIds) {
      const parentSocket = this.io.sockets.sockets.get(socketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("stream_force_released", payload);
      }
    }
  }

  getSingleConnectedChildDeviceId() {
    if (this.childSockets.size !== 1) return null;
    return Array.from(this.childSockets.keys())[0] || null;
  }

  getSingleConnectedParentSocketId() {
    if (this.parentSockets.size !== 1) return null;
    return Array.from(this.parentSockets.keys())[0] || null;
  }

  isChildConnectedById(deviceId) {
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized) return false;
    const socketId = this.childSockets.get(normalized);
    if (!socketId) return false;
    const socket = this.io.sockets.sockets.get(socketId);
    if (!socket || !socket.connected) {
      this.childSockets.delete(normalized);
      return false;
    }
    return true;
  }

  resolveConnectedChildDeviceId(...candidates) {
    for (const rawCandidate of candidates) {
      const candidate = this.normalizeDeviceId(rawCandidate);
      if (!candidate) continue;
      if (this.isChildConnectedById(candidate)) {
        return candidate;
      }
    }

    const onlyChild = this.getSingleConnectedChildDeviceId();
    if (onlyChild && this.isChildConnectedById(onlyChild)) {
      return onlyChild;
    }

    return "";
  }

  getAnyConnectedParentSocketId(excludedSocketId = null) {
    for (const [parentSocketId] of this.parentSockets.entries()) {
      if (excludedSocketId && parentSocketId === excludedSocketId) continue;
      const socket = this.io.sockets.sockets.get(parentSocketId);
      if (socket && socket.connected) {
        return parentSocketId;
      }
    }
    return null;
  }

  syncPendingPhotoRequestsForChild(deviceId, socketId) {
    const normalizedDeviceId = this.normalizeDeviceId(deviceId);
    const normalizedSocketId = this.normalizeDeviceId(socketId);
    if (!normalizedDeviceId || !normalizedSocketId) return;

    for (const [, pending] of this.pendingPhotoRequests.entries()) {
      if (this.normalizeDeviceId(pending.deviceId) !== normalizedDeviceId) continue;
      pending.childSocketId = normalizedSocketId;
    }
  }

  getConnectedParentSocketIdsForDevice(deviceId, excludedSocketId = null) {
    const normalizedDeviceId = this.normalizeDeviceId(deviceId);
    if (!normalizedDeviceId) return [];

    const result = [];
    for (const [parentSocketId, mappedDeviceId] of this.parentSockets.entries()) {
      if (excludedSocketId && parentSocketId === excludedSocketId) continue;
      if (this.normalizeDeviceId(mappedDeviceId) !== normalizedDeviceId) continue;

      const parentSocket = this.io.sockets.sockets.get(parentSocketId);
      if (parentSocket && parentSocket.connected) {
        result.push(parentSocketId);
      } else {
        // Cleanup stale map entries to avoid routing to dead sockets.
        this.parentSockets.delete(parentSocketId);
        this.removePhotoRequestsForParent(parentSocketId);
      }
    }

    return result;
  }

  /**
   * Initialize WebSocket event handlers
   */
  initialize() {
    // Return missed chat messages on explicit client sync requests.
    this.io.on("connection", (socket) => {
      socket.on("get_missed_messages", async (data) => {
        try {
          const { deviceId } = data || {};
          const rawSinceTimestamp = data?.sinceTimestamp;
          const parsedSinceTimestamp = Number.parseInt(rawSinceTimestamp, 10);
          const sinceTimestamp = Number.isFinite(parsedSinceTimestamp)
            ? Math.max(0, parsedSinceTimestamp)
            : 0;
          if (!deviceId) {
            socket.emit("missed_messages", {
              success: false,
              error: "Missing deviceId",
            });
            return;
          }
          const messages =
            sinceTimestamp > 0 && this.dbManager.getChatMessagesSince
              ? await this.dbManager.getChatMessagesSince(
                  deviceId,
                  sinceTimestamp,
                  100
                )
              : await this.dbManager.getChatMessages(deviceId, 100);
          // Recovery mode: without a sync cursor, return recent history to heal client gaps.
          const targetRole =
            socket.deviceType === "parent"
              ? "parent"
              : socket.deviceType === "child"
                ? "child"
                : "";
          const missed = (messages || []).filter((message) =>
            this.shouldDeliverChatMessageToTarget(
              message,
              targetRole,
              socket.parentDeviceId || null
            )
          );
          console.log(
            `Chat sync for ${deviceId}: since=${sinceTimestamp}, returned=${missed.length}`
          );
          socket.emit("missed_messages", {
            success: true,
            messages: missed.map((msg) => ({
              id: String(
                msg.client_id || msg.client_message_id || msg.id || ""
              ),
              clientMessageId: String(
                msg.client_id || msg.client_message_id || msg.id || ""
              ),
              sender: msg.sender,
              senderRole: msg.sender,
              senderDeviceId: msg.sender_device_id || "",
              senderDisplayName: this.getChatDisplayLabel(
                msg.sender === "child" ? "child" : "parent",
                msg.sender_device_id,
                msg.sender_display_name
              ),
              message: msg.message,
              timestamp: msg.timestamp,
              isRead: msg.is_read === 1,
              createdAt: msg.created_at,
            })),
          });
        } catch (e) {
          socket.emit("missed_messages", { success: false, error: e.message });
        }
      });
    });
    this.io.on("connection", (socket) => {
      console.log(`[ws] Client connected: ${socket.id}`);

      // Debug: log any incoming WS events to verify routing
      if (this.verboseWsLogs) {
        try {
          socket.onAny((event, ...args) => {
            const type = socket.deviceType || "unknown";
            const did = socket.deviceId || "n/a";
            console.log(
              `[ws] event '${event}' from ${type} ${socket.id} (deviceId=${did})`
            );
          });
        } catch (e) {
          console.warn("[ws] Failed to attach onAny logger:", e?.message || e);
        }
      }

      // Handle child device (ParentWatch) connection
      socket.on("register_child", (data) => {
        this.handleChildRegistration(socket, data);
      });

      // Handle parent device (ChildWatch) connection
      socket.on("register_parent", (data) => {
        this.handleParentRegistration(socket, data);
      });

      // Handle audio chunk from child device
      // Receives: metadata (JSON), binaryData (Buffer)
      socket.on("audio_chunk", (metadata, binaryData) => {
        if (this.shouldLogAudioChunk(metadata?.sequence)) {
          console.log(
            `[ws] audio_chunk received from ${socket.id}, metadata:`,
            metadata,
            `dataSize: ${binaryData ? binaryData.length : 0}`
          );
        }
        this.handleAudioChunk(socket, metadata, binaryData);
      });

      socket.on("audio_capture_error", (data) => {
        console.warn(
          `Audio capture diagnostic from ${socket.deviceType || "unknown"} ${socket.id} (deviceId=${socket.deviceId || "n/a"}):`,
          data
        );

        const deviceId = this.normalizeDeviceId(socket.deviceId);
        if (socket.deviceType !== "child" || !deviceId) return;

        const payload = {
          deviceId,
          reason: typeof data?.reason === "string" ? data.reason : "capture_failed",
          message: typeof data?.message === "string" ? data.message : "",
          timestamp: Number(data?.timestamp) || Date.now(),
        };
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        for (const parentSocketId of parentSocketIds) {
          const parentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (parentSocket && parentSocket.connected) {
            parentSocket.emit("audio_capture_error", payload);
          }
        }
      });

      // Handle heartbeat/ping
      socket.on("ping", () => {
        if (
          socket.deviceType === "parent" &&
          this.commandManager &&
          socket.deviceId &&
          socket.parentDeviceId
        ) {
          this.commandManager.touchStreamingOwner(
            socket.deviceId,
            socket.parentDeviceId
          );
        }
        socket.emit("pong", { timestamp: Date.now() });
      });

      // Handle chat message
      socket.on("chat_message", (data) => {
        this.handleChatMessage(socket, data);
      });
      socket.on("chat_message_status", (data) => {
        this.handleChatMessageStatus(socket, data);
      });

      // Handle parent location updates
      socket.on("parent_location", (data) => {
        this.handleParentLocation(socket, data);
      });

      // Handle direct commands from parent app (e.g. take_photo)
      socket.on("command", (data) => {
        this.handleCommand(socket, data);
      });

      socket.on("attention_signal_request", (data) => {
        this.attentionSignalManager?.handleRequest(socket, data).catch((error) => {
          console.error("[attention] request handler failed:", error);
        });
      });
      socket.on("attention_signal_status", (data) => {
        this.attentionSignalManager?.handleStatus(socket, data).catch((error) => {
          console.error("[attention] status handler failed:", error);
        });
      });
      socket.on("attention_signal_stop_request", (data) => {
        this.attentionSignalManager
          ?.handleStopRequest(socket, data)
          .catch((error) => {
            console.error("[attention] stop handler failed:", error);
          });
      });

      socket.on("force_release_stream", (data) => {
        this.handleForceReleaseStream(socket, data);
      });

        // Handle photo request from parent
        socket.on("request_photo", (data) => {
          this.handlePhotoRequest(socket, data);
        });

        // Handle photo response from child
        socket.on("photo", (data) => {
          this.handlePhotoResponse(socket, data);
        });
        // Handle photo error from child
        socket.on("photo_error", (data) => {
          this.handlePhotoError(socket, data);
        });

      // Handle disconnection
      socket.on("disconnect", () => {
        this.handleDisconnect(socket);
      });

      // Handle errors
      socket.on("error", (error) => {
        console.error(`[ws] Socket error (${socket.id}):`, error);
      });
    });

    console.log("[ws] WebSocket event handlers registered");
  }

  /**
   * Handle command request coming from a parent socket
   */
  handleCommand(socket, data) {
    console.log(
      `[ws] [handleCommand] Received command from socket ${socket.id}, deviceType=${socket.deviceType}:`,
      JSON.stringify(data)
    );

    try {
      if (!data || typeof data !== "object") {
        console.warn("[ws] Invalid command payload", data);
        return;
      }

      const rawType = data.type;
      const payload = data.data || {};
      const explicitDeviceId = this.normalizeDeviceId(data.deviceId);
      const requesterParentId = this.normalizeDeviceId(
        payload.parentId || data.parentId || socket.parentDeviceId
      );

      console.log(
        `[ws] Command details: type=${rawType}, deviceId=${explicitDeviceId}, socketType=${socket.deviceType}`
      );

      if (!rawType) {
        console.warn("[ws] Command missing type", data);
        return;
      }

      const mappedDeviceId = this.parentSockets.get(socket.id);
      // Prefer connected targets. If explicit ID is stale/wrong but only one child is online,
      // fallback keeps legacy single-pair setups working after contact-migration mistakes.
      let targetDeviceId = this.resolveConnectedChildDeviceId(
        explicitDeviceId,
        mappedDeviceId
      );

      if (!targetDeviceId) {
        targetDeviceId = explicitDeviceId || this.normalizeDeviceId(mappedDeviceId);
      }

      if (!targetDeviceId) {
        console.warn(
          `[ws] Unable to resolve target device for command ${rawType}`,
          data
        );
        return;
      }

      if (explicitDeviceId && explicitDeviceId !== targetDeviceId) {
        console.warn(
          `Command ${rawType} remapped from ${explicitDeviceId} to connected child ${targetDeviceId}`
        );
      }

      if (socket.deviceType === "parent" && this.commandManager) {
        if (rawType === "start_audio_stream") {
          const startResult = this.commandManager.requestStreamingStart(
            targetDeviceId,
            requesterParentId,
            30,
            {
              sampleRate: payload.sampleRate,
              ownerDisplayName: socket.parentDisplayName || requesterParentId,
            }
          );
          if (!startResult.ok) {
            socket.emit("stream_busy", {
              code: startResult.code,
              deviceId: targetDeviceId,
              ownerParentId: startResult.session?.ownerParentId || "",
              ownerDisplayName: startResult.session?.ownerDisplayName || "",
              startedAt: startResult.session?.startTime || 0,
              durationMs: startResult.session?.durationMs || 0,
              timestamp: Date.now(),
            });
            if (payload.requestTakeover) {
              this.notifyStreamTakeoverRequested(
                targetDeviceId,
                startResult.session?.ownerParentId,
                requesterParentId,
                startResult.session
              );
            }
            return;
          }
        } else if (rawType === "stop_audio_stream") {
          const stopResult = this.commandManager.requestStreamingStop(
            targetDeviceId,
            requesterParentId
          );
          if (!stopResult.ok) {
            socket.emit("stream_busy", {
              code: stopResult.code,
              deviceId: targetDeviceId,
              ownerParentId: stopResult.session?.ownerParentId || "",
              ownerDisplayName: stopResult.session?.ownerDisplayName || "",
              startedAt: stopResult.session?.startTime || 0,
              durationMs: stopResult.session?.durationMs || 0,
              timestamp: Date.now(),
            });
            return;
          }
        } else if (rawType === "start_recording") {
          const recordStartResult = this.commandManager.requestRecordingStart(
            targetDeviceId,
            requesterParentId
          );
          if (!recordStartResult.ok) {
            socket.emit("stream_busy", {
              code: recordStartResult.code,
              deviceId: targetDeviceId,
              ownerParentId: recordStartResult.session?.ownerParentId || "",
              ownerDisplayName: recordStartResult.session?.ownerDisplayName || "",
              startedAt: recordStartResult.session?.startTime || 0,
              durationMs: recordStartResult.session?.durationMs || 0,
              timestamp: Date.now(),
            });
            return;
          }
        } else if (rawType === "stop_recording") {
          const recordStopResult = this.commandManager.requestRecordingStop(
            targetDeviceId,
            requesterParentId
          );
          if (!recordStopResult.ok) {
            socket.emit("stream_busy", {
              code: recordStopResult.code,
              deviceId: targetDeviceId,
              ownerParentId: recordStopResult.session?.ownerParentId || "",
              ownerDisplayName: recordStopResult.session?.ownerDisplayName || "",
              startedAt: recordStopResult.session?.startTime || 0,
              durationMs: recordStopResult.session?.durationMs || 0,
              timestamp: Date.now(),
            });
            return;
          }
        }
      }

      const commandEnvelope = {
        type: rawType,
        data: payload,
        timestamp: Date.now(),
        origin: socket.deviceType || "unknown",
      };

      const sent = this.sendCommandToChild(targetDeviceId, commandEnvelope);

      if (!sent && this.commandManager) {
        this.commandManager.addCommand(targetDeviceId, rawType, payload);
        console.log(
          `[ws] Child ${targetDeviceId} is offline - queued command ${rawType}`
        );
      }
    } catch (error) {
      console.error("[ws] Error handling command from parent:", error);
    }
  }

  handleForceReleaseStream(socket, data) {
    try {
      if (socket.deviceType !== "child") {
        console.warn("[ws] force_release_stream ignored from non-child socket");
        socket.emit("stream_force_release_result", {
          success: false,
          code: "FORBIDDEN",
          error: "Only child device may force release the listening line",
          timestamp: Date.now(),
        });
        return;
      }

      const socketDeviceId = this.normalizeDeviceId(socket.deviceId);
      const explicitDeviceId = this.normalizeDeviceId(data?.deviceId);
      const targetDeviceId = this.resolveConnectedChildDeviceId(
        explicitDeviceId,
        socketDeviceId
      ) || socketDeviceId;

      if (!targetDeviceId) {
        socket.emit("stream_force_release_result", {
          success: false,
          code: "MISSING_DEVICE_ID",
          error: "Child device id is missing",
          timestamp: Date.now(),
        });
        return;
      }

      if (explicitDeviceId && explicitDeviceId !== targetDeviceId) {
        console.warn(
          `[ws] force_release_stream remapped from ${explicitDeviceId} to ${targetDeviceId}`
        );
      }

      const releasedByDisplayName =
        this.normalizeDeviceId(data?.releasedByDisplayName) ||
        this.normalizeDeviceId(socket.childDisplayName) ||
        this.formatShortId(targetDeviceId);

      const result = this.commandManager?.forceReleaseStreaming(targetDeviceId, {
        reason: "FORCED_BY_CHILD",
        releasedByType: "child",
        releasedByDisplayName,
      });

      if (!result?.ok) {
        socket.emit("stream_force_release_result", {
          success: false,
          code: result?.code || "NO_ACTIVE_SESSION",
          error: "No active listening session",
          deviceId: targetDeviceId,
          timestamp: Date.now(),
        });
        return;
      }

      const stopSent = this.sendCommandToChild(targetDeviceId, {
        type: "stop_audio_stream",
        data: {
          forced: true,
          reason: "FORCED_BY_CHILD",
        },
        timestamp: Date.now(),
      });
      if (!stopSent) {
        console.warn(
          `[ws] force_release_stream stop command queued for ${targetDeviceId}`
        );
      }

      this.notifyStreamForceReleased(
        targetDeviceId,
        result.session,
        result.releasedByType,
        result.releasedByDisplayName
      );

      socket.emit("stream_force_release_result", {
        success: true,
        deviceId: targetDeviceId,
        ownerParentId: result.session?.ownerParentId || "",
        ownerDisplayName: result.session?.ownerDisplayName || "",
        releasedByType: result.releasedByType || "child",
        releasedByDisplayName: result.releasedByDisplayName || releasedByDisplayName,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error("[ws] Error handling force_release_stream:", error);
      socket.emit("stream_force_release_result", {
        success: false,
        code: "FORCE_RELEASE_ERROR",
        error: error?.message || "Failed to force release stream",
        timestamp: Date.now(),
      });
    }
  }

  /**
   * Handle parent location updates from parent device
   */
  handleParentLocation(socket, data) {
    try {
      if (!data || typeof data !== "object") {
        console.warn("Invalid parent_location payload", data);
        return;
      }
      const {
        parentId,
        latitude,
        longitude,
        accuracy,
        timestamp,
        speed,
        bearing,
        targetDevice,
      } = data;

      if (!parentId || typeof latitude !== "number" || typeof longitude !== "number") {
        console.warn("parent_location missing required fields", data);
        return;
      }

      const mappedDeviceId = this.parentSockets.get(socket.id);
      let deviceId = this.resolveConnectedChildDeviceId(targetDevice, mappedDeviceId);
      if (!deviceId) {
        console.warn("parent_location: target device not resolved");
        return;
      }

      const childSocketId = this.childSockets.get(deviceId);
      if (!childSocketId) {
        console.warn(`parent_location: child ${deviceId} not connected`);
        return;
      }

      const childSocket = this.io.sockets.sockets.get(childSocketId);
      if (!childSocket || !childSocket.connected) {
        console.warn(`parent_location: socket ${childSocketId} not available`);
        return;
      }

      childSocket.emit("parent_location", {
        parentId,
        latitude,
        longitude,
        accuracy,
        timestamp: timestamp || Date.now(),
        speed,
        bearing,
      });
    } catch (error) {
      console.error("Error handling parent_location:", error);
    }
  }

  /**
   * Handle photo request from parent device
   */
  handlePhotoRequest(socket, data) {
    try {
      const { targetDevice, requestId, camera } = data || {};
      const normalizedTargetDevice = this.normalizeDeviceId(targetDevice);
      const requesterParentId = this.normalizeDeviceId(
        data?.parentId || socket.parentDeviceId
      );
      const requesterDisplayName = this.getParentDisplayLabel(
        requesterParentId,
        socket.parentDisplayName
      );
      const mappedDeviceId = this.parentSockets.get(socket.id);
      let resolvedDeviceId = this.resolveConnectedChildDeviceId(
        normalizedTargetDevice,
        mappedDeviceId
      );
      if (!resolvedDeviceId) {
        resolvedDeviceId =
          normalizedTargetDevice || this.normalizeDeviceId(mappedDeviceId);
      }
      const reqId = requestId || `${Date.now()}_${Math.random().toString(16).slice(2)}`;
      const cameraFacing =
        typeof camera === "string" && camera.trim()
          ? camera.trim().toLowerCase()
          : "back";
      console.log(
        `[photo] request from ${socket.id} -> device=${resolvedDeviceId} requestId=${reqId} camera=${cameraFacing}`
      );

      if (!resolvedDeviceId) {
        socket.emit("photo_error", {
          requestId: reqId,
          error: "Missing target device",
        });
        return;
      }

      const fallbackResolved =
        normalizedTargetDevice &&
        resolvedDeviceId &&
        resolvedDeviceId !== normalizedTargetDevice;
      if (fallbackResolved) {
        console.warn(
          `Photo request target remapped from ${normalizedTargetDevice} to connected child ${resolvedDeviceId}`
        );
      }

      const activePhotoRequest = this.activePhotoRequests.get(resolvedDeviceId);
      if (
        activePhotoRequest &&
        this.isPhotoRequestActive(activePhotoRequest) &&
        this.normalizeDeviceId(activePhotoRequest.parentDeviceId) !== requesterParentId
      ) {
        socket.emit("photo_busy", {
          requestId: reqId,
          deviceId: resolvedDeviceId,
          ownerParentId: activePhotoRequest.parentDeviceId || "",
          ownerDisplayName: activePhotoRequest.ownerDisplayName || "",
          startedAt: activePhotoRequest.createdAt || Date.now(),
          camera: activePhotoRequest.camera || "back",
          timestamp: Date.now(),
        });
        return;
      }

      const childSocketId = this.childSockets.get(resolvedDeviceId);
      if (!childSocketId) {
        socket.emit("photo_error", {
          requestId: reqId,
          error: "Child device not connected",
        });
        this.pendingPhotoRequests.delete(reqId);
        return;
      }

      const childSocket = this.io.sockets.sockets.get(childSocketId);
      if (!childSocket || !childSocket.connected) {
        socket.emit("photo_error", {
          requestId: reqId,
          error: "Child socket not available",
        });
        this.pendingPhotoRequests.delete(reqId);
        return;
      }

      this.pendingPhotoRequests.set(reqId, {
        parentSocketId: socket.id,
        childSocketId,
        deviceId: resolvedDeviceId,
        parentDeviceId: requesterParentId,
        ownerDisplayName: requesterDisplayName,
        createdAt: Date.now(),
      });
      this.activePhotoRequests.set(resolvedDeviceId, {
        requestId: reqId,
        parentSocketId: socket.id,
        parentDeviceId: requesterParentId,
        ownerDisplayName: requesterDisplayName,
        deviceId: resolvedDeviceId,
        camera: cameraFacing,
        createdAt: Date.now(),
      });

      childSocket.emit("request_photo", {
        requestId: reqId,
        targetDevice: resolvedDeviceId,
        camera: cameraFacing,
        timestamp: Date.now(),
      });
      socket.emit("photo_request_queued", {
        requestId: reqId,
        deviceId: resolvedDeviceId,
        camera: cameraFacing,
        timestamp: Date.now(),
      });
      console.log(`[photo] request routed to child ${resolvedDeviceId}`);
    } catch (error) {
      console.error("Error handling photo request:", error);
      try {
        socket.emit("photo_error", {
          requestId: data?.requestId,
          error: error.message || "Unknown error",
        });
      } catch (_) {}
    }
  }

  /**
   * Handle photo response from child device
   */
  handlePhotoResponse(socket, data) {
    try {
      const { requestId, photo, timestamp } = data || {};
      if (!requestId) {
        console.warn("Photo response missing requestId");
        return;
      }
      console.log(
        `[photo] response received: requestId=${requestId} size=${photo ? photo.length : 0}`
      );

      const pending = this.pendingPhotoRequests.get(requestId);
      if (!pending) {
        console.warn(`No pending photo request for id=${requestId}`);
        return;
      }

      const responseDeviceId = this.normalizeDeviceId(socket.deviceId);
      const pendingDeviceId = this.normalizeDeviceId(pending.deviceId);
      const sameChildDevice =
        responseDeviceId &&
        pendingDeviceId &&
        responseDeviceId === pendingDeviceId;

      if (pending.childSocketId && pending.childSocketId !== socket.id && !sameChildDevice) {
        console.warn(
          `Ignoring photo response from unexpected socket ${socket.id}; expected ${pending.childSocketId}`
        );
        return;
      }

      if (sameChildDevice && pending.childSocketId !== socket.id) {
        pending.childSocketId = socket.id;
      }

      if (typeof photo !== "string" || !photo.trim()) {
        const parentSocket = this.io.sockets.sockets.get(pending.parentSocketId);
        if (parentSocket && parentSocket.connected) {
          parentSocket.emit("photo_error", {
            requestId,
            error: "Received empty photo payload",
          });
        }
        this.activePhotoRequests.delete(pending.deviceId);
        this.pendingPhotoRequests.delete(requestId);
        return;
      }

      const parentSocket = this.io.sockets.sockets.get(pending.parentSocketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("photo", {
          requestId,
          photo,
          timestamp: timestamp || Date.now(),
        });
        console.log(`[photo] delivered to parent socket ${pending.parentSocketId}`);
      }
      this.activePhotoRequests.delete(pending.deviceId);
      this.pendingPhotoRequests.delete(requestId);
    } catch (error) {
      console.error("Error handling photo response:", error);
    }
  }

  /**
   * Handle photo error from child device
   */
  handlePhotoError(socket, data) {
    try {
      const { requestId, error } = data || {};
      if (!requestId) {
        console.warn("Photo error missing requestId");
        return;
      }
      console.warn(`[photo] error: requestId=${requestId} error=${error || "unknown"}`);
      const pending = this.pendingPhotoRequests.get(requestId);
      if (!pending) {
        return;
      }

      const responseDeviceId = this.normalizeDeviceId(socket.deviceId);
      const pendingDeviceId = this.normalizeDeviceId(pending.deviceId);
      const sameChildDevice =
        responseDeviceId &&
        pendingDeviceId &&
        responseDeviceId === pendingDeviceId;

      if (pending.childSocketId && pending.childSocketId !== socket.id && !sameChildDevice) {
        console.warn(
          `Ignoring photo error from unexpected socket ${socket.id}; expected ${pending.childSocketId}`
        );
        return;
      }
      if (sameChildDevice && pending.childSocketId !== socket.id) {
        pending.childSocketId = socket.id;
      }
      const parentSocket = this.io.sockets.sockets.get(pending.parentSocketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("photo_error", {
          requestId,
          error: error || "Unknown error",
        });
      }
      this.activePhotoRequests.delete(pending.deviceId);
      this.pendingPhotoRequests.delete(requestId);
    } catch (err) {
      console.error("Error handling photo error:", err);
    }
  }

  removePhotoRequestsForParent(parentSocketId) {
    if (!parentSocketId) return;
    for (const [requestId, entry] of this.pendingPhotoRequests.entries()) {
      if (entry.parentSocketId === parentSocketId) {
        this.activePhotoRequests.delete(entry.deviceId);
        this.pendingPhotoRequests.delete(requestId);
      }
    }
  }

  /**
   * Register child device (ParentWatch)
   */
  async handleChildRegistration(socket, data) {
    const requestedDeviceId = this.normalizeDeviceId(data?.deviceId);
    const authenticatedDeviceId = this.normalizeDeviceId(
      socket?.authenticatedDeviceId
    );

    if (
      authenticatedDeviceId &&
      requestedDeviceId &&
      authenticatedDeviceId !== requestedDeviceId
    ) {
      console.warn(
        `[ws] Child registration identity mismatch: token=${authenticatedDeviceId}, requested=${requestedDeviceId}`
      );
      socket.emit("registration_error", {
        code: "WS_DEVICE_ID_MISMATCH",
        message: "Authenticated device does not match requested child identity",
      });
      return;
    }

    const deviceId = authenticatedDeviceId || requestedDeviceId;
    const childDisplayName = this.getChatDisplayLabel(
      "child",
      deviceId,
      data?.childDisplayName
    );

    if (!deviceId) {
      console.error("[ws] Child registration failed: missing deviceId");
      socket.emit("error", { message: "Missing deviceId" });
      return;
    }

    // Replace stale mapping for this device with latest socket.
    const previousSocketId = this.childSockets.get(deviceId);
    if (previousSocketId && previousSocketId !== socket.id) {
      this.childSockets.delete(deviceId);
      console.warn(
        `Replacing stale child mapping for ${deviceId}: ${previousSocketId} -> ${socket.id}`
      );
    }

    // Store child socket mapping
    this.childSockets.set(deviceId, socket.id);
    socket.deviceId = deviceId;
    socket.deviceType = "child";
    socket.childDisplayName = childDisplayName;
    this.registerDeviceSocket(socket, deviceId);
    this.syncPendingPhotoRequestsForChild(deviceId, socket.id);

    console.log(
      `[ws] Child device registered: ${deviceId} (socket: ${socket.id})`
    );
    console.log(`[ws] Total child devices connected: ${this.childSockets.size}`);

    socket.emit("registered", {
      success: true,
      deviceId: deviceId,
      timestamp: Date.now(),
    });

    // Notify child that server is ready to receive audio
    console.log(`[ws] Child ${deviceId} is now ready to send audio chunks`);
    const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);

    if (parentSocketIds.length > 0) {
      for (const parentSocketId of parentSocketIds) {
        const parentSocket = this.io.sockets.sockets.get(parentSocketId);
        if (parentSocket) {
          parentSocket.emit("child_connected", {
            deviceId,
            timestamp: Date.now(),
          });
        }
      }
      socket.emit("parent_connected", { deviceId, timestamp: Date.now() });
    } else {
      socket.emit("parent_disconnected", { deviceId, timestamp: Date.now() });
    }

    if (this.commandManager && this.commandManager.isStreaming(deviceId)) {
      try {
        const sessionInfo = this.commandManager.getSessionInfo(deviceId);
        const parentId = sessionInfo?.parentId || "parent";
        const sampleRate = sessionInfo?.sampleRate;
        const commandType =
          (this.commandManager.COMMANDS &&
            this.commandManager.COMMANDS.START_STREAM) ||
          "start_audio_stream";
        const commandPayload = {
          type: commandType,
          data: { parentId, replay: true, sampleRate },
          timestamp: Date.now(),
        };

        const replaySent = this.sendCommandToChild(deviceId, commandPayload);
        console.log(
          `Active stream detected for ${deviceId} - replay command sent: ${replaySent}`
        );
      } catch (error) {
        console.error(`Error replaying start command for ${deviceId}:`, error);
      }
    }

    await this.deliverPendingMessages(deviceId, "child", socket);
  }

  /**
   * Register parent device (ChildWatch)
   */
  async handleParentRegistration(socket, data) {
    const requestedDeviceId = this.normalizeDeviceId(data?.deviceId);
    const explicitParentDeviceId = this.normalizeDeviceId(data?.parentId);
    const authenticatedParentDeviceId = this.normalizeDeviceId(
      socket?.authenticatedDeviceId
    );

    if (
      authenticatedParentDeviceId &&
      explicitParentDeviceId &&
      authenticatedParentDeviceId !== explicitParentDeviceId
    ) {
      console.warn(
        `[ws] Parent registration identity mismatch: token=${authenticatedParentDeviceId}, requested=${explicitParentDeviceId}`
      );
      socket.emit("registration_error", {
        code: "WS_DEVICE_ID_MISMATCH",
        message: "Authenticated device does not match requested parent identity",
      });
      return;
    }

    const parentDeviceId =
      authenticatedParentDeviceId ||
      explicitParentDeviceId ||
      `parent_socket_${socket.id}`;
    const previousRegistrationKey = socket.parentRegistrationKey || "";
    const parentDisplayName = this.getParentDisplayLabel(
      parentDeviceId,
      data?.parentDisplayName
    );

    if (!requestedDeviceId) {
      console.error("[ws] Parent registration failed: missing deviceId");
      socket.emit("error", { message: "Missing deviceId" });
      return;
    }

    let deviceId = requestedDeviceId;
    if (!this.isChildConnectedById(deviceId)) {
      const onlyChild = this.resolveConnectedChildDeviceId();
      if (onlyChild) {
        console.warn(
          `Parent requested ${requestedDeviceId}, but only child ${onlyChild} is connected. Using fallback mapping.`
        );
        deviceId = onlyChild;
      }
    }

    // Store parent socket mapping
    this.parentSockets.set(socket.id, deviceId);
    socket.deviceId = deviceId;
    socket.deviceType = "parent";
    socket.parentDeviceId = parentDeviceId;
    socket.parentDisplayName = parentDisplayName;
    socket.parentRegistrationKey = `${deviceId}|${parentDeviceId}`;
    const exactParentDeviceId =
      authenticatedParentDeviceId ||
      (!this.looksLikeSyntheticParentDeviceId(explicitParentDeviceId)
        ? explicitParentDeviceId
        : "");
    if (exactParentDeviceId) {
      this.registerDeviceSocket(socket, exactParentDeviceId);
    }
    const isRepeatRegistration =
      previousRegistrationKey === socket.parentRegistrationKey;

    let shouldPersistParentLink = false;
    if (this.dbManager?.getDevice && parentDeviceId) {
      try {
        const knownParentDevice = await this.dbManager.getDevice(parentDeviceId);
        shouldPersistParentLink =
          Boolean(knownParentDevice) ||
          !this.looksLikeSyntheticParentDeviceId(parentDeviceId);
      } catch (error) {
        console.warn(
          `Unable to verify parent device record for ${parentDeviceId}:`,
          error
        );
      }
    }

    if (shouldPersistParentLink && this.dbManager?.upsertDeviceLink) {
      try {
        await this.dbManager.upsertDeviceLink({
          parentDeviceId,
          childDeviceId: deviceId,
          parentDisplayName,
          createdBy: "ws_registration",
          isActive: true,
        });
      } catch (error) {
        console.warn(
          `Unable to persist parent-child link for ${parentDeviceId} -> ${deviceId}:`,
          error
        );
      }
    } else if (this.looksLikeSyntheticParentDeviceId(parentDeviceId)) {
      console.warn(
        `[ws] Skipping persistence for synthetic parent identity ${parentDeviceId} -> ${deviceId}`
      );
    }

    console.log(
      `[ws] Parent device registered for child: ${deviceId} (parent=${parentDeviceId}, socket: ${socket.id})`
    );

    socket.emit("registered", {
      success: true,
      deviceId,
      requestedDeviceId,
      parentId: parentDeviceId,
      timestamp: Date.now(),
    });

    if (this.commandManager) {
      this.commandManager.touchStreamingOwner(deviceId, parentDeviceId);
    }

    // Notify child that parent is connected
    const childSocketId = this.childSockets.get(deviceId);
    if (!isRepeatRegistration) {
      if (childSocketId) {
        const childSocket = this.io.sockets.sockets.get(childSocketId);
        if (childSocket) {
          childSocket.emit("parent_connected", { deviceId, timestamp: Date.now() });
          console.log(`Parent connected notification sent to child: ${deviceId}`);
        }
        socket.emit("child_connected", { deviceId, timestamp: Date.now() });
      } else {
        socket.emit("child_disconnected", { deviceId, timestamp: Date.now() });
      }
    } else {
      console.log(
        `[ws] Skipping child status echo on repeated parent registration for ${deviceId} (${parentDeviceId})`
      );
    }
    if (!isRepeatRegistration) {
      await this.deliverPendingMessages(
        deviceId,
        "parent",
        socket,
        parentDeviceId
      );
    } else {
      console.log(
        `[ws] Skipping pending chat delivery on repeated parent registration for ${deviceId} (${parentDeviceId})`
      );
    }
  }


  async deliverPendingMessages(deviceId, targetRole, socket, targetParentDeviceId = null) {
    if (!this.dbManager || !this.dbManager.getUndeliveredMessages) {
      return;
    }

    try {
      const pending = await this.dbManager.getUndeliveredMessages(
        deviceId,
        targetRole,
        targetParentDeviceId || socket?.parentDeviceId || null
      );
      if (!pending || !pending.length || !socket) {
        return;
      }

      const deliveredIds = [];
      for (const message of pending) {
        if (!socket.connected) {
          break;
        }

        const clientId =
          message.client_id ||
          message.client_message_id ||
          message.clientMessageId ||
          message.id;

        const payload = {
          deviceId,
          text: message.message,
          sender: message.sender,
          senderRole: message.sender === "child" ? "child" : "parent",
          senderDeviceId: message.sender_device_id || "",
          senderDisplayName: this.getChatDisplayLabel(
            message.sender === "child" ? "child" : "parent",
            message.sender_device_id,
            message.sender_display_name
          ),
          timestamp: message.timestamp,
          id: clientId,
          offline: true,
        };

        socket.emit("chat_message", payload);
        if (clientId) {
          deliveredIds.push(clientId);
        }
      }

      if (deliveredIds.length) {
        console.log(
          `Delivered ${deliveredIds.length} pending messages to ${targetRole} for device ${deviceId}`
        );
      }
    } catch (error) {
      console.error("Error delivering pending messages:", error);
    }
  }
  /**
   * Handle audio chunk from child device
   */
  handleAudioChunk(socket, metadata, binaryData) {
    try {
      const sequence = metadata?.sequence;
      const timestamp = metadata?.timestamp;
      const recording = metadata?.recording;
      const deviceId = this.normalizeDeviceId(metadata?.deviceId);

      if (!deviceId) {
        console.error("[ws] Audio chunk missing deviceId");
        return;
      }

      if (!binaryData || binaryData.length === 0) {
        console.error("[ws] Audio chunk is empty");
        return;
      }

      if (this.shouldLogAudioChunk(sequence)) {
        console.log(
          `[audio] chunk received from ${deviceId} (#${sequence}, ${binaryData.length} bytes)`
        );
      }

      // Forward chunk to ALL parent sockets mapped to this child.
      // Parent app may keep several sockets alive (main UI + playback service),
      // and restricting to one socket causes silent playback.
      const mappedParentSocketIds = [];
      for (const [parentSocketId, childDeviceId] of this.parentSockets.entries()) {
        if (this.normalizeDeviceId(childDeviceId) !== deviceId) continue;
        const mappedParentSocket = this.io.sockets.sockets.get(parentSocketId);
        if (mappedParentSocket && mappedParentSocket.connected) {
          mappedParentSocketIds.push(parentSocketId);
        } else {
          this.parentSockets.delete(parentSocketId);
        }
      }

      const targetParentSocketIds = new Set(mappedParentSocketIds);

      if (targetParentSocketIds.size > 0) {
        let forwardedCount = 0;
        const targetList = Array.from(targetParentSocketIds.values());
        if (this.shouldLogAudioChunk(sequence)) {
          console.log(
            `Audio routing: chunk #${sequence} childSocket=${socket.id} device=${deviceId} -> parentSockets=${targetList.join(",")}`
          );
        }

        for (const parentSocketId of targetParentSocketIds) {
          const parentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (!parentSocket || !parentSocket.connected) {
            this.parentSockets.delete(parentSocketId);
            continue;
          }

          // Keep fallback socket mapping in sync for future direct routing.
          if (this.normalizeDeviceId(this.parentSockets.get(parentSocketId)) !== deviceId) {
            this.parentSockets.set(parentSocketId, deviceId);
            parentSocket.emit("child_connected", {
              deviceId,
              timestamp: Date.now(),
              fallback: true,
            });
            socket.emit("parent_connected", {
              deviceId,
              timestamp: Date.now(),
              fallback: true,
            });
            console.warn(
              `Updated parent mapping by audio fallback: parentSocket=${parentSocketId}, deviceId=${deviceId}`
            );
          }

          parentSocket.emit("audio_chunk", metadata, binaryData);
          if (this.shouldLogAudioChunk(sequence)) {
            console.log(
              `Audio route detail: #${sequence} ${deviceId} ${socket.id} -> ${parentSocketId}`
            );
          }
          forwardedCount += 1;
        }

        if (forwardedCount > 0) {
          if (this.shouldLogAudioChunk(sequence)) {
            console.log(
              `[audio] chunk #${sequence} forwarded to parent (${forwardedCount} socket${forwardedCount > 1 ? "s" : ""})`
            );
          }
        } else {
          if (this.shouldLogMissingParent(deviceId)) {
            console.log(
              `[audio] No mapped parent socket for device: ${deviceId}. parentMappings=${JSON.stringify(
                Array.from(this.parentSockets.entries())
              )}`
            );
          }
        }
      } else {
        if (this.shouldLogMissingParent(deviceId)) {
          console.log(
            `[audio] No mapped parent socket for device: ${deviceId}. parentMappings=${JSON.stringify(
              Array.from(this.parentSockets.entries())
            )}`
          );
        }
      }
    } catch (error) {
      console.error("[audio] Error handling audio chunk:", error);
    }
  }

  resolveChatSender(socket, data = {}) {
    const incomingSender = this.normalizeDeviceId(data?.sender);
    const explicitParentId = this.normalizeDeviceId(data?.parentId);

    if (socket?.deviceType === "child") {
      return {
        sender: "child",
        senderRole: "child",
        senderDeviceId: this.normalizeDeviceId(socket.deviceId || data?.deviceId),
        senderDisplayName: this.getChatDisplayLabel(
          "child",
          socket.deviceId || data?.deviceId,
          socket.childDisplayName || data?.authorDisplayName || data?.childDisplayName
        ),
      };
    }

    if (socket?.deviceType === "parent") {
      const parentDeviceId = this.normalizeDeviceId(
        socket.parentDeviceId || explicitParentId || incomingSender
      );
      return {
        sender: parentDeviceId ? "parent" : "",
        senderRole: parentDeviceId ? "parent" : "",
        senderDeviceId: parentDeviceId,
        senderDisplayName: this.getParentDisplayLabel(
          parentDeviceId,
          socket.parentDisplayName
        ),
      };
    }

    if (incomingSender === "child") {
      return {
        sender: "child",
        senderRole: "child",
        senderDeviceId: this.normalizeDeviceId(data?.deviceId),
        senderDisplayName: this.getChatDisplayLabel(
          "child",
          data?.deviceId,
          data?.authorDisplayName || data?.childDisplayName
        ),
      };
    }

    const fallbackParentId = this.normalizeDeviceId(explicitParentId || incomingSender);
    return {
      sender: fallbackParentId ? "parent" : "",
      senderRole: fallbackParentId ? "parent" : "",
      senderDeviceId: fallbackParentId,
      senderDisplayName: this.getParentDisplayLabel(fallbackParentId),
    };
  }

  buildChatPayload(deviceId, messageText, senderInfo, messageTimestamp, messageId, extra = {}) {
    return {
      deviceId,
      text: messageText,
      sender: senderInfo.senderRole || senderInfo.sender,
      senderRole: senderInfo.senderRole,
      senderDeviceId: senderInfo.senderDeviceId || "",
      senderDisplayName: senderInfo.senderDisplayName || "",
      timestamp: messageTimestamp,
      id: messageId,
      ...extra,
    };
  }


  /**
   * Handle chat message (bidirectional)
   */
  async handleChatMessage(socket, data) {
    try {
      const { deviceId, text: messageText } = data || {};

      if (!deviceId || !messageText) {
        console.error("Chat message missing required fields");
        socket.emit("chat_message_error", { error: "Missing required fields" });
        return;
      }

      const senderInfo = this.resolveChatSender(socket, data);
      if (!senderInfo.sender || !senderInfo.senderRole) {
        console.error("Chat message has invalid sender context");
        socket.emit("chat_message_error", { error: "Invalid sender" });
        return;
      }

      const messageTimestamp = data?.timestamp || Date.now();
      const messageId =
        data?.id ||
        `${deviceId}_${messageTimestamp}_${Math.floor(Math.random() * 1000)}`;

      const outboundPayload = this.buildChatPayload(
        deviceId,
        messageText,
        senderInfo,
        messageTimestamp,
        messageId
      );

      if (this.dbManager?.saveChatMessage) {
        try {
          await this.dbManager.saveChatMessage(deviceId, {
            sender: senderInfo.senderRole,
            senderDeviceId: senderInfo.senderDeviceId || null,
            senderDisplayName: senderInfo.senderDisplayName || null,
            message: messageText,
            timestamp: messageTimestamp,
            id: messageId,
          });
          console.log(`Chat message saved to database for ${deviceId}`);
        } catch (dbError) {
          console.error("Failed to save chat message to database:", dbError);
        }
      }

      let delivered = false;
      let childDelivered = false;
      let targetSocket = null;

      if (senderInfo.senderRole === "child") {
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        if (parentSocketIds.length > 0) {
          for (const parentSocketId of parentSocketIds) {
            const parentSocket = this.io.sockets.sockets.get(parentSocketId);
            if (!parentSocket || !parentSocket.connected) continue;
            parentSocket.emit("chat_message", outboundPayload);
            delivered = true;
          }
        }
      } else if (senderInfo.senderRole === "parent") {
        const childSocketId = this.childSockets.get(deviceId);
        if (childSocketId) {
          targetSocket = this.io.sockets.sockets.get(childSocketId);
        }
        const familyParentSocketIds = this.getConnectedParentSocketIdsForDevice(
          deviceId,
          socket.id
        );
        for (const parentSocketId of familyParentSocketIds) {
          const familyParentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (!familyParentSocket || !familyParentSocket.connected) continue;
          familyParentSocket.emit("chat_message", outboundPayload);
          delivered = true;
        }
      } else {
        console.error(`Invalid sender: ${senderInfo.sender}`);
        socket.emit("chat_message_error", { error: "Invalid sender" });
        return;
      }

      if (
        senderInfo.senderRole === "parent" &&
        targetSocket &&
        targetSocket.connected
      ) {
        targetSocket.emit("chat_message", outboundPayload);
        childDelivered = true;
        delivered = true;
        console.log(`Chat message forwarded for device ${deviceId}`);
      } else if (senderInfo.senderRole === "child" && !delivered) {
        console.log(`No parent online for ${deviceId}; message stored`);
      } else if (senderInfo.senderRole === "parent") {
        console.log(`No child online for ${deviceId}; message stored`);
      }

      const shouldMarkDelivered =
        senderInfo.senderRole === "child" ? delivered : childDelivered;
      if (shouldMarkDelivered && this.dbManager?.markMessageDelivered) {
        try {
          await this.dbManager.markMessageDelivered(messageId);
        } catch (dbError) {
          console.error("Failed to mark message delivered:", dbError);
        }
      }

      socket.emit("chat_message_sent", {
        id: messageId,
        timestamp: Date.now(),
        delivered,
      });
    } catch (error) {
      console.error("Error handling chat message:", error);
      socket.emit("chat_message_error", { error: error.message });
    }
  }


  async handleChatMessageStatus(socket, data) {
    try {
      const { deviceId, id, status, actor } = data || {};
      if (!deviceId || !id || !status || !actor) {
        console.warn("chat_message_status missing required fields", data);
        return;
      }

      if (this.dbManager) {
        try {
          if (status === "delivered" && this.dbManager.markMessageDelivered) {
            await this.dbManager.markMessageDelivered(id);
          }
          if (status === "read") {
            if (this.dbManager.markMessageAsReadByClientId) {
              await this.dbManager.markMessageAsReadByClientId(id);
            } else if (this.dbManager.markMessageAsRead) {
              await this.dbManager.markMessageAsRead(id);
            }
          }
        } catch (dbError) {
          console.error("Failed to update message status:", dbError);
        }
      }

      let targetSocketId = null;
      if (actor === "child") {
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        for (const parentSocketId of parentSocketIds) {
          const targetSocket = this.io.sockets.sockets.get(parentSocketId);
          if (!targetSocket || !targetSocket.connected) continue;
          targetSocket.emit("chat_message_status", {
            id,
            status,
            timestamp: Date.now(),
          });
        }
      } else if (actor === "parent") {
        targetSocketId = this.childSockets.get(deviceId);
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        for (const parentSocketId of parentSocketIds) {
          const targetSocket = this.io.sockets.sockets.get(parentSocketId);
          if (!targetSocket || !targetSocket.connected) continue;
          targetSocket.emit("chat_message_status", {
            id,
            status,
            timestamp: Date.now(),
          });
        }
      }

      if (actor === "parent" && targetSocketId) {
        const targetSocket = this.io.sockets.sockets.get(targetSocketId);
        if (targetSocket) {
          targetSocket.emit("chat_message_status", {
            id,
            status,
            timestamp: Date.now(),
          });
        }
      }

      socket.emit("chat_message_status_ack", {
        id,
        status,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error("Error handling chat message status:", error);
    }
  }

  /**
   * Handle client disconnection
   */
  handleDisconnect(socket) {
    this.unregisterDeviceSocket(socket);
    console.log(
      `[ws] Client disconnected: ${socket.id} (${socket.deviceType || "unknown"})`
    );

    if (socket.deviceType === "child") {
      // Child device disconnected
      const deviceId = socket.deviceId;
      if (deviceId) {
        const currentSocketId = this.childSockets.get(deviceId);
        if (currentSocketId !== socket.id) {
          console.log(
            `[ws] Ignoring stale child disconnect: ${deviceId}, socket=${socket.id}, current=${currentSocketId || "none"}`
          );
          return;
        }

        this.childSockets.delete(deviceId);
        console.log(`[ws] Child device removed: ${deviceId}`);

        // Notify all mapped parent sockets that child disconnected
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        for (const parentSocketId of parentSocketIds) {
          const parentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (parentSocket) {
            parentSocket.emit("child_disconnected", { deviceId, timestamp: Date.now() });
          }
        }
      }
    } else if (socket.deviceType === "parent") {
      // Parent device disconnected
      const deviceId = this.parentSockets.get(socket.id);
      if (deviceId) {
        this.parentSockets.delete(socket.id);
        this.removePhotoRequestsForParent(socket.id);
        console.log(`Parent device removed for child: ${deviceId}`);

        const remainingParentSocketIds = this.getConnectedParentSocketIdsForDevice(
          deviceId,
          socket.id
        );
        if (remainingParentSocketIds.length === 0) {
          const childSocketId = this.childSockets.get(deviceId);
          if (childSocketId) {
            const childSocket = this.io.sockets.sockets.get(childSocketId);
            if (childSocket) {
              childSocket.emit("parent_disconnected", { deviceId, timestamp: Date.now() });
            }
          }
        }
      }
    }

  }


  /**
   * Send command to child device via WebSocket
   */
  sendCommandToChild(deviceId, command) {
    let targetDeviceId = this.normalizeDeviceId(deviceId);
    if (!this.isChildConnectedById(targetDeviceId)) {
      const fallbackDeviceId = this.resolveConnectedChildDeviceId(targetDeviceId);
      if (!fallbackDeviceId) {
        console.warn(`[ws] Cannot send command: child ${deviceId} not connected`);
        return false;
      }
      if (fallbackDeviceId !== targetDeviceId) {
        console.warn(
          `Command target remapped from ${targetDeviceId || "<empty>"} to ${fallbackDeviceId}`
        );
      }
      targetDeviceId = fallbackDeviceId;
    }

    const childSocketId = this.childSockets.get(targetDeviceId);
    if (!childSocketId) {
      console.warn(`[ws] Cannot send command: child ${targetDeviceId} not connected`);
      return false;
    }

    const childSocket = this.io.sockets.sockets.get(childSocketId);
    if (!childSocket) {
      console.warn(`[ws] Cannot send command: socket ${childSocketId} not found`);
      this.childSockets.delete(targetDeviceId);
      return false;
    }

    // Send command via WebSocket
    childSocket.emit("command", command);
    console.log(`[ws] Command sent to child ${targetDeviceId}:`, command.type);
    return true;
  }

  /**
   * Check if child device is connected
   */
  isChildConnected(deviceId) {
    const socketId = this.childSockets.get(deviceId);
    if (!socketId) return false;

    const socket = this.io.sockets.sockets.get(socketId);
    return socket && socket.connected;
  }

  /**
   * Check if there's an active listener for a child device
   */
  hasActiveListener(deviceId) {
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized) return false;
    return Array.from(this.parentSockets.values()).some(
      (value) => this.normalizeDeviceId(value) === normalized
    );
  }

  /**
   * Get connection statistics
   */
  getStats() {
    return {
      totalConnections: this.io.engine.clientsCount,
      activeChildDevices: this.childSockets.size,
      activeParentDevices: this.parentSockets.size,
      exactDeviceRegistrations: this.deviceSockets.size,
      activeStreams: this.activeStreams.size,
    };
  }
}

module.exports = WebSocketManager;
