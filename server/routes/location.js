const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const DeviceAccessService = require("../services/DeviceAccessService");

const router = express.Router();

/**
 * Every location endpoint verifies the authenticated caller against the device
 * whose data is requested. The router previously had no authentication at all,
 * so anyone who knew a device id could read a family's live position and its
 * history, and could write a position into someone else's record.
 *
 * The device id stays in the request because a parent legitimately reads the
 * position of its child; it is verified instead of trusted.
 */
let sharedDatabase;
let deviceAccess;
let parentLocationTablesReady;

router.init = (databaseManager) => {
  sharedDatabase = databaseManager;
  deviceAccess = new DeviceAccessService(databaseManager);
  parentLocationTablesReady = null;
};

async function withDatabase(work) {
  if (sharedDatabase) {
    return work(sharedDatabase);
  }
  const temporary = new DatabaseManager();
  await temporary.initialize();
  try {
    return await work(temporary);
  } finally {
    await temporary.close();
  }
}

/** The parent_locations table lives here, so it is ensured once per process. */
async function ensureSharedParentLocationTables() {
  if (!sharedDatabase) return;
  if (!parentLocationTablesReady) {
    parentLocationTablesReady = ensureParentLocationTables(sharedDatabase);
  }
  await parentLocationTablesReady;
}

/**
 * Express-friendly access check. Returns the verified device id, or null when a
 * response has already been sent.
 *
 * `relatedRead` accepts a link in either direction, which read-only endpoints
 * need: the child app reads the position of its linked parent. Writes stay
 * directed so a device can never publish a position for another device.
 */
async function requireDevice(req, res, requestedDeviceId, { relatedRead = false } = {}) {
  if (!deviceAccess) {
    res.status(503).json({
      error: "Location access is not configured",
      code: "LOCATION_NOT_INITIALIZED",
    });
    return null;
  }
  return relatedRead
    ? deviceAccess.requireRelatedDeviceRead(req, res, requestedDeviceId)
    : deviceAccess.requireDeviceAccess(req, res, requestedDeviceId);
}

function formatLocationTimestamp(timestamp) {
  if (!timestamp || Number.isNaN(Number(timestamp))) {
    return null;
  }

  try {
    return new Date(Number(timestamp)).toISOString();
  } catch (_) {
    return String(timestamp);
  }
}

const LIVE_SHARING_AGE_MS = 3 * 60 * 1000;
const LAST_KNOWN_AGE_MS = 24 * 60 * 60 * 1000;

function locationTimeMillis(value) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return null;
  return timestamp < 100_000_000_000 ? timestamp * 1000 : timestamp;
}

/** Read both upload paths and both historical spellings of one device id. */
async function latestDevicePosition(db, deviceId) {
  const forms = deviceAccess.idForms(deviceId);
  if (!forms.length) return null;
  const placeholders = forms.map(() => "?").join(", ");
  const [child, parent] = await Promise.all([
    db.get(
      `SELECT latitude, longitude, accuracy, timestamp
       FROM locations WHERE device_id IN (${placeholders})
       ORDER BY timestamp DESC LIMIT 1`,
      forms
    ),
    db.get(
      `SELECT latitude, longitude, accuracy, timestamp
       FROM parent_locations WHERE parent_id IN (${placeholders})
       ORDER BY timestamp DESC LIMIT 1`,
      forms
    ),
  ]);
  const positions = [child, parent]
    .filter(Boolean)
    .map((row) => ({
      latitude: Number(row.latitude),
      longitude: Number(row.longitude),
      accuracy: row.accuracy == null ? null : Number(row.accuracy),
      timestamp: locationTimeMillis(row.timestamp),
    }))
    .filter((row) =>
      Number.isFinite(row.latitude) && Math.abs(row.latitude) <= 90 &&
      Number.isFinite(row.longitude) && Math.abs(row.longitude) <= 180 &&
      row.timestamp !== null
    )
    .sort((first, second) => second.timestamp - first.timestamp);
  try { await require('../services/LocationMotionStore').attach(db, forms, positions); }
  catch (error) { console.error('Motion read failed', error.message); }
  return positions[0] || null;
}

