const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const http = require('node:http');
const WebSocket = require('ws');
const { Server } = require('socket.io');
const sqlite = require('sqlite3');
const DatabaseManager = require('../database/DatabaseManager');
const Games = require('../services/FamilyGameService');
const Sessions = require('../services/ArcanoidSessionService');
const SocketService = require('../services/ArcanoidSocketService');
const SocketAuth = require('../middleware/SocketAuthMiddleware');

const NS = '/family-games';
const credentials = { host: 'a'.repeat(64), guest: 'b'.repeat(64), other: 'c'.repeat(64) };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, description) {
  const deadline = Date.now() + 4000;
  while (Date.now() < deadline) {
    const result = predicate(); if (result) return result;
    await delay(10);
  }
  assert.fail(`Timed out: ${description}`);
}

/** Real WebSocket transport, minimal Engine.IO v4 + Socket.IO namespace/ack protocol. */
class Client {
  constructor(port, token) {
    this.states = []; this.acks = new Map(); this.nextId = 0;
    this.socket = new WebSocket(`ws://127.0.0.1:${port}/socket.io/?EIO=4&transport=websocket`);
    this.socket.on('error', error => { this.error = error; });
    this.socket.on('message', bytes => {
      const packet = bytes.toString();
      if (packet.startsWith('0')) {
        this.socket.send(`40${NS},${JSON.stringify(token ? { token } : {})}`); return;
      }
      if (packet === '2') { this.socket.send('3'); return; }
      if (packet.startsWith(`40${NS},`)) { this.connected = true; return; }
      if (packet.startsWith(`44${NS},`)) {
        this.rejected = JSON.parse(packet.slice(`44${NS},`.length)); return;
      }
      if (packet.startsWith(`42${NS},`)) {
        const [event, payload] = JSON.parse(packet.slice(`42${NS},`.length));
        if (event === 'arkanoid:state') this.states.push(payload);
        return;
      }
      if (packet.startsWith(`43${NS},`)) {
        const body = packet.slice(`43${NS},`.length), bracket = body.indexOf('[');
        const id = Number(body.slice(0, bracket));
        const [payload] = JSON.parse(body.slice(bracket));
        this.acks.set(id, payload);
      }
    });
  }
  async connect() {
    await until(() => this.connected || this.rejected || this.error, 'namespace handshake');
    if (this.error) throw this.error;
    return this;
  }
  async call(event, payload) {
    assert.equal(this.connected, true);
    const id = ++this.nextId;
    this.socket.send(`42${NS},${id}${JSON.stringify([event, payload])}`);
    await until(() => this.acks.has(id) || this.error, `ack ${event}`);
    if (this.error) throw this.error;
    const result = this.acks.get(id); this.acks.delete(id); return result;
  }
  async state(predicate) {
    return until(() => this.states.find(predicate), 'authoritative state event');
  }
  close() { this.socket.terminate(); }
}

async function fixture() {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve, reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  const members = [{ id: 'host', role: 'PARENT' }, { id: 'guest', role: 'CHILD' }, { id: 'other', role: 'GUARDIAN' }];
  const bindings = { a: 'host', b: 'guest', c: 'other' };
  db.getFamilyMembers = async family => family === 'family' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(m => m.id === bindings[device]);
    return member ? [{ familyId: 'family', memberId: member.id, memberRole: member.role }] : [];
  };
  const games = new Games(db, { clock: () => 1000000 });
  games.access.isDeviceRevoked = async () => false;
  const host = await games.actor('a', 'family'), guest = await games.actor('b', 'family');
  let game = (await games.create(host, { gameId: randomUUID(), type: 'ARKANOID_COOP', targetMemberId: 'guest' })).game;
  game = (await games.action(guest, game.id, { actionId: randomUUID(), version: game.version, action: 'ACCEPT' })).game;
  let now = 0;
  const sessions = new Sessions(games, { clock: () => now, wallClock: () => 1000000 + now });
  const web = http.createServer(), io = new Server(web, { transports: ['websocket'], serveClient: false });
  const namespace = io.of(NS);
  const revokedTokens = new Set();
  const authenticate = new SocketAuth({ validateToken(token) {
    const member = Object.keys(credentials).find(key => credentials[key] === token);
    return member && !revokedTokens.has(token) ? { valid: true, deviceId: { host: 'a', guest: 'b', other: 'c' }[member], deviceData: {} }
      : { valid: false, error: 'Test credential rejected' };
  } }, { required: true }).authenticate();
  namespace.use(authenticate);
  const service = new SocketService(namespace, sessions, { authenticate }).start();
  await new Promise((resolve, reject) => { web.once('error', reject); web.listen(0, '127.0.0.1', resolve); });
  const clients = [];
  return { game, sessions, advance: ms => { now += ms; }, revoke: member => revokedTokens.add(credentials[member]),
    async client(member) { const client = new Client(web.address().port, credentials[member]); clients.push(client); return client.connect(); },
    async close() {
      for (const client of clients) client.close();
      await service.close();
      await new Promise(resolve => io.close(resolve));
      await sessions.chain;
      await new Promise((resolve, reject) => db.db.close(error => error ? reject(error) : resolve()));
    }
  };
}
const join = (client, gameId, actorMemberId) => client.call('arkanoid:join', { gameId, familyId: 'family', actorMemberId });
const command = (client, state, action, extra = {}) => client.call('arkanoid:command', {
  gameId: state.gameId, epoch: state.epoch, action, ...extra
});

