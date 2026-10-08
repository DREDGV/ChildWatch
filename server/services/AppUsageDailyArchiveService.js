const DeviceAccessService = require('./DeviceAccessService');
const StatusReader = require('./DeviceStatusReadService');

const integer = value => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
function calendar(timestamp, zone) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: zone, year:'numeric',
    month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23' })
    .formatToParts(timestamp).filter(p => p.type !== 'literal').map(p => [p.type,p.value]));
}
function normalize(entry, now = Date.now()) {
  const daily = entry?.dailyUsage, at = entry?.appUsageCollectedAt;
  if (!daily || daily.available !== true || !integer(at) || at > now + 60000 ||
      !integer(daily.start) || !integer(daily.end) || daily.start >= daily.end || daily.end > at ||
      typeof daily.timeZone !== 'string' || daily.timeZone.length > 100 ||
      !Array.isArray(daily.apps) || !daily.apps.length || daily.apps.length > 500) return null;
  let start, day, zone;
  try {
    zone=new Intl.DateTimeFormat('en',{timeZone:daily.timeZone}).resolvedOptions().timeZone;
    start = calendar(daily.start,zone);
    day = `${start.year}-${start.month}-${start.day}`;
  } catch { return null; }
  // Find the next local date boundary, respecting 23/25-hour DST days.
  let lo = daily.start, hi = daily.start + 27 * 3600000;
  const dateAt = time => { const p=calendar(time,zone); return `${p.year}-${p.month}-${p.day}`; };
  while (hi - lo > 1) { const mid=Math.floor((hi+lo)/2); if(dateAt(mid)===day)lo=mid;else hi=mid; }
  const boundary=hi;
  if(daily.end>boundary) return null;
  const apps=[], seen=new Set(); let total=0;
  for(const app of daily.apps) {
    if(!app || typeof app.packageName!=='string' || !app.packageName.trim() || app.packageName.length>255 ||
        seen.has(app.packageName.trim()) || !integer(app.totalTimeInForeground) ||
        app.totalTimeInForeground>daily.end-daily.start) return null;
    seen.add(app.packageName.trim()); total+=app.totalTimeInForeground;
    const row={packageName:app.packageName.trim(),appName:typeof app.appName==='string'?app.appName.slice(0,255):app.packageName.trim(),
      totalTimeInForeground:app.totalTimeInForeground};
    for(const key of ['lastUsed','firstUsed','lastForegroundAt']) if(app[key]!=null) {
      if(!integer(app[key]) || (app[key]!==0 && (app[key]<daily.start || app[key]>daily.end))) return null;
      row[key]=app[key];
    }
    apps.push(row);
  }
  if(total<=0 || total>daily.end-daily.start || !integer(daily.totalTime) || daily.totalTime!==total) return null;
  const claimed=daily.dailyWindowState || daily.windowState;
  if(claimed==='CLOSED' && daily.end!==boundary) return null;
  const beginsAtMidnight=start.hour==='00' && start.minute==='00' && start.second==='00' && daily.start%1000===0;
  const state=claimed==='CLOSED'?(beginsAtMidnight?'CLOSED':'PARTIAL'):claimed==='PARTIAL'?'PARTIAL':'UNKNOWN';
  // The storage key uses ICU's canonical name, but the report keeps the child's
  // spelling so it still matches the same live snapshot on Android (Kyiv/Kiev etc.).
  return { day,timeZone:zone,collectedAt:at,dailyUsage:{available:true,start:daily.start,
    end:daily.end,timeZone:daily.timeZone,totalTime:total,apps,windowState:state,
    appsTruncated:daily.appsTruncated === true} };
}