/**
 * One stored parent row, whichever spelling of the identifier wrote it.
 *
 * The installed clients upload the same phone's position as both
 * `device_<androidId>` and `<androidId>`. A lookup that names a single spelling
 * therefore returns only the half of the history that matches it, and a phone
 * reporting every thirty seconds looked four hours old to the child application
 * while its fresh rows sat under the other spelling.
 */
async function latestParentPositionRow(db, parentId) {
  const forms = deviceAccess.idForms(parentId);
  if (!forms.length) return null;
  const placeholders = forms.map(() => "?").join(", ");
  return db.get(
    `SELECT * FROM parent_locations
     WHERE parent_id IN (${placeholders})
     ORDER BY timestamp DESC LIMIT 1`,
    forms
  );
}

/** The same equivalence, as an SQL fragment and its parameters. */
function parentIdFilter(parentId) {
  const forms = deviceAccess.idForms(parentId);
  const list = forms.length ? forms : [parentId];
  return {
    clause: `parent_id IN (${list.map(() => "?").join(", ")})`,
    params: list,
  };
}

async function ensureParentLocationTables(dbManager) {  await dbManager.run(`
        CREATE TABLE IF NOT EXISTS parent_locations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            parent_id TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            accuracy REAL,
            timestamp INTEGER NOT NULL,
            battery INTEGER,
            speed REAL,
            bearing REAL,
            created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
        )
    `);

  await dbManager.run(`
        CREATE INDEX IF NOT EXISTS idx_parent_locations_parent_id
        ON parent_locations(parent_id)
    `);

  await dbManager.run(`
        CREATE INDEX IF NOT EXISTS idx_parent_locations_timestamp
        ON parent_locations(timestamp)
    `);
}

/**
 * Location API Routes
 * Handles location history and tracking data
 */

/**
 * One current point per person on the shared family map. A fresh upload is a
 * location-sharing signal inside this family; an explicit LOCATION denial still
 * wins. Existing granted relationships may see a last known point for 24 hours,
 * while an ungranted adult disappears three minutes after uploads stop.
 */
router.get("/family/latest", async (req, res) => {
  try {
    const familyId = String(req.query.familyId || "").trim();
    if (!familyId) {
      return res.status(400).json({ error: "familyId is required", code: "FAMILY_ID_REQUIRED" });
    }
    if (!req.deviceId) {
      return res.status(401).json({ error: "Authentication required", code: "AUTH_REQUIRED" });
    }
    const memberships = (await Promise.all(
      deviceAccess.idForms(req.deviceId).map((id) =>
        sharedDatabase.getFamilyIdentityMembershipsForDevice(id)
      )
    )).flat();
    const actor = memberships.find((membership) => membership.familyId === familyId);
    if (!actor) {
      return res.status(403).json({
        error: "Device is not a member of this family",
        code: "FAMILY_ACCESS_DENIED",
      });
    }

    await ensureSharedParentLocationTables();
    const [members, devices] = await Promise.all([
      sharedDatabase.getFamilyMembers(familyId),
      sharedDatabase.getFamilyDevices(familyId),
    ]);
    const devicesByMember = new Map();
    for (const device of devices) {
      const group = devicesByMember.get(device.memberId) || [];
      group.push(device);
      devicesByMember.set(device.memberId, group);
    }

    const now = Date.now();
    const locations = (await Promise.all(members.map(async (member) => {
      const positions = await Promise.all(
        (devicesByMember.get(member.id) || []).map(async (device) => ({
          deviceId: device.deviceId,
          point: await latestDevicePosition(sharedDatabase, device.deviceId),
        }))
      );
      const latest = positions
        .filter(({ point }) => point && point.timestamp <= now + 60_000)
        .sort((first, second) => second.point.timestamp - first.point.timestamp)[0];
      if (!latest || now - latest.point.timestamp > LAST_KNOWN_AGE_MS) return null;

      if (member.id !== actor.memberId) {
        const permission = await sharedDatabase.getFamilyPermission({
          familyId,
          actorMemberId: actor.memberId,
          targetMemberId: member.id,
          feature: "LOCATION",
        });
        if (permission?.allowed === 0) return null;
        if (permission?.allowed !== 1 && now - latest.point.timestamp > LIVE_SHARING_AGE_MS) {
          return null;
        }
      }
      return {
        memberId: member.id,
        displayName: member.displayName,
        role: member.role,
        avatarKey: member.avatarKey,
        deviceId: latest.deviceId,
        ...latest.point,
      };
    }))).filter(Boolean);

    return res.json({ success: true, familyId, serverTimestamp: now, locations });
  } catch (error) {
    console.error("Get family live locations error:", error);
    return res.status(500).json({
      error: "Failed to get family locations",
      code: "FAMILY_LOCATION_ERROR",
    });
  }
});

