const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const os = require('os');
const Engine = require('./ChatTranscriptionRunner');
const ErrorType = Engine.TranscriptionError;
const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED'];
const LEASE_MS = 15000;
const TEMP_TTL_MS = 60 * 60 * 1000;
const liveOwners = new Set();
function processIdentity(pid) {
  if (process.platform !== 'linux' || !Number.isSafeInteger(pid) || pid <= 1) return null;
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
    const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ');
    return fields[0] === 'Z' ? null : fields[19];
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ESRCH') return null;
    throw error; // An unreadable owner cannot safely be treated as a dead owner.
  }
}

class ChatTranscriptionService {
  constructor(chat, options = {}) {
    this.chat = chat; this.db = chat.dbManager;
    this.root = path.resolve(options.root || process.env.CHAT_TRANSCRIPTION_ROOT || path.join(__dirname, '../private-data/chat-transcriptions'));
    this.temp = path.join(this.root, 'staging');
    fs.mkdirSync(this.temp, { recursive: true, mode: 0o700 });
    this.configuration = (options.configuration ? Promise.resolve(options.configuration) : Engine.loadConfiguration())
      .catch(() => ({ enabled: false, reason: 'TRANSCRIPTION_NOT_READY' }));
    this.runner = options.runner || Engine.runWhisper;
    this.owner = crypto.randomUUID(); this.stopped = false; this.busy = false; this.current = null;
    liveOwners.add(this.owner);
    this.autoStart = options.autoStart !== false;
    this.db.db?.once('close', () => this.stop());
  }
  async ensure() {
    if (!this.ready) this.ready = (async () => {
      await this.db.run(`CREATE TABLE IF NOT EXISTS chat_transcription_jobs (
        id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, family_id TEXT NOT NULL,
        owner_member_id TEXT NOT NULL, owner_device_id TEXT NOT NULL, client_request_id TEXT NOT NULL,
        sha256 TEXT NOT NULL, language TEXT NOT NULL, duration_ms INTEGER NOT NULL,
        state TEXT NOT NULL, text TEXT, error_code TEXT, created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL, completed_at INTEGER, cleaned_at INTEGER, lease_owner TEXT,
        UNIQUE(conversation_id,owner_device_id,client_request_id),
        FOREIGN KEY(conversation_id) REFERENCES chat_conversations(id))`);
      await this.db.run('CREATE INDEX IF NOT EXISTS idx_chat_transcription_queue ON chat_transcription_jobs(state,created_at)');
      await this.db.run(`CREATE TABLE IF NOT EXISTS chat_transcription_worker_lease (
        id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, expires_at INTEGER NOT NULL,
        owner_pid INTEGER, owner_start TEXT, owner_host TEXT)`);
      const columns = new Set((await this.db.all('PRAGMA table_info(chat_transcription_worker_lease)')).map(row => row.name));
      for (const [name, type] of [['owner_pid', 'INTEGER'], ['owner_start', 'TEXT'], ['owner_host', 'TEXT']]) {
        if (!columns.has(name)) await this.db.run(`ALTER TABLE chat_transcription_worker_lease ADD COLUMN ${name} ${type}`);
      }
    })().catch(error => { this.ready = null; throw error; });
    await this.ready;
  }
  async capability() {
    const config = await this.configuration;
    return { transcription: config.enabled === true,
      transcriptionReason: config.enabled ? null : config.reason || 'TRANSCRIPTION_NOT_READY',
      transcriptionMaxDurationMs: Engine.MAX_DURATION_MS };
  }
  start() {
    if (this.timer || this.stopped || !this.autoStart) return;
    this.timer = setInterval(() => this.tick().catch(error => {
      if (!this.stopped) console.warn('Chat transcription worker deferred:', error.message);
    }), 2000);
    this.timer.unref();
    this.tick().catch(error => { if (!this.stopped) console.warn('Chat transcription startup deferred:', error.message); });
    this.gcTimer = setInterval(() => this.gc().catch(() => {}), 5 * 60 * 1000);
    this.gcTimer.unref();
  }
  stop() {
    this.stopped = true; clearInterval(this.timer); clearInterval(this.gcTimer);
    this.current?.controller.abort();
    if (!this.busy) liveOwners.delete(this.owner);
    for (const iterator of this.gcDirectories?.values() || []) iterator.close().catch(() => {});
    this.gcDirectories?.clear();
  }
  input(id) {
    if (!/^[0-9a-f-]{36}$/.test(id)) throw new ErrorType(404, 'TRANSCRIPTION_NOT_FOUND');
    return path.join(this.root, id + '.m4a');
  }
  dto(row) {
    return { jobId: row.id, clientRequestId: row.client_request_id, state: row.state,
      text: row.state === 'SUCCEEDED' ? row.text : null, errorCode: row.error_code,
      createdAt: row.created_at, updatedAt: row.updated_at, completedAt: row.completed_at };
  }
  validateId(value) {
    if (typeof value !== 'string' || !value.trim() || value.length > 200 || /[\x00-\x1f\x7f]/.test(value)) throw new ErrorType(400, 'INVALID_TRANSCRIPTION_REQUEST_ID');
    return value;
  }
  async assertOwner(row, deviceId, conversationId) {
    const actor = await this.chat.resolveConversationActor(deviceId, conversationId);
    if (!row || row.conversation_id !== actor.conversation.id || row.owner_device_id !== actor.deviceId ||
        row.owner_member_id !== actor.memberId || row.family_id !== actor.familyId) throw new ErrorType(404, 'TRANSCRIPTION_NOT_FOUND');
    return actor;
  }
  async create(actor, file, input) {
    if (!file) throw new ErrorType(400, 'TRANSCRIPTION_AUDIO_REQUIRED');
    try {
      const cap = await this.capability();
      if (!cap.transcription) throw new ErrorType(503, cap.transcriptionReason);
      const clientId = this.validateId(input.clientRequestId);
      const language = input.language === undefined ? 'ru' : input.language;
      if (language !== 'ru') throw new ErrorType(400, 'INVALID_TRANSCRIPTION_LANGUAGE');
      const durationMs = Number(input.durationMs);
      if (!Number.isInteger(durationMs) || durationMs < 1 || durationMs > Engine.MAX_DURATION_MS) throw new ErrorType(422, 'TRANSCRIPTION_INVALID_DURATION');
      const stat = await fsp.stat(file.path);
      if (!stat.isFile() || stat.size < 12 || stat.size > Engine.MAX_INPUT_BYTES) throw new ErrorType(413, 'TRANSCRIPTION_AUDIO_TOO_LARGE');
      const handle = await fsp.open(file.path, 'r'); const header = Buffer.alloc(12);
      try { await handle.read(header, 0, 12, 0); } finally { await handle.close(); }
      if (header.toString('ascii', 4, 8) !== 'ftyp') throw new ErrorType(415, 'TRANSCRIPTION_INVALID_AUDIO');
      const sha = await Engine.sha256(file.path);
      await this.ensure();
      const result = await this.db.withTransaction(async () => {
        // Resolve again within transaction: membership could have changed during upload/hash.
        const current = await this.chat.resolveConversationActor(actor.deviceId, actor.conversation.id);
        if (current.memberId !== actor.memberId || current.familyId !== actor.familyId) throw new ErrorType(403, 'TRANSCRIPTION_ACCESS_CHANGED');
        const old = await this.db.get('SELECT * FROM chat_transcription_jobs WHERE conversation_id=? AND owner_device_id=? AND client_request_id=?', [actor.conversation.id, actor.deviceId, clientId]);
        if (old) {
          await this.assertOwner(old, actor.deviceId, actor.conversation.id);
          if (old.sha256 !== sha || old.language !== language || old.duration_ms !== durationMs) throw new ErrorType(409, 'TRANSCRIPTION_REQUEST_CONFLICT');
          return { job: this.dto(old), created: false };
        }
        const pending = await this.db.get(`SELECT COUNT(*) total,
          SUM(CASE WHEN owner_device_id=? THEN 1 ELSE 0 END) own
          FROM chat_transcription_jobs WHERE state IN ('QUEUED','RUNNING')`, [actor.deviceId]);
        if (pending.total >= 20 || pending.own >= 3) throw new ErrorType(429, 'TRANSCRIPTION_QUEUE_FULL');
        const id = crypto.randomUUID(), now = Date.now();
        await fsp.rename(file.path, this.input(id));
        try {
          await this.db.run(`INSERT INTO chat_transcription_jobs(id,conversation_id,family_id,owner_member_id,owner_device_id,
            client_request_id,sha256,language,duration_ms,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?)`,
          [id, actor.conversation.id, actor.familyId, actor.memberId, actor.deviceId, clientId, sha, language, durationMs, now, now]);
        } catch (error) { await fsp.rm(this.input(id), { force: true }); throw error; }
        const row = await this.db.get('SELECT * FROM chat_transcription_jobs WHERE id=?', [id]);
        return { job: this.dto(row), created: true };
      });
      this.start();
      return result;
    } finally { await fsp.rm(file.path, { force: true }).catch(() => {}); }
  }
  async read(deviceId, conversationId, clientId) {
    this.validateId(clientId); await this.ensure();
    const row = await this.db.get('SELECT * FROM chat_transcription_jobs WHERE conversation_id=? AND owner_device_id=? AND client_request_id=?', [conversationId, deviceId, clientId]);
    await this.assertOwner(row, deviceId, conversationId);
    return this.dto(row);
  }
  async cancel(deviceId, conversationId, clientId) {
    await this.ensure(); this.validateId(clientId);
    let id;
    const result = await this.db.withTransaction(async () => {
      const row = await this.db.get('SELECT * FROM chat_transcription_jobs WHERE conversation_id=? AND owner_device_id=? AND client_request_id=?', [conversationId, deviceId, clientId]);
      await this.assertOwner(row, deviceId, conversationId); id = row.id;
      const now = Date.now();
      await this.db.run(`UPDATE chat_transcription_jobs SET state='CANCELLED',text=NULL,error_code='TRANSCRIPTION_CANCELLED',updated_at=?,completed_at=COALESCE(completed_at,?) WHERE id=?`, [now, now, id]);
      return this.dto(await this.db.get('SELECT * FROM chat_transcription_jobs WHERE id=?', [id]));
    });
    if (this.current?.id === id) this.current.controller.abort();
    return result;
  }
  async claim() {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const now = Date.now();
      const ownStart = processIdentity(process.pid);
      if (process.platform === 'linux' && !ownStart) throw new ErrorType(503, 'TRANSCRIPTION_NOT_READY');
      const lease = await this.db.get('SELECT * FROM chat_transcription_worker_lease WHERE id=1');
      if (lease && lease.owner !== this.owner && lease.expires_at > now) return null;
      // Fencing alone would allow a second engine during a live owner's event-loop stall.
      // On Linux, retain ownership until the exact PID/start identity has disappeared.
      if (lease && lease.owner !== this.owner && lease.owner_pid && lease.owner_start) {
        if (lease.owner_host !== os.hostname()) return null;
        if (processIdentity(lease.owner_pid) === lease.owner_start &&
            (lease.owner_pid !== process.pid || liveOwners.has(lease.owner))) return null;
      }
      if (!lease || lease.owner !== this.owner || lease.expires_at <= now) {
        // A different worker can recover only after the global lease expires; its result is fenced by owner.
        await this.db.run(`UPDATE chat_transcription_jobs SET state='QUEUED',lease_owner=NULL,updated_at=? WHERE state='RUNNING'`, [now]);
      }
      await this.db.run('INSERT OR REPLACE INTO chat_transcription_worker_lease(id,owner,expires_at,owner_pid,owner_start,owner_host) VALUES(1,?,?,?,?,?)',
        [this.owner, now + LEASE_MS, process.pid, ownStart, os.hostname()]);
      const row = await this.db.get("SELECT * FROM chat_transcription_jobs WHERE state='QUEUED' ORDER BY created_at,id LIMIT 1");
      if (!row) return null;
      await this.db.run("UPDATE chat_transcription_jobs SET state='RUNNING',lease_owner=?,updated_at=? WHERE id=? AND state='QUEUED'", [this.owner, now, row.id]);
      return { ...row, state: 'RUNNING', lease_owner: this.owner };
    });
  }
  async tick() {
    if (this.busy || this.stopped) return;
    if (!(await this.configuration).enabled || this.busy || this.stopped) return;
    this.busy = true;
    try {
      const row = await this.claim(); if (!row) return;
      const controller = new AbortController(); this.current = { id: row.id, controller };
      let renewing = false;
      const renew = setInterval(async () => {
        if (renewing || this.stopped) return;
        renewing = true;
        try {
          const now = Date.now();
          const changed = await this.db.run('UPDATE chat_transcription_worker_lease SET expires_at=? WHERE id=1 AND owner=? AND expires_at>?', [now + LEASE_MS, this.owner, now]);
          const job = await this.db.get('SELECT state,lease_owner FROM chat_transcription_jobs WHERE id=?', [row.id]);
          if (changed.changes !== 1 || job?.state !== 'RUNNING' || job.lease_owner !== this.owner) controller.abort();
          if (!controller.signal.aborted) await this.assertOwner(row, row.owner_device_id, row.conversation_id);
        } catch { controller.abort(); } finally { renewing = false; }
      }, 3000);
      renew.unref();
      const directory = path.join(this.root, row.id + '-' + this.owner + '.work');
      let text, errorCode;
      try {
        await this.assertOwner(row, row.owner_device_id, row.conversation_id);
        if (await Engine.sha256(this.input(row.id)) !== row.sha256) throw new ErrorType(422, 'TRANSCRIPTION_INVALID_AUDIO');
        await fsp.mkdir(directory, { mode: 0o700 });
        text = await this.runner({ input: this.input(row.id), directory, language: row.language,
          signal: controller.signal, config: await this.configuration });
        if (typeof text !== 'string' || Buffer.byteLength(text) > 16 * 1024) throw new ErrorType(422, 'TRANSCRIPTION_INVALID_OUTPUT');
        await this.assertOwner(row, row.owner_device_id, row.conversation_id);
        if (controller.signal.aborted) throw new ErrorType(409, 'TRANSCRIPTION_CANCELLED');
      } catch (error) { errorCode = error instanceof ErrorType ? error.code : 'TRANSCRIPTION_FAILED'; }
      finally { clearInterval(renew); await fsp.rm(directory, { force: true, recursive: true }).catch(() => {}); this.current = null; }
      await this.db.withTransaction(async () => {
        const now = Date.now();
        const lease = await this.db.get('SELECT * FROM chat_transcription_worker_lease WHERE id=1');
        if (lease?.owner !== this.owner || lease.expires_at <= now || this.stopped) return;
        // Permission checking and output publication share the transaction.
        try { await this.assertOwner(row, row.owner_device_id, row.conversation_id); }
        catch { errorCode = 'TRANSCRIPTION_ACCESS_CHANGED'; text = null; }
        await this.db.run(`UPDATE chat_transcription_jobs SET state=?,text=?,error_code=?,updated_at=?,completed_at=?,lease_owner=NULL
          WHERE id=? AND state='RUNNING' AND lease_owner=?`,
        [errorCode ? 'FAILED' : 'SUCCEEDED', errorCode ? null : text.trim(), errorCode || null, now, now, row.id, this.owner]);
      });
    } finally { this.busy = false; if (this.stopped) liveOwners.delete(this.owner); }
  }
  async gc(now = Date.now()) {
    if (this.gcBusy || this.stopped) return;
    this.gcBusy = true;
    try {
      await this.ensure();
      const rows = await this.db.all(`SELECT id FROM chat_transcription_jobs WHERE state IN ('SUCCEEDED','FAILED','CANCELLED')
        AND completed_at<? AND cleaned_at IS NULL LIMIT 100`, [now - TEMP_TTL_MS]);
      for (const row of rows) {
        await fsp.rm(this.input(row.id), { force: true });
        await this.db.run('UPDATE chat_transcription_jobs SET cleaned_at=? WHERE id=?', [now, row.id]);
      }
      // Keep an iterator between passes so large private directories do not starve later files.
      for (const directory of [this.root, this.temp]) {
        this.gcDirectories ||= new Map();
        let iterator = this.gcDirectories.get(directory);
        if (!iterator) { iterator = await fsp.opendir(directory); this.gcDirectories.set(directory, iterator); }
        for (let count = 0; count < 100; count++) {
          const entry = await iterator.read();
          if (!entry) { await iterator.close(); this.gcDirectories.delete(directory); break; }
          if (entry.isDirectory() && directory === this.root && /^[0-9a-f-]{36}-[0-9a-f-]{36}\.work$/.test(entry.name)) {
            const target = path.resolve(directory, entry.name);
            if (!target.startsWith(this.root + path.sep)) throw new Error('Transcription cleanup path escaped private root');
            const stat = await fsp.stat(target).catch(() => null);
            if (!stat || stat.mtimeMs >= now - TEMP_TTL_MS) continue;
            const row = await this.db.get('SELECT state,lease_owner FROM chat_transcription_jobs WHERE id=?', [entry.name.slice(0, 36)]);
            const lease = await this.db.get('SELECT owner,expires_at FROM chat_transcription_worker_lease WHERE id=1');
            if (row?.state === 'RUNNING' && row.lease_owner === entry.name.slice(37, 73) && lease?.owner === row.lease_owner && lease.expires_at > now) continue;
            await fsp.rm(target, { recursive: true, force: true });
            continue;
          }
          if (!entry.isFile() || !/^[0-9a-f-]{36}\.(m4a|part)$/.test(entry.name)) continue;
          const filename = path.join(directory, entry.name), stat = await fsp.stat(filename).catch(() => null);
          if (!stat || stat.mtimeMs >= now - TEMP_TTL_MS) continue;
          if (entry.name.endsWith('.m4a') && await this.db.get('SELECT id FROM chat_transcription_jobs WHERE id=?', [entry.name.slice(0, -4)])) continue;
          await fsp.rm(filename, { force: true });
        }
      }
    } finally { this.gcBusy = false; }
  }
}
ChatTranscriptionService.Error = ErrorType;
ChatTranscriptionService.LEASE_MS = LEASE_MS;
ChatTranscriptionService.TEMP_TTL_MS = TEMP_TTL_MS;
module.exports = ChatTranscriptionService;
