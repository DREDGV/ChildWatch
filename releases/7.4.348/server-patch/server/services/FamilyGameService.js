const { randomBytes } = require('node:crypto');
const Battleship = require('./BattleshipRules');
const Snake = require('./SnakeRules');
const Arcanoid = require('./ArcanoidRules');
const DeviceAccessService = require('./DeviceAccessService');

class GameError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
const fail = (status, code) => { throw new GameError(status, code); };
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const OPEN = ['INVITED', 'SETUP', 'PLAYING'];
const LINES = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];

/** Authoritative, consented family games. Participants are members, never handset IDs. */
class FamilyGameService {
  constructor(db, { clock = Date.now } = {}) {
    this.db = db;
    this.clock = clock;
    this.access = new DeviceAccessService(db);
  }

  async ensure() {
    if (!this.ready) this.ready = (async () => {
      await this.db.run(`CREATE TABLE IF NOT EXISTS family_games (
        id TEXT PRIMARY KEY, family_id TEXT NOT NULL, host_member_id TEXT NOT NULL,
        guest_member_id TEXT NOT NULL, status TEXT NOT NULL, board TEXT NOT NULL,
        next_mark TEXT, winner_member_id TEXT, winning_line TEXT,
        version INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL)`);
      const columns = await this.db.all('PRAGMA table_info(family_games)');
      if (!columns.some(column => column.name === 'type'))
        await this.db.run("ALTER TABLE family_games ADD COLUMN type TEXT NOT NULL DEFAULT 'TIC_TAC_TOE'");
      await this.db.run('CREATE INDEX IF NOT EXISTS games_family_updated ON family_games(family_id,updated_at)');
      await this.db.run(`CREATE TABLE IF NOT EXISTS family_game_actions (
        id TEXT PRIMARY KEY, game_id TEXT NOT NULL, member_id TEXT NOT NULL,
        payload TEXT NOT NULL, created_at INTEGER NOT NULL)`);
      await this.db.run('CREATE INDEX IF NOT EXISTS game_actions_game ON family_game_actions(game_id)');
    })().catch(error => { this.ready = null; throw error; });
    return this.ready;
  }