/** Recent recorded fixes for one visible family member, for a live map trail. */
router.get(["/family/trail/:memberId", "/family/history/:memberId"], async (req, res) => {
  try {
    const familyId = String(req.query.familyId || "").trim();
    const memberId = String(req.params.memberId || "").trim();
    const historyPage = req.path.startsWith("/family/history/");
    const ranged = historyPage || req.query.from !== undefined || req.query.to !== undefined;
    const rangeFrom = Number(req.query.from);
    const rangeTo = Number(req.query.to);
    const requestedDevice = String(req.query.deviceId || "").trim();
    if (ranged && (!Number.isSafeInteger(rangeFrom) || !Number.isSafeInteger(rangeTo) ||
        rangeFrom <= 0 || rangeTo < rangeFrom || rangeTo - rangeFrom > 31 * 86400000 ||
        rangeTo > Date.now() + 60000 || !requestedDevice)) {
      return res.status(400).json({ code: "HISTORY_RANGE_INVALID", error: "Choose a period up to 31 days and a device" });
    }
    if (!familyId || !memberId) {
      return res.status(400).json({ error: "Family and member are required", code: "FAMILY_TRAIL_TARGET_REQUIRED" });
    }
    if (!req.deviceId) {
      return res.status(401).json({ error: "Authentication required", code: "AUTH_REQUIRED" });
    }
    if (!deviceAccess || !sharedDatabase) {
      return res.status(503).json({ error: "Location access is not configured", code: "LOCATION_NOT_INITIALIZED" });
    }
    const memberships = (await Promise.all(
      deviceAccess.idForms(req.deviceId).map((id) =>
        sharedDatabase.getFamilyIdentityMembershipsForDevice(id)
      )
    )).flat();
    const actor = memberships.find((membership) => membership.familyId === familyId);
    if (!actor) {
      return res.status(403).json({ error: "Family access denied", code: "FAMILY_ACCESS_DENIED" });
    }
    const [members, devices] = await Promise.all([
      sharedDatabase.getFamilyMembers(familyId),
      sharedDatabase.getFamilyDevices(familyId),
    ]);
    const target = members.find((member) => member.id === memberId);
    if (!target) {
      return res.status(404).json({ error: "Member not found", code: "FAMILY_MEMBER_NOT_FOUND" });
    }
    const targetDevices = devices.filter((device) => device.memberId === memberId);
    if (ranged && !targetDevices.some(device => device.deviceId === requestedDevice)) {
      return res.status(403).json({ code: "HISTORY_DEVICE_DENIED", error: "Device does not belong to member" });
    }
    await ensureSharedParentLocationTables();
    const now = Date.now();
    const latest = (await Promise.all(targetDevices.map(async (device) => ({
      deviceId: device.deviceId,
      point: await latestDevicePosition(sharedDatabase, device.deviceId),
    })))).filter(({ point }) => point && point.timestamp <= now + 60_000)
      .sort((a, b) => b.point.timestamp - a.point.timestamp)[0];
    if (!ranged && (!latest || now - latest.point.timestamp > LAST_KNOWN_AGE_MS)) {
      return res.json({ success: true, memberId, points: [] });
    }
    if (memberId !== actor.memberId) {
      const [locationPermission, historyPermission] = await Promise.all([
        sharedDatabase.getFamilyPermission({
          familyId, actorMemberId: actor.memberId, targetMemberId: memberId, feature: "LOCATION",
        }),
        sharedDatabase.getFamilyPermission({
          familyId, actorMemberId: actor.memberId, targetMemberId: memberId,
          feature: "LOCATION_HISTORY",
        }),
      ]);
      if (historyPermission?.allowed !== 1 || locationPermission?.allowed === 0 ||
          (locationPermission?.allowed !== 1 &&
            (ranged || !latest || now - latest.point.timestamp > LIVE_SHARING_AGE_MS))) {
        return res.status(403).json({ error: "Location access denied", code: "LOCATION_ACCESS_DENIED" });
      }
    }
    const historyDevice = ranged ? requestedDevice : latest.deviceId;
    const forms = deviceAccess.idForms(historyDevice);
    if (!forms.length) return res.json({ success: true, memberId, points: [] });
    if (historyPage) {
      try {
        const page = await require('../services/FamilyHistoryPageStore').read(sharedDatabase, forms, {
          familyId, memberId, deviceId: historyDevice, actorId: actor.memberId,
          from: rangeFrom, to: rangeTo, cursor: req.query.cursor,
        });
        await require('../services/LocationMotionStore').attach(sharedDatabase, forms, page.points);
        return res.json({ success: true, familyId, memberId, deviceId: historyDevice,
          from: rangeFrom, to: rangeTo, ...page });
      } catch (error) {
        if (error.code === 'HISTORY_CHANGED' || error.code === 'HISTORY_CURSOR_INVALID')
          return res.status(error.code === 'HISTORY_CHANGED' ? 409 : 400).json({ code: error.code });
        throw error;
      }
    }
    const placeholders = forms.map(() => "?").join(", ");
    const from = ranged ? rangeFrom : now - 30 * 60 * 1000;
    const to = ranged ? rangeTo : now + 60_000;
    const cap = ranged ? 1000 : 600;
    // Both legacy seconds and ISO dates exist in these tables. Filter before LIMIT.
    const timeSql = `CASE WHEN typeof(timestamp) IN ('integer','real') OR
      (CAST(timestamp AS TEXT) NOT GLOB '*[^0-9]*' AND CAST(timestamp AS TEXT) <> '')
      THEN CASE WHEN CAST(timestamp AS REAL) < 100000000000
        THEN CAST(timestamp AS REAL) * 1000 ELSE CAST(timestamp AS REAL) END
      ELSE CAST(ROUND((julianday(timestamp) - 2440587.5) * 86400000) AS INTEGER) END`;
    const rows = (await Promise.all([
      sharedDatabase.all(
        `SELECT latitude, longitude, accuracy, (${timeSql}) AS timestamp FROM locations
         WHERE device_id IN (${placeholders}) AND (${timeSql}) BETWEEN ? AND ? ORDER BY (${timeSql}) DESC LIMIT ?`, [...forms, from, to, cap]),
      sharedDatabase.all(
        `SELECT latitude, longitude, accuracy, (${timeSql}) AS timestamp FROM parent_locations
         WHERE parent_id IN (${placeholders}) AND (${timeSql}) BETWEEN ? AND ? ORDER BY (${timeSql}) DESC LIMIT ?`, [...forms, from, to, cap]),
    ])).flat();
    const points = rows.map((row) => ({
      latitude: Number(row.latitude), longitude: Number(row.longitude),
      accuracy: row.accuracy == null ? null : Number(row.accuracy),
      timestamp: locationTimeMillis(row.timestamp),
    })).filter((point) => point.timestamp !== null && point.timestamp >= from &&
      point.timestamp <= to && Number.isFinite(point.latitude) &&
      Number.isFinite(point.longitude) && Math.abs(point.latitude) <= 90 &&
      Math.abs(point.longitude) <= 180)
      .sort((a, b) => a.timestamp - b.timestamp)
      .filter((point, index, ordered) => index === 0 ||
        point.timestamp !== ordered[index - 1].timestamp ||
        point.latitude !== ordered[index - 1].latitude ||
        point.longitude !== ordered[index - 1].longitude)
      .slice(-cap);
    try { await require('../services/LocationMotionStore').attach(sharedDatabase, forms, points); }
    catch (error) { console.error('Trail motion read failed', error.message); }
    return res.json({ success: true, memberId, deviceId: historyDevice, ...(ranged ? { from, to } : {}), points });
  } catch (error) {
    console.error("Get family trail error:", error);
    return res.status(500).json({ error: "Failed to get family trail", code: "FAMILY_TRAIL_ERROR" });
  }
});

