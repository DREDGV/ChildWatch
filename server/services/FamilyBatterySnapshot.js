// Telemetry is tied to the chosen map device, with its own capture time.
async function read(db, deviceId, forms, now = Date.now()) {
  if (!deviceId || !forms.length) return null;
  const row = await db.get(`SELECT device_id, battery_level, is_charging, timestamp
    FROM device_status WHERE device_id IN (${forms.map(() => '?').join(',')})
    ORDER BY timestamp DESC LIMIT 1`, forms);
  if (!row || !forms.includes(row.device_id)) return null;
  const level = row.battery_level;
  const rawTime = Number(row.timestamp);
  const timestamp = rawTime < 100_000_000_000 ? rawTime * 1000 : rawTime;
  if (!Number.isInteger(level) || level < 0 || level > 100 ||
      !Number.isFinite(timestamp) || timestamp <= 0 || timestamp > now) return null;
  return { deviceId, level, isCharging: row.is_charging === 1 ? true :
    row.is_charging === 0 ? false : null, timestamp };
}
module.exports = { read };
