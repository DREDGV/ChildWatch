/**
 * WebSocketManager - Manages WebSocket connections for real-time audio streaming
 *
 * Architecture:
 * - ParentWatch (child device) connects and sends audio chunks
 * - ChildWatch (parent device) connects and receives audio chunks
 * - Server routes chunks from child в†’ parent in real-time
 */

class WebSocketManager {
  constructor(io, commandManager = null) {
    this.io = io;
    this.commandManager = commandManager;

    // Map: deviceId (child) в†’ socket.id
    this.childSockets = new Map();

    // Map: deviceId (child) в†’ parentSocketId
    this.activeStreams = new Map();

    // Map: parentSocketId в†’ deviceId (child being monitored)
    this.parentSockets = new Map();
    // Map: requestId в†’ { parentSocketId, deviceId, createdAt }
    this.pendingPhotoRequests = new Map();

    console.log("рџ”Њ WebSocketManager initialized");
  }

  normalizeDeviceId(value) {
    if (value === null || value === undefined) return "";
    return String(value).trim();
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
    // РџРѕР»СѓС‡РµРЅРёРµ РЅРµРґРѕСЃС‚Р°РІР»РµРЅРЅС‹С… СЃРѕРѕР±С‰РµРЅРёР№ РїРѕ Р·Р°РїСЂРѕСЃСѓ
    this.io.on("connection", (socket) => {
      socket.on("get_missed_messages", async (data) => {
        try {
          const { deviceId } = data || {};
          if (!deviceId) {
            socket.emit("missed_messages", {
              success: false,
              error: "Missing deviceId",
            });
            return;
          }
          const messages = await this.dbManager.getChatMessages(deviceId, 100);
          // Recovery mode: return recent history (not only unread) to heal client gaps.
          const missed = messages || [];
          socket.emit("missed_messages", {
            success: true,
            messages: missed.map((msg) => ({
              id: msg.id,
              sender: msg.sender,
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
      console.log(`рџ”Њ Client connected: ${socket.id}`);

      // Debug: log any incoming WS events to verify routing
      try {
        socket.onAny((event, ...args) => {
          const type = socket.deviceType || "unknown";
          const did = socket.deviceId || "n/a";
          console.log(
            `рџ”µ WS event '${event}' from ${type} ${socket.id} (deviceId=${did})`
          );
        });
      } catch (e) {
        console.warn("вљ пёЏ Failed to attach onAny logger:", e?.message || e);
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
        console.log(
          `рџЋ¤ audio_chunk event received from ${socket.id}, metadata:`,
          metadata,
          `dataSize: ${binaryData ? binaryData.length : 0}`
        );
        this.handleAudioChunk(socket, metadata, binaryData);
      });

      socket.on("audio_capture_error", (data) => {
        console.warn(
          `Audio capture diagnostic from ${socket.deviceType || "unknown"} ${socket.id} (deviceId=${socket.deviceId || "n/a"}):`,
          data
        );
      });

      // Handle heartbeat/ping
      socket.on("ping", () => {
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
        console.error(`вќЊ Socket error (${socket.id}):`, error);
      });
    });

    console.log("вњ… WebSocket event handlers registered");
  }

  /**
   * Handle command request coming from a parent socket
   */
  handleCommand(socket, data) {
    console.log(
      `рџ“Ґ [handleCommand] Received command from socket ${socket.id}, deviceType=${socket.deviceType}:`,
      JSON.stringify(data)
    );

    try {
      if (!data || typeof data !== "object") {
        console.warn("вљ пёЏ Invalid command payload", data);
        return;
      }

      const rawType = data.type;
      const payload = data.data || {};
      const explicitDeviceId = this.normalizeDeviceId(data.deviceId);

      console.log(
        `рџ“‹ Command details: type=${rawType}, deviceId=${explicitDeviceId}, socketType=${socket.deviceType}`
      );

      if (!rawType) {
        console.warn("вљ пёЏ Command missing type", data);
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
          `вљ пёЏ Unable to resolve target device for command ${rawType}`,
          data
        );
        return;
      }

      if (explicitDeviceId && explicitDeviceId !== targetDeviceId) {
        console.warn(
          `Command ${rawType} remapped from ${explicitDeviceId} to connected child ${targetDeviceId}`
        );
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
          `рџ“Ґ Child ${targetDeviceId} РѕС„С„Р»Р°Р№РЅ вЂ” РєРѕРјР°РЅРґР° ${rawType} РїРѕСЃС‚Р°РІР»РµРЅР° РІ РѕС‡РµСЂРµРґСЊ`
        );
      }
    } catch (error) {
      console.error("вќЊ Error handling command from parent:", error);
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
      const mappedDeviceId = this.parentSockets.get(socket.id);
      let resolvedDeviceId = this.resolveConnectedChildDeviceId(
        targetDevice,
        mappedDeviceId
      );
      if (!resolvedDeviceId) {
        resolvedDeviceId =
          this.normalizeDeviceId(targetDevice) ||
          this.normalizeDeviceId(mappedDeviceId);
      }
      const reqId = requestId || `${Date.now()}_${Math.random().toString(16).slice(2)}`;
      const cameraFacing =
        typeof camera === "string" && camera.trim()
          ? camera.trim().toLowerCase()
          : "back";
      console.log(
        `рџ“ё Photo request from ${socket.id} -> device=${resolvedDeviceId} requestId=${reqId} camera=${cameraFacing}`
      );

      if (!resolvedDeviceId) {
        socket.emit("photo_error", {
          requestId: reqId,
          error: "Missing target device",
        });
        return;
      }

      this.pendingPhotoRequests.set(reqId, {
        parentSocketId: socket.id,
        deviceId: resolvedDeviceId,
        createdAt: Date.now(),
      });

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

      childSocket.emit("request_photo", {
        requestId: reqId,
        targetDevice: resolvedDeviceId,
        camera: cameraFacing,
        timestamp: Date.now(),
      });
      console.log(`рџ“ё Photo request routed to child ${resolvedDeviceId}`);
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
        `рџ“ё Photo response received: requestId=${requestId} size=${photo ? photo.length : 0}`
      );

      const pending = this.pendingPhotoRequests.get(requestId);
      if (!pending) {
        console.warn(`No pending photo request for id=${requestId}`);
        return;
      }

      const parentSocket = this.io.sockets.sockets.get(pending.parentSocketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("photo", {
          requestId,
          photo,
          timestamp: timestamp || Date.now(),
        });
        console.log(`рџ“ё Photo delivered to parent socket ${pending.parentSocketId}`);
      }
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
      console.warn(`рџ“ё Photo error: requestId=${requestId} error=${error || "unknown"}`);
      const pending = this.pendingPhotoRequests.get(requestId);
      if (!pending) {
        return;
      }
      const parentSocket = this.io.sockets.sockets.get(pending.parentSocketId);
      if (parentSocket && parentSocket.connected) {
        parentSocket.emit("photo_error", {
          requestId,
          error: error || "Unknown error",
        });
      }
      this.pendingPhotoRequests.delete(requestId);
    } catch (err) {
      console.error("Error handling photo error:", err);
    }
  }

  removePhotoRequestsForParent(parentSocketId) {
    if (!parentSocketId) return;
    for (const [requestId, entry] of this.pendingPhotoRequests.entries()) {
      if (entry.parentSocketId === parentSocketId) {
        this.pendingPhotoRequests.delete(requestId);
      }
    }
  }

  /**
   * Register child device (ParentWatch)
   */
  async handleChildRegistration(socket, data) {
    const deviceId = this.normalizeDeviceId(data?.deviceId);

    if (!deviceId) {
      console.error("вќЊ Child registration failed: missing deviceId");
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

    console.log(
      `рџ“± Child device registered: ${deviceId} (socket: ${socket.id})`
    );
    console.log(`рџ“Љ Total child devices connected: ${this.childSockets.size}`);

    socket.emit("registered", {
      success: true,
      deviceId: deviceId,
      timestamp: Date.now(),
    });

    // Notify child that server is ready to receive audio
    console.log(`вњ… Child ${deviceId} is now ready to send audio chunks`);
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

    if (!requestedDeviceId) {
      console.error("вќЊ Parent registration failed: missing deviceId");
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

    console.log(
      `рџ‘ЁвЂЌрџ‘©вЂЌрџ‘§ Parent device registered for child: ${deviceId} (socket: ${socket.id})`
    );

    socket.emit("registered", {
      success: true,
      deviceId,
      requestedDeviceId,
      timestamp: Date.now(),
    });

    // Notify child that parent is connected
    const childSocketId = this.childSockets.get(deviceId);
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
    await this.deliverPendingMessages(deviceId, "parent", socket);
  }


  async deliverPendingMessages(deviceId, targetRole, socket) {
    if (!this.dbManager || !this.dbManager.getUndeliveredMessages) {
      return;
    }

    try {
      const pending = await this.dbManager.getUndeliveredMessages(
        deviceId,
        targetRole
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
        console.error("вќЊ Audio chunk missing deviceId");
        return;
      }

      if (!binaryData || binaryData.length === 0) {
        console.error("вќЊ Audio chunk is empty");
        return;
      }

      console.log(
        `рџЋµ Audio chunk received from ${deviceId} (#${sequence}, ${binaryData.length} bytes)`
      );

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
        console.log(
          `Audio routing: chunk #${sequence} childSocket=${socket.id} device=${deviceId} -> parentSockets=${targetList.join(",")}`
        );

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
          if (sequence % 25 === 0) {
            console.log(
              `Audio route detail: #${sequence} ${deviceId} ${socket.id} -> ${parentSocketId}`
            );
          }
          forwardedCount += 1;
        }

        if (forwardedCount > 0) {
          console.log(
            `рџ“¤ Audio chunk #${sequence} forwarded to parent (${forwardedCount} socket${forwardedCount > 1 ? "s" : ""})`
          );
        } else {
          console.log(
            `рџ“­ No mapped parent socket for device: ${deviceId}. parentMappings=${JSON.stringify(
              Array.from(this.parentSockets.entries())
            )}`
          );
        }
      } else {
        console.log(
          `рџ“­ No mapped parent socket for device: ${deviceId}. parentMappings=${JSON.stringify(
            Array.from(this.parentSockets.entries())
          )}`
        );
      }
    } catch (error) {
      console.error("вќЊ Error handling audio chunk:", error);
    }
  }


  /**
   * Handle chat message (bidirectional)
   */
  async handleChatMessage(socket, data) {
    try {
      const { deviceId, text: messageText, sender } = data || {};

      if (!deviceId || !messageText || !sender) {
        console.error("Chat message missing required fields");
        socket.emit("chat_message_error", { error: "Missing required fields" });
        return;
      }

      const messageTimestamp = data?.timestamp || Date.now();
      const messageId =
        data?.id ||
        `${deviceId}_${messageTimestamp}_${Math.floor(Math.random() * 1000)}`;

      const outboundPayload = {
        deviceId,
        text: messageText,
        sender,
        timestamp: messageTimestamp,
        id: messageId,
      };

      if (this.dbManager?.saveChatMessage) {
        try {
          await this.dbManager.saveChatMessage(deviceId, {
            sender,
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
      let targetSocket = null;

      if (sender === "child") {
        const parentSocketIds = this.getConnectedParentSocketIdsForDevice(deviceId);
        if (parentSocketIds.length > 0) {
          for (const parentSocketId of parentSocketIds) {
            const parentSocket = this.io.sockets.sockets.get(parentSocketId);
            if (!parentSocket || !parentSocket.connected) continue;
            parentSocket.emit("chat_message", outboundPayload);
            delivered = true;
          }
        }
      } else if (sender === "parent") {
        const childSocketId = this.childSockets.get(deviceId);
        if (childSocketId) {
          targetSocket = this.io.sockets.sockets.get(childSocketId);
        }
      } else {
        console.error(`Invalid sender: ${sender}`);
        socket.emit("chat_message_error", { error: "Invalid sender" });
        return;
      }

      if (sender === "parent" && targetSocket && targetSocket.connected) {
        targetSocket.emit("chat_message", outboundPayload);
        delivered = true;
        console.log(`Chat message forwarded for device ${deviceId}`);
      } else if (sender === "child" && !delivered) {
        console.log(`No parent online for ${deviceId}; message stored`);
      } else if (sender === "parent") {
        console.log(`No child online for ${deviceId}; message stored`);
      }

      if (delivered && this.dbManager?.markMessageDelivered) {
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
    console.log(
      `рџ”Њ Client disconnected: ${socket.id} (${socket.deviceType || "unknown"})`
    );

    if (socket.deviceType === "child") {
      // Child device disconnected
      const deviceId = socket.deviceId;
      if (deviceId) {
        this.childSockets.delete(deviceId);
        console.log(`рџ“± Child device removed: ${deviceId}`);

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


  /**
   * Send command to child device via WebSocket
   */
  sendCommandToChild(deviceId, command) {
    let targetDeviceId = this.normalizeDeviceId(deviceId);
    if (!this.isChildConnectedById(targetDeviceId)) {
      const fallbackDeviceId = this.resolveConnectedChildDeviceId(targetDeviceId);
      if (!fallbackDeviceId) {
        console.warn(`вљ пёЏ Cannot send command: child ${deviceId} not connected`);
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
      console.warn(`вљ пёЏ Cannot send command: child ${targetDeviceId} not connected`);
      return false;
    }

    const childSocket = this.io.sockets.sockets.get(childSocketId);
    if (!childSocket) {
      console.warn(`вљ пёЏ Cannot send command: socket ${childSocketId} not found`);
      this.childSockets.delete(targetDeviceId);
      return false;
    }

    // Send command via WebSocket
    childSocket.emit("command", command);
    console.log(`вњ… Command sent to child ${targetDeviceId}:`, command.type);
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
      activeStreams: this.activeStreams.size,
    };
  }
}

module.exports = WebSocketManager;
