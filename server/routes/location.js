const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");

const router = express.Router();

/**
 * Location API Routes
 * Handles location history and tracking data
 */

// Get location history for a device
router.get("/history/:deviceId", async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { limit = 100, offset = 0, from, to } = req.query;

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    let sql = `SELECT * FROM locations WHERE device_id = ?`;
    let params = [deviceId];

    // Add date range filter if provided
    if (from) {
      sql += ` AND timestamp >= ?`;
      params.push(parseInt(from));
    }

    if (to) {
      sql += ` AND timestamp <= ?`;
      params.push(parseInt(to));
    }

    sql += ` ORDER BY timestamp DESC LIMIT ? OFFSET ?`;
    params.push(parseInt(limit), parseInt(offset));

    const locations = await dbManager.all(sql, params);

    await dbManager.close();

    res.json({
      success: true,
      locations: locations.map((loc) => ({
        id: loc.id,
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy,
        timestamp: loc.timestamp,
        createdAt: loc.created_at,
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

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    const location = await dbManager.getLatestLocation(deviceId);

    await dbManager.close();

    if (!location) {
      return res.status(404).json({
        error: "No location data found for device",
        code: "NO_LOCATION_DATA",
      });
    }

    res.json({
      success: true,
      location: {
        id: location.id,
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy: location.accuracy,
        timestamp: location.timestamp,
        createdAt: location.created_at,
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

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    const fromTimestamp = Date.now() - parseInt(days) * 24 * 60 * 60 * 1000;

    // Total locations in period
    const totalCount = await dbManager.get(
      "SELECT COUNT(*) as count FROM locations WHERE device_id = ? AND timestamp >= ?",
      [deviceId, fromTimestamp]
    );

    // Locations per day
    const dailyStats = await dbManager.all(
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
      [deviceId, fromTimestamp]
    );

    // Average accuracy
    const accuracyStats = await dbManager.get(
      "SELECT AVG(accuracy) as avg_accuracy, MIN(accuracy) as best_accuracy, MAX(accuracy) as worst_accuracy FROM locations WHERE device_id = ? AND timestamp >= ?",
      [deviceId, fromTimestamp]
    );

    await dbManager.close();

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
    if (!latitude || !longitude) {
      return res.status(400).json({
        error: "Missing required fields: latitude, longitude",
        code: "MISSING_FIELDS",
      });
    }

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    // Create parent_locations table if not exists
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

    // Create index if not exists
    await dbManager.run(`
            CREATE INDEX IF NOT EXISTS idx_parent_locations_parent_id 
            ON parent_locations(parent_id)
        `);

    await dbManager.run(`
            CREATE INDEX IF NOT EXISTS idx_parent_locations_timestamp 
            ON parent_locations(timestamp)
        `);

    // Insert location
    await dbManager.run(
      `
            INSERT INTO parent_locations (
                parent_id, latitude, longitude, accuracy, 
                timestamp, battery, speed, bearing
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        `,
      [
        parentId,
        parseFloat(latitude),
        parseFloat(longitude),
        accuracy ? parseFloat(accuracy) : null,
        timestamp ? parseInt(timestamp) : Date.now(),
        battery ? parseInt(battery) : null,
        speed ? parseFloat(speed) : null,
        bearing ? parseFloat(bearing) : null,
      ]
    );

    // Clean up old locations (keep last 1000)
    await dbManager.run(
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
      [parentId, parentId]
    );

    await dbManager.close();

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

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    const location = await dbManager.get(
      `
            SELECT * FROM parent_locations 
            WHERE parent_id = ? 
            ORDER BY timestamp DESC 
            LIMIT 1
        `,
      [parentId]
    );

    await dbManager.close();

    if (!location) {
      return res.status(404).json({
        error: "No location data found for parent",
        code: "NO_PARENT_LOCATION",
      });
    }

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
    const { limit = 100, offset = 0 } = req.query;

    const dbManager = new DatabaseManager();
    await dbManager.initialize();

    const locations = await dbManager.all(
      `
            SELECT * FROM parent_locations 
            WHERE parent_id = ? 
            ORDER BY timestamp DESC 
            LIMIT ? OFFSET ?
        `,
      [parentId, parseInt(limit), parseInt(offset)]
    );

    await dbManager.close();

    res.json({
      success: true,
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
