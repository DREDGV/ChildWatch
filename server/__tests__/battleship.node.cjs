// Isolated SQL and HTTP checks. Run: node --test __tests__/family-games.node.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const sqlite = require('sqlite3');
const express = require('express');
const DatabaseManager = require('../database/DatabaseManager');
const FamilyGameService = require('../services/FamilyGameService');
const createRoutes = require('../routes/family-games');

async function fixture(filename = ':memory:') {
  const db = new DatabaseManager(filename);
  db.db = await new Promise((resolve,reject) => {
    const connection = new sqlite.Database(filename,error => error ? reject(error) : resolve(connection));
  });
  const members = [
    { id:'host',role:'PARENT',displayName:'Parent' },
    { id:'guest',role:'CHILD',displayName:'Child' },
    { id:'other',role:'GUARDIAN',displayName:'Other' }
  ];
  const bindings = new Map([['a','host'],['b','guest'],['b2','guest'],['c','other']]);
  const revoked = new Set();
  db.getFamilyMembers = async family => family === 'family' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(m => m.id === bindings.get(device));
    return member ? [{ familyId:'family',memberId:member.id,memberRole:member.role }] : [];
  };
  let now = 1_000_000;
  const service = new FamilyGameService(db,{ clock:() => now });
  service.access.isDeviceRevoked = async device => revoked.has(device);
  const actors = {
    host:await service.actor('a','family'),guest:await service.actor('b','family'),
    guest2:await service.actor('b2','family'),other:await service.actor('c','family')
  };
  const close = () => new Promise((resolve,reject) => db.db.close(error => error ? reject(error) : resolve()));
  return { db,service,actors,bindings,members,revoked,close,advance:ms => { now+=ms; } };
}
const FLEET=[[0,1,2,3],[20,21,22],[40,41,42],[6,16],[60,61],[80,81],[36],[56],[76],[96]];
const cmd=(game,action,extra={})=>({actionId:randomUUID(),version:game.version,action,...extra});
async function setup(f) {
 let game=(await f.service.create(f.actors.host,{gameId:randomUUID(),type:'BATTLESHIP',targetMemberId:'guest'})).game;
 return (await f.service.action(f.actors.guest,game.id,cmd(game,'ACCEPT'))).game;
}
async function play(f) {
 let game=await setup(f);
 game=(await f.service.action(f.actors.host,game.id,cmd(game,'PLACE_FLEET',{ships:FLEET}))).game;
 return (await f.service.action(f.actors.guest,game.id,cmd(game,'PLACE_FLEET',{ships:FLEET}))).game;
}
test('classic fleet rejects diagonal contact, duplicates, bends and wrapping',()=>{
 const rules=require('../services/BattleshipRules');
 assert.ok(rules.fleet(FLEET));
 for (const replacement of [[9,10,11,12],[0,1,11,12],[0,1,2,2]]) assert.equal(rules.fleet([replacement,...FLEET.slice(1)]),null);
 const diagonal=FLEET.map(s=>[...s]);diagonal[6]=[27];assert.equal(rules.fleet(diagonal),null);
});
test('consented setup hides fleets from get/list/replay and persists actions',async()=>{
 const f=await fixture();try {
 let game=await setup(f);assert.equal(game.status,'SETUP');
 const input=cmd(game,'PLACE_FLEET',{ships:FLEET});
 game=(await f.service.action(f.actors.host,game.id,input)).game;
 assert.deepEqual(game.battleship.myShips,FLEET);assert.equal(game.battleship.opponentShips,null);
 const guest=await f.service.get(f.actors.guest,game.id);
 assert.equal(guest.board,null);assert.equal(guest.battleship.opponentShips,null);assert.equal(guest.battleship.opponentReady,true);
 assert.equal((await f.service.list(f.actors.guest))[0].battleship.opponentShips,null);
 assert.equal((await f.service.action(f.actors.host,game.id,input)).replayed,true);
 await assert.rejects(f.service.action(f.actors.host,game.id,cmd(game,'PLACE_FLEET',{ships:FLEET})),{code:'GAME_ACTION_NOT_ALLOWED'});
 await assert.rejects(f.service.get(f.actors.other,game.id),{code:'GAME_NOT_FOUND'});
 const restarted=new FamilyGameService(f.db,{clock:()=>1_000_000});restarted.access=f.service.access;
 assert.deepEqual((await restarted.get(f.actors.host,game.id)).battleship.myShips,FLEET);
 f.advance(86400001);assert.equal((await f.service.get(f.actors.host,game.id)).status,'EXPIRED');
 }finally{await f.close();}
});
test('shots preserve turn on hit, miss switches, duplicates/version fail and sinking all wins',async()=>{
 const f=await fixture();try {
 let game=await play(f);assert.equal(game.status,'PLAYING');
 await assert.rejects(f.service.action(f.actors.guest,game.id,cmd(game,'FIRE',{cell:0})),{code:'GAME_NOT_YOUR_TURN'});
 const shot=cmd(game,'FIRE',{cell:0});game=(await f.service.action(f.actors.host,game.id,shot)).game;
 assert.equal(game.battleship.myShots[0].result,'HIT');assert.equal(game.battleship.nextMemberId,'host');
 assert.equal((await f.service.action(f.actors.host,game.id,shot)).replayed,true);
 await assert.rejects(f.service.action(f.actors.host,game.id,cmd(game,'FIRE',{cell:0})),{code:'GAME_CELL_OCCUPIED'});
 await assert.rejects(f.service.action(f.actors.host,game.id,{...cmd(game,'FIRE',{cell:1}),version:1}),{code:'GAME_VERSION_CONFLICT'});
 for(const cell of FLEET.flat().slice(1)) game=(await f.service.action(f.actors.host,game.id,cmd(game,'FIRE',{cell}))).game;
 assert.equal(game.status,'WON');assert.equal(game.winnerMemberId,'host');assert.equal(game.battleship.opponentSunkShips.length,10);
 assert.deepEqual(game.battleship.opponentShips,FLEET);
 game=await play(f);game=(await f.service.action(f.actors.host,game.id,cmd(game,'FIRE',{cell:99}))).game;
 assert.equal(game.battleship.myShots[0].result,'MISS');assert.equal(game.battleship.nextMemberId,'guest');
 }finally{await f.close();}
});
test('additive migration preserves old board and typed idempotent invitations',async()=>{
 const f=await fixture();try {
 await f.db.run(`CREATE TABLE family_games(id TEXT PRIMARY KEY,family_id TEXT,host_member_id TEXT,guest_member_id TEXT,status TEXT,board TEXT,next_mark TEXT,winner_member_id TEXT,winning_line TEXT,version INTEGER,created_at INTEGER,updated_at INTEGER,expires_at INTEGER)`);
 const id=randomUUID();await f.db.run(`INSERT INTO family_games VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,[id,'family','host','guest','PLAYING',JSON.stringify(['X',null,null,null,null,null,null,null,null]),'O',null,null,3,1,1,999999999]);
 const game=await f.service.get(f.actors.host,id);assert.equal(game.type,'TIC_TAC_TOE');assert.equal(game.board[0],'X');assert.equal(game.version,3);
 await assert.rejects(f.service.create(f.actors.host,{gameId:id,targetMemberId:'guest',type:'BATTLESHIP'}),{code:'GAME_ID_REUSED'});
 const naval=await setup(f);const resigned=(await f.service.action(f.actors.host,naval.id,cmd(naval,'RESIGN'))).game;assert.equal(resigned.status,'RESIGNED');
 }finally{await f.close();}
});
test('fleet and shot transactions roll back journal failure; simultaneous shots serialize',async()=>{
 const f=await fixture();try {
 let game=await setup(f);
 const run=f.db.run.bind(f.db);f.db.run=async(sql,args)=>{if(sql.startsWith('INSERT INTO family_game_actions'))throw Error('journal');return run(sql,args);};
 await assert.rejects(f.service.action(f.actors.host,game.id,cmd(game,'PLACE_FLEET',{ships:FLEET})),/journal/);
 f.db.run=run;assert.equal((await f.service.get(f.actors.host,game.id)).battleship.myReady,false);
 game=await play(f);
 const results=await Promise.allSettled([0,1].map(cell=>f.service.action(f.actors.host,game.id,cmd(game,'FIRE',{cell}))));
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 assert.equal(results.find(r=>r.status==='rejected').reason.code,'GAME_VERSION_CONFLICT');
 const latest=await f.service.get(f.actors.host,game.id);assert.equal(latest.battleship.myShots.length,1);
 f.revoked.add('a');await assert.rejects(f.service.get(f.actors.host,game.id),{code:'GAME_ACCESS_DENIED'});
 }finally{await f.close();}
});