// Get current parent+child snapshot in one response
router.get("/pair", async (req, res) => {
  try {
    const { parentId, childId } = req.query;
    console.info("[location/pair] lookup", { parentId, childId });

    if (!parentId || !childId) {
      return res.status(400).json({
        error: "parentId and childId are required",
        code: "PAIR_IDS_REQUIRED",
      });
    }

    // Both sides of the pair must be reachable by this caller.
    const authorizedParentId = await requireDevice(req, res, parentId, { relatedRead: true });
    if (authorizedParentId === null) {
      return undefined;
    }
    const authorizedChildId = await requireDevice(req, res, childId, { relatedRead: true });
    if (authorizedChildId === null) {
      return undefined;
    }

    await ensureSharedParentLocationTables();

    const [parentLocation, childLocation] = await withDatabase((db) =>
      Promise.all([
        latestParentPositionRow(db, authorizedParentId),
        db.getLatestLocation(authorizedChildId),
      ])
    );

    console.info("[location/pair] result", {
      parentId: authorizedParentId,
      childId: authorizedChildId,
      hasParent: Boolean(parentLocation),
      hasChild: Boolean(childLocation),
      parentTimestamp: formatLocationTimestamp(parentLocation?.timestamp),
      childTimestamp: formatLocationTimestamp(childLocation?.timestamp),
    });

    res.json({
      success: true,
      pair: {
        parent: parentLocation
          ? {
              id: parentLocation.id,
              parentId: parentLocation.parent_id,
              latitude: parentLocation.latitude,
              longitude: parentLocation.longitude,
              accuracy: parentLocation.accuracy,
              timestamp: parentLocation.timestamp,
              battery: parentLocation.battery,
              speed: parentLocation.speed,
              bearing: parentLocation.bearing,
              createdAt: parentLocation.created_at,
            }
          : null,
        child: childLocation
          ? {
              deviceId: authorizedChildId,
              latitude: childLocation.latitude,
              longitude: childLocation.longitude,
              accuracy: childLocation.accuracy,
              timestamp: childLocation.timestamp,
              recordedAt: new Date(childLocation.timestamp).toISOString(),
            }
          : null,
      },
      requested: {
        parentId: authorizedParentId,
        childId: authorizedChildId,
      },
      serverTimestamp: Date.now(),
    });
  } catch (error) {
    console.error("Get location pair error:", error);
    res.status(500).json({
      error: "Failed to get location pair",
      code: "LOCATION_PAIR_ERROR",
    });
  }
});

