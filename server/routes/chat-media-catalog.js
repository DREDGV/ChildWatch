const express = require('express');
module.exports = function createChatMediaCatalogRoutes(chat, handleError) {
  const router = express.Router();
  router.get('/conversations/:id/media-catalog', async (req, res) => {
    try {
      const actor = await chat.resolveConversationActor(req.deviceId, req.params.id);
      const catalog = await chat.mediaCatalog.list(actor);
      res.set({ 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' }).json({ success: true, ...catalog });
    } catch (error) { handleError(res, error); }
  });
  router.get('/conversations/:id/media-catalog/:version/:itemId/content', async (req, res) => {
    try {
      const actor = await chat.resolveConversationActor(req.deviceId, req.params.id);
      const { item, bytes } = await chat.mediaCatalog.content(actor, req.params.version, req.params.itemId);
      res.set({ 'Content-Type': item.mimeType, 'Content-Length': String(bytes.length), 'Cache-Control': 'private, no-store',
        'X-Content-Type-Options': 'nosniff', 'X-Content-SHA256': item.sha256, 'Content-Disposition': `inline; filename="${item.filename}"` }).send(bytes);
    } catch (error) { handleError(res, error); }
  });
  return router;
};
