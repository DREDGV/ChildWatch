// Optional motion belongs to an exact measured fix, never just to a device's latest upload.
const ready = new WeakMap();
const cleaned = new WeakMap();
async function ensure(db) {
  if (!ready.has(db)) ready.set(db, (async () => {
    await db.run(`CREATE TABLE IF NOT EXISTS location_motion (
      device_id TEXT NOT NULL, timestamp INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
      speed REAL, speed_accuracy REAL, elapsed_nanos TEXT, boot_session TEXT,
      PRIMARY KEY(device_id,timestamp,latitude,longitude))`);
    const columns = new Set((await db.all('PRAGMA table_info(location_motion)')).map(row => row.name));
    if (!columns.has('elapsed_nanos')) await db.run('ALTER TABLE location_motion ADD COLUMN elapsed_nanos TEXT');
    if (!columns.has('boot_session')) await db.run('ALTER TABLE location_motion ADD COLUMN boot_session TEXT');
    await db.run('CREATE INDEX IF NOT EXISTS idx_location_motion_time ON location_motion(timestamp)');
  })().catch(error => { ready.delete(db); throw error; }));
  await ready.get(db);
}
function values(point) {
  const speed = point.speedMps;
  const accuracy = point.speedAccuracyMps;
  const hasSpeed = typeof speed === 'number' && Number.isFinite(speed) && speed >= 0 && speed <= 60 &&
    typeof accuracy === 'number' && Number.isFinite(accuracy) && accuracy >= 0 && accuracy <= 20;
  // Android long values travel as decimal strings. Never round them through Number.
  const raw = point.measurementElapsedRealtimeNanos;
  const nanos = typeof raw === 'string' ? raw : Number.isSafeInteger(raw) ? String(raw) : null;
  const boot = point.bootSessionId;
  const hasClock = nanos !== null && /^[1-9][0-9]{0,18}$/.test(nanos) && BigInt(nanos) <= 9223372036854775807n &&
    typeof boot === 'string' && /^[A-Za-z0-9:_-]{1,120}$/.test(boot);
  if (!hasSpeed && !hasClock) return null;
  return {speed: hasSpeed ? speed : null, accuracy: hasSpeed ? accuracy : null,
    nanos: hasClock ? nanos : null, boot: hasClock ? boot : null};
}
function timestampOf(value) {
  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric > 0) return numeric < 100000000000 ? numeric * 1000 : numeric;
  const parsed = typeof value === 'string' ? Date.parse(value) : NaN;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}
async function save(db, deviceId, point) {
  const motion = values(point); if (!motion) return;
  const timestamp = timestampOf(point.timestamp);
  if (!Number.isFinite(timestamp) || timestamp <= 0 || !Number.isFinite(Number(point.latitude)) || !Number.isFinite(Number(point.longitude))) return;
  await ensure(db);
  await db.run(`INSERT INTO location_motion(device_id,timestamp,latitude,longitude,speed,speed_accuracy,elapsed_nanos,boot_session) VALUES(?,?,?,?,?,?,?,?)
    ON CONFLICT(device_id,timestamp,latitude,longitude) DO UPDATE SET
      speed=COALESCE(excluded.speed,speed), speed_accuracy=COALESCE(excluded.speed_accuracy,speed_accuracy),
      elapsed_nanos=COALESCE(excluded.elapsed_nanos,elapsed_nanos), boot_session=COALESCE(excluded.boot_session,boot_session)`,
    [deviceId, timestamp, Number(point.latitude), Number(point.longitude), motion.speed, motion.accuracy, motion.nanos, motion.boot]);
  if (Date.now() - (cleaned.get(db) || 0) > 5*60*1000) {
    cleaned.set(db, Date.now());
    // The window is the shared position policy (three months), not the former
    // 48 hours: speed and accuracy belong to a fix that is still inside it.
    await db.run('DELETE FROM location_motion WHERE timestamp<?', [require('./LocationRetention').cutoffFor()]);
  }
}
async function attach(db, forms, points) {
  await ensure(db); if (!forms.length || !points.length) return;
  const times = points.map(p=>timestampOf(p.timestamp)).filter(time=>time !== null);
  if (!times.length) return;
  const rows = await db.all(`SELECT * FROM location_motion WHERE device_id IN (${forms.map(()=>'?').join(',')}) AND timestamp>=? AND timestamp<=?`,
    [...forms, Math.min(...times), Math.max(...times)]);
  const fixes = new Map();
  for (const row of rows) {
    const key = JSON.stringify([row.timestamp,row.latitude,row.longitude]);
    const candidates = fixes.get(key) || []; candidates.push(row); fixes.set(key,candidates);
  }
  for (const point of points) {
    const candidates = fixes.get(JSON.stringify([timestampOf(point.timestamp),point.latitude,point.longitude])) || [];
    const device = point.deviceId || point.parentId || point.device_id;
    const exact = device ? candidates.filter(row => row.device_id === device) : candidates;
    // Alias forms normally refer to one phone. Conflicting exact fixes are ambiguous,
    // so do not attribute another boot's measurement to this coordinate.
    const motion = exact.length === 1 ? exact[0] : null;
    if (motion) {
      if (motion.speed != null && motion.speed_accuracy != null) { point.speedMps=motion.speed; point.speedAccuracyMps=motion.speed_accuracy; }
      if (motion.elapsed_nanos != null && motion.boot_session != null) {
        point.measurementElapsedRealtimeNanos=motion.elapsed_nanos; point.bootSessionId=motion.boot_session;
      }
    }
  }
}
module.exports={save,attach};
