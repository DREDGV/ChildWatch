const { randomUUID } = require('crypto');
const DeviceAccessService = require('./DeviceAccessService');
const instances = new WeakMap();

class FamilyPlacesService {
  static forDatabase(db) {
    if (!instances.has(db)) instances.set(db, new FamilyPlacesService(db));
    return instances.get(db);
  }
  constructor(db) { this.db = db; this.access = new DeviceAccessService(db); this.queue = Promise.resolve(); }
  async ensure() {
    if (!this.ready) this.ready = (async () => {
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
    })().catch(error => { this.ready = null; throw error; });
    return this.ready;
  }
  async actor(deviceId, familyId) {
    const memberships = (await Promise.all(this.access.idForms(deviceId).map(id =>
      this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    return memberships.find(m => m.familyId === familyId) || null;
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
  async list(actor, targetId) {
    await this.ensure();
    const rows = await this.db.all(`SELECT * FROM family_place_watches WHERE family_id=? AND owner_member_id=?
      ${targetId ? 'AND target_member_id=?' : ''} ORDER BY created_at DESC`,
      [actor.familyId, actor.memberId, ...(targetId ? [targetId] : [])]);
    const visible = [];
    for (const row of rows) if (await this.allowed(row.family_id, actor.memberId, row.target_member_id)) visible.push(row);
    return visible;
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
    if (!/^[0-9a-f-]{36}$/i.test(id)) throw Object.assign(new Error('Invalid request id'), { status: 400 });
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
      candidate_since=NULL, candidate_count=0, last_timestamp=0, last_device_id=NULL WHERE id=?`, [enabled ? 1 : 0, id]);
  }
  serial(work) {
    const result = this.queue.then(work);
    this.queue = result.catch(() => {});
    return result;
  }
  create(actor, input) { return this.serial(() => this.createInternal(actor, input)); }
  remove(actor, id) { return this.serial(() => this.removeInternal(actor, id)); }
  toggle(actor, id, enabled) { return this.serial(() => this.toggleInternal(actor, id, enabled)); }
  update(actor, id, input) { return this.serial(async () => {
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
      side=NULL, candidate=NULL, candidate_count=0, last_timestamp=0, last_device_id=NULL WHERE id=?`,
      [name.trim(), latitude, longitude, radius, Number(onEnter), Number(onExit), id]);
  }); }
  async events(actor, after) {
    await this.ensure();
    // Per-device cursor lives on the client. Revocation is rechecked when replaying events.
    const rows = await this.db.all(`SELECT * FROM family_place_events WHERE family_id=? AND owner_member_id=? AND id>?
      AND created_at>=? ORDER BY id LIMIT 100`, [actor.familyId, actor.memberId, after, Date.now() - 7 * 86400000]);
    const visible = [];
    for (const row of rows) {
      const watch = await this.db.get('SELECT enabled FROM family_place_watches WHERE id=?', [row.watch_id]);
      if (watch?.enabled === 1 && await this.allowed(row.family_id, actor.memberId, row.target_member_id)) visible.push(row);
    }
    return { events: visible, cursor: rows.length ? rows[rows.length - 1].id : after };
  }
  onLocation(deviceId, point) {
    return this.serial(() => this.evaluate(deviceId, point));
  }
  async evaluate(deviceId, point) {
    const latitude = Number(point.latitude), longitude = Number(point.longitude), accuracy = Number(point.accuracy);
    let timestamp = Number(point.timestamp);
    if (timestamp > 0 && timestamp < 10000000000) timestamp *= 1000;
    const age = Date.now() - timestamp;
    if (!Number.isFinite(latitude) || Math.abs(latitude) > 90 || !Number.isFinite(longitude) || Math.abs(longitude) > 180 ||
        !Number.isFinite(timestamp) || age < -60000 || age > 300000 || !Number.isFinite(accuracy) || accuracy <= 0 || accuracy > 500) return;
    await this.ensure();
    const memberships = (await Promise.all(this.access.idForms(deviceId).map(id =>
      this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const unique = [...new Map(memberships.map(m => [m.familyId + ':' + m.memberId, m])).values()];
    for (const member of unique) {
      const watches = await this.db.all('SELECT * FROM family_place_watches WHERE family_id=? AND target_member_id=? AND enabled=1', [member.familyId, member.memberId]);
      for (const watch of watches) {
        if (timestamp <= watch.last_timestamp || accuracy > watch.radius / 2) continue;
        if (!await this.allowed(watch.family_id, watch.owner_member_id, watch.target_member_id)) {
          await this.db.run('UPDATE family_place_watches SET side=NULL, candidate=NULL, last_timestamp=? WHERE id=?', [timestamp, watch.id]);
          continue;
        }
        const distance = FamilyPlacesService.distance(watch.latitude, watch.longitude, latitude, longitude);
        const side = distance + accuracy < watch.radius - 25 ? 'INSIDE' :
          distance - accuracy > watch.radius + 25 ? 'OUTSIDE' : null;
        const canonicalDevice = this.access.idForms(deviceId).sort().join('|');
        const gap = timestamp - watch.last_timestamp > 600000;
        const replaced = watch.last_device_id && watch.last_device_id !== canonicalDevice;
        if (side === null) {
          await this.db.run('UPDATE family_place_watches SET candidate=NULL, candidate_count=0, last_timestamp=? WHERE id=?', [timestamp, watch.id]);
          continue;
        }
        // First observation, a long gap or a replacement handset establishes a baseline.
        if (!watch.side || gap || replaced) {
          await this.db.run(`UPDATE family_place_watches SET side=?, candidate=NULL, candidate_count=0,
            last_timestamp=?, last_device_id=? WHERE id=?`, [side, timestamp, canonicalDevice, watch.id]);
          continue;
        }
        const count = watch.candidate === side ? watch.candidate_count + 1 : 1;
        const since = watch.candidate === side ? watch.candidate_since : timestamp;
        if (side !== watch.side && count >= 2 && timestamp - since >= 15000) {
          const transition = side === 'INSIDE' ? 'ENTER' : 'EXIT';
          const enabled = transition === 'ENTER' ? watch.on_enter : watch.on_exit;
          if (enabled) await this.db.run(`INSERT OR IGNORE INTO family_place_events
            (watch_id, family_id, owner_member_id, target_member_id, device_id, person_name, place_name, transition, measured_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [watch.id, watch.family_id, watch.owner_member_id,
              member.memberId, deviceId, member.memberDisplayName, watch.name, transition, timestamp, Date.now()]);
          await this.db.run(`UPDATE family_place_watches SET side=?, candidate=NULL, candidate_count=0,
            last_timestamp=?, last_device_id=? WHERE id=?`, [side, timestamp, canonicalDevice, watch.id]);
        } else {
          await this.db.run(`UPDATE family_place_watches SET candidate=?, candidate_since=?, candidate_count=?,
            last_timestamp=?, last_device_id=? WHERE id=?`, [side === watch.side ? null : side, since,
              side === watch.side ? 0 : count, timestamp, canonicalDevice, watch.id]);
        }
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
