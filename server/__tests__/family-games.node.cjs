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
const invite = () => ({ gameId:randomUUID(),targetMemberId:'guest',type:'TIC_TAC_TOE' });
const command = (game,action,cell) => ({ actionId:randomUUID(),version:game.version,action,...(cell === undefined ? {} : {cell}) });
async function playing(f) {
  let result = await f.service.create(f.actors.host,invite());
  result = await f.service.action(f.actors.guest,result.game.id,command(result.game,'ACCEPT'));
  return result.game;
}
async function move(f,game,cell) {
  return (await f.service.action(game.nextMark === 'X' ? f.actors.host : f.actors.guest,game.id,command(game,'MOVE',cell))).game;
}

test('invitation is consented, retryable, and member-bound across devices',async () => {
  const f = await fixture();
  try {
    const input = invite(), initial = await f.service.create(f.actors.host,input);
    assert.equal(initial.game.status,'INVITED'); assert.equal(initial.game.nextMark,null);
    assert.equal((await f.service.create(f.actors.host,input)).replayed,true);
    await assert.rejects(f.service.create(f.actors.host,{...input,targetMemberId:'other'}),{code:'GAME_ID_REUSED'});
    await assert.rejects(f.service.create(f.actors.host,{...invite(),targetMemberId:'host'}),{code:'GAME_INVALID_OPPONENT'});
    await assert.rejects(f.service.create(f.actors.host,{...invite(),targetMemberId:'stranger'}),{code:'GAME_INVALID_OPPONENT'});
    await assert.rejects(f.service.action(f.actors.host,input.gameId,command(initial.game,'ACCEPT')),{code:'GAME_ACTION_NOT_ALLOWED'});
    await assert.rejects(f.service.action(f.actors.host,input.gameId,command(initial.game,'MOVE',0)),{code:'GAME_ACTION_NOT_ALLOWED'});
    const accepted = await f.service.action(f.actors.guest2,input.gameId,command(initial.game,'ACCEPT'));
    assert.equal(accepted.game.status,'PLAYING'); assert.equal(accepted.game.myMark,'O');
    assert.equal((await f.service.get(f.actors.guest,input.gameId)).version,2);
  } finally { await f.close(); }
});

test('all eight winning lines end the game and prohibit more moves',async () => {
  const f = await fixture();
  try {
    for (const line of [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]]) {
      let game = await playing(f);
      const fillers = Array.from({length:9},(_,i) => i).filter(i => !line.includes(i));
      for (let i=0;i<3;i++) {
        game = await move(f,game,line[i]);
        if (i<2) game = await move(f,game,fillers[i]);
      }
      assert.equal(game.status,'WON'); assert.equal(game.winnerMemberId,'host');
      assert.deepEqual(game.winningLine,line); assert.equal(game.nextMark,null);
      await assert.rejects(f.service.action(f.actors.guest,game.id,command(game,'MOVE',fillers[2])),{code:'GAME_CLOSED'});
    }
  } finally { await f.close(); }
});

test('O can win, full non-winning board draws, and either player can resign',async () => {
  const f = await fixture();
  try {
    let game = await playing(f);
    for (const cell of [0,3,1,4,8,5]) game = await move(f,game,cell);
    assert.equal(game.winnerMemberId,'guest'); assert.equal(game.status,'WON');
    game = await playing(f);
    for (const cell of [0,1,2,4,3,5,7,6,8]) game = await move(f,game,cell);
    assert.equal(game.status,'DRAW'); assert.equal(game.winnerMemberId,null);
    for (const actor of [f.actors.host,f.actors.guest]) {
      game = await playing(f);
      game = (await f.service.action(actor,game.id,command(game,'RESIGN'))).game;
      assert.equal(game.status,'RESIGNED'); assert.equal(game.winnerMemberId,actor.memberId === 'host' ? 'guest' : 'host');
    }
  } finally { await f.close(); }
});

test('invalid/occupied cells and out-of-turn actions never change the board',async () => {
  const f = await fixture();
  try {
    let game = await playing(f);
    for (const cell of [-1,9,1.5,'0',null])
      await assert.rejects(f.service.action(f.actors.host,game.id,command(game,'MOVE',cell)),{code:'GAME_INVALID_ACTION'});
    await assert.rejects(f.service.action(f.actors.guest,game.id,command(game,'MOVE',0)),{code:'GAME_NOT_YOUR_TURN'});
    assert.deepEqual((await f.service.get(f.actors.host,game.id)).board,Array(9).fill(null));
    game = await move(f,game,0);
    await assert.rejects(f.service.action(f.actors.guest,game.id,command(game,'MOVE',0)),{code:'GAME_CELL_OCCUPIED'});
    assert.equal((await f.service.get(f.actors.host,game.id)).version,game.version);
  } finally { await f.close(); }
});

test('simultaneous moves serialize, retried commands replay without undoing newer moves',async () => {
  const f = await fixture();
  try {
    let game = await playing(f);
    const inputs = [command(game,'MOVE',0),command(game,'MOVE',1)];
    const results = await Promise.allSettled(inputs.map(input => f.service.action(f.actors.host,game.id,input)));
    assert.equal(results.filter(r => r.status === 'fulfilled').length,1);
    const index = results.findIndex(r => r.status === 'fulfilled');
    game = results[index].value.game;
    assert.equal(game.board.filter(Boolean).length,1);
    game = await move(f,game,8);
    const retry = await f.service.action(f.actors.host,game.id,inputs[index]);
    assert.equal(retry.replayed,true); assert.equal(retry.game.version,game.version);
    assert.deepEqual(retry.game.board,game.board);
    await assert.rejects(f.service.action(f.actors.host,game.id,{...inputs[index],cell:2}),{code:'GAME_ACTION_ID_REUSED'});
    await assert.rejects(f.service.action(f.actors.host,game.id,{...command(game,'MOVE',2),version:2}),{code:'GAME_VERSION_CONFLICT'});
  } finally { await f.close(); }
});

