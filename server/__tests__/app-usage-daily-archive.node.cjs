const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const Database=require('../database/DatabaseManager');
const Archive=require('../services/AppUsageDailyArchiveService');
const StatusReader=require('../services/DeviceStatusReadService');
const START=Date.parse('2025-01-02T00:00:00Z');
const END=START+86400000;
const PHONE='device_0123456789abcdef';
const entry=(amount=60000,at=END+1000,extra={})=>({dailyUsage:{available:true,start:START,end:END,timeZone:'UTC',
  totalTime:amount,apps:[{packageName:'example.app',appName:'Example',totalTimeInForeground:amount,lastUsed:END-1000}],
  dailyWindowState:'CLOSED',...extra},appUsageCollectedAt:at});
const raw=e=>({usagePermissionGranted:true,...e});
const request=extra=>({callerDeviceId:'parent',targetDeviceId:PHONE,familyId:'f',actorMemberId:'adult',...extra});
async function fixture(file=':memory:'){
  const db=new Database(file);await db.initialize();
  await db.run("INSERT OR IGNORE INTO devices(device_id,device_type,device_name,app_version) VALUES('parent','parent','Parent','test'),('device_0123456789abcdef','child','Child','test'),('0123456789abcdef','child','Child','test'),('newparent','parent','Adult','test')");
  await db.run("INSERT OR IGNORE INTO families(id,name) VALUES('f','Family'),('g','Other')");
  await db.run("INSERT OR IGNORE INTO family_members(id,family_id,display_name,role) VALUES('adult','f','Adult','PARENT'),('child','f','Child','CHILD'),('newadult','g','Adult','PARENT'),('newchild','g','Child','CHILD')");
  await db.run(`INSERT OR IGNORE INTO family_devices(id,family_id,member_id,device_id,display_name,member_binding_source,created_at)
    VALUES('pb','f','adult','parent','Parent','EXPLICIT',1704067200),('cb','f','child',?,'Child','EXPLICIT',1704067200)`,[PHONE]);
  await db.run("INSERT OR IGNORE INTO family_permissions(id,family_id,actor_member_id,target_member_id,feature,allowed) VALUES('p','f','adult','child','APP_USAGE',1),('p2','g','newadult','newchild','APP_USAGE',1)");
  return {db,service:new Archive(db)};
}
test('saveDeviceStatus ingests durable cumulative days, aliases/repeats/out-of-order never sum',async()=>{
  const {db,service}=await fixture();try{
    await db.saveDeviceStatus(PHONE,{raw:raw(entry(60000))});
    await db.saveDeviceStatus('0123456789abcdef',{raw:raw(entry(120000,END+2000))});
    await db.saveDeviceStatus(PHONE,{raw:raw(entry(90000,END+1000))});
    await db.saveDeviceStatus(PHONE,{raw:raw(entry(120000,END+2000))});
    const result=await service.read(request());assert.equal(result.allowed,true);assert.equal(result.statuses.length,1);
    assert.equal(result.statuses[0].raw.dailyUsage.totalTime,120000);
    assert.equal(result.statuses[0].raw.dailyUsage.windowState,'CLOSED');
    assert.equal(result.archive.completeCoverage,false);
    assert.equal((await db.get('SELECT count(*) AS n FROM app_usage_daily_archive')).n,1);
  }finally{await db.close();}
});
test('history upload bounded, telemetry strips recovery array without mutating input; permission false no archive',async()=>{
  const {db,service}=await fixture();try{
    const input={usagePermissionGranted:true,batteryLevel:49,dailyUsageHistory:[entry()]};
    await db.saveDeviceStatus(PHONE,{raw:input});assert.equal(input.dailyUsageHistory.length,1);
    assert.equal((await db.getLatestDeviceStatus(PHONE)).raw.dailyUsageHistory,undefined);
    assert.equal((await service.read(request())).statuses.length,1);
    await db.saveDeviceStatus(PHONE,{raw:{usagePermissionGranted:false,...entry(240000,END+3000)}});
    assert.equal((await service.read(request())).statuses[0].raw.dailyUsage.totalTime,60000);
  }finally{await db.close();}
});
test('permissions/scope revoke takes effect immediately; device rebind cannot expose previous owner archive',async()=>{
  const {db,service}=await fixture();try{
    await db.saveDeviceStatus(PHONE,{raw:raw(entry())});
    await db.run("UPDATE family_permissions SET allowed=0 WHERE id='p'");
    assert.equal((await service.read(request())).allowed,false);
    assert.equal((await service.read(request({familyId:'wrong'}))).allowed,false);
    await db.run("UPDATE family_devices SET is_active=0 WHERE id='cb'");
    await db.run(`INSERT INTO family_devices(id,family_id,member_id,device_id,display_name,member_binding_source,created_at)
      VALUES('newpb','g','newadult','newparent','Adult','EXPLICIT',1704067200),('newcb','g','newchild',?,'Child','EXPLICIT',1704067200)`,[PHONE]);
    const other=await service.read({callerDeviceId:'newparent',targetDeviceId:PHONE,familyId:'g',actorMemberId:'newadult'});
    assert.equal(other.allowed,true);assert.equal(other.statuses.length,0);
  }finally{await db.close();}
});
test('binding floor rejects prebinding queries; clipped currentday can remain PARTIAL',async()=>{
  const {db,service}=await fixture();try{
    const floor=START+3600000;await db.run("UPDATE family_devices SET created_at=? WHERE id='cb'",[floor/1000]);
    await db.saveDeviceStatus(PHONE,{raw:raw(entry())});assert.equal((await service.read(request())).statuses.length,0);
    await db.saveDeviceStatus(PHONE,{raw:raw(entry(60000,END+2000,{start:floor}))});
    const result=await service.read(request());assert.equal(result.statuses[0].raw.dailyUsage.start,floor);
    assert.equal(result.statuses[0].raw.dailyUsage.windowState,'PARTIAL');
  }finally{await db.close();}
});
test('server owner stamp protects status fallback, ignores forged client stamp, and survives enrichment',async()=>{
  const {db}=await fixture();try{
    const reader=new StatusReader(db), input={...raw(entry()),__usageOwnerScopes:[{familyId:'forged'}]};
    await db.saveDeviceStatus(PHONE,{raw:input});
    const own=await reader.read({...request(),purpose:'app_usage'});
    assert.equal(own.status.raw.dailyUsage.totalTime,60000);assert.equal(own.status.raw.__usageOwnerScopes,undefined);
    assert.equal(input.__usageOwnerScopes[0].familyId,'forged');
    await db.run("UPDATE family_devices SET is_active=0 WHERE id='cb'");
    await db.run(`INSERT INTO family_devices(id,family_id,member_id,device_id,display_name,member_binding_source,created_at)
      VALUES('newpb','g','newadult','newparent','Adult','EXPLICIT',1704067200),('newcb','g','newchild',?,'Child','EXPLICIT',1704067200)`,[PHONE]);
    const other=await reader.read({callerDeviceId:'newparent',targetDeviceId:PHONE,familyId:'g',actorMemberId:'newadult',purpose:'app_usage'});
    assert.equal(other.allowed,true);assert.equal(other.status.raw.dailyUsage,undefined);
    assert.equal(StatusReader.redact({...other.status,recentApps:['restored']},other.usageAllowed).recentApps,undefined);
    await db.run('UPDATE device_status SET status_json=?',[JSON.stringify(raw(entry()))]);
    const legacy=await reader.read({callerDeviceId:'newparent',targetDeviceId:PHONE,purpose:'app_usage'});
    assert.equal(legacy.status.raw.dailyUsage,undefined);
  }finally{await db.close();}
});
test('normalization rejects invalid/empty/future/duplicate/duration windows; DST 23/25h valid',()=>{
  assert.equal(Archive.normalize(entry(0)),null);
  assert.equal(Archive.normalize(entry(60000,END+1000,{timeZone:'invalid-zone'})),null);
  assert.equal(Archive.normalize(entry(60000,END-1)),null);
  assert.equal(Archive.normalize(entry(60000,Date.now()+600000)),null);
  assert.equal(Archive.normalize(entry(86400001)),null);
  const duplicate=entry();duplicate.dailyUsage.apps.push({...duplicate.dailyUsage.apps[0]});assert.equal(Archive.normalize(duplicate),null);
  for(const [start,end] of [['2025-03-09T05:00:00Z','2025-03-10T04:00:00Z'],['2025-11-02T04:00:00Z','2025-11-03T05:00:00Z']]){
    const e=entry(60000,Date.parse(end)+1000,{start:Date.parse(start),end:Date.parse(end),timeZone:'America/New_York'});
    e.dailyUsage.apps[0].lastUsed=Date.parse(end)-1000;
    assert.equal(Archive.normalize(e).dailyUsage.windowState,'CLOSED');
  }
});
test('timezone aliases share a storage key while preserving Android report identity',async()=>{
  const {db,service}=await fixture();try{
    const first=entry(60000,END+1000,{timeZone:'Europe/Kyiv',appsTruncated:true});
    const alias=entry(90000,END+2000,{timeZone:'Europe/Kiev',appsTruncated:true});
    // UTC start is 02:00 local; this is a partial day ending at local midnight.
    for(const e of [first,alias]) { e.dailyUsage.end=END-7200000;e.dailyUsage.apps[0].lastUsed=e.dailyUsage.end-1000;
      e.dailyUsage.dailyWindowState='PARTIAL'; }
    assert.equal(Archive.normalize(first).dailyUsage.timeZone,'Europe/Kyiv');
    await db.saveDeviceStatus(PHONE,{raw:raw(first)});
    await db.saveDeviceStatus(PHONE,{raw:raw(alias)});
    const result=await service.read(request());
    assert.equal(result.statuses.length,1);
    assert.equal(result.statuses[0].raw.dailyUsage.timeZone,'Europe/Kiev');
    assert.equal(result.statuses[0].raw.dailyUsage.appsTruncated,true);
    assert.equal((await db.get('SELECT count(*) AS n FROM app_usage_daily_archive')).n,1);
  }finally{await db.close();}
});