// Get location history for a device
router.get("/history/:deviceId", async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { limit = 100, offset = 0, from, to } = req.query;
    console.info("[location/history] lookup", { deviceId, limit, offset, from, to });

    const authorizedDeviceId = await requireDevice(req, res, deviceId);
    if (authorizedDeviceId === null) {
      return undefined;
    }

    const parsedLimit = parseInt(limit, 10) || 100;
    const parsedOffset = parseInt(offset, 10) || 0;
    const parsedFrom = from !== undefined ? parseInt(from, 10) : null;
    const parsedTo = to !== undefined ? parseInt(to, 10) : null;

    const locations = await withDatabase((db) =>
      db.getLocationHistory(
        authorizedDeviceId,
        parsedLimit,
        parsedOffset,
        parsedFrom,
        parsedTo
      )
    );

    console.info("[location/history] result", {
      deviceId: authorizedDeviceId,
      count: locations.length,
      firstTimestamp: formatLocationTimestamp(locations[0]?.timestamp),
      lastTimestamp: formatLocationTimestamp(locations[locations.length - 1]?.timestamp),
    });

    res.json({
      success: true,
      deviceId: authorizedDeviceId,
      limit: parsedLimit,
      offset: parsedOffset,
      locations: locations.map((loc) => ({
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy,
        timestamp: loc.timestamp,
        recordedAt: new Date(loc.timestamp).toISOString(),
      })),
      count: locations.length,
    });
  } catch (error) {
    console.error("Get location history error:", error);
    res.status(500).json({
      error: "Failed to get location history",
      code: "LOCATION_HISTORY_ERROR",
    });
  }
});

