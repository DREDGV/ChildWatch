const { randomUUID } = require('crypto');
const DeviceAccessService = require('./DeviceAccessService');
const Presence = require('./FamilyPlacePresencePolicy');
const instances = new WeakMap();

class FamilyPlacesService {
  static forDatabase(db) {
    if (!instances.has(db)) instances.set(db, new FamilyPlacesService(db));
    return instances.get(db);
  }
  constructor(db) { this.db = db; this.access = new DeviceAccessService(db); this.queue = Promise.resolve(); }
  async ensure() {
    if (!this.ready) this.ready = this.db.withTransaction(async () => {
      await this.db.run(`CREATE TABLE IF NOT EXISTS family_place_watches (
        id TEXT PRIMARY KEY, family_id TEXT NOT NULL, owner_member_id TEXT NOT NULL,
        target_member_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
        radius REAL NOT NULL, on_enter INTEGER NOT NULL, on_exit INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
        side TEXT, candidate TEXT, candidate_since INTEGER, candidate_count INTEGER DEFAULT 0,
        last_timestamp INTEGER DEFAULT 0, last_device_id TEXT, created_at INTEGER NOT NULL)`);
      await this.db.run(`CREATE TABLE IF NOT EXISTS family_place_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, watch_id TEXT NOT NULL, family_id TEXT NOT NULL,
        owner_member_id TEXT NOT NULL, target_member_id TEXT NOT NULL, device_id TEXT NOT NULL,
        person_name TEXT NOT NULL, place_name TEXT NOT NULL, transition TEXT NOT NULL,
        measured_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
        UNIQUE(watch_id, measured_at, transition))`);
      await this.db.run('CREATE INDEX IF NOT EXISTS idx_place_target ON family_place_watches(family_id, target_member_id, enabled)');
      await this.db.run('CREATE INDEX IF NOT EXISTS idx_place_events_owner ON family_place_events(family_id, owner_member_id, id)');
      await this.db.run('CREATE INDEX IF NOT EXISTS idx_place_events_history ON family_place_events(family_id, owner_member_id, target_member_id, id)');
      const additions = {
        family_place_watches: { last_certain_timestamp: 'INTEGER NOT NULL DEFAULT 0', observation_reason: 'TEXT', last_accuracy: 'REAL', last_distance: 'REAL' },
        family_place_events: { evidence_json: 'TEXT', policy_version: 'INTEGER NOT NULL DEFAULT 1' }
      };
      for (const [table, columns] of Object.entries(additions)) {
        const existing = new Set((await this.db.all(`PRAGMA table_info(${table})`)).map(row => row.name));
        for (const [name, type] of Object.entries(columns))
          if (!existing.has(name)) await this.db.run(`ALTER TABLE ${table} ADD COLUMN ${name} ${type}`);
      }
      // Reset immediately on revoke/grant, even when no GPS fix arrives during the gap.
      // Triggers run in the permission writer's transaction without cross-queue deadlocks.
      if (await this.db.get("SELECT name FROM sqlite_master WHERE type='table' AND name='family_permissions'")) {
        for (const operation of ['INSERT', 'UPDATE', 'DELETE']) {
          const row = operation === 'DELETE' ? 'OLD' : 'NEW';
          const changed = operation === 'UPDATE' ? ' AND OLD.allowed IS NOT NEW.allowed' : '';
          await this.db.run(`CREATE TRIGGER IF NOT EXISTS place_permission_reset_${operation.toLowerCase()}
            AFTER ${operation} ON family_permissions
            WHEN ${row}.feature IN ('LOCATION','LOCATION_HISTORY')${changed}
            BEGIN UPDATE family_place_watches SET side=NULL, candidate=NULL, candidate_since=NULL,
              candidate_count=0, last_timestamp=0, last_certain_timestamp=0, last_device_id=NULL,
              observation_reason='permission_reset', last_accuracy=NULL, last_distance=NULL
              WHERE family_id=${row}.family_id AND owner_member_id=${row}.actor_member_id
              AND target_member_id=${row}.target_member_id; END`);
        }
      }
    }).catch(error => { this.ready = null; throw error; });
    return this.ready;
  }
  async actor(deviceId, familyId) {
    if (await this.access.isDeviceRevoked(deviceId)) return null;
    const memberships = (await Promise.all(this.access.idForms(deviceId).map(id =>
      this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const member = memberships.find(m => m.familyId === familyId);
    return member ? { ...member, authenticatedDeviceId: deviceId } : null;
  }
  async atomicActor(actor, work) {
    return this.serial(async () => {
      await this.ensure();
      return this.db.withTransaction(async () => {
        const fresh = actor?.authenticatedDeviceId ? await this.actor(actor.authenticatedDeviceId, actor.familyId) : null;
        if (!fresh || fresh.memberId !== actor.memberId || fresh.memberRole !== actor.memberRole ||
            !['PARENT', 'GUARDIAN'].includes(fresh.memberRole))
          throw Object.assign(new Error('Place context changed'), { status: 403 });
        return work(fresh);
      });
    });
  }
  async allowed(familyId, ownerId, targetId) {
    const members = await this.db.getFamilyMembers(familyId);
    const owner = members.find(m => m.id === ownerId);
    const target = members.find(m => m.id === targetId);
    if (!owner || !target || !['PARENT', 'GUARDIAN'].includes(owner.role)) return false;
    if (ownerId === targetId) return true;
    const permissions = await Promise.all(['LOCATION', 'LOCATION_HISTORY'].map(feature =>
      this.db.getFamilyPermission({ familyId, actorMemberId: ownerId, targetMemberId: targetId, feature })));
    return permissions.every(p => p?.allowed === 1);
  }
  list(actor, targetId) { return this.atomicActor(actor, fresh => this.listInternal(fresh, targetId)); }
  async listInternal(actor, targetId) {
    await this.ensure();
    const rows = await this.db.all(`SELECT * FROM family_place_watches WHERE family_id=? AND owner_member_id=?
      ${targetId ? 'AND target_member_id=?' : ''} ORDER BY created_at DESC`,
      [actor.familyId, actor.memberId, ...(targetId ? [targetId] : [])]);
    const visible = [];
    for (const row of rows) if (await this.allowed(row.family_id, actor.memberId, row.target_member_id))
      visible.push({ ...row, presence: this.presenceView(row) });
    return visible;
  }
  presenceView(row, now = Date.now()) {
    const at = row.last_timestamp || 0, certain = row.last_certain_timestamp || 0;
    let status = 'WAITING';
    if (!row.enabled) status = 'PAUSED';
    else if (at > 0 && now - at > Presence.DEFAULTS.maxAgeMs) status = 'STALE';
    else if (['poor_accuracy', 'uncertain', 'invalid_coordinates'].includes(row.observation_reason)) status = 'UNCERTAIN';
    else if (certain > 0 && certain <= now && now - certain <= Presence.DEFAULTS.maxAgeMs && row.candidate) status = 'CONFIRMING';
    else if (certain > 0 && certain <= now && now - certain <= Presence.DEFAULTS.maxAgeMs) status = row.side || 'WAITING';
    return { status, measuredAt: at || null, confirmedAt: certain || null,
      candidate: row.candidate, reason: row.observation_reason, accuracyMeters: row.last_accuracy };
  }
  async createInternal(actor, input) {
    await this.ensure();
    const { targetMemberId, name, latitude, longitude, radius, onEnter, onExit } = input;
    if (typeof name !== 'string' || name.trim().length < 2 || name.trim().length > 80 ||
        !Number.isFinite(latitude) || Math.abs(latitude) > 90 || !Number.isFinite(longitude) || Math.abs(longitude) > 180 ||
        !Number.isFinite(radius) || radius < 100 || radius > 2000 ||
        typeof onEnter !== 'boolean' || typeof onExit !== 'boolean' || (!onEnter && !onExit))
      throw Object.assign(new Error('Invalid place'), { status: 400 });
    if (!await this.allowed(actor.familyId, actor.memberId, targetMemberId))
      throw Object.assign(new Error('Location and history permission required'), { status: 403 });
    const id = input.requestId || randomUUID();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw Object.assign(new Error('Invalid request id'), { status: 400 });
    const previous = await this.db.get('SELECT * FROM family_place_watches WHERE id=?', [id]);
    if (previous) {
      if (previous.family_id === actor.familyId && previous.owner_member_id === actor.memberId &&
          previous.target_member_id === targetMemberId && previous.name === name.trim() && previous.latitude === latitude &&
          previous.longitude === longitude && previous.radius === radius && previous.on_enter === Number(onEnter) && previous.on_exit === Number(onExit)) return id;
      throw Object.assign(new Error('Request already saved with different values'), { status: 409 });
    }
    const count = await this.db.get('SELECT COUNT(*) AS count FROM family_place_watches WHERE family_id=? AND owner_member_id=?', [actor.familyId, actor.memberId]);
    if (count.count >= 50) throw Object.assign(new Error('Limit of 50 places reached'), { status: 409 });
    await this.db.run(`INSERT INTO family_place_watches
      (id, family_id, owner_member_id, target_member_id, name, latitude, longitude, radius, on_enter, on_exit, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [id, actor.familyId, actor.memberId, targetMemberId, name.trim(), latitude, longitude, radius, onEnter ? 1 : 0, onExit ? 1 : 0, Date.now()]);
    return id;
  }
  async removeInternal(actor, id) {
    await this.ensure();
    return this.db.run('DELETE FROM family_place_watches WHERE id=? AND family_id=? AND owner_member_id=?', [id, actor.familyId, actor.memberId]);
  }
  async toggleInternal(actor, id, enabled) {
    await this.ensure();
    const row = await this.db.get('SELECT * FROM family_place_watches WHERE id=? AND family_id=? AND owner_member_id=?', [id, actor.familyId, actor.memberId]);
    if (!row || !await this.allowed(actor.familyId, actor.memberId, row.target_member_id))
      throw Object.assign(new Error('Place access denied'), { status: 403 });
    return this.db.run(`UPDATE family_place_watches SET enabled=?, side=NULL, candidate=NULL,
      candidate_since=NULL, candidate_count=0, last_timestamp=0, last_device_id=NULL,
      last_certain_timestamp=0, observation_reason=NULL, last_accuracy=NULL, last_distance=NULL WHERE id=?`, [enabled ? 1 : 0, id]);
  }
  serial(work) {
    const result = this.queue.then(work);
    this.queue = result.catch(() => {});
    return result;
  }
  create(actor, input) { return this.atomicActor(actor, fresh => this.createInternal(fresh, input)); }
  remove(actor, id) { return this.atomicActor(actor, fresh => this.removeInternal(fresh, id)); }
  toggle(actor, id, enabled) { return this.atomicActor(actor, fresh => this.toggleInternal(fresh, id, enabled)); }
  update(actor, id, input) { return this.atomicActor(actor, async actor => {
    await this.ensure();
    const row = await this.db.get('SELECT * FROM family_place_watches WHERE id=? AND family_id=? AND owner_member_id=?', [id, actor.familyId, actor.memberId]);
    if (!row || input.targetMemberId !== row.target_member_id || !await this.allowed(actor.familyId, actor.memberId, row.target_member_id))
      throw Object.assign(new Error('Place access denied'), { status: 403 });
    const { name, latitude, longitude, radius, onEnter, onExit } = input;
    if (typeof name !== 'string' || name.trim().length < 2 || name.trim().length > 80 ||
        !Number.isFinite(latitude) || Math.abs(latitude) > 90 || !Number.isFinite(longitude) || Math.abs(longitude) > 180 ||
        !Number.isFinite(radius) || radius < 100 || radius > 2000 || typeof onEnter !== 'boolean' || typeof onExit !== 'boolean' || (!onEnter && !onExit))
      throw Object.assign(new Error('Invalid place'), { status: 400 });
    await this.db.run(`UPDATE family_place_watches SET name=?, latitude=?, longitude=?, radius=?, on_enter=?, on_exit=?,
      side=NULL, candidate=NULL, candidate_since=NULL, candidate_count=0, last_timestamp=0, last_device_id=NULL,
      last_certain_timestamp=0, observation_reason=NULL, last_accuracy=NULL, last_distance=NULL WHERE id=?`,
      [name.trim(), latitude, longitude, radius, Number(onEnter), Number(onExit), id]);
  }); }
  events(actor, after) { return this.atomicActor(actor, fresh => this.eventsInternal(fresh, after)); }
  history(actor, targetMemberId, options = {}) {
    return this.atomicActor(actor, fresh => this.historyInternal(fresh, targetMemberId, options));
  }
  async historyInternal(actor, targetMemberId, options) {
    const invalid = message => Object.assign(new Error(message), { status: 400 });
    if (typeof targetMemberId !== 'string' || !targetMemberId.trim()) throw invalid('Target member required');
    const target = targetMemberId.trim();
    if (!await this.allowed(actor.familyId, actor.memberId, target))
      throw Object.assign(new Error('Location and history permission required'), { status: 403 });
    if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalid('Invalid history options');
    const { before, snapshot: requestedSnapshot, limit = 30 } = options;
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw invalid('Invalid history limit');
    if (requestedSnapshot !== undefined && (!Number.isSafeInteger(requestedSnapshot) || requestedSnapshot < 0))
      throw invalid('Invalid history snapshot');
    if (before !== undefined && (!Number.isSafeInteger(before) || before <= 0)) throw invalid('Invalid history cursor');
    if (before !== undefined && requestedSnapshot === undefined) throw invalid('Snapshot required for history paging');
    const scope = [actor.familyId, actor.memberId, target];
    const snapshot = requestedSnapshot === undefined
      ? (await this.db.get(`SELECT COALESCE(MAX(id),0) AS snapshot FROM family_place_events
          WHERE family_id=? AND owner_member_id=? AND target_member_id=?`, scope)).snapshot
      : requestedSnapshot;
    if (before !== undefined && before > snapshot) throw invalid('History cursor exceeds snapshot');
    const rows = await this.db.all(`SELECT * FROM family_place_events
      WHERE family_id=? AND owner_member_id=? AND target_member_id=? AND id<=? AND created_at>=?
      ${before !== undefined ? 'AND id<?' : ''} ORDER BY id DESC LIMIT ?`,
      [...scope, snapshot, Date.now() - 30 * 86400000, ...(before !== undefined ? [before] : []), limit + 1]);
    const hasMore = rows.length > limit;
    const events = rows.slice(0, limit);
    return { success: true, familyId: actor.familyId, ownerMemberId: actor.memberId, targetMemberId: target,
      events, snapshot, nextBefore: hasMore ? events[events.length - 1].id : null, hasMore, retentionDays: 30 };
  }
  async eventsInternal(actor, after) {
    if (!Number.isSafeInteger(after) || after < 0) throw Object.assign(new Error('Invalid cursor'), { status: 400 });
    await this.ensure();
    // Per-device cursor lives on the client. Revocation is rechecked when replaying events.
    const rows = await this.db.all(`SELECT * FROM family_place_events WHERE family_id=? AND owner_member_id=? AND id>?
      AND created_at>=? ORDER BY id LIMIT 100`, [actor.familyId, actor.memberId, after, Date.now() - 7 * 86400000]);
    const visible = [];
    for (const row of rows) {
      const watch = await this.db.get('SELECT enabled FROM family_place_watches WHERE id=?', [row.watch_id]);
      if (watch?.enabled === 1 && await this.allowed(row.family_id, actor.memberId, row.target_member_id)) visible.push(row);
    }
    return { familyId: actor.familyId, ownerMemberId: actor.memberId,
      events: visible, cursor: rows.length ? rows[rows.length - 1].id : after };
  }
  onLocation(deviceId, point) {
    return this.serial(() => this.evaluate(deviceId, point));
  }
  async evaluate(deviceId, point) {
    if (!point) return;
    const { latitude, longitude, accuracy } = point;
    let timestamp = Number(point.timestamp);
    if (timestamp > 0 && timestamp < 10000000000) timestamp *= 1000;
    const age = Date.now() - timestamp;
    if (!Number.isSafeInteger(timestamp) || timestamp <= 0 || age < 0 || age > Presence.DEFAULTS.maxAgeMs) return;
    await this.ensure();
    return this.db.withTransaction(() => this.evaluateInternal(deviceId, { latitude, longitude, accuracy, timestamp }));
  }
  async evaluateInternal(deviceId, point) {
    const { timestamp } = point;
    if (await this.access.isDeviceRevoked(deviceId)) return;
    const memberships = (await Promise.all(this.access.idForms(deviceId).map(id =>
      this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const unique = [...new Map(memberships.map(m => [m.familyId + ':' + m.memberId, m])).values()];
    for (const member of unique) {
      const watches = await this.db.all('SELECT * FROM family_place_watches WHERE family_id=? AND target_member_id=? AND enabled=1', [member.familyId, member.memberId]);
      for (const watch of watches) {
        if (timestamp <= watch.last_timestamp) continue;
        if (!await this.allowed(watch.family_id, watch.owner_member_id, watch.target_member_id)) {
          await this.db.run(`UPDATE family_place_watches SET side=NULL, candidate=NULL, candidate_since=NULL,
            candidate_count=0, last_certain_timestamp=0, last_timestamp=?, observation_reason='permission_reset' WHERE id=?`, [timestamp, watch.id]);
          continue;
        }
        const canonicalDevice = this.access.idForms(deviceId).sort().join('|');
        const decision = Presence.step(watch, point, watch, Date.now(), canonicalDevice);
        if (decision.ignored) continue;
        if (decision.transition) {
          const enabled = decision.transition === 'ENTER' ? watch.on_enter : watch.on_exit;
          if (enabled) await this.db.run(`INSERT OR IGNORE INTO family_place_events
            (watch_id, family_id, owner_member_id, target_member_id, device_id, person_name, place_name, transition, measured_at, created_at, evidence_json, policy_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [watch.id, watch.family_id, watch.owner_member_id,
              member.memberId, deviceId, member.memberDisplayName || 'Участник', watch.name, decision.transition,
              timestamp, Date.now(), JSON.stringify(decision.evidence), 2]);
        }
        const s = decision.state;
        await this.db.run(`UPDATE family_place_watches SET side=?, candidate=?, candidate_since=?, candidate_count=?,
          last_timestamp=?, last_device_id=?, last_certain_timestamp=?, observation_reason=?, last_accuracy=?, last_distance=? WHERE id=?`,
          [s.side, s.candidate, s.candidate_since, s.candidate_count, s.last_timestamp, s.last_device_id,
            s.last_certain_timestamp, decision.reason, decision.evidence.accuracyMeters, decision.evidence.distanceMeters, watch.id]);
      }
    }
    // Once per day rather than cleanup work on every GPS fix.
    if (!this.lastCleanup || Date.now() - this.lastCleanup > 86400000) {
      await this.db.run('DELETE FROM family_place_events WHERE created_at<?', [Date.now() - 30 * 86400000]);
      this.lastCleanup = Date.now();
    }
  }
  static distance(lat1, lon1, lat2, lon2) {
    const rad = value => value * Math.PI / 180;
    const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
  }
}
module.exports = FamilyPlacesService;
