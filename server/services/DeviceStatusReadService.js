const DeviceAccessService = require('./DeviceAccessService');
const ROW_USAGE_ALLOWED = Symbol('server-verified-row-usage');

const USAGE_KEYS = ['currentAppName', 'currentAppPackage', 'currentApp', 'recentApps',
  'dailyUsage', 'dailyUsageHistory', 'appUsageCollectedAt', 'appUsageStale', 'usagePermissionGranted'];

class DeviceStatusReadService {
  constructor(db) { this.db = db; this.access = new DeviceAccessService(db); }
  static redact(status, usageAllowed) {
    if (!status) return status;
    usageAllowed = usageAllowed && status[ROW_USAGE_ALLOWED] !== false;
    const result = { ...status };
    if (!usageAllowed) for (const key of USAGE_KEYS) delete result[key];
    if (result.raw && typeof result.raw === 'object') {
      result.raw = { ...result.raw };
      delete result.raw.__usageOwnerScopes;
      if (!usageAllowed) for (const key of USAGE_KEYS) delete result.raw[key];
    }
    return result;
  }
  async memberships(id) {
    return (await Promise.all(this.access.idForms(id).map(form =>
      this.db.getFamilyIdentityMembershipsForDevice(form)))).flat();
  }
  async read(input, fallback = () => null, authorizedRead = null) {
    return this.db.withTransaction(async () => {
      const caller = this.access.normalizeDeviceId(input.callerDeviceId);
      const target = this.access.normalizeDeviceId(input.targetDeviceId);
      const deny = code => ({ allowed: false, code });
      if (!caller || !target || await this.access.isDeviceRevoked(caller) ||
          await this.access.isDeviceRevoked(target)) return deny('DEVICE_ACCESS_DENIED');
      const actors = await this.memberships(caller), targets = await this.memberships(target);
      const scoped = input.familyId != null || input.actorMemberId != null;
      const matchingActors = actors.filter(actor => !scoped ||
        (actor.familyId === input.familyId && actor.memberId === input.actorMemberId));
      if (scoped && !matchingActors.length) return deny('DEVICE_STATUS_SCOPE_CHANGED');
      const self = this.access.isSameDevice(caller, target);
      const pairs = matchingActors.flatMap(actor => targets.filter(member =>
        member.familyId === actor.familyId).map(member => ({ actor, member })));
      let genericAllowed = self || pairs.length > 0;
      if (!genericAllowed && !scoped) {
        // A removed canonical binding must not fall back to a lingering legacy link.
        const forms = [...new Set([...this.access.idForms(caller), ...this.access.idForms(target)])];
        const canonical = await this.db.get(`SELECT 1 AS bound FROM family_devices
          WHERE device_id IN (${forms.map(() => '?').join(',')}) LIMIT 1`, forms);
        if (!canonical) genericAllowed = await this.access.sharesLinkWithAnySpelling(caller, target);
      }
      if (!genericAllowed) return deny('DEVICE_ACCESS_DENIED');
      let usageAllowed = self;
      const usageTargets = self ? targets : [];
      for (const { actor, member } of pairs) {
        if (!['PARENT', 'GUARDIAN'].includes(actor.memberRole) || member.memberRole !== 'CHILD') continue;
        const permission = await this.db.getFamilyPermission({ familyId: actor.familyId,
          actorMemberId: actor.memberId, targetMemberId: member.memberId, feature: 'APP_USAGE' });
        if (permission?.allowed === 1) { usageAllowed = true; usageTargets.push(member); }
      }
      if (input.purpose === 'app_usage' && !usageAllowed) return deny('APP_USAGE_PERMISSION_DENIED');
      if (authorizedRead) return { allowed: true, usageAllowed, ...await authorizedRead({ usageTargets, self }) };
      const sanitizeRow = row => {
        if (!row) return row;
        const stamps = Array.isArray(row.raw?.__usageOwnerScopes) ? row.raw.__usageOwnerScopes : [];
        const rowAllowed = usageAllowed && (self || usageTargets.some(member => stamps.some(stamp =>
          stamp.familyId === member.familyId && stamp.memberId === member.memberId &&
          stamp.bindingId === member.bindingId && stamp.bindingCreatedAt === member.bindingCreatedAt &&
          Number.isFinite(Number(member.bindingCreatedAt)) && Number(member.bindingCreatedAt) > 0 &&
          Number(row.raw?.appUsageCollectedAt) >= Number(member.bindingCreatedAt) * 1000 &&
          (!row.raw?.dailyUsage || Number(row.raw.dailyUsage.start) >= Number(member.bindingCreatedAt) * 1000))));
        return DeviceStatusReadService.redact({ ...row, [ROW_USAGE_ALLOWED]: rowAllowed }, rowAllowed);
      };
      const forms = this.access.idForms(target);
      if (input.history) {
        const limit = Math.min(Math.max(Number.parseInt(input.limit, 10) || 60, 1), 200);
        const rows = (await Promise.all(forms.map(id => this.db.getDeviceStatusHistory(id, limit)))).flat();
        rows.sort((a, b) => Number(b.timestamp) - Number(a.timestamp));
        return { allowed: true, usageAllowed,
          statuses: rows.slice(0, limit).map(sanitizeRow) };
      }
      const rows = (await Promise.all(forms.map(id => this.db.getLatestDeviceStatus(id)))).filter(Boolean);
      if (!rows.length) for (const form of forms) { const row = fallback(form); if (row) rows.push(row); }
      rows.sort((a, b) => Number(b.timestamp) - Number(a.timestamp));
      return { allowed: true, usageAllowed,
        status: sanitizeRow(rows[0] || null) };
    });
  }
}
module.exports = DeviceStatusReadService;