// Get latest location for a device
router.get("/latest/:deviceId", async (req, res) => {
  try {
    const { deviceId } = req.params;
    console.info("[location/latest] lookup", { deviceId });

    const authorizedDeviceId = await requireDevice(req, res, deviceId);
    if (authorizedDeviceId === null) {
      return undefined;
    }

    const location = await withDatabase((db) => db.getLatestLocation(authorizedDeviceId));

    if (!location) {
      console.info("[location/latest] result", { deviceId: authorizedDeviceId, found: false });
      return res.status(404).json({
        error: "No location data found for device",
        code: "NO_LOCATION_DATA",
      });
    }

    console.info("[location/latest] result", {
      deviceId: authorizedDeviceId,
      found: true,
      timestamp: formatLocationTimestamp(location.timestamp),
    });

    res.json({
      success: true,
      deviceId: authorizedDeviceId,
      location: {
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy: location.accuracy,
        timestamp: location.timestamp,
        recordedAt: new Date(location.timestamp).toISOString(),
      },
    });
  } catch (error) {
    console.error("Get latest location error:", error);
    res.status(500).json({
      error: "Failed to get latest location",
      code: "LATEST_LOCATION_ERROR",
    });
  }
});

// Get location statistics
router.get("/stats/:deviceId", async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { days = 7 } = req.query;

    const authorizedDeviceId = await requireDevice(req, res, deviceId);
    if (authorizedDeviceId === null) {
      return undefined;
    }

    const fromTimestamp = Date.now() - parseInt(days, 10) * 24 * 60 * 60 * 1000;

    const { totalCount, dailyStats, accuracyStats } = await withDatabase(async (db) => {
      // Total locations in period
      const total = await db.get(
        "SELECT COUNT(*) as count FROM locations WHERE device_id = ? AND timestamp >= ?",
        [authorizedDeviceId, fromTimestamp]
      );

      // Locations per day
      const daily = await db.all(
        `
            SELECT 
                DATE(timestamp/1000, 'unixepoch') as date,
                COUNT(*) as count,
                AVG(accuracy) as avg_accuracy,
                MIN(timestamp) as first_location,
                MAX(timestamp) as last_location
            FROM locations 
            WHERE device_id = ? AND timestamp >= ?
            GROUP BY DATE(timestamp/1000, 'unixepoch')
            ORDER BY date DESC
        `,
        [authorizedDeviceId, fromTimestamp]
      );

      // Average accuracy
      const accuracy = await db.get(
        "SELECT AVG(accuracy) as avg_accuracy, MIN(accuracy) as best_accuracy, MAX(accuracy) as worst_accuracy FROM locations WHERE device_id = ? AND timestamp >= ?",
        [authorizedDeviceId, fromTimestamp]
      );

      return { totalCount: total, dailyStats: daily, accuracyStats: accuracy };
    });

    res.json({
      success: true,
      stats: {
        totalLocations: totalCount.count,
        averageAccuracy: Math.round(accuracyStats.avg_accuracy || 0),
        bestAccuracy: accuracyStats.best_accuracy || 0,
        worstAccuracy: accuracyStats.worst_accuracy || 0,
        dailyBreakdown: dailyStats.map((stat) => ({
          date: stat.date,
          count: stat.count,
          avgAccuracy: Math.round(stat.avg_accuracy),
          firstLocation: stat.first_location,
          lastLocation: stat.last_location,
        })),
      },
    });
  } catch (error) {
    console.error("Get location stats error:", error);
    res.status(500).json({
      error: "Failed to get location statistics",
      code: "LOCATION_STATS_ERROR",
    });
  }
});

// ===== PARENT LOCATION ENDPOINTS =====

/**
 * POST /api/location/parent/:parentId
 * Upload parent location
 */
