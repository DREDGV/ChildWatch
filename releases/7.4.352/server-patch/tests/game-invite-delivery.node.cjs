const { test } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const Delivery = require('../services/FamilyGameInviteDelivery');
const createRoutes = require('../routes/family-games');

const game = { id:'00000000-0000-0000-0000-000000000001', familyId:'family',
  host:{memberId:'host'},guest:{memberId:'guest'} };
function socket(device,mode='authenticated',connected=true) {
  return {authenticatedDeviceId:device,authMode:mode,connected,events:[],emit(event,payload){this.events.push({event,payload});}};
}
test('wakeups reach only fresh authenticated host and guest devices, without private game data', async () => {
  const sockets=[socket('a'),socket('b'),socket('b'),socket('c'),socket('revoked'),socket('a','legacy'),socket('a','authenticated',false)];
  let queries=0;
  const service={async actor(device,family){queries++;assert.equal(family,'family');
    if(device==='revoked')throw new Error('revoked');return {memberId:{a:'host',b:'guest',c:'other'}[device]};}};
  await new Delivery({sockets:{sockets:new Map(sockets.map((s,i)=>[i,s]))}},service).changed(game);
  assert.equal(queries,4);
  for(const s of sockets.slice(0,3))assert.deepEqual(s.events,[{event:'family_games:changed',payload:{familyId:'family'}}]);
  for(const s of sockets.slice(3))assert.deepEqual(s.events,[]);
});
test('disconnect while identity is checked is respected', async () => {
  const s=socket('b');
  await new Delivery({sockets:{sockets:new Map([['socket',s]])}}, {async actor(){s.connected=false;return {memberId:'guest'};}}).changed(game);
  assert.deepEqual(s.events,[]);
});
test('HTTP commit success survives delivery failure; retries do not generate new wakeups',async () => {
  let replayed=false,notifications=0;
  const service={clock:()=>1234,actor:async()=>({memberId:'host'}),list:async()=>[],
    create:async()=>({replayed,game}),action:async()=>({replayed,game})};
  const app=express();app.use(express.json());app.use((req,res,next)=>{req.deviceId='a';next();});
  app.use('/games',createRoutes(null,service,{notifyChanged:async()=>{notifications++;throw new Error('delivery offline');}}));
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
  try {
    const url=`http://127.0.0.1:${server.address().port}/games`,query='?familyId=family&actorMemberId=host';
    async function write(path){return fetch(url+path+query,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});}
    let result=await write('/');assert.equal(result.status,201);assert.equal((await result.json()).success,true);
    replayed=true;assert.equal((await write('/')).status,200);assert.equal(notifications,1);
    replayed=false;assert.equal((await write('/'+game.id+'/actions')).status,200);assert.equal(notifications,2);
    replayed=true;assert.equal((await write('/'+game.id+'/actions')).status,200);assert.equal(notifications,2);
    assert.equal((await (await fetch(url+'/'+query)).json()).serverTime,1234);
  } finally {await new Promise(resolve=>server.close(resolve));}
});