test('real namespace requires mandatory authentication and keeps outsider snapshots private',async()=>{
  const f=await fixture();try{
    const anonymous=await f.client();assert.equal(anonymous.connected,undefined);
    assert.equal(anonymous.rejected.data.code,'WS_MISSING_TOKEN');assert.equal(anonymous.states.length,0);
    const outsider=await f.client('other');assert.equal((await join(outsider,f.game.id,'other')).code,'GAME_NOT_FOUND');assert.equal(outsider.states.length,0);
    const host=await f.client('host');assert.equal((await join(host,f.game.id,'guest')).code,'GAME_CONTEXT_CHANGED');assert.equal(host.states.length,0);
  }finally{await f.close();}
});

test('two actual WebSockets see one authoritative ball, bounded paddles and paused reconnection',async()=>{
  const f=await fixture();try{
    const host=await f.client('host'),guest=await f.client('guest');
    assert.equal((await join(host,f.game.id,'host')).success,true);const joined=await join(guest,f.game.id,'guest');
    assert.equal(joined.phase,'PAUSED');assert.equal(joined.arcanoid.tick,0);assert.equal(joined.player,1);
    await command(host,joined,'READY');const countdown=await command(guest,joined,'READY');assert.equal(countdown.phase,'COUNTDOWN');
    assert.deepEqual(await host.state(s=>s.phase==='COUNTDOWN'),await guest.state(s=>s.phase==='COUNTDOWN'));
    f.advance(2000);await f.sessions.pulse();const playing=await host.state(s=>s.phase==='PLAYING');
    assert.deepEqual(playing,await guest.state(s=>s.phase==='PLAYING'));
    const accepted=await command(host,playing,'MOVE',{sequence:1,position:1,player:1,score:999999,tick:999999});
    assert.equal(accepted.success,true);assert.equal(accepted.replayed,false);assert.equal(accepted.sequence,1);
    assert.equal((await command(host,playing,'MOVE',{sequence:1,position:1})).replayed,true);
    assert.equal((await command(host,playing,'MOVE',{sequence:1,position:0})).code,'ARKANOID_SEQUENCE_REUSED');
    assert.equal((await command(guest,playing,'MOVE',{sequence:1,position:0})).success,true);
    assert.equal((await command(guest,playing,'MOVE',{sequence:2,position:'0.5'})).code,'ARKANOID_INVALID_INPUT');
    f.advance(50);await f.sessions.pulse();const moved=await host.state(s=>s.phase==='PLAYING'&&s.arcanoid.tick===3);
    assert.deepEqual(moved,await guest.state(s=>s.phase==='PLAYING'&&s.arcanoid.tick===3));
    assert.ok(Math.abs(moved.arcanoid.paddles[0]-610)<1e-7);assert.ok(Math.abs(moved.arcanoid.paddles[1]-390)<1e-7);
    assert.equal(moved.arcanoid.score,0);assert.notEqual(moved.arcanoid.ball.y,playing.arcanoid.ball.y);
    guest.close();const paused=await host.state(s=>s.reason==='PLAYER_DISCONNECTED');
    assert.equal(paused.phase,'PAUSED');assert.notEqual(paused.epoch,moved.epoch);assert.equal(paused.arcanoid.tick,3);
    f.advance(1000);await f.sessions.pulse();assert.equal(f.sessions.rooms.get(f.game.id).tick,3);
    const replacement=await f.client('guest');const fresh=await join(replacement,f.game.id,'guest');
    assert.equal(fresh.success,true);assert.equal(fresh.phase,'PAUSED');assert.notEqual(fresh.epoch,paused.epoch);
    assert.deepEqual(fresh.arcanoid,paused.arcanoid);
    assert.equal((await command(host,moved,'MOVE',{sequence:2,position:0})).code,'ARKANOID_EPOCH_CHANGED');
    await command(host,fresh,'READY');await command(replacement,fresh,'READY');
    f.advance(2000);await f.sessions.pulse();f.advance(50);await f.sessions.pulse();
    const resumed=await host.state(s=>s.phase==='PLAYING'&&s.arcanoid.tick===6);
    assert.deepEqual(resumed,await replacement.state(s=>s.phase==='PLAYING'&&s.arcanoid.tick===6));
    assert.deepEqual(resumed.arcanoid.paddles,paused.arcanoid.paddles);
  }finally{await f.close();}
});

test('expired token loses both commands and streaming on existing real socket',async()=>{
  const f=await fixture();try{
    const host=await f.client('host'),guest=await f.client('guest');await join(host,f.game.id,'host');const state=await join(guest,f.game.id,'guest');
    await command(host,state,'READY');await command(guest,state,'READY');f.advance(2000);await f.sessions.pulse();
    f.revoke('guest');assert.equal((await command(guest,state,'PING')).code,'GAME_AUTH_REQUIRED');
    f.advance(250);await f.sessions.pulse();assert.equal((await host.state(s=>s.reason==='ACCESS_CHANGED')).phase,'PAUSED');
    const denied=await guest.state(s=>s.code==='GAME_AUTH_REQUIRED');assert.equal(denied.arcanoid,undefined);
    assert.equal(f.sessions.rooms.get(f.game.id).connections[1],null);
  }finally{await f.close();}
});
