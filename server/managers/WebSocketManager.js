/**
 * WebSocketManager - Manages WebSocket connections for real-time audio streaming
 *
 * Architecture:
 * - ParentWatch (child device) connects and sends audio chunks
 * - ChildWatch (parent device) connects and receives audio chunks
 * - Server routes chunks from child → parent in real-time
 */

class WebSocketManager {
  constructor(io, commandManager = null) {
    this.io = io;
    this.commandManager = commandManager;

    // Map: deviceId (child) → socket.id
    this.childSockets = new Map();

    // Map: deviceId (child) → parentSocketId
    this.activeStreams = new Map();

    // Map: parentSocketId → deviceId (child being monitored)
    this.parentSockets = new Map();

    console.log("🔌 WebSocketManager initialized");
  }

  /**
   * Initialize WebSocket event handlers
   */
  initialize() {
    // Получение недоставленных сообщений по запросу
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
          // Отправляем только непрочитанные
          const missed = (messages || []).filter((msg) => !msg.is_read);
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
      console.log(`🔌 Client connected: ${socket.id}`);

      // Debug: log any incoming WS events to verify routing
      try {
        socket.onAny((event, ...args) => {
          const type = socket.deviceType || "unknown";
          const did = socket.deviceId || "n/a";
          console.log(
            `🔵 WS event '${event}' from ${type} ${socket.id} (deviceId=${did})`
          );
        });
      } catch (e) {
        console.warn("⚠️ Failed to attach onAny logger:", e?.message || e);
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
          `🎤 audio_chunk event received from ${socket.id}, metadata:`,
          metadata,
          `dataSize: ${binaryData ? binaryData.length : 0}`
        );
        this.handleAudioChunk(socket, metadata, binaryData);
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

      // Handle disconnection
      socket.on("disconnect", () => {
        this.handleDisconnect(socket);
      });

      // Handle errors
      socket.on("error", (error) => {
        console.error(`❌ Socket error (${socket.id}):`, error);
      });
    });

    console.log("✅ WebSocket event handlers registered");
  }

  /**
   * Handle command request coming from a parent socket
   */
  handleCommand(socket, data) {
    console.log(
      `📥 [handleCommand] Received command from socket ${socket.id}, deviceType=${socket.deviceType}:`,
      JSON.stringify(data)
    );

    try {
      if (!data || typeof data !== "object") {
        console.warn("⚠️ Invalid command payload", data);
        return;
      }

      const rawType = data.type;
      const payload = data.data || {};
      const explicitDeviceId = data.deviceId;

      console.log(
        `📋 Command details: type=${rawType}, deviceId=${explicitDeviceId}, socketType=${socket.deviceType}`
      );

      if (!rawType) {
        console.warn("⚠️ Command missing type", data);
        return;
      }

      // Determine target child device
      let targetDeviceId = explicitDeviceId;
      if (!targetDeviceId) {
        const mappedDeviceId = this.parentSockets.get(socket.id);
        if (mappedDeviceId) {
          targetDeviceId = mappedDeviceId;
        }
      }

      if (!targetDeviceId) {
        console.warn(
          `⚠️ Unable to resolve target device for command ${rawType}`,
          data
        );
        return;
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
          `📥 Child ${targetDeviceId} оффлайн — команда ${rawType} поставлена в очередь`
        );
      }
    } catch (error) {
      console.error("❌ Error handling command from parent:", error);
    }
  }

  /**
   * Register child device (ParentWatch)
   */
  handleChildRegistration(socket, data) {
    const { deviceId } = data;

    if (!deviceId) {
      console.error("❌ Child registration failed: missing deviceId");
      socket.emit("error", { message: "Missing deviceId" });
      return;
    }

    // Store child socket mapping
    this.childSockets.set(deviceId, socket.id);
    socket.deviceId = deviceId;
    socket.deviceType = "child";

    console.log(
      `📱 Child device registered: ${deviceId} (socket: ${socket.id})`
    );
    console.log(`📊 Total child devices connected: ${this.childSockets.size}`);

    socket.emit("registered", {
      success: true,
      deviceId: deviceId,
      timestamp: Date.now(),
    });

    // Notify child that server is ready to receive audio
    console.log(`✅ Child ${deviceId} is now ready to send audio chunks`);
    if (this.commandManager && this.commandManager.isStreaming(deviceId)) {
      try {
        const sessionInfo = this.commandManager.getSessionInfo(deviceId);
        const parentId = sessionInfo?.parentId || "parent";
        const commandType =
          (this.commandManager.COMMANDS &&
            this.commandManager.COMMANDS.START_STREAM) ||
          "start_audio_stream";
        const commandPayload = {
          type: commandType,
          data: { parentId, replay: true },
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
  }

  /**
   * Register parent device (ChildWatch)
   */
  handleParentRegistration(socket, data) {
    const { deviceId } = data;

    if (!deviceId) {
      console.error("❌ Parent registration failed: missing deviceId");
      socket.emit("error", { message: "Missing deviceId" });
      return;
    }

    // Store parent socket mapping
    this.parentSockets.set(socket.id, deviceId);
    socket.deviceId = deviceId;
    socket.deviceType = "parent";

    console.log(
      `👨‍👩‍👧 Parent device registered for child: ${deviceId} (socket: ${socket.id})`
    );

    socket.emit("registered", {
      success: true,
      deviceId: deviceId,
      timestamp: Date.now(),
    });

    // Notify child that parent is connected
    const childSocketId = this.childSockets.get(deviceId);
    if (childSocketId) {
      const childSocket = this.io.sockets.sockets.get(childSocketId);
      if (childSocket) {
        childSocket.emit("parent_connected");
        console.log(
          `🔔 Parent connected notification sent to child: ${deviceId}`
        );
      }
    }
  }

  /**
   * Handle audio chunk from child device
   */
  handleAudioChunk(socket, metadata, binaryData) {
    try {
      const { deviceId, sequence, timestamp, recording } = metadata;

      if (!deviceId) {
        console.error("❌ Audio chunk missing deviceId");
        return;
      }

      if (!binaryData || binaryData.length === 0) {
        console.error("❌ Audio chunk is empty");
        return;
      }

      console.log(
        `🎵 Audio chunk received from ${deviceId} (#${sequence}, ${binaryData.length} bytes)`
      );

      // Forward chunk to parent device if connected
      const parentSocketId = Array.from(this.parentSockets.entries()).find(
        ([id, childDeviceId]) => childDeviceId === deviceId
      )?.[0];

      if (parentSocketId) {
        const parentSocket = this.io.sockets.sockets.get(parentSocketId);
        if (parentSocket) {
          // Send both metadata and binary data
          parentSocket.emit("audio_chunk", metadata, binaryData);
          console.log(`📤 Audio chunk #${sequence} forwarded to parent`);
        } else {
          console.log(`⚠️ Parent socket not found for device: ${deviceId}`);
          this.parentSockets.delete(parentSocketId);
        }
      } else {
        console.log(`📭 No parent connected for device: ${deviceId}`);
      }
    } catch (error) {
      console.error("❌ Error handling audio chunk:", error);
    }
  }

  /**
   * Handle chat message (bidirectional)
   */
  async handleChatMessage(socket, data) {
    try {
      const { deviceId, text, sender, timestamp, id } = data;

      if (!deviceId || !text || !sender) {
        console.error("❌ Chat message missing required fields");
        socket.emit("chat_message_error", { error: "Missing required fields" });
        return;
      }

      // Save message to database
      try {
        await this.dbManager.saveChatMessage(deviceId, {
          sender,
          message: text,
          timestamp: timestamp || Date.now(),
          id: id,
        });
        console.log(
          `💾 Chat message saved to database: ${deviceId} from ${sender}`
        );
      } catch (dbError) {
        console.error("❌ Failed to save chat message to database:", dbError);
        // Continue forwarding even if DB save fails
      }

      // Determine direction based on sender
      let delivered = false;

      if (sender === "child") {
        // Message FROM child → Forward TO parent
        const parentSocketId = Array.from(this.parentSockets.entries()).find(
          ([socketId, childDeviceId]) => childDeviceId === deviceId
        )?.[0];

        if (parentSocketId) {
          const parentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (parentSocket) {
            parentSocket.emit("chat_message", data);
            delivered = true;
            console.log(
              `💬 Chat message forwarded to parent for device: ${deviceId}`
            );
          } else {
            console.log(`⚠️ Parent socket not found for device: ${deviceId}`);
          }
        } else {
          console.log(
            `📭 No parent connected for device: ${deviceId} (message saved for later)`
          );
        }
      } else if (sender === "parent") {
        // Message FROM parent → Forward TO child
        const childSocketId = this.childSockets.get(deviceId);

        if (childSocketId) {
          const childSocket = this.io.sockets.sockets.get(childSocketId);
          if (childSocket) {
            childSocket.emit("chat_message", data);
            delivered = true;
            console.log(
              `💬 Chat message forwarded to child device: ${deviceId}`
            );
          } else {
            console.log(`⚠️ Child socket not found for device: ${deviceId}`);
            this.childSockets.delete(deviceId);
          }
        } else {
          console.log(
            `📭 No child connected with device ID: ${deviceId} (message saved for later)`
          );
        }
      } else {
        console.error(
          `❌ Invalid sender: ${sender}. Must be 'parent' or 'child'`
        );
        socket.emit("chat_message_error", { error: "Invalid sender" });
        return;
      }

      // Confirm message sent back to sender
      socket.emit("chat_message_sent", {
        id: id || data.id,
        timestamp: Date.now(),
        delivered: delivered,
      });
    } catch (error) {
      console.error("❌ Error handling chat message:", error);
      socket.emit("chat_message_error", { error: error.message });
    }
  }

  async handleChatMessageStatus(socket, data) {
    try {
      const { deviceId, id, status, actor } = data || {};
      if (!deviceId || !id || !status || !actor) {
        console.warn("⚠️ chat_message_status missing required fields", data);
        return;
      }

      if (status === "read") {
        try {
          await this.dbManager.markMessageAsRead(id);
        } catch (error) {
          console.error("❌ Failed to mark message as read:", error);
        }
      }

      let targetSocketId = null;
      if (actor === "child") {
        // Child reports status (message from parent)
        targetSocketId = Array.from(this.parentSockets.entries()).find(
          ([socketId, childDeviceId]) => childDeviceId === deviceId
        )?.[0];
      } else if (actor === "parent") {
        // Parent reports status (message from child)
        targetSocketId = this.childSockets.get(deviceId);
      }

      if (targetSocketId) {
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
      console.error("❌ Error handling chat message status:", error);
    }
  }

  /**
   * Handle client disconnection
   */
  handleDisconnect(socket) {
    console.log(
      `🔌 Client disconnected: ${socket.id} (${socket.deviceType || "unknown"})`
    );

    if (socket.deviceType === "child") {
      // Child device disconnected
      const deviceId = socket.deviceId;
      if (deviceId) {
        this.childSockets.delete(deviceId);
        console.log(`📱 Child device removed: ${deviceId}`);

        // Notify parent that child disconnected
        const parentSocketId = Array.from(this.parentSockets.entries()).find(
          ([id, childDeviceId]) => childDeviceId === deviceId
        )?.[0];

        if (parentSocketId) {
          const parentSocket = this.io.sockets.sockets.get(parentSocketId);
          if (parentSocket) {
            parentSocket.emit("child_disconnected");
          }
          this.parentSockets.delete(parentSocketId);
        }
      }
    } else if (socket.deviceType === "parent") {
      // Parent device disconnected
      const deviceId = this.parentSockets.get(socket.id);
      if (deviceId) {
        this.parentSockets.delete(socket.id);
        console.log(`👨‍👩‍👧 Parent device removed for child: ${deviceId}`);
      }
    }
  }

  /**
   * Send command to child device via WebSocket
   */
  sendCommandToChild(deviceId, command) {
    const childSocketId = this.childSockets.get(deviceId);
    if (!childSocketId) {
      console.warn(`⚠️ Cannot send command: child ${deviceId} not connected`);
      return false;
    }

    const childSocket = this.io.sockets.sockets.get(childSocketId);
    if (!childSocket) {
      console.warn(`⚠️ Cannot send command: socket ${childSocketId} not found`);
      this.childSockets.delete(deviceId);
      return false;
    }

    // Send command via WebSocket
    childSocket.emit("command", command);
    console.log(`✅ Command sent to child ${deviceId}:`, command.type);
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
    return Array.from(this.parentSockets.values()).includes(deviceId);
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
