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
    if (error instanceof ChatConversationService.Error) {
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

  router.post("/conversations/:id/receipts", async (req, res) => {
    try {
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

  return router;
}

module.exports = createChatV2Routes;
