const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const Games = require('../services/FamilyGameService');
const Sessions = require('../services/ArcanoidSessionService');
const SocketService = require('../services/ArcanoidSocketService');

async function fixture() {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', e => e ? reject(e) : resolve(connection));
  });
  const members = [{ id: 'host', role: 'PARENT' }, { id: 'guest', role: 'CHILD' }, { id: 'other', role: 'GUARDIAN' }];
  const bindings = { a: 'host', b: 'guest', b2: 'guest', c: 'other' }, revoked = new Set();
  db.getFamilyMembers = async family => family === 'family' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(m => m.id === bindings[device]);
    return member ? [{ familyId: 'family', memberId: member.id, memberRole: member.role }] : [];
  };
  const games = new Games(db, { clock: () => 1000000 });
  games.access.isDeviceRevoked = async device => revoked.has(device);
  const actors = { host: await games.actor('a', 'family'), guest: await games.actor('b', 'family'),
    guest2: await games.actor('b2', 'family'), other: await games.actor('c', 'family') };
  let now = 0;
  const options = { clock: () => now, wallClock: () => 1000000 + now };
  const sessions = new Sessions(games, options);
  let game = (await games.create(actors.host, { gameId: randomUUID(), type: 'ARKANOID_COOP', targetMemberId: 'guest' })).game;
  const invited = game;
  game = (await games.action(actors.guest, game.id, { actionId: randomUUID(), version: game.version, action: 'ACCEPT' })).game;
  const sent = [[], []];
  const join = (player, token = player ? 'b' : 'a', actor = player ? actors.guest : actors.host) =>
    sessions.join(actor, game.id, token, state => sent[player].push(state));
  const command = (player, action, extra = {}) => sessions.command(player ? actors.guest : actors.host,
    game.id, player ? 'b' : 'a', { epoch: sessions.rooms.get(game.id).epoch, action, ...extra });
  const start = async () => { await join(0); await join(1); await command(0, 'READY'); await command(1, 'READY'); now += 2000; await sessions.pulse(); };
  return { db, games, actors, sessions, options, game, invited, sent, revoked, bindings, members, join, command, start,
    advance: ms => { now += ms; }, close: () => new Promise((resolve, reject) => db.db.close(e => e ? reject(e) : resolve())) };
}

const Rules = require('../services/ArcanoidRules');
const STEP = 1000 / 60;

test('both ready before countdown; server advances60Hz and clamps each paddle travel', async()=>{
  const f=await fixture();try{
    await f.join(0);await f.command(0,'READY');f.advance(2000);await f.sessions.pulse();
    assert.equal(f.sessions.state(f.sessions.rooms.get(f.game.id)).arcanoid.tick,0);
    await f.join(1);await f.command(0,'READY');await f.command(1,'READY');
    assert.equal(f.sent[0].at(-1).phase,'COUNTDOWN');assert.deepEqual(f.sent[0].at(-1),f.sent[1].at(-1));
    await assert.rejects(f.command(0,'MOVE',{sequence:1,position:1}),{code:'ARKANOID_NOT_PLAYING'});
    f.advance(2000);await f.sessions.pulse();const room=f.sessions.rooms.get(f.game.id);
    await f.command(0,'MOVE',{sequence:1,position:1});await f.command(1,'MOVE',{sequence:1,position:0});
    f.advance(STEP);await f.sessions.pulse();assert.equal(room.tick,1);
    const view=Rules.view(room.engine);assert.ok(Math.abs(view.paddles[0]-(500+2200/60))<1e-8);
    assert.ok(Math.abs(view.paddles[1]-(500-2200/60))<1e-8);
    assert.equal(room.targets[0],905);assert.equal(room.targets[1],95);
    const before=f.sent[0].length;f.advance(STEP);await f.sessions.pulse();assert.equal(f.sent[0].length,before);
    f.advance(STEP);await f.sessions.pulse();assert.equal(room.tick,3);assert.equal(f.sent[0].at(-1).arcanoid.tick,3);
    assert.deepEqual(f.sent[0].at(-1),f.sent[1].at(-1));
  }finally{await f.close();}
});

