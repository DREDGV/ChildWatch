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

async function ensureParentLocationTables(dbManager) {
  await dbManager.run(`
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
        db.get(
          `
            SELECT * FROM parent_locations
            WHERE parent_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
        `,
          [authorizedParentId]
        ),
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

      // Clean up old locations (keep last 1000)
      await db.run(
        `
            DELETE FROM parent_locations 
            WHERE parent_id = ? 
            AND id NOT IN (
                SELECT id FROM parent_locations 
                WHERE parent_id = ? 
                ORDER BY timestamp DESC 
                LIMIT 1000
            )
        `,
        [authorizedParentId, authorizedParentId]
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
      db.get(
        `
            SELECT * FROM parent_locations 
            WHERE parent_id = ? 
            ORDER BY timestamp DESC 
            LIMIT 1
        `,
        [authorizedParentId]
      )
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

    const filters = ["parent_id = ?"];
    const params = [authorizedParentId];

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
