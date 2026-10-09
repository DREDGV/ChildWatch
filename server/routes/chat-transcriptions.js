const express = require('express');
const multer = require('multer');
const fs = require('fs');
const crypto = require('crypto');
const Engine = require('../services/ChatTranscriptionRunner');
module.exports = function createChatTranscriptionRoutes(chat, handleError) {
  const router = express.Router(), service = chat.transcriptions;
  service.start();
  const upload = multer({ storage: multer.diskStorage({ destination: service.temp,
    filename: (req, file, done) => done(null, crypto.randomUUID() + '.part') }),
  limits: { fileSize: Engine.MAX_INPUT_BYTES, files: 1, fields: 3, fieldSize: 512 } }).single('file');
  router.post('/conversations/:id/transcriptions', async (req, res) => {
    try {
      await chat.resolveConversationActor(req.deviceId, req.params.id);
      const capability = await service.capability();
      if (!capability.transcription) throw new Engine.TranscriptionError(503, capability.transcriptionReason);
    } catch (error) { return handleError(res, error); }
    upload(req, res, async error => {
      if (error) {
        if (req.file) await fs.promises.rm(req.file.path, { force: true }).catch(() => {});
        return res.status(error.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ success: false,
          code: error.code === 'LIMIT_FILE_SIZE' ? 'TRANSCRIPTION_AUDIO_TOO_LARGE' : 'INVALID_MULTIPART', error: 'Audio upload rejected' });
      }
      try {
        const actor = await chat.resolveConversationActor(req.deviceId, req.params.id);
        const result = await service.create(actor, req.file, req.body || {});
        res.status(result.created ? 201 : 200).set('Cache-Control', 'private, no-store').json({ success: true, job: result.job });
      } catch (failure) {
        if (req.file) await fs.promises.rm(req.file.path, { force: true }).catch(() => {});
        handleError(res, failure);
      }
    });
  });
  router.get('/conversations/:id/transcriptions/by-client/:clientRequestId', async (req, res) => {
    try { res.set('Cache-Control', 'private, no-store').json({ success: true,
      job: await service.read(req.deviceId, req.params.id, req.params.clientRequestId) }); }
    catch (error) { handleError(res, error); }
  });
  router.delete('/conversations/:id/transcriptions/by-client/:clientRequestId', async (req, res) => {
    try { res.set('Cache-Control', 'private, no-store').json({ success: true,
      job: await service.cancel(req.deviceId, req.params.id, req.params.clientRequestId) }); }
    catch (error) { handleError(res, error); }
  });
  return router;
};
