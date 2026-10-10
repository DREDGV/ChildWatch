const express = require('express');
const FamilyGameService = require('../services/FamilyGameService');

module.exports = function createFamilyGameRoutes(db, service = new FamilyGameService(db)) {
  const router = express.Router();
  router.use(async (req,res,next) => {
    res.set('Cache-Control','no-store');
    try {
      if (!req.deviceId) return res.status(401).json({ success:false,code:'GAME_AUTH_REQUIRED' });
      req.gameActor = await service.actor(req.deviceId,req.query.familyId);
      if (req.query.actorMemberId !== req.gameActor.memberId)
        throw new FamilyGameService.Error(403,'GAME_CONTEXT_CHANGED');
      next();
    } catch (error) { next(error); }
  });
  router.get('/capabilities',(req,res) => res.json({ success:true, protocolVersion:1,
    games:['TIC_TAC_TOE'], invitationTtlSeconds:900, gameTtlSeconds:86400 }));
  router.get('/',async(req,res,next) => {
    try { res.json({ success:true,games:await service.list(req.gameActor) }); } catch(error) { next(error); }
  });
  router.post('/',async(req,res,next) => {
    try {
      const result = await service.create(req.gameActor,req.body || {});
      res.status(result.replayed ? 200 : 201).json({ success:true,...result });
    } catch(error) { next(error); }
  });
  router.get('/:id',async(req,res,next) => {
    try { res.json({ success:true,game:await service.get(req.gameActor,req.params.id) }); } catch(error) { next(error); }
  });
  router.post('/:id/actions',async(req,res,next) => {
    try { res.json({ success:true,...await service.action(req.gameActor,req.params.id,req.body || {}) }); }
    catch(error) { next(error); }
  });
  router.use((error,req,res,next) => {
    if (!(error instanceof FamilyGameService.Error)) console.error('Family game request failed',error.message);
    res.status(error instanceof FamilyGameService.Error ? error.status : 500)
      .json({ success:false,code:error instanceof FamilyGameService.Error ? error.code : 'GAME_UNAVAILABLE' });
  });
  return router;
};