// Historical replay is authenticated as the sending phone and never emits place/status events.
router.post("/history", async (req, res) => {
  try {
    const { familyId, kind, points, ownDeviceId } = req.body;
    if (typeof ownDeviceId !== 'string' || !deviceAccess.isSameDevice(ownDeviceId, req.deviceId)) return res.status(403).json({ success: false, error: 'History belongs to another phone' });
    if (typeof familyId !== 'string' || !['parent', 'child'].includes(kind) || !Array.isArray(points) || points.length < 1 || points.length > 100)
      return res.status(400).json({ success: false, error: 'Invalid history batch' });
    const service = require('../services/FamilyPlacesService').forDatabase(sharedDatabase);
    const actor = await service.actor(req.deviceId, familyId);
    if (!actor || (kind === 'parent' ? !['PARENT', 'GUARDIAN'].includes(actor.memberRole) : actor.memberRole !== 'CHILD'))
      return res.status(403).json({ success: false, error: 'Wrong family or phone role' });
    const now = Date.now();
    if (points.some(p => !p || !Number.isSafeInteger(p.timestamp) || p.timestamp < now - 48*60*60*1000 || p.timestamp > now + 60000 ||
      !Number.isFinite(p.latitude) || Math.abs(p.latitude)>90 || !Number.isFinite(p.longitude) || Math.abs(p.longitude)>180 ||
      !Number.isFinite(p.accuracy) || p.accuracy<=0 || p.accuracy>500))
      return res.status(400).json({ success: false, error: 'Invalid historical fix' });
    if (kind === 'parent') await ensureSharedParentLocationTables();
    const table = kind === 'parent' ? 'parent_locations' : 'locations';
    const deviceColumn = kind === 'parent' ? 'parent_id' : 'device_id';
    for (const p of points) {
      await sharedDatabase.run(`INSERT INTO ${table} (${deviceColumn},latitude,longitude,accuracy,timestamp)
        SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM ${table} WHERE ${deviceColumn}=? AND timestamp=? AND latitude=? AND longitude=?)`,
        [req.deviceId,p.latitude,p.longitude,p.accuracy,p.timestamp,req.deviceId,p.timestamp,p.latitude,p.longitude]);
      await require('../services/LocationMotionStore').save(sharedDatabase, req.deviceId, p);
    }
    return res.json({ success: true, accepted: points.length });
  } catch (error) {
    console.error('History replay failed', error.message);
    return res.status(500).json({ success: false, error: 'History replay failed' });
  }
});

router.post("/parent/:parentId", async (req, res) => {
  try {
    const { parentId } = req.params;
    const {
      latitude,
      longitude,
      accuracy,
      timestamp,
      battery,
      speed,
      bearing,
    } = req.body;

    // Validate required fields
    if (latitude === undefined || latitude === null || longitude === undefined || longitude === null) {
      return res.status(400).json({
        error: "Missing required fields: latitude, longitude",
        code: "MISSING_FIELDS",
      });
    }

    const authorizedParentId = await requireDevice(req, res, parentId);
    if (authorizedParentId === null) {
      return undefined;
    }

    if (!deviceAccess.isSameDevice(authorizedParentId, req.deviceId)) {
      return res.status(403).json({ success: false, error: "A device can only publish its own location", code: "LOCATION_WRITE_SELF_ONLY" });
    }

    console.info("[location/parent/upload] incoming", {
      parentId: authorizedParentId,
      latitude,
      longitude,
      timestamp: formatLocationTimestamp(timestamp),
    });

    await ensureSharedParentLocationTables();

    await withDatabase(async (db) => {
      // Insert location
      await db.run(
        `
            INSERT INTO parent_locations (
                parent_id, latitude, longitude, accuracy, 
                timestamp, battery, speed, bearing
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        `,
        [
          authorizedParentId,
          parseFloat(latitude),
          parseFloat(longitude),
          accuracy ? parseFloat(accuracy) : null,
          timestamp ? parseInt(timestamp, 10) : Date.now(),
          battery ? parseInt(battery, 10) : null,
          speed ? parseFloat(speed) : null,
          bearing ? parseFloat(bearing) : null,
        ]
      );

      try { await require('../services/LocationMotionStore').save(db, authorizedParentId, req.body); }
      catch (error) { console.error('Parent motion storage failed', error.message); }
      try {
        await require('../services/FamilyPlacesService').forDatabase(db).onLocation(authorizedParentId, {
          latitude, longitude, accuracy, timestamp: timestamp || Date.now()
        });
      } catch (error) { console.error('Parent place notification processing failed', error.message); }

      // Parent fixes expire by TIME, not by count. The previous rule kept the
      // newest 20 000 rows per parent, so the real window followed the upload rate
      // (about 27.8 hours at one fix per five seconds) instead of the agreed three
      // months, while the neighbouring comment still promised 1000 rows. The
      // hourly sweep in LocationRetention drops what the time window drops anyway.
      await db.run(
        `DELETE FROM parent_locations WHERE parent_id = ? AND timestamp < ?`,
        [authorizedParentId, require('../services/LocationRetention').cutoffFor()]
      );
    });

    res.json({
      success: true,
      message: "Parent location saved",
    });
  } catch (error) {
    console.error("Upload parent location error:", error);
    res.status(500).json({
      error: "Failed to save parent location",
      code: "PARENT_LOCATION_SAVE_ERROR",
    });
  }
});

