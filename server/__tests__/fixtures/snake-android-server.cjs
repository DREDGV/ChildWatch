// Test-only loopback fixture; never opens a production database or external listener.
const http = require('node:http');
const { randomBytes, randomUUID } = require('node:crypto');
const { Server } = require('socket.io');
const sqlite = require('sqlite3');
const DatabaseManager = require('../../database/DatabaseManager');
const Games = require('../../services/FamilyGameService');
const Sessions = require('../../services/SnakeSessionService');
const SocketService = require('../../services/SnakeSocketService');
const SocketAuth = require('../../middleware/SocketAuthMiddleware');

async function main() {
  const db = new DatabaseManager(':memory:');
  db.db = await new Promise((resolve,reject) => {
    const connection = new sqlite.Database(':memory:', error => error ? reject(error) : resolve(connection));
  });
  const members = [{id:'android-host',role:'PARENT'},{id:'android-guest',role:'CHILD'}];
  const bindings = {'android-a':'android-host','android-b':'android-guest'};
  const tokens = {host:randomBytes(32).toString('hex'),guest:randomBytes(32).toString('hex')};
  db.getFamilyMembers = async family => family === 'android-fixture-family' ? members : [];
  db.getFamilyIdentityMembershipsForDevice = async device => {
    const member = members.find(value => value.id === bindings[device]);
    return member ? [{familyId:'android-fixture-family',memberId:member.id,memberRole:member.role}] : [];
  };
  const games = new Games(db); games.access.isDeviceRevoked = async () => false;
  const host = await games.actor('android-a','android-fixture-family');
  const guest = await games.actor('android-b','android-fixture-family');
  let stopping = false;
  const web = http.createServer(async (req,res) => {
    res.setHeader('Cache-Control','no-store');
    if (req.method !== 'GET' || req.url !== '/fixture' || stopping) { res.writeHead(404);res.end();return; }
    try {
      let game = (await games.create(host,{gameId:randomUUID(),targetMemberId:guest.memberId,type:'SNAKE_COOP'})).game;
      game = (await games.action(guest,game.id,{actionId:randomUUID(),version:game.version,action:'ACCEPT'})).game;
      res.writeHead(200,{'Content-Type':'application/json'});
      res.end(JSON.stringify({gameId:game.id,url:'http://127.0.0.1:8879',familyId:host.familyId,
        host:{memberId:host.memberId,token:tokens.host},guest:{memberId:guest.memberId,token:tokens.guest}}));
    } catch(error) { res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'FIXTURE_FAILED'}));console.error(error); }
  });
  const io = new Server(web,{transports:['websocket'],serveClient:false});
  const sessions = new Sessions(games,{countdownMs:200});
  const authenticate = new SocketAuth({validateToken(token) {
    const deviceId = token === tokens.host ? 'android-a' : token === tokens.guest ? 'android-b' : null;
    return deviceId ? {valid:true,deviceId,deviceData:{}} : {valid:false,error:'Fixture token rejected'};
  }},{required:true}).authenticate();
  const namespace = io.of('/family-games'); namespace.use(authenticate);
  const service = new SocketService(namespace,sessions,{authenticate}).start();
  async function close() {
    if(stopping)return;stopping=true;
    await service.close(); await new Promise(resolve => io.close(resolve)); await sessions.chain;
    await new Promise((resolve,reject) => db.db.close(error => error ? reject(error) : resolve()));
  }
  process.once('SIGTERM',() => close().then(() => process.exit(0),error => {console.error(error);process.exit(1);}));
  process.once('SIGINT',() => close().then(() => process.exit(0),error => {console.error(error);process.exit(1);}));
  await new Promise((resolve,reject) => {web.once('error',reject);web.listen(8879,'127.0.0.1',resolve);});
  console.log('SNAKE_ANDROID_FIXTURE_READY 127.0.0.1:8879 memory-db');
}
main().catch(error => {console.error(error);process.exit(1);});
