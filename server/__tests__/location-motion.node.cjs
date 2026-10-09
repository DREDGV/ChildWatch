const {test}=require('node:test');
const assert=require('node:assert/strict');
const sqlite=require('sqlite3');
const motion=require('../services/LocationMotionStore');
async function database(legacy=false) {
  const connection=await new Promise((resolve,reject)=>{ const db=new sqlite.Database(':memory:',error=>error?reject(error):resolve(db)); });
  const db={
    run:(sql,args=[])=>new Promise((resolve,reject)=>connection.run(sql,args,error=>error?reject(error):resolve())),
    all:(sql,args=[])=>new Promise((resolve,reject)=>connection.all(sql,args,(error,rows)=>error?reject(error):resolve(rows))),
    close:()=>new Promise((resolve,reject)=>connection.close(error=>error?reject(error):resolve()))
  };
  if(legacy) await db.run(`CREATE TABLE location_motion(device_id TEXT NOT NULL,timestamp INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,speed REAL,speed_accuracy REAL,PRIMARY KEY(device_id,timestamp,latitude,longitude))`);
  return db;
}
const fix=extra=>({timestamp:Date.now(),latitude:55,longitude:83,...extra});
test('existing SQLite schema upgrades without losing old fixes; clock is lossless beyond 53 bits',async()=>{
 const db=await database(true);try{
  const old=fix();await db.run('INSERT INTO location_motion VALUES(?,?,?,?,?,?)',['child',old.timestamp,55,83,1.5,0.1]);
  const measured=fix({timestamp:old.timestamp+1,speedMps:2,speedAccuracyMps:0.2,measurementElapsedRealtimeNanos:'9007199254740993',bootSessionId:'install:12'});
  await motion.save(db,'child',measured);
  const points=[{...old},{timestamp:measured.timestamp,latitude:55,longitude:83}];await motion.attach(db,['child'],points);
  assert.equal(points[0].speedMps,1.5);assert.equal(points[0].bootSessionId,undefined);
  assert.equal(points[1].measurementElapsedRealtimeNanos,'9007199254740993');assert.equal(points[1].speedMps,2);assert.equal(points[1].bootSessionId,'install:12');
  assert.equal((await db.all('SELECT * FROM location_motion')).length,2);
 }finally{await db.close();}
});
test('clock-only fixes are retained for fallback estimation; no speed is invented',async()=>{
 const db=await database();try{
  const p=fix({measurementElapsedRealtimeNanos:'23456789012',bootSessionId:'install:3'});await motion.save(db,'child',p);
  const points=[{timestamp:p.timestamp,latitude:55,longitude:83}];await motion.attach(db,['child'],points);
  assert.equal(points[0].measurementElapsedRealtimeNanos,p.measurementElapsedRealtimeNanos);assert.equal(points[0].speedMps,undefined);
 }finally{await db.close();}
});
test('clock metadata and speed attach only to the authorized exact device/time/coordinate fix',async()=>{
 const db=await database();try{
  const p=fix({speedMps:4,speedAccuracyMps:0.2,measurementElapsedRealtimeNanos:'4444444444',bootSessionId:'install:4'});await motion.save(db,'other',p);
  const own=[{timestamp:p.timestamp,latitude:55,longitude:83}];await motion.attach(db,['child'],own);assert.equal(own[0].speedMps,undefined);
  for(const altered of [{timestamp:p.timestamp+1,latitude:55,longitude:83},{timestamp:p.timestamp,latitude:55.1,longitude:83}]) {
   await motion.attach(db,['other'],[altered]);assert.equal(altered.measurementElapsedRealtimeNanos,undefined);
  }
 }finally{await db.close();}
});
test('bad and incomplete monotonic values are omitted; legacy measured speed survives',async()=>{
 const db=await database();try{
  for(const [index,nanos,boot] of [[0,9007199254740992,'install:1'],[1,'9223372036854775808','install:1'],[2,'-1','install:1'],[3,'100',null],[4,'100','bad session']]) {
   const p=fix({timestamp:Date.now()+index,speedMps:1,speedAccuracyMps:0.1,measurementElapsedRealtimeNanos:nanos,bootSessionId:boot});await motion.save(db,'child',p);
   const points=[{timestamp:p.timestamp,latitude:55,longitude:83}];await motion.attach(db,['child'],points);assert.equal(points[0].speedMps,1);assert.equal(points[0].measurementElapsedRealtimeNanos,undefined);
  }
 }finally{await db.close();}
});
test('replay without clock does not erase previously captured clock; reboot identifiers stay distinct',async()=>{
 const db=await database();try{
  const p=fix({speedMps:1,speedAccuracyMps:0.1,measurementElapsedRealtimeNanos:'123456789',bootSessionId:'install:1'});await motion.save(db,'child',p);
  await motion.save(db,'child',{timestamp:p.timestamp,latitude:55,longitude:83,speedMps:1,speedAccuracyMps:0.1});
  const q={...p,timestamp:p.timestamp+1,measurementElapsedRealtimeNanos:'12',bootSessionId:'install:2'};await motion.save(db,'child',q);
  const points=[{timestamp:p.timestamp,latitude:55,longitude:83},{timestamp:q.timestamp,latitude:55,longitude:83}];await motion.attach(db,['child'],points);
  assert.deepEqual(points.map(p=>p.bootSessionId),['install:1','install:2']);assert.deepEqual(points.map(p=>p.measurementElapsedRealtimeNanos),['123456789','12']);
 }finally{await db.close();}
});

test('colliding alias fixes are not assigned arbitrarily; explicit device selects its own fix',async()=>{
 const db=await database();try{
  const p=fix({speedMps:1,speedAccuracyMps:0.1,measurementElapsedRealtimeNanos:'10',bootSessionId:'install:1'});
  await motion.save(db,'child',p);await motion.save(db,'alias',{...p,speedMps:2,bootSessionId:'other:1'});
  const points=[{timestamp:p.timestamp,latitude:55,longitude:83},{timestamp:p.timestamp,latitude:55,longitude:83,deviceId:'child'}];
  await motion.attach(db,['child','alias'],points);assert.equal(points[0].speedMps,undefined);assert.equal(points[1].speedMps,1);
 }finally{await db.close();}
});

test('legacy seconds and ISO timestamps attach to the same measured instant',async()=>{
 const db=await database();try{
  const epoch=Math.floor(Date.now()/1000)*1000;const p=fix({timestamp:epoch,speedMps:1,speedAccuracyMps:0.1});await motion.save(db,'child',p);
  const points=[{timestamp:epoch/1000,latitude:55,longitude:83},{timestamp:new Date(epoch).toISOString(),latitude:55,longitude:83}];
  await motion.attach(db,['child'],points);assert.deepEqual(points.map(p=>p.speedMps),[1,1]);
 }finally{await db.close();}
});