test('positions are finite normalized values; receipt dedup and25Hz movement quota',async()=>{
  const f=await fixture();try{
    await f.start();const input={sequence:1,position:.9};await f.command(0,'MOVE',input);
    assert.equal((await f.command(0,'MOVE',input)).replayed,true);
    await assert.rejects(f.command(0,'MOVE',{...input,position:.8}),{code:'ARKANOID_SEQUENCE_REUSED'});
    await assert.rejects(f.command(0,'MOVE',{sequence:3,position:.8}),{code:'ARKANOID_SEQUENCE_CONFLICT'});
    for(const position of [NaN,Infinity,-Infinity,-.1,1.1,'0.4',null])
      await assert.rejects(f.command(0,'MOVE',{sequence:2,position}),{code:'ARKANOID_INVALID_INPUT'});
    for(let sequence=2;sequence<=25;sequence++)await f.command(0,'MOVE',{sequence,position:.4});
    await assert.rejects(f.command(0,'MOVE',{sequence:26,position:.5}),{code:'ARKANOID_MOVE_RATE'});
    assert.equal((await f.command(0,'MOVE',input)).replayed,true);
    f.advance(1000);await f.command(0,'MOVE',{sequence:26,position:.5});
    assert.equal(f.sessions.rooms.get(f.game.id).receipts[0].size,26);
  }finally{await f.close();}
});

test('disconnect removes desired movement and epochs; old disconnect cannot evict replacement',async()=>{
  const f=await fixture();try{
    await f.start();const room=f.sessions.rooms.get(f.game.id),epoch=room.epoch;
    await f.command(1,'MOVE',{sequence:1,position:0});await f.sessions.leave('b');
    assert.equal(room.phase,'PAUSED');assert.notEqual(room.epoch,epoch);assert.deepEqual(room.targets,[500,500]);
    assert.deepEqual(room.sequences,[0,0]);f.advance(2000);await f.sessions.pulse();assert.equal(room.tick,0);
    await assert.rejects(f.sessions.command(f.actors.host,f.game.id,'a',{action:'READY',epoch}),{code:'ARKANOID_EPOCH_CHANGED'});
    await f.join(1,'replacement',f.actors.guest2);await f.sessions.leave('b');assert.equal(room.connections[1].token,'replacement');
    await f.command(0,'READY');await f.sessions.command(f.actors.guest2,f.game.id,'replacement',{action:'READY',epoch:room.epoch});
    f.advance(2000);await f.sessions.pulse();f.advance(STEP);await f.sessions.pulse();assert.equal(room.tick,1);
    assert.deepEqual(Rules.view(room.engine).paddles,[500,500]);
  }finally{await f.close();}
});

test('lease loss explicitpause and server delay freeze globally withoutcatchup',async()=>{
  for(const kind of ['lease','pause','stall']){
    const f=await fixture();try{
      await f.start();const room=f.sessions.rooms.get(f.game.id);
      if(kind==='pause')await f.command(0,'PAUSE');else{f.advance(kind==='lease'?12001:300);await f.sessions.pulse();}
      assert.equal(room.phase,'PAUSED');assert.equal(room.tick,0);assert.deepEqual(room.ready,[false,false]);
      assert.equal(room.reason,kind==='pause'?'PLAYER_PAUSED':kind==='lease'?'PLAYER_DISCONNECTED':'SERVER_DELAY');
    }finally{await f.close();}
  }
});

test('life loss preserves remaining lives and waits both ready instead of automatic launch',async()=>{
  const f=await fixture();try{
    await f.start();const room=f.sessions.rooms.get(f.game.id),epoch=room.epoch;
    const saved=Rules.snapshot(room.engine);saved[1]=20;saved[2]=1419;saved[3]=0;saved[4]=600;
    room.engine=Rules.restore(saved);Rules.launchOrResume(room.engine);
    f.advance(STEP);await f.sessions.pulse();assert.equal(room.phase,'PAUSED');assert.equal(room.reason,'LIFE_LOST');
    assert.notEqual(room.epoch,epoch);assert.equal(Rules.view(room.engine).lives,2);assert.equal(room.engine.state,'READY');
    const tick=room.tick;f.advance(1000);await f.sessions.pulse();assert.equal(room.tick,tick);
    await f.command(0,'READY');assert.equal(room.phase,'PAUSED');await f.command(1,'READY');assert.equal(room.phase,'COUNTDOWN');
    f.advance(2000);await f.sessions.pulse();assert.equal(Rules.view(room.engine).lives,2);assert.equal(room.phase,'PLAYING');
  }finally{await f.close();}
});