test('action journal failure rolls back the move atomically',async () => {
  const f = await fixture();
  try {
    const accepted = await playing(f);
    const game = await f.service.get(f.actors.host,accepted.id);
    const input = command(game,'MOVE',0), run = f.db.run.bind(f.db);
    f.db.run = async (sql,args) => {
      if (sql.startsWith('INSERT INTO family_game_actions')) throw new Error('isolated injected write failure');
      return run(sql,args);
    };
    await assert.rejects(f.service.action(f.actors.host,game.id,input),/isolated injected/);
    f.db.run = run;
    assert.deepEqual(await f.service.get(f.actors.host,game.id),game);
    assert.equal((await f.service.action(f.actors.host,game.id,input)).replayed,false);
  } finally { await f.close(); }
});

test('decline, cancel, expiration and invitation cap have explicit states',async () => {
  const f = await fixture();
  try {
    let game = (await f.service.create(f.actors.host,invite())).game;
    assert.equal((await f.service.action(f.actors.guest,game.id,command(game,'DECLINE'))).game.status,'DECLINED');
    game = (await f.service.create(f.actors.host,invite())).game;
    assert.equal((await f.service.action(f.actors.host,game.id,command(game,'CANCEL'))).game.status,'CANCELLED');
    for (let i=0;i<5;i++) await f.service.create(f.actors.host,invite());
    await assert.rejects(f.service.create(f.actors.host,invite()),{code:'GAME_INVITATION_LIMIT'});
    f.advance(15*60_000);
    assert.equal((await f.service.list(f.actors.guest)).filter(g => g.status === 'INVITED').length,0);
    game = await playing(f); f.advance(24*60*60_000);
    assert.equal((await f.service.get(f.actors.host,game.id)).status,'EXPIRED');
    await assert.rejects(f.service.action(f.actors.host,game.id,command(game,'MOVE',0)),{code:'GAME_CLOSED'});
  } finally { await f.close(); }
});

test('third-party, revoked device, changed identity and unavailable participant are denied',async () => {
  const f = await fixture();
  try {
    const game = await playing(f);
    assert.deepEqual(await f.service.list(f.actors.other),[]);
    await assert.rejects(f.service.get(f.actors.other,game.id),{status:404});
    await assert.rejects(f.service.actor('a','foreign-family'),{status:403});
    f.revoked.add('a'); await assert.rejects(f.service.get(f.actors.host,game.id),{status:403}); f.revoked.clear();
    f.bindings.set('a','other'); await assert.rejects(f.service.get(f.actors.host,game.id),{code:'GAME_CONTEXT_CHANGED'});
    f.bindings.set('a','host'); f.members.splice(1,1);
    await assert.rejects(f.service.get(f.actors.host,game.id),{code:'GAME_PARTICIPANT_UNAVAILABLE'});
  } finally { await f.close(); }
});

test('disk database restores the same party and deduplicates actions after restart',async () => {
  const dir = path.resolve(__dirname,'../../.runtime/game-tests'); fs.mkdirSync(dir,{recursive:true});
  const filename = path.join(dir,`isolated-${randomUUID()}.db`);
  let f = await fixture(filename);
  try {
    const game = await playing(f), input = command(game,'MOVE',4);
    const expected = (await f.service.action(f.actors.host,game.id,input)).game;
    await f.close(); f = await fixture(filename);
    assert.deepEqual(await f.service.get(f.actors.host,game.id),expected);
    assert.equal((await f.service.action(f.actors.host,game.id,input)).replayed,true);
  } finally {
    await f.close();
    for (const suffix of ['', '-wal', '-shm']) if (fs.existsSync(filename+suffix)) fs.unlinkSync(filename+suffix);
  }
});

test('HTTP routes require actor context and keep rooms private',async () => {
  const f = await fixture(), app = express(); app.use(express.json());
  app.use((req,res,next) => { req.deviceId=req.headers['x-fixture-device']; next(); });
  app.use('/games',createRoutes(f.db,f.service));
  const server = await new Promise(resolve => { const s=app.listen(0,'127.0.0.1',() => resolve(s)); });
  const base = `http://127.0.0.1:${server.address().port}/games`;
  async function request(device,member,route='',body,method=body ? 'POST' : 'GET') {
    const response = await fetch(`${base}${route}?familyId=family&actorMemberId=${member}`,{
      method,headers:{'Content-Type':'application/json',...(device ? {'x-fixture-device':device} : {})},
      ...(body ? {body:JSON.stringify(body)} : {}) });
    return { status:response.status,body:await response.json(),cache:response.headers.get('cache-control') };
  }
  try {
    assert.equal((await request(null,'host','/capabilities')).status,401);
    assert.equal((await request('a','guest','/capabilities')).status,403);
    assert.deepEqual((await request('a','host','/capabilities')).body.games,['TIC_TAC_TOE']);
    const input = invite(), created = await request('a','host','',input);
    assert.equal(created.status,201); assert.equal(created.cache,'no-store');
    assert.equal((await request('a','host','',input)).status,200);
    assert.equal((await request('c','other','/'+input.gameId)).status,404);
    const accepted = await request('b','guest','/'+input.gameId+'/actions',command(created.body.game,'ACCEPT'));
    assert.equal(accepted.status,200); assert.equal(accepted.body.game.status,'PLAYING');
    const missing = await request('a','host','/'+randomUUID()); assert.equal(missing.status,404);
  } finally { await new Promise(resolve => server.close(resolve)); await f.close(); }
});
