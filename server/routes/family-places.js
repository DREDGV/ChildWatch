const express = require('express');
const FamilyPlacesService = require('../services/FamilyPlacesService');
const router = express.Router();
let service;
router.init = db => { service = FamilyPlacesService.forDatabase(db); };
router.use(async (req, res, next) => {
  try {
    if (!service) return res.status(503).json({ success: false, error: 'Places unavailable' });
    if (!req.deviceId) return res.status(401).json({ success: false, error: 'Authentication required' });
    const familyId = String(req.query.familyId || '').trim();
    if (!familyId) return res.status(400).json({ success: false, error: 'Family required' });
    req.placeActor = await service.actor(req.deviceId, familyId);
    if (!req.placeActor || !['PARENT', 'GUARDIAN'].includes(req.placeActor.memberRole))
      return res.status(403).json({ success: false, error: 'Adult family membership required' });
    if (req.query.actorMemberId && req.query.actorMemberId !== req.placeActor.memberId)
      return res.status(403).json({ success: false, error: 'Place context changed' });
    next();
  } catch (error) { next(error); }
});
router.get('/', async (req, res, next) => {
  try { res.json({ success: true, places: await service.list(req.placeActor, String(req.query.targetMemberId || '')) }); }
  catch (error) { next(error); }
});
router.get('/events', async (req, res, next) => {
  try {
    const after = Number(req.query.after || 0);
    if (!Number.isSafeInteger(after) || after < 0) return res.status(400).json({ success: false, error: 'Invalid cursor' });
    res.json({ success: true, ...await service.events(req.placeActor, after) });
  } catch (error) { next(error); }
});
router.get('/history', async (req, res, next) => {
  try {
    const integerQuery = name => {
      const value = req.query[name];
      if (value === undefined) return undefined;
      if (typeof value !== 'string' || !/^\d+$/.test(value))
        throw Object.assign(new Error(`Invalid history ${name}`), { status: 400 });
      return Number(value);
    };
    if (typeof req.query.targetMemberId !== 'string' || !req.query.targetMemberId.trim())
      return res.status(400).json({ success: false, error: 'Target member required' });
    res.json(await service.history(req.placeActor, req.query.targetMemberId, {
      before: integerQuery('before'), snapshot: integerQuery('snapshot'), limit: integerQuery('limit')
    }));
  } catch (error) { next(error); }
});
router.post('/', async (req, res, next) => {
  try { res.status(201).json({ success: true, id: await service.create(req.placeActor, req.body) }); }
  catch (error) { next(error); }
});
router.patch('/:id', async (req, res, next) => {
  try {
    if (Object.prototype.hasOwnProperty.call(req.body, 'enabled')) {
      if (typeof req.body.enabled !== 'boolean') return res.status(400).json({ success: false, error: 'Enabled boolean required' });
      await service.toggle(req.placeActor, req.params.id, req.body.enabled);
    } else await service.update(req.placeActor, req.params.id, req.body);
    res.json({ success: true });
  } catch (error) { next(error); }
});
router.delete('/:id', async (req, res, next) => {
  try { await service.remove(req.placeActor, req.params.id); res.json({ success: true }); }
  catch (error) { next(error); }
});
router.use((error, req, res, next) => {
  console.error('Family places request failed', error.message);
  res.status(error.status || 500).json({ success: false, error: error.status ? error.message : 'Places request failed' });
});
module.exports = router;