test('level complete checkpoint resumes safely and nextLevel runs exactlyonce afterbothready',async()=>{
  const f=await fixture();try{
    await f.start();const room=f.sessions.rooms.get(f.game.id);let saved=Rules.snapshot(room.engine);
    saved[1]=116;saved[2]=400;saved[3]=0;saved[4]=700;saved[7]=290;saved[8]=1;
    room.engine=Rules.restore(saved);Rules.launchOrResume(room.engine);
    f.advance(STEP);await f.sessions.pulse();assert.equal(room.phase,'PAUSED');assert.equal(room.reason,'LEVEL_COMPLETE');
    assert.equal(room.engine.state,'WON');assert.equal(Rules.view(room.engine).level,1);assert.equal(Rules.view(room.engine).score,300);
    const restarted=new Sessions(f.games,f.options);
    const recovered=await restarted.join(f.actors.host,f.game.id,'new-a',()=>{});
    assert.equal(recovered.phase,'PAUSED');assert.equal(recovered.reason,'LEVEL_COMPLETE');assert.equal(recovered.arcanoid.level,1);
    await f.command(1,'READY');assert.equal(Rules.view(room.engine).level,1);
    await f.command(0,'READY');assert.equal(room.phase,'COUNTDOWN');assert.equal(Rules.view(room.engine).level,2);
    await f.command(0,'READY');await f.command(1,'READY');assert.equal(Rules.view(room.engine).level,2);
    assert.equal(Rules.view(room.engine).lives,3);assert.equal(Rules.view(room.engine).score,300);
  }finally{await f.close();}
});

test('restart checkpoint is paused withnew epoch, no target replay and retained simulationtick',async()=>{
  const f=await fixture();try{
    await f.start();f.advance(50);await f.sessions.pulse();await f.command(0,'PAUSE');
    const old=f.sessions.rooms.get(f.game.id);const saved=Rules.view(old.engine);
    const state=await new Sessions(f.games,f.options).join(f.actors.host,f.game.id,'new-a',()=>{});
    assert.equal(state.phase,'PAUSED');assert.notEqual(state.epoch,old.epoch);assert.equal(state.arcanoid.tick,3);
    assert.deepEqual(state.arcanoid.ball,saved.ball);assert.deepEqual(state.ready,[false,false]);
  }finally{await f.close();}
});

test('outsider revokedmember changedidentity andclosedgame do not receive playable stream',async()=>{
  const f=await fixture();try{
    await assert.rejects(f.sessions.join(f.actors.other,f.game.id,'x',()=>{}),{status:404});
    await f.start();f.revoked.add('b');f.advance(250);await f.sessions.pulse();const room=f.sessions.rooms.get(f.game.id);
    assert.equal(room.phase,'PAUSED');assert.equal(room.connections[1],null);await assert.rejects(f.command(1,'READY'),{status:403});
    f.revoked.clear();f.bindings.b='other';await assert.rejects(f.join(1),{code:'GAME_CONTEXT_CHANGED'});
    const game=await f.games.get(f.actors.host,f.game.id);
    await f.games.action(f.actors.host,f.game.id,{actionId:randomUUID(),version:game.version,action:'RESIGN'});
    await assert.rejects(f.command(0,'READY'),{code:'GAME_CLOSED'});
    f.advance(250);await f.sessions.pulse();assert.equal(room.connections[0],null);
    assert.equal((await f.games.get(f.actors.host,f.game.id)).status,'RESIGNED');
  }finally{await f.close();}
});

test('expiredtoken stream stopswithin250ms and capacity is bounded',async()=>{
  const f=await fixture();try{
    let valid=true;await f.sessions.join(f.actors.host,f.game.id,'a',s=>f.sent[0].push(s),async()=>valid);
    await f.join(1);await f.command(0,'READY');await f.command(1,'READY');f.advance(2000);await f.sessions.pulse();valid=false;
    f.advance(250);await f.sessions.pulse();const room=f.sessions.rooms.get(f.game.id);
    assert.equal(room.connections[0],null);assert.equal(room.phase,'PAUSED');assert.equal(f.sent[0].at(-1).code,'GAME_AUTH_REQUIRED');
    await assert.rejects(new Sessions(f.games,{...f.options,maxRooms:0}).join(f.actors.guest,f.game.id,'z',()=>{}),{code:'ARKANOID_ROOM_LIMIT'});
  }finally{await f.close();}
});