test('archive write failure rolls back telemetry in the same real SQLite transaction',async()=>{
  const {db,service}=await fixture();try{
    await service.ensure();
    await db.run(`CREATE TRIGGER reject_archive BEFORE INSERT ON app_usage_daily_archive
      BEGIN SELECT RAISE(ABORT,'archive-write-failed'); END`);
    await assert.rejects(db.saveDeviceStatus(PHONE,{batteryLevel:49,raw:raw(entry())}),/archive-write-failed/);
    assert.equal((await db.get('SELECT count(*) AS n FROM device_status')).n,0);
    assert.equal((await db.get('SELECT count(*) AS n FROM app_usage_daily_archive')).n,0);
    await db.run('DROP TRIGGER reject_archive');
    await db.saveDeviceStatus(PHONE,{batteryLevel:49,raw:raw(entry())});
    assert.equal((await db.get('SELECT count(*) AS n FROM device_status')).n,1);
    assert.equal((await service.read(request())).statuses.length,1);
  }finally{await db.close();}
});

test('archive survives actual DB close/reopen and bounded read is explicit',async()=>{
  const dir=path.join(__dirname,'../../.runtime/usage-archive-tests');fs.mkdirSync(dir,{recursive:true});
  const file=path.join(dir,`archive-${process.pid}-${Date.now()}.db`);
  let db;try{
    ({db}=await fixture(file));await db.saveDeviceStatus(PHONE,{raw:raw(entry())});await db.close();
    db=new Database(file);await db.initialize();
    const service=new Archive(db);const e=entry(90000,END+2000,{timeZone:'Europe/London'});
    await db.saveDeviceStatus(PHONE,{raw:raw(e)});
    const result=await service.read(request({limit:1}));assert.equal(result.statuses.length,1);assert.equal(result.archive.hasMore,true);
    assert.equal((await service.read(request({limit:999}))).limit,90);
  }finally{if(db?.isInitialized)await db.close();for(const suffix of ['', '-wal','-shm'])if(fs.existsSync(file+suffix))fs.unlinkSync(file+suffix);}
});
