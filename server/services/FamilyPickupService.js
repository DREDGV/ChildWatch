const DeviceAccessService = require('./DeviceAccessService');
const ACTIVE = ['REQUESTED', 'ACCEPTED', 'EN_ROUTE', 'ARRIVED', 'HANDOFF'];
const fail = (status, code) => { throw Object.assign(new Error(code), { status, code }); };
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);

/** A pickup is a consented meeting point, not a claim about live GPS or an SOS. */
class FamilyPickupService {
  constructor(db) { this.db = db; this.access = new DeviceAccessService(db); }
  async ensure() {
    if (!this.ready) this.ready = (async () => {
      await this.db.run(`CREATE TABLE IF NOT EXISTS family_pickups (
        id TEXT PRIMARY KEY, family_id TEXT NOT NULL, child_member_id TEXT NOT NULL,
        payload TEXT NOT NULL, status TEXT NOT NULL, adult_member_id TEXT,
        child_confirmed INTEGER NOT NULL DEFAULT 0, adult_confirmed INTEGER NOT NULL DEFAULT 0,
        version INTEGER NOT NULL DEFAULT 1, journal TEXT NOT NULL, created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)`);
      await this.db.run(`CREATE UNIQUE INDEX IF NOT EXISTS pickup_one_active_child ON family_pickups(family_id,child_member_id)
        WHERE status IN ('REQUESTED','ACCEPTED','EN_ROUTE','ARRIVED','HANDOFF')`);
      await this.db.run('CREATE INDEX IF NOT EXISTS pickup_family_updated ON family_pickups(family_id,updated_at)');
    })().catch(error => { this.ready = null; throw error; });
    await this.ready;
  }
  async actor(deviceId, familyId) {
    if (!familyId || familyId.length > 160) fail(400, 'PICKUP_FAMILY_REQUIRED');
    if (await this.access.isDeviceRevoked(deviceId)) fail(403,'PICKUP_ACCESS_DENIED');
    const identities = (await Promise.all(this.access.idForms(deviceId).map(id => this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const actor = identities.find(m => m.familyId === familyId);
    if (!actor || !['CHILD', 'PARENT', 'GUARDIAN'].includes(actor.memberRole)) fail(403, 'PICKUP_ACCESS_DENIED');
    return { ...actor, authenticatedDeviceId: deviceId };
  }
  async authorized(actor, childId, members = null) {
    members = members || await this.db.getFamilyMembers(actor.familyId);
    const child = members.find(m => m.id === childId && m.role === 'CHILD');
    const self = members.find(m => m.id === actor.memberId && m.role === actor.memberRole);
    if (!child || !self) return false;
    if (actor.memberRole === 'CHILD') return actor.memberId === childId;
    const permission = await this.db.getFamilyPermission({ familyId: actor.familyId,
      actorMemberId: actor.memberId, targetMemberId: childId, feature: 'LOCATION' });
    return permission?.allowed === 1;
  }
  async expire(familyId) {
    await this.ensure();
    const now = Date.now();
    // One atomic statement: an expired request cannot be claimed, even after server restart.
    await this.db.run(`UPDATE family_pickups SET status='EXPIRED',version=version+1,updated_at=?
      WHERE family_id=? AND expires_at<=? AND status IN ('REQUESTED','ACCEPTED','EN_ROUTE','ARRIVED','HANDOFF')`, [now,familyId,now]);
  }
  async view(row, actor, members) {
    const child = members.find(m => m.id === row.child_member_id);
    const adult = members.find(m => m.id === row.adult_member_id);
    const journal = JSON.parse(row.journal);
    return { id: row.id, familyId: row.family_id, childMemberId: row.child_member_id,
      childName: child?.displayName || child?.display_name || child?.name || 'Ребёнок',
      adultMemberId: row.adult_member_id, adultName: adult?.displayName || adult?.display_name || adult?.name || 'Родитель',
      ...JSON.parse(row.payload), status: row.status, version: row.version,
      childConfirmed: !!row.child_confirmed, adultConfirmed: !!row.adult_confirmed,
      createdAt: row.created_at, updatedAt: row.updated_at, expiresAt: row.expires_at,
      events: journal.map(e => ({ action: e.action, actorMemberId: e.actor, at: e.at })),
      // A response acknowledgement is not confirmation that another phone has read it.
      ownActionIds: journal.filter(e => e.actor === actor.memberId).map(e => e.id) };
  }
  async list(actor) {
    await this.expire(actor.familyId);
    const rows = await this.db.all(`SELECT * FROM family_pickups WHERE family_id=?
      AND (status IN ('REQUESTED','ACCEPTED','EN_ROUTE','ARRIVED','HANDOFF') OR updated_at>?)
      ORDER BY CASE WHEN status IN ('REQUESTED','ACCEPTED','EN_ROUTE','ARRIVED','HANDOFF') THEN 0 ELSE 1 END, updated_at DESC`,
    [actor.familyId, Date.now()-86400000]);
    const members = await this.db.getFamilyMembers(actor.familyId);
    const visible = [];
    const permissions = new Map();
    for (const row of rows) {
      if (!permissions.has(row.child_member_id)) permissions.set(row.child_member_id,await this.authorized(actor,row.child_member_id,members));
      if (permissions.get(row.child_member_id)) visible.push(await this.view(row,actor,members));
    }
    return { familyId: actor.familyId, actor: { memberId: actor.memberId, role: actor.memberRole }, requests: visible.slice(0,100), truncated: visible.length>100 };
  }
  normalize(input) {
    if (!uuid(input.requestId) || !Number.isFinite(input.latitude) || Math.abs(input.latitude)>90 ||
      !Number.isFinite(input.longitude) || Math.abs(input.longitude)>180 ||
      typeof input.place!=='string' || !input.place.trim() || input.place.trim().length>120 ||
      typeof input.note!=='string' || input.note.trim().length>280 || input.pointConfirmed!==true)
      fail(400,'PICKUP_INVALID_POINT');
    return JSON.stringify({ latitude:input.latitude,longitude:input.longitude,place:input.place.trim(),note:input.note.trim(),pointSource:'MANUAL_CONFIRMED' });
  }
  async create(actor,input) {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const fresh = await this.actor(actor.authenticatedDeviceId,actor.familyId);
      if (fresh.memberId!==actor.memberId || fresh.memberRole!==actor.memberRole) fail(403,'PICKUP_CONTEXT_CHANGED');
      return this.createInternal(fresh,input);
    });
  }
  async createInternal(actor,input) {
    await this.expire(actor.familyId);
    if (actor.memberRole!=='CHILD' || !await this.authorized(actor,actor.memberId)) fail(403,'PICKUP_CHILD_ONLY');
    const payload = this.normalize(input);
    const previous = await this.db.get('SELECT * FROM family_pickups WHERE id=?',[input.requestId]);
    if (previous) {
      if (previous.family_id!==actor.familyId || previous.child_member_id!==actor.memberId || previous.payload!==payload) fail(409,'PICKUP_ID_REUSED');
      return this.view(previous,actor,await this.db.getFamilyMembers(actor.familyId));
    }
    const count = await this.db.get('SELECT COUNT(*) AS count FROM family_pickups WHERE family_id=? AND child_member_id=? AND created_at>?',
      [actor.familyId,actor.memberId,Date.now()-86400000]);
    if (count.count>=30) fail(429,'PICKUP_DAILY_LIMIT');
    const now = Date.now();
    try {
      await this.db.run(`INSERT INTO family_pickups (id,family_id,child_member_id,payload,status,journal,created_at,updated_at,expires_at)
        VALUES (?,?,?,?,'REQUESTED',?,?,?,?)`,[input.requestId,actor.familyId,actor.memberId,payload,
        JSON.stringify([{id:input.requestId,actor:actor.memberId,action:'CREATE',at:now}]),now,now,now+6*3600000]);
    } catch (error) {
      if (!String(error.code).startsWith('SQLITE_CONSTRAINT')) throw error;
      const same = await this.db.get('SELECT * FROM family_pickups WHERE id=?',[input.requestId]);
      if (same?.family_id===actor.familyId && same.child_member_id===actor.memberId && same.payload===payload)
        return this.view(same,actor,await this.db.getFamilyMembers(actor.familyId));
      fail(409,'PICKUP_ALREADY_ACTIVE');
    }
    return this.view(await this.db.get('SELECT * FROM family_pickups WHERE id=?',[input.requestId]),actor,await this.db.getFamilyMembers(actor.familyId));
  }
  async action(actor,id,input) {
    await this.ensure();
    return this.db.withTransaction(async () => {
      const fresh = await this.actor(actor.authenticatedDeviceId,actor.familyId);
      if (fresh.memberId!==actor.memberId || fresh.memberRole!==actor.memberRole) fail(403,'PICKUP_CONTEXT_CHANGED');
      return this.actionInternal(fresh,id,input);
    });
  }
  async actionInternal(actor,id,input) {
    await this.expire(actor.familyId);
    if (!uuid(id) || !uuid(input.actionId) || !Number.isSafeInteger(input.version) || input.version<1 ||
      !['ACCEPT','DEPART','ARRIVE','CONFIRM','RELEASE','CANCEL'].includes(input.action)) fail(400,'PICKUP_INVALID_ACTION');
    const row = await this.db.get('SELECT * FROM family_pickups WHERE id=? AND family_id=?',[id,actor.familyId]);
    if (!row || !await this.authorized(actor,row.child_member_id)) fail(403,'PICKUP_ACCESS_DENIED');
    const journal = JSON.parse(row.journal);
    const previous = journal.find(e => e.id===input.actionId);
    if (previous) {
      if (previous.actor!==actor.memberId || previous.action!==input.action || previous.version!==input.version) fail(409,'PICKUP_ID_REUSED');
      return this.view(row,actor,await this.db.getFamilyMembers(actor.familyId));
    }
    if (row.version!==input.version) fail(409,'PICKUP_CHANGED');
    if (!ACTIVE.includes(row.status)) fail(409,'PICKUP_CLOSED');
    if (journal.length>=120) fail(409,'PICKUP_ACTION_LIMIT');
    const child = actor.memberId===row.child_member_id;
    const owner = actor.memberId===row.adult_member_id;
    const adult = ['PARENT','GUARDIAN'].includes(actor.memberRole);
    let status=row.status, adultId=row.adult_member_id, cc=row.child_confirmed, ac=row.adult_confirmed;
    switch (input.action) {
      case 'ACCEPT':
        if (!adult || status!=='REQUESTED') fail(409,'PICKUP_CANNOT_ACCEPT');
        adultId=actor.memberId; status='ACCEPTED'; break;
      case 'DEPART':
        if (!owner || status!=='ACCEPTED') fail(409,'PICKUP_INVALID_TRANSITION');
        status='EN_ROUTE'; break;
      case 'ARRIVE':
        if (!owner || !['ACCEPTED','EN_ROUTE'].includes(status)) fail(409,'PICKUP_INVALID_TRANSITION');
        status='ARRIVED'; break;
      case 'CONFIRM':
        if ((!child && !owner) || !['ARRIVED','HANDOFF'].includes(status)) fail(409,'PICKUP_INVALID_TRANSITION');
        if (child) cc=1; else ac=1;
        status=cc && ac ? 'COMPLETED' : 'HANDOFF'; break;
      case 'RELEASE':
        if (!owner || !['ACCEPTED','EN_ROUTE','ARRIVED'].includes(status)) fail(409,'PICKUP_INVALID_TRANSITION');
        adultId=null; cc=0; ac=0; status='REQUESTED'; break;
      case 'CANCEL':
        if (!child && !owner) fail(403,'PICKUP_ACCESS_DENIED');
        status='CANCELLED'; break;
    }
    const now=Date.now();
    journal.push({id:input.actionId,actor:actor.memberId,action:input.action,version:input.version,at:now});
    // Compare-and-swap, including deadline. Journal and state change in the same row;
    // BEGIN IMMEDIATE also protects the fresh authorization and daily limit.
    await this.db.run(`UPDATE family_pickups SET status=?,adult_member_id=?,child_confirmed=?,adult_confirmed=?,
      version=version+1,journal=?,updated_at=? WHERE id=? AND family_id=? AND version=? AND expires_at>?
      AND status IN ('REQUESTED','ACCEPTED','EN_ROUTE','ARRIVED','HANDOFF')`,
      [status,adultId,cc,ac,JSON.stringify(journal),now,id,actor.familyId,input.version,now]);
    const saved=await this.db.get('SELECT * FROM family_pickups WHERE id=? AND family_id=?',[id,actor.familyId]);
    if (!JSON.parse(saved.journal).some(e=>e.id===input.actionId && e.actor===actor.memberId)) fail(409,'PICKUP_CHANGED');
    return this.view(saved,actor,await this.db.getFamilyMembers(actor.familyId));
  }
}
module.exports=FamilyPickupService;
