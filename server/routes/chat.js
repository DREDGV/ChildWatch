const express = require("express");

const MAX_CHAT_TEXT_BYTES = 16 * 1024;
const DEFAULT_HISTORY_LIMIT = 100;
const MAX_HISTORY_LIMIT = 500;

/**
 * Backwards-compatible HTTP endpoints for installed clients that still use
 * the device-thread chat protocol. Authentication is applied by index.js.
 *
 * A single process-wide DatabaseManager is injected deliberately: opening and
 * initializing SQLite for every request used to race migrations and could
 * close the connection while another request was still using it.
 */
function createChatRoutes(dbManager, familyPermissionService) {
  if (!dbManager || !familyPermissionService) {
    throw new Error("Legacy chat routes require database and permission services");
  }

  const router = express.Router();

  const normalizeId = (value) => String(value ?? "").trim();

  const parseHistoryNumber = (value, fallback, maximum) => {
    if (value === undefined || value === null || value === "") return fallback;
    if (!/^\d+$/.test(String(value))) return null;
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > maximum) {
      return null;
    }
    return parsed;
  };

  const authorizeTarget = async (req, res, targetDeviceId) => {
    const actorDeviceId = normalizeId(req.deviceId);
    const target = normalizeId(targetDeviceId);
    if (!actorDeviceId) {
      res.status(401).json({
        error: "Authenticated device is required",
        code: "AUTHENTICATED_DEVICE_REQUIRED",
      });
      return null;
    }
    if (!target) {
      res.status(400).json({
        error: "Target device is required",
        code: "INVALID_DEVICE_ID",
      });
      return null;
    }

    const permission = await familyPermissionService.authorizeFeature(
      actorDeviceId,
      target,
      "CHAT"
    );
    if (!permission?.allowed) {
      res.status(403).json({
        error: "Chat access denied",
        code: permission?.code || "CHAT_ACCESS_DENIED",
      });
      return null;
    }
    return { actorDeviceId, targetDeviceId: target, permission };
  };

  const resolveLegacySender = async (authorization) => {
    const { actorDeviceId, targetDeviceId, permission } = authorization;
    if (actorDeviceId !== targetDeviceId && permission?.actorMemberId) {
      const members = await dbManager.getFamilyMembers(permission.familyId);
      const actor = (members || []).find(
        (member) => member.id === permission.actorMemberId
      );
      const role = String(actor?.role || "").toUpperCase();
      return {
        sender: role === "CHILD" ? "child" : "parent",
        senderDisplayName:
          permission.actorDisplayName || actor?.displayName || "Участник",
      };
    }

    const families = await dbManager.getFamiliesForDevice(actorDeviceId);
    for (const family of families || []) {
      const familyId = normalizeId(family?.id);
      if (!familyId) continue;
      const membership = await dbManager.getFamilyDeviceMembership(
        familyId,
        actorDeviceId
      );
      if (!membership) continue;
      const members = await dbManager.getFamilyMembers(familyId);
      const actor = (members || []).find(
        (member) => member.id === membership.memberId
      );
      const role = String(membership.memberRole || actor?.role || "").toUpperCase();
      return {
        sender: role === "CHILD" ? "child" : "parent",
        senderDisplayName: actor?.displayName || "Участник",
      };
    }

    return { sender: "child", senderDisplayName: "Участник" };
  };

  router.get("/messages/:deviceId", async (req, res) => {
    try {
      const authorization = await authorizeTarget(req, res, req.params.deviceId);
      if (!authorization) return;

      const limit = parseHistoryNumber(
        req.query.limit,
        DEFAULT_HISTORY_LIMIT,
        MAX_HISTORY_LIMIT
      );
      const offset = parseHistoryNumber(
        req.query.offset,
        0,
        Number.MAX_SAFE_INTEGER
      );
      if (limit === null || limit < 1 || offset === null) {
        return res.status(400).json({
          error: "Invalid history pagination",
          code: "INVALID_PAGINATION",
        });
      }

      const messages = await dbManager.getChatMessages(
        authorization.targetDeviceId,
        limit,
        offset
      );
      return res.json({
        success: true,
        messages: messages.map((message) => ({
          id:
            message.client_id ||
            message.client_message_id ||
            String(message.id),
          clientMessageId:
            message.client_id ||
            message.client_message_id ||
            String(message.id),
          sender: message.sender,
          message: message.message,
          timestamp: message.timestamp,
          isRead: message.is_read === 1,
          createdAt: message.created_at,
        })),
      });
    } catch (error) {
      console.error("Get chat messages error:", error);
      return res.status(500).json({
        error: "Failed to get chat messages",
        code: "CHAT_GET_ERROR",
      });
    }
  });

  router.post("/messages", async (req, res) => {
    try {
      const targetDeviceId = normalizeId(req.body?.deviceId);
      const authorization = await authorizeTarget(req, res, targetDeviceId);
      if (!authorization) return;

      const text = req.body?.message;
      if (typeof text !== "string" || !text.trim()) {
        return res.status(400).json({
          error: "Message text is required",
          code: "INVALID_MESSAGE_TEXT",
        });
      }
      if (Buffer.byteLength(text, "utf8") > MAX_CHAT_TEXT_BYTES) {
        return res.status(413).json({
          error: "Message text exceeds 16 KiB",
          code: "MESSAGE_TEXT_TOO_LARGE",
        });
      }

      const timestamp = req.body?.timestamp;
      if (
        timestamp !== undefined &&
        (!Number.isSafeInteger(timestamp) || timestamp <= 0)
      ) {
        return res.status(400).json({
          error: "Invalid message timestamp",
          code: "INVALID_MESSAGE_TIMESTAMP",
        });
      }

      // Ignore the client-provided sender field. Identity and role come only
      // from the authenticated family membership.
      const resolvedSender = await resolveLegacySender(authorization);
      const messageData = {
        sender: resolvedSender.sender,
        senderDeviceId: authorization.actorDeviceId,
        senderDisplayName: resolvedSender.senderDisplayName,
        message: text,
        timestamp: timestamp || Date.now(),
        isRead: false,
        id: req.body?.id,
      };
      const result = await dbManager.saveChatMessage(
        authorization.targetDeviceId,
        messageData
      );
      await dbManager.logActivity(authorization.targetDeviceId, {
        activity_type: "chat",
        activity_data: {
          action: "message_sent",
          sender: resolvedSender.sender,
          senderDeviceId: authorization.actorDeviceId,
          messageId: result.id,
        },
        timestamp: messageData.timestamp,
      });

      return res.json({
        success: true,
        messageId: result.id,
        message: "Message sent successfully",
      });
    } catch (error) {
      console.error("Send chat message error:", error);
      return res.status(500).json({
        error: "Failed to send message",
        code: "CHAT_SEND_ERROR",
      });
    }
  });

  router.put("/messages/:messageId/read", async (req, res) => {
    try {
      const messageId = normalizeId(req.params.messageId);
      const numericId = Number.parseInt(messageId, 10);
      const message = await dbManager.get(
        `SELECT id, device_id AS deviceId
         FROM chat_messages
         WHERE client_message_id = ? OR id = ?
         LIMIT 1`,
        [messageId, Number.isNaN(numericId) ? -1 : numericId]
      );
      if (!message) {
        return res.status(404).json({
          error: "Message not found",
          code: "CHAT_MESSAGE_NOT_FOUND",
        });
      }

      const authorization = await authorizeTarget(req, res, message.deviceId);
      if (!authorization) return;
      await dbManager.markMessageAsRead(messageId);
      return res.json({
        success: true,
        message: "Message marked as read",
      });
    } catch (error) {
      console.error("Mark message as read error:", error);
      return res.status(500).json({
        error: "Failed to mark message as read",
        code: "CHAT_READ_ERROR",
      });
    }
  });

  return router;
}

module.exports = createChatRoutes;
module.exports.MAX_CHAT_TEXT_BYTES = MAX_CHAT_TEXT_BYTES;