/**
 * GET /api/location/parent/latest/:parentId
 * Get latest parent location
 */
router.get("/parent/latest/:parentId", async (req, res) => {
  try {
    const { parentId } = req.params;
    console.info("[location/parent/latest] lookup", { parentId });

    const authorizedParentId = await requireDevice(req, res, parentId, { relatedRead: true });
    if (authorizedParentId === null) {
      return undefined;
    }

    await ensureSharedParentLocationTables();

    const location = await withDatabase((db) =>
      latestParentPositionRow(db, authorizedParentId)
    );

    if (!location) {
      console.info("[location/parent/latest] result", {
        parentId: authorizedParentId,
        found: false,
      });
      return res.status(404).json({
        error: "No location data found for parent",
        code: "NO_PARENT_LOCATION",
      });
    }

    console.info("[location/parent/latest] result", {
      parentId: authorizedParentId,
      found: true,
      timestamp: formatLocationTimestamp(location.timestamp),
    });

    res.json({
      success: true,
      location: {
        id: location.id,
        parentId: location.parent_id,
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy: location.accuracy,
        timestamp: location.timestamp,
        battery: location.battery,
        speed: location.speed,
        bearing: location.bearing,
        createdAt: location.created_at,
      },
    });
  } catch (error) {
    console.error("Get latest parent location error:", error);
    res.status(500).json({
      error: "Failed to get parent location",
      code: "PARENT_LOCATION_GET_ERROR",
    });
  }
});

/**
 * GET /api/location/parent/history/:parentId
 * Get parent location history
 */
router.get("/parent/history/:parentId", async (req, res) => {
  try {
    const { parentId } = req.params;
    const { limit = 100, offset = 0, from, to } = req.query;
    console.info("[location/parent/history] lookup", { parentId, limit, offset, from, to });

    const authorizedParentId = await requireDevice(req, res, parentId, { relatedRead: true });
    if (authorizedParentId === null) {
      return undefined;
    }

    await ensureSharedParentLocationTables();

    const parsedLimit = parseInt(limit, 10) || 100;
    const parsedOffset = parseInt(offset, 10) || 0;
    const parsedFrom = from !== undefined ? parseInt(from, 10) : null;
    const parsedTo = to !== undefined ? parseInt(to, 10) : null;

    const parentFilter = parentIdFilter(authorizedParentId);
    const filters = [parentFilter.clause];
    const params = [...parentFilter.params];

    if (parsedFrom !== null && !Number.isNaN(parsedFrom)) {
      filters.push("timestamp >= ?");
      params.push(parsedFrom);
    }
    if (parsedTo !== null && !Number.isNaN(parsedTo)) {
      filters.push("timestamp <= ?");
      params.push(parsedTo);
    }

    params.push(parsedLimit, parsedOffset);

    const locations = await withDatabase((db) =>
      db.all(
        `
            SELECT * FROM parent_locations 
            WHERE ${filters.join(" AND ")}
            ORDER BY timestamp DESC 
            LIMIT ? OFFSET ?
        `,
        params
      )
    );

    console.info("[location/parent/history] result", {
      parentId: authorizedParentId,
      count: locations.length,
      firstTimestamp: formatLocationTimestamp(locations[0]?.timestamp),
      lastTimestamp: formatLocationTimestamp(locations[locations.length - 1]?.timestamp),
    });

    res.json({
      success: true,
      parentId: authorizedParentId,
      limit: parsedLimit,
      offset: parsedOffset,
      locations: locations.map((loc) => ({
        id: loc.id,
        parentId: loc.parent_id,
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy,
        timestamp: loc.timestamp,
        battery: loc.battery,
        speed: loc.speed,
        bearing: loc.bearing,
        createdAt: loc.created_at,
      })),
      count: locations.length,
    });
  } catch (error) {
    console.error("Get parent location history error:", error);
    res.status(500).json({
      error: "Failed to get parent location history",
      code: "PARENT_LOCATION_HISTORY_ERROR",
    });
  }
});

module.exports = router;
