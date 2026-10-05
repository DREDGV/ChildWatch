// Optional motion belongs to an exact measured fix, never just to a device's latest upload.
const ready = new WeakMap();
const cleaned = new WeakMap();
async function ensure(db) {
  if (!ready.has(db)) ready.set(db, db.run(`CREATE TABLE IF NOT EXISTS location_motion (
    device_id TEXT NOT NULL, timestamp INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
    speed REAL, speed_accuracy REAL, PRIMARY KEY(device_id,timestamp,latitude,longitude))`).then(()=>db.run("CREATE INDEX IF NOT EXISTS idx_location_motion_time ON location_motion(timestamp)")).catch(error => { ready.delete(db); throw error; }));
  await ready.get(db);
}
function values(point) {
  const speed = point.speedMps;
  const accuracy = point.speedAccuracyMps;
  if (typeof speed !== 'number' || !Number.isFinite(speed) || speed < 0 || speed > 60 ||
      typeof accuracy !== 'number' || !Number.isFinite(accuracy) || accuracy < 0 || accuracy > 20) return null;
  return {speed, accuracy};
}
async function save(db, deviceId, point) {
  const motion = values(point); if (!motion) return;
  const raw = Number(point.timestamp); const timestamp = raw < 100000000000 ? raw * 1000 : raw;
  if (!Number.isFinite(timestamp) || timestamp <= 0 || !Number.isFinite(Number(point.latitude)) || !Number.isFinite(Number(point.longitude))) return;
  await ensure(db);
  await db.run(`INSERT OR REPLACE INTO location_motion VALUES(?,?,?,?,?,?)`,
    [deviceId, timestamp, Number(point.latitude), Number(point.longitude), motion.speed, motion.accuracy]);
  if (Date.now() - (cleaned.get(db) || 0) > 5*60*1000) {
    cleaned.set(db, Date.now());
    // The window is the shared position policy (three months), not the former
    // 48 hours: speed and accuracy belong to a fix that is still inside it.
    await db.run('DELETE FROM location_motion WHERE timestamp<?', [require('./LocationRetention').cutoffFor()]);
  }
}
async function attach(db, forms, points) {
  await ensure(db); if (!forms.length || !points.length) return;
  const rows = await db.all(`SELECT * FROM location_motion WHERE device_id IN (${forms.map(()=>'?').join(',')}) AND timestamp>=? AND timestamp<=?`,
    [...forms, Math.min(...points.map(p=>p.timestamp)), Math.max(...points.map(p=>p.timestamp))]);
  const fixes = new Map(rows.map(r=>[JSON.stringify([r.timestamp,r.latitude,r.longitude]),r]));
  for (const point of points) {
    const motion = fixes.get(JSON.stringify([point.timestamp,point.latitude,point.longitude]));
    if (motion) { point.speedMps=motion.speed; point.speedAccuracyMps=motion.speed_accuracy; }
  }
}
module.exports={save,attach};
