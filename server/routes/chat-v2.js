const express = require("express");
const ChatConversationService = require("../services/ChatConversationService");

function createChatV2Routes(
  dbManager,
  chatService = new ChatConversationService(dbManager)
) {
  if (!dbManager || !chatService) {
    throw new Error("Chat v2 routes require database and chat services");
  }

  const router = express.Router();

  const handleError = (res, error) => {
    if (error instanceof ChatConversationService.Error || error instanceof require("../services/ChatAttachmentStore").Error || error instanceof require("../services/ChatTranscriptionService").Error || error instanceof require("../services/ChatMediaCatalog").Error) {
      return res.status(error.status).json({
        error: error.message,
        code: error.code,
      });
    }
    console.error("Chat v2 request failed:", error);
    return res.status(500).json({
      error: "Chat request failed",
      code: "CHAT_INTERNAL_ERROR",
    });
  };

  router.use(require("./chat-attachments")(chatService, handleError));
  router.use(require("./chat-transcriptions")(chatService, handleError));
  router.use(require("./chat-media-catalog")(chatService, handleError));

  router.get("/conversations", async (req, res) => {
    try {
      const result = await chatService.listConversations(req.deviceId);
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  router.post("/conversations/direct", async (req, res) => {
    try {
      const result = await chatService.createDirectConversation(
        req.deviceId,
        req.body?.targetMemberId
      );
      res.status(result.created ? 201 : 200).json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  /**
   * Creates a group with a chosen name and membership.
   *
   * The caller is always in it and becomes its administrator, and everybody named
   * must belong to the caller's family: a group is a conversation inside one family.
   */
  router.post("/conversations/group", async (req, res) => {
    try {
      const result = await chatService.createGroup(req.deviceId, req.body);
      res.status(result.created ? 201 : 200).json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  router.get("/conversations/:id/messages", async (req, res) => {
    try {
      const result = await chatService.getMessages(req.deviceId, req.params.id, {
        beforeSequence: req.query.beforeSequence,
        limit: req.query.limit,
      });
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  router.post("/conversations/:id/messages", async (req, res) => {
    try {
      const result = await chatService.sendMessage(
        req.deviceId,
        req.params.id,
        req.body
      );
      res.status(result.created ? 201 : 200).json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Rewrites the caller's own recent message. Only the author may do this.
  router.patch("/conversations/:id/messages/:messageId", async (req, res) => {
    try {
      const result = await chatService.editMessage(
        req.deviceId,
        req.params.id,
        req.params.messageId,
        req.body
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  /**
   * Removes a message.
   *
   * `forEveryone=true` withdraws it for all participants and is limited to the
   * author; without it the message is hidden for this device only, which is what
   * "delete for me" means.
   */
  router.delete("/conversations/:id/messages/:messageId", async (req, res) => {
    try {
      const forEveryone =
        String(req.query.forEveryone || "").toLowerCase() === "true";
      const result = await chatService.deleteMessage(
        req.deviceId,
        req.params.id,
        req.params.messageId,
        { forEveryone }
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  /**
   * Shared settings of a group chat.
   *
   * A group is shared, so its name and picture come from the family and are the
   * same for every participant. `canManage` tells the client whether to offer the
   * editing actions at all.
   */
  router.get("/conversations/:id/group", async (req, res) => {
    try {
      const result = await chatService.getGroupSettings(
        req.deviceId,
        req.params.id
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Renames the group for everyone. Only the administrator may do this.
  router.patch("/conversations/:id/group", async (req, res) => {
    try {
      const result = await chatService.updateGroupTitle(
        req.deviceId,
        req.params.id,
        req.body
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Sets the shared picture of the group. Only the administrator may do this.
  router.put("/conversations/:id/group/avatar", async (req, res) => {
    try {
      const result = await chatService.updateGroupAvatar(
        req.deviceId,
        req.params.id,
        req.body
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  router.post("/conversations/:id/receipts", async (req, res) => {    try {
      const result = await chatService.advanceReceipt(
        req.deviceId,
        req.params.id,
        req.body
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Adds people to a group. Only its administrator may.
  router.post("/conversations/:id/group/members", async (req, res) => {
    try {
      const result = await chatService.addGroupMembers(
        req.deviceId,
        req.params.id,
        req.body
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Takes one person out of a group. Only its administrator may.
  router.delete("/conversations/:id/group/members/:memberId", async (req, res) => {
    try {
      const result = await chatService.removeGroupMember(
        req.deviceId,
        req.params.id,
        req.params.memberId
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  /**
   * Leaves the group.
   *
   * Any member may leave; the administrator is told to hand the group over or close
   * it instead, because a group whose administrator walked away would have a name
   * nobody can change and a membership nobody can change either.
   */
  router.post("/conversations/:id/group/leave", async (req, res) => {
    try {
      const result = await chatService.leaveGroup(req.deviceId, req.params.id);
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Hands administration to another member of the group. Only the administrator may.
  router.post("/conversations/:id/group/admin", async (req, res) => {
    try {
      const result = await chatService.transferGroupAdmin(
        req.deviceId,
        req.params.id,
        req.body?.memberId
      );
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  // Closes the group for everybody. Only the administrator may.
  router.delete("/conversations/:id/group", async (req, res) => {
    try {
      const result = await chatService.closeGroup(req.deviceId, req.params.id);
      res.json({ success: true, ...result });
    } catch (error) {
      handleError(res, error);
    }
  });

  return router;
}

module.exports = createChatV2Routes;