class AppUsageDailyArchiveService {
  constructor(db) { this.db=db;this.access=new DeviceAccessService(db); }
  static normalize(entry,now) {return normalize(entry,now);}
  canonical(id) {
    const normalized=this.access.normalizeDeviceId(id), bare=this.access.rawAndroidId(normalized);
    return bare ? `device_${bare}` : /^[0-9a-fA-F]{16}$/.test(normalized)?`device_${normalized.toLowerCase()}`:normalized;
  }
  async ensure() {
    await this.db.run(`CREATE TABLE IF NOT EXISTS app_usage_daily_archive (
      device_id TEXT NOT NULL,family_id TEXT NOT NULL,member_id TEXT NOT NULL,binding_id TEXT NOT NULL,
      day TEXT NOT NULL,time_zone TEXT NOT NULL,collected_at INTEGER NOT NULL,
      snapshot_json TEXT NOT NULL,updated_at INTEGER NOT NULL,
      PRIMARY KEY(device_id,family_id,member_id,binding_id,day,time_zone))`);
  }
  async ingest(deviceId,raw,now=Date.now()) {
    if(raw?.usagePermissionGranted!==true) return 0;
    await this.ensure();
    if(await this.access.isDeviceRevoked(deviceId)) return 0;
    const memberships=(await Promise.all(this.access.idForms(deviceId).map(id=>
      this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
    const entries=[{dailyUsage:raw.dailyUsage,appUsageCollectedAt:raw.appUsageCollectedAt},
      ...(Array.isArray(raw.dailyUsageHistory)?raw.dailyUsageHistory.slice(0,7):[])];
    let accepted=0;
    for(const entry of entries) {
      const normalized=normalize(entry,now);if(!normalized)continue;
      for(const member of memberships) {
      if(!member.familyId || !member.memberId || !member.bindingId) continue;
      const bindingAt=Number(member.bindingCreatedAt)*1000;
      if(!Number.isFinite(bindingAt) || bindingAt<=0 || normalized.dailyUsage.start<bindingAt) continue;
      await this.db.run(`INSERT INTO app_usage_daily_archive
        (device_id,family_id,member_id,binding_id,day,time_zone,collected_at,snapshot_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
        ON CONFLICT(device_id,family_id,member_id,binding_id,day,time_zone) DO UPDATE SET collected_at=excluded.collected_at,
        snapshot_json=excluded.snapshot_json,updated_at=excluded.updated_at
        WHERE excluded.collected_at>app_usage_daily_archive.collected_at`,
      [this.canonical(deviceId),member.familyId,member.memberId,member.bindingId,normalized.day,normalized.timeZone,
        normalized.collectedAt,JSON.stringify(normalized.dailyUsage),now]);
      accepted++;
      }
    }
    return accepted;
  }
  async read(input) {
    return new StatusReader(this.db).read({...input,purpose:'app_usage'},()=>null,async({usageTargets})=>{
      await this.ensure();
      const limit=Math.min(Math.max(Number.parseInt(input.limit,10)||31,1),90);
      const scopes=usageTargets.filter(member=>member.bindingId && Number(member.bindingCreatedAt)>0);
      const scopeSql=scopes.map(()=>"(family_id=? AND member_id=? AND binding_id=? AND json_extract(snapshot_json,'$.start')>=?)").join(' OR ');
      const rows=scopes.length ? await this.db.all(`SELECT * FROM app_usage_daily_archive WHERE device_id=?
        AND (${scopeSql}) ORDER BY day DESC,collected_at DESC,time_zone ASC LIMIT ?`,
        [this.canonical(input.targetDeviceId),...scopes.flatMap(m=>[m.familyId,m.memberId,m.bindingId,Number(m.bindingCreatedAt)*1000]),limit+1]) : [];
      return {limit,statuses:rows.slice(0,limit).map(row=>({timestamp:row.collected_at,raw:{
        dailyUsage:JSON.parse(row.snapshot_json),appUsageCollectedAt:row.collected_at,
        usagePermissionGranted:true,appUsageStale:Date.now()-row.collected_at>300000,
        appUsageSource:'daily_archive'}})),archive:{retentionDays:null,hasMore:rows.length>limit,completeCoverage:false}};
    });
  }
}
module.exports=AppUsageDailyArchiveService;