  async actor(deviceId, familyId) {
    if (typeof familyId !== 'string' || !familyId.trim() || familyId.length > 160)
      fail(400, 'GAME_FAMILY_REQUIRED');
    if (!deviceId || await this.access.isDeviceRevoked(deviceId)) fail(403, 'GAME_ACCESS_DENIED');
    const identities = (await Promise.all(this.access.idForms(deviceId)
      .map(id => this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const matches = identities.filter(m => m.familyId === familyId && ['CHILD','PARENT','GUARDIAN'].includes(m.memberRole));
    if (new Set(matches.map(m => m.memberId)).size !== 1) fail(403, 'GAME_ACCESS_DENIED');
    return { ...matches[0], authenticatedDeviceId: deviceId };
  }

  async context(actor) {
    const fresh = await this.actor(actor.authenticatedDeviceId, actor.familyId);
    if (fresh.memberId !== actor.memberId || fresh.memberRole !== actor.memberRole)
      fail(403, 'GAME_CONTEXT_CHANGED');
    const members = await this.db.getFamilyMembers(actor.familyId);
    if (!members.some(m => m.id === fresh.memberId && m.role === fresh.memberRole))
      fail(403, 'GAME_ACCESS_DENIED');
    return members;
  }

  async expire(familyId) {
    const now = this.clock();
    await this.db.run(`UPDATE family_games SET status='EXPIRED',next_mark=NULL,version=version+1,updated_at=?
      WHERE family_id=? AND expires_at<=? AND status IN ('INVITED','SETUP','PLAYING')`, [now, familyId, now]);
  }

  async row(actor, id, members) {
    if (!uuid(id)) fail(400, 'GAME_INVALID_ID');
    const row = await this.db.get('SELECT * FROM family_games WHERE id=?', [id]);
    // A guessed room ID never reveals whether a different family's room exists.
    if (!row || row.family_id !== actor.familyId ||
        ![row.host_member_id,row.guest_member_id].includes(actor.memberId)) fail(404, 'GAME_NOT_FOUND');
    if (![row.host_member_id,row.guest_member_id].every(id => members.some(m => m.id === id)))
      fail(403, 'GAME_PARTICIPANT_UNAVAILABLE');
    return row;
  }

  view(row, actor, members) {
    const battleship = row.type === 'BATTLESHIP';
    const snake = row.type === 'SNAKE_COOP';
    const arcanoid = row.type === 'ARKANOID_COOP';
    const mark = actor.memberId === row.host_member_id ? 'X' : 'O';
    return {
      id: row.id, familyId: row.family_id, type: row.type, status: row.status,
      version: row.version, board: battleship || snake || arcanoid ? null : JSON.parse(row.board), nextMark: row.next_mark,
      ...(battleship ? { battleship: Battleship.view(JSON.parse(row.board),mark,!OPEN.includes(row.status),
        row.next_mark === 'X' ? row.host_member_id : row.next_mark === 'O' ? row.guest_member_id : null) } : {}),
      ...(snake ? { snake: Snake.view(Snake.restore(JSON.parse(row.board))) } : {}),
      ...(arcanoid ? { arcanoid: Arcanoid.view(Arcanoid.restore(JSON.parse(row.board))) } : {}),
      myMark: actor.memberId === row.host_member_id ? 'X' : 'O',
      host: { memberId: row.host_member_id, displayName: members.find(m => m.id === row.host_member_id)?.displayName || '' },
      guest: { memberId: row.guest_member_id, displayName: members.find(m => m.id === row.guest_member_id)?.displayName || '' },
      winnerMemberId: row.winner_member_id, winningLine: row.winning_line ? JSON.parse(row.winning_line) : null,
      createdAt: row.created_at, updatedAt: row.updated_at, expiresAt: row.expires_at
    };
  }

  async list(actor) {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      const rows = await this.db.all(`SELECT * FROM family_games WHERE family_id=?
        AND (host_member_id=? OR guest_member_id=?) ORDER BY updated_at DESC,id LIMIT 50`,
      [actor.familyId,actor.memberId,actor.memberId]);
      return rows.filter(r => [r.host_member_id,r.guest_member_id].every(id => members.some(m => m.id === id)))
        .map(r => this.view(r,actor,members));
    });
  }

  async authorize(actor, id) {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      return { row: await this.row(actor,id,members), members };
    });
  }

  async get(actor, id) {
    const { row, members } = await this.authorize(actor, id);
    return this.view(row,actor,members);
  }

  async create(actor, input) {
    await this.ensure();
    if (!uuid(input.gameId) || !['TIC_TAC_TOE','BATTLESHIP','SNAKE_COOP','ARKANOID_COOP'].includes(input.type) ||
        typeof input.targetMemberId !== 'string' || input.targetMemberId.length > 160)
      fail(400, 'GAME_INVALID_INVITATION');
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      const existing = await this.db.get('SELECT * FROM family_games WHERE id=?', [input.gameId]);
      if (existing) {
        if (existing.family_id !== actor.familyId || existing.host_member_id !== actor.memberId ||
            existing.guest_member_id !== input.targetMemberId || existing.type !== input.type) fail(409,'GAME_ID_REUSED');
        return { replayed: true, game: this.view(await this.row(actor,input.gameId,members),actor,members) };
      }
      if (input.targetMemberId === actor.memberId || !members.some(m => m.id === input.targetMemberId))
        fail(400, 'GAME_INVALID_OPPONENT');
      // Bound unaccepted invitations without blocking ordinary finished games.
      const count = await this.db.get(`SELECT COUNT(*) AS total FROM family_games
        WHERE family_id=? AND status='INVITED' AND
        (host_member_id IN (?,?) OR guest_member_id IN (?,?))`,
      [actor.familyId,actor.memberId,input.targetMemberId,actor.memberId,input.targetMemberId]);
      if (count.total >= 5) fail(429, 'GAME_INVITATION_LIMIT');
      const now = this.clock();
      const initialBoard = input.type === 'BATTLESHIP' ? Battleship.initial()
        : input.type === 'SNAKE_COOP' ? Snake.snapshot(Snake.initial(randomBytes(8).readBigInt64BE().toString(), 'NORMAL'))
        : input.type === 'ARKANOID_COOP' ? Arcanoid.snapshot(Arcanoid.initial())
        : Array(9).fill(null);
      await this.db.run(`INSERT INTO family_games
        (id,family_id,host_member_id,guest_member_id,type,status,board,next_mark,version,created_at,updated_at,expires_at)
        VALUES (?,?,?,?,?,'INVITED',?,NULL,1,?,?,?)`,
      [input.gameId,actor.familyId,actor.memberId,input.targetMemberId,input.type,JSON.stringify(initialBoard),now,now,now+15*60_000]);
      return { replayed: false, game: this.view(await this.row(actor,input.gameId,members),actor,members) };
    });
  }

  async action(actor, id, input) {
    await this.ensure();
    if (!uuid(input.actionId) || !Number.isSafeInteger(input.version) || input.version < 1 ||
        !['ACCEPT','DECLINE','CANCEL','MOVE','RESIGN','PLACE_FLEET','FIRE'].includes(input.action) ||
        (input.action === 'MOVE' && (!Number.isInteger(input.cell) || input.cell < 0 || input.cell > 8)) ||
        (input.action === 'FIRE' && (!Number.isInteger(input.cell) || input.cell < 0 || input.cell > 99)) ||
        (!['MOVE','FIRE'].includes(input.action) && input.cell !== undefined) ||
        (input.action !== 'PLACE_FLEET' && input.ships !== undefined) ||
        (input.action === 'PLACE_FLEET' && !Battleship.fleet(input.ships))) fail(400,'GAME_INVALID_ACTION');
    const payload = JSON.stringify({ action: input.action, version: input.version, cell: input.cell, ships: input.action === 'PLACE_FLEET' ? Battleship.fleet(input.ships) : undefined });
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      const row = await this.row(actor,id,members);
      const previous = await this.db.get('SELECT * FROM family_game_actions WHERE id=?',[input.actionId]);
      if (previous) {
        if (previous.game_id !== id || previous.member_id !== actor.memberId || previous.payload !== payload)
          fail(409,'GAME_ACTION_ID_REUSED');
        return { replayed: true, game: this.view(row,actor,members) };
      }
      if (!OPEN.includes(row.status)) fail(409,'GAME_CLOSED');
      if (row.version !== input.version) fail(409,'GAME_VERSION_CONFLICT');
      const board = JSON.parse(row.board);
      const battleship = row.type === 'BATTLESHIP';
      const snake = row.type === 'SNAKE_COOP';
      const arcanoid = row.type === 'ARKANOID_COOP';
      if (((snake || arcanoid) && ['MOVE','FIRE','PLACE_FLEET'].includes(input.action)) || (battleship && input.action === 'MOVE') || (!battleship && ['FIRE','PLACE_FLEET'].includes(input.action)))
        fail(400,'GAME_INVALID_ACTION');
      let status = row.status, next = row.next_mark, winner = null, line = null;
      if (['ACCEPT','DECLINE'].includes(input.action)) {
        if (row.status !== 'INVITED' || actor.memberId !== row.guest_member_id) fail(409,'GAME_ACTION_NOT_ALLOWED');
        status = input.action === 'ACCEPT' ? (battleship || snake || arcanoid ? 'SETUP' : 'PLAYING') : 'DECLINED';
        next = input.action === 'ACCEPT' && !battleship && !snake && !arcanoid ? 'X' : null;
      } else if (input.action === 'CANCEL') {
        if (row.status !== 'INVITED' || actor.memberId !== row.host_member_id) fail(409,'GAME_ACTION_NOT_ALLOWED');
        status = 'CANCELLED'; next = null;
      } else if (battleship && input.action === 'PLACE_FLEET') {
        const mark = actor.memberId === row.host_member_id ? 'X' : 'O';
        if (row.status !== 'SETUP' || board.fleets[mark]) fail(409,'GAME_ACTION_NOT_ALLOWED');
        board.fleets[mark] = Battleship.fleet(input.ships);
        if (board.fleets.X && board.fleets.O) { status = 'PLAYING'; next = 'X'; }
      } else {
        if (row.status !== 'PLAYING' && !((battleship || snake || arcanoid) && row.status === 'SETUP' && input.action === 'RESIGN')) fail(409,'GAME_ACTION_NOT_ALLOWED');
        const mark = actor.memberId === row.host_member_id ? 'X' : 'O';
        if (input.action === 'RESIGN') {
          status = 'RESIGNED'; next = null;
          winner = arcanoid ? null : actor.memberId === row.host_member_id ? row.guest_member_id : row.host_member_id;
        } else {
          if (mark !== next) fail(409,'GAME_NOT_YOUR_TURN');
          if (battleship) {
            if (board.shots[mark].some(shot => shot.cell === input.cell)) fail(409,'GAME_CELL_OCCUPIED');
            const shot = Battleship.fire(board,mark,input.cell);
            if (shot.won) { status = 'WON'; winner = actor.memberId; next = null; }
            else if (shot.result === 'MISS') next = mark === 'X' ? 'O' : 'X';
          } else {
            if (board[input.cell] !== null) fail(409,'GAME_CELL_OCCUPIED');
            board[input.cell] = mark;
            line = LINES.find(cells => cells.every(cell => board[cell] === mark)) || null;
            if (line) { status = 'WON'; winner = actor.memberId; next = null; }
            else if (board.every(Boolean)) { status = 'DRAW'; next = null; }
            else next = mark === 'X' ? 'O' : 'X';
          }
        }
      }
      const now = this.clock();
      const expires = input.action === 'ACCEPT' ? now+24*60*60_000 : row.expires_at;
      await this.db.run(`UPDATE family_games SET status=?,board=?,next_mark=?,winner_member_id=?,winning_line=?,
        version=version+1,updated_at=?,expires_at=? WHERE id=?`,
      [status,JSON.stringify(board),next,winner,line ? JSON.stringify(line) : null,now,expires,id]);
      await this.db.run('INSERT INTO family_game_actions(id,game_id,member_id,payload,created_at) VALUES (?,?,?,?,?)',
        [input.actionId,id,actor.memberId,payload,now]);
      return { replayed: false, game: this.view(await this.row(actor,id,members),actor,members) };
    });
  }
}
FamilyGameService.Error = GameError;
module.exports = FamilyGameService;