test('failedpaused andterminal checkpoints retry withoutlosing result',async()=>{
  for(const terminal of [false,true]){
    const f=await fixture();try{
      await f.start();const room=f.sessions.rooms.get(f.game.id);
      if(terminal){const saved=Rules.snapshot(room.engine);saved[1]=20;saved[2]=1419;saved[3]=0;saved[4]=600;saved[6]=1;
        room.engine=Rules.restore(saved);Rules.launchOrResume(room.engine);}
      const run=f.db.run.bind(f.db);let rejected=false;
      f.db.run=async(sql,args)=>{if(!rejected&&sql.includes('INSERT INTO family_arcanoid_sessions')){rejected=true;throw Error('checkpoint once');}return run(sql,args);};
      if(terminal){f.advance(STEP);await assert.rejects(f.sessions.pulse(),/checkpoint once/);assert.equal(room.phase,'FINISHED');}
      else{await assert.rejects(f.command(0,'PAUSE'),/checkpoint once/);assert.equal(room.phase,'PAUSED');}
      assert.equal(room.checkpointPending,true);await f.sessions.pulse();assert.equal(room.checkpointPending,false);
      const recovered=await new Sessions(f.games,f.options).join(f.actors.host,f.game.id,'new',()=>{});
      assert.equal(recovered.phase,terminal?'FINISHED':'PAUSED');
      if(terminal){assert.equal(recovered.arcanoid.state,'GAME_OVER');assert.equal((await f.games.get(f.actors.host,f.game.id)).status,'LOST');}
    }finally{await f.close();}
  }
});

test('final campaign win persists FINISHED and cannot advance or lose its result on restart',async()=>{
  const f=await fixture();try{
    await f.start();const room=f.sessions.rooms.get(f.game.id);let engine=Rules.initial();
    const bits=mask=>{let count=0;for(let i=0;i<30;i++)if(mask&(1<<i))count++;return count;};
    for(let level=0;level<5;level++){
      engine.score+=bits(Rules.view(engine).bricks)*10;engine.bricks=0;engine.state='WON';assert.equal(Rules.nextLevel(engine),true);
    }
    const saved=Rules.snapshot(engine);saved[1]=116;saved[2]=400;saved[3]=0;saved[4]=700;
    saved[7]+=(bits(saved[8])-1)*10;saved[8]=1;saved[9]=1;
    room.engine=Rules.restore(saved);assert.ok(room.engine);Rules.launchOrResume(room.engine);
    f.advance(STEP);await f.sessions.pulse();assert.equal(room.phase,'FINISHED');assert.equal(room.engine.state,'WON');
    assert.equal(Rules.view(room.engine).level,6);assert.equal((await f.games.get(f.actors.host,f.game.id)).status,'WON');
    await assert.rejects(f.command(0,'READY'),{code:'ARKANOID_NOT_PAUSED'});
    const restored=await new Sessions(f.games,f.options).join(f.actors.guest,f.game.id,'new',()=>{});
    assert.equal(restored.phase,'FINISHED');assert.equal(restored.arcanoid.level,6);
    assert.equal(restored.arcanoid.score,Rules.view(room.engine).score);
  }finally{await f.close();}
});

test('forgotten move receipt cannot be replayed and checkpoint error failsafe keeps rooms frozen',async()=>{
  const f=await fixture();try{
    await f.start();const room=f.sessions.rooms.get(f.game.id);
    for(let sequence=1;sequence<=40;sequence++){
      if(sequence===26)f.advance(1000);
      await f.command(0,'MOVE',{sequence,position:.2});
    }
    assert.equal(room.receipts[0].size,32);
    await assert.rejects(f.command(0,'MOVE',{sequence:1,position:.2}),{code:'ARKANOID_SEQUENCE_CONFLICT'});
    await f.sessions.failSafe();const epoch=room.epoch;
    assert.equal(room.phase,'PAUSED');assert.equal(room.reason,'SERVER_ERROR');assert.equal(room.checkpointPending,true);
    await f.sessions.failSafe();assert.equal(room.epoch,epoch);
    f.advance(1000);await f.sessions.pulse();assert.equal(room.tick,0);assert.equal(room.checkpointPending,false);
  }finally{await f.close();}
});
