// Read-only history paging. Authorization is repeated by the route on every page.
const timeSql = `CASE WHEN typeof(timestamp) IN ('integer','real') OR
  (CAST(timestamp AS TEXT) NOT GLOB '*[^0-9]*' AND CAST(timestamp AS TEXT)<>'')
  THEN CASE WHEN CAST(timestamp AS REAL)<100000000000
    THEN CAST(timestamp AS REAL)*1000 ELSE CAST(timestamp AS REAL) END
  ELSE CAST(ROUND((julianday(timestamp)-2440587.5)*86400000) AS INTEGER) END`;
function fail(code) { const error = new Error(code); error.code = code; throw error; }
async function read(db, forms, context) {
  const { cursor, ...binding } = context;
  const placeholders = forms.map(() => '?').join(',');
  const union = `SELECT id, 'c' AS source, latitude, longitude, accuracy, (${timeSql}) AS capturedAt
    FROM locations WHERE device_id IN (${placeholders})
    UNION ALL SELECT id, 'p' AS source, latitude, longitude, accuracy, (${timeSql}) AS capturedAt
    FROM parent_locations WHERE parent_id IN (${placeholders})`;
  const where = `capturedAt BETWEEN ? AND ? AND latitude BETWEEN -90 AND 90
    AND longitude BETWEEN -180 AND 180 AND NOT(latitude=0 AND longitude=0)`;
  const args = [...forms, ...forms, binding.from, binding.to];
  // Location records are inserted, not updated in-place. Count/max detect late inserts and pruning.
  const version = await db.all(`SELECT source, COUNT(*) AS n, MAX(id) AS lastId FROM (${union})
    WHERE ${where} GROUP BY source ORDER BY source`, args);
  const revision = JSON.stringify(version);
  let after = null;
  if (cursor) {
    if (typeof cursor !== 'string' || cursor.length > 4096) fail('HISTORY_CURSOR_INVALID');
    let decoded;
    try { decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')); }
    catch (_) { fail('HISTORY_CURSOR_INVALID'); }
    if (!decoded || JSON.stringify(decoded.binding) !== JSON.stringify(binding) ||
        !decoded.after || !Number.isFinite(decoded.after.time) ||
        !Number.isSafeInteger(decoded.after.id) || !['c', 'p'].includes(decoded.after.source))
      fail('HISTORY_CURSOR_INVALID');
    if (decoded.revision !== revision) fail('HISTORY_CHANGED');
    after = decoded.after;
  }
  const rows = await db.all(`SELECT * FROM (${union}) WHERE ${where}
    ${after ? 'AND (capturedAt>? OR (capturedAt=? AND (source>? OR (source=? AND id>?))))' : ''}
    ORDER BY capturedAt, source, id LIMIT 1001`,
    [...args, ...(after ? [after.time, after.time, after.source, after.source, after.id] : [])]);
  const afterVersion = await db.all(`SELECT source, COUNT(*) AS n, MAX(id) AS lastId FROM (${union})
    WHERE ${where} GROUP BY source ORDER BY source`, args);
  if (JSON.stringify(afterVersion) !== revision) fail('HISTORY_CHANGED');
  const hasMore = rows.length > 1000;
  const visible = rows.slice(0, 1000);
  const last = visible[visible.length - 1];
  const nextCursor = hasMore ? Buffer.from(JSON.stringify({ binding, revision,
    after: { time: last.capturedAt, source: last.source, id: last.id } })).toString('base64url') : null;
  return { revision, hasMore, nextCursor, points: visible.map(row => ({
    fixId: `${row.source}:${row.id}`, latitude: row.latitude, longitude: row.longitude,
    accuracy: row.accuracy, timestamp: row.capturedAt,
  })) };
}
module.exports = { read };
