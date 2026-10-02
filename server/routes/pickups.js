const router=require('express').Router();
const FamilyPickupService=require('../services/FamilyPickupService');
let service;
router.init=db=>{service=new FamilyPickupService(db);};
router.use(async(req,res,next)=>{
  try {
    if (!service) return res.status(503).json({success:false,code:'PICKUP_UNAVAILABLE'});
    if (!req.deviceId) return res.status(401).json({success:false,code:'PICKUP_AUTH_REQUIRED'});
    req.pickupActor=await service.actor(req.deviceId,String(req.query.familyId||'').trim());
    if (!req.query.actorMemberId || String(req.query.actorMemberId)!==req.pickupActor.memberId)
      return res.status(403).json({success:false,code:'PICKUP_CONTEXT_CHANGED'});
    next();
  } catch(error){next(error);}
});
router.get('/',async(req,res,next)=>{try{res.json({success:true,...await service.list(req.pickupActor)});}catch(error){next(error);}});
router.post('/',async(req,res,next)=>{try{res.status(201).json({success:true,request:await service.create(req.pickupActor,req.body||{})});}catch(error){next(error);}});
router.post('/:id/actions',async(req,res,next)=>{try{res.json({success:true,request:await service.action(req.pickupActor,req.params.id,req.body||{})});}catch(error){next(error);}});
router.use((error,req,res,next)=>{
  if (!error.status) console.error('Pickup request failed',error.message);
  res.status(error.status||500).json({success:false,code:error.code && error.status ? error.code:'PICKUP_UNAVAILABLE'});
});
module.exports=router;
