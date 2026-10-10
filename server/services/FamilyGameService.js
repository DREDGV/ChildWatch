const DeviceAccessService = require('./DeviceAccessService');

class GameError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
const fail = (status, code) => { throw new GameError(status, code); };
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const OPEN = ['INVITED', 'PLAYING'];
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
      WHERE family_id=? AND expires_at<=? AND status IN ('INVITED','PLAYING')`, [now, familyId, now]);
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
    return {
      id: row.id, familyId: row.family_id, type: 'TIC_TAC_TOE', status: row.status,
      version: row.version, board: JSON.parse(row.board), nextMark: row.next_mark,
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

  async get(actor, id) {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      return this.view(await this.row(actor,id,members),actor,members);
    });
  }

  async create(actor, input) {
    await this.ensure();
    if (!uuid(input.gameId) || input.type !== 'TIC_TAC_TOE' ||
        typeof input.targetMemberId !== 'string' || input.targetMemberId.length > 160)
      fail(400, 'GAME_INVALID_INVITATION');
    return this.db.withTransaction(async () => {
      const members = await this.context(actor);
      await this.expire(actor.familyId);
      const existing = await this.db.get('SELECT * FROM family_games WHERE id=?', [input.gameId]);
      if (existing) {
        if (existing.family_id !== actor.familyId || existing.host_member_id !== actor.memberId ||
            existing.guest_member_id !== input.targetMemberId) fail(409,'GAME_ID_REUSED');
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
      await this.db.run(`INSERT INTO family_games
        (id,family_id,host_member_id,guest_member_id,status,board,next_mark,version,created_at,updated_at,expires_at)
        VALUES (?,?,?,?,'INVITED',?,NULL,1,?,?,?)`,
      [input.gameId,actor.familyId,actor.memberId,input.targetMemberId,JSON.stringify(Array(9).fill(null)),now,now,now+15*60_000]);
      return { replayed: false, game: this.view(await this.row(actor,input.gameId,members),actor,members) };
    });
  }

  async action(actor, id, input) {
    await this.ensure();
    if (!uuid(input.actionId) || !Number.isSafeInteger(input.version) || input.version < 1 ||
        !['ACCEPT','DECLINE','CANCEL','MOVE','RESIGN'].includes(input.action) ||
        (input.action === 'MOVE' && (!Number.isInteger(input.cell) || input.cell < 0 || input.cell > 8)) ||
        (input.action !== 'MOVE' && input.cell !== undefined)) fail(400,'GAME_INVALID_ACTION');
    const payload = JSON.stringify({ action: input.action, version: input.version, cell: input.cell });
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
      let status = row.status, next = row.next_mark, winner = null, line = null;
      if (['ACCEPT','DECLINE'].includes(input.action)) {
        if (row.status !== 'INVITED' || actor.memberId !== row.guest_member_id) fail(409,'GAME_ACTION_NOT_ALLOWED');
        status = input.action === 'ACCEPT' ? 'PLAYING' : 'DECLINED';
        next = input.action === 'ACCEPT' ? 'X' : null;
      } else if (input.action === 'CANCEL') {
        if (row.status !== 'INVITED' || actor.memberId !== row.host_member_id) fail(409,'GAME_ACTION_NOT_ALLOWED');
        status = 'CANCELLED'; next = null;
      } else {
        if (row.status !== 'PLAYING') fail(409,'GAME_ACTION_NOT_ALLOWED');
        const mark = actor.memberId === row.host_member_id ? 'X' : 'O';
        if (input.action === 'RESIGN') {
          status = 'RESIGNED'; next = null;
          winner = actor.memberId === row.host_member_id ? row.guest_member_id : row.host_member_id;
        } else {
          if (mark !== next) fail(409,'GAME_NOT_YOUR_TURN');
          if (board[input.cell] !== null) fail(409,'GAME_CELL_OCCUPIED');
          board[input.cell] = mark;
          line = LINES.find(cells => cells.every(cell => board[cell] === mark)) || null;
          if (line) { status = 'WON'; winner = actor.memberId; next = null; }
          else if (board.every(Boolean)) { status = 'DRAW'; next = null; }
          else next = mark === 'X' ? 'O' : 'X';
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
