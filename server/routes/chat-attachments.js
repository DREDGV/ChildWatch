const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Store = require('../services/ChatAttachmentStore');
module.exports = function createChatAttachmentRoutes(chatService, handleError) {
  const router = express.Router();
  const store = chatService.attachments;
  store.startGc();
  const upload = multer({
    storage: multer.diskStorage({
      destination: store.temp,
      filename: (req, file, done) => done(null, crypto.randomUUID() + '.part')
    }),
    limits: {
      fileSize: Store.FILE_LIMIT,
      files: 1,
      fields: 4,
      fieldSize: 512
    }
  }).single('file');
  router.get('/capabilities', async (req, res) => {
    try {
      await chatService.resolveDeviceMemberships(req.deviceId);
      await store.ensure();
      res.json({
        success: true,
        attachments: true,
        maxFileBytes: Store.FILE_LIMIT,
        maxImageBytes: Store.IMAGE_LIMIT,
        attachmentTypes: Store.TYPES,
        ...await chatService.transcriptions.capability()
      });
    } catch (error) {
      handleError(res, error);
    }
  });
  router.post('/conversations/:id/attachments', async (req, res) => {
    try {
      await chatService.resolveConversationActor(req.deviceId, req.params.id);
    } catch (error) {
      return handleError(res, error);
    }
    upload(req, res, async error => {
      if (error) {
        if (req.file) await fs.promises.rm(req.file.path, {
          force: true
        });
        return res.status(error.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({
          code: error.code === 'LIMIT_FILE_SIZE' ? 'ATTACHMENT_TOO_LARGE' : 'INVALID_MULTIPART',
          error: 'Attachment upload rejected'
        });
      }
      try {
        // Permission may have changed during a slow upload.
        const actor = await chatService.resolveConversationActor(req.deviceId, req.params.id);
          const attachment = await store.stage(actor, req.file, req.body || {});
        res.status(201).json({
          success: true,
          attachment
        });
      } catch (failure) {
        if (req.file) await fs.promises.rm(req.file.path, {
          force: true
        });
        handleError(res, failure);
      }
    });
  });
  router.get('/conversations/:id/attachments/:attachmentId/content', async (req, res) => {
    try {
      const actor = await chatService.resolveConversationActor(req.deviceId, req.params.id);
      const row = await store.readable(actor, req.params.attachmentId);
      const filename = store.blob(row.id);
      const stat = await fs.promises.stat(filename);
      if (stat.size !== row.size_bytes) throw new Store.Error(410, 'ATTACHMENT_UNAVAILABLE', 'Stored attachment unavailable');
      res.set({
        'Content-Type': row.mime_type,
        'Content-Length': String(stat.size),
        'Cache-Control': 'private, no-store',
        'X-Content-Type-Options': 'nosniff',
        'Content-Disposition': `${row.type === 'FILE' ? 'attachment' : 'inline'}; filename*=UTF-8''${encodeURIComponent(row.filename).replace(/['()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase())}`,
        'X-Content-SHA256': row.sha256
      });
      const stream = fs.createReadStream(filename);
      stream.on('error', () => res.destroy());
      res.on('close', () => stream.destroy());
      stream.pipe(res);
    } catch (error) {
      if (error.code === 'ENOENT') return res.status(410).json({
        code: 'ATTACHMENT_UNAVAILABLE',
        error: 'Stored attachment unavailable'
      });
      handleError(res, error);
    }
  });
  router.delete('/conversations/:id/attachments/:attachmentId', async (req, res) => {
    try {
      const actor = await chatService.resolveConversationActor(req.deviceId, req.params.id);
      await store.cancel(actor, req.params.attachmentId);
      res.json({
        success: true
      });
    } catch (error) {
      handleError(res, error);
    }
  });
  return router;
};
