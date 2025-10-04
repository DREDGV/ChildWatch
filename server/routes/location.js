const express = require('express');
const DatabaseManager = require('../database/DatabaseManager');

const router = express.Router();

/**
 * Location API Routes
 * Handles location history and tracking data
 */

// Get location history for a device
router.get('/history/:deviceId', async (req, res) => {
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
            locations: locations.map(loc => ({
                id: loc.id,
                latitude: loc.latitude,
                longitude: loc.longitude,
                accuracy: loc.accuracy,
                timestamp: loc.timestamp,
                createdAt: loc.created_at
            })),
            count: locations.length
        });
        
    } catch (error) {
        console.error('Get location history error:', error);
        res.status(500).json({
            error: 'Failed to get location history',
            code: 'LOCATION_HISTORY_ERROR'
        });
    }
});

// Get latest location for a device
router.get('/latest/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const location = await dbManager.getLatestLocation(deviceId);
        
        await dbManager.close();
        
        if (!location) {
            return res.status(404).json({
                error: 'No location data found for device',
                code: 'NO_LOCATION_DATA'
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
                createdAt: location.created_at
            }
        });
        
    } catch (error) {
        console.error('Get latest location error:', error);
        res.status(500).json({
            error: 'Failed to get latest location',
            code: 'LATEST_LOCATION_ERROR'
        });
    }
});

// Get location statistics
router.get('/stats/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { days = 7 } = req.query;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const fromTimestamp = Date.now() - (parseInt(days) * 24 * 60 * 60 * 1000);
        
        // Total locations in period
        const totalCount = await dbManager.get(
            'SELECT COUNT(*) as count FROM locations WHERE device_id = ? AND timestamp >= ?',
            [deviceId, fromTimestamp]
        );
        
        // Locations per day
        const dailyStats = await dbManager.all(`
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
        `, [deviceId, fromTimestamp]);
        
        // Average accuracy
        const accuracyStats = await dbManager.get(
            'SELECT AVG(accuracy) as avg_accuracy, MIN(accuracy) as best_accuracy, MAX(accuracy) as worst_accuracy FROM locations WHERE device_id = ? AND timestamp >= ?',
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
                dailyBreakdown: dailyStats.map(stat => ({
                    date: stat.date,
                    count: stat.count,
                    avgAccuracy: Math.round(stat.avg_accuracy),
                    firstLocation: stat.first_location,
                    lastLocation: stat.last_location
                }))
            }
        });
        
    } catch (error) {
        console.error('Get location stats error:', error);
        res.status(500).json({
            error: 'Failed to get location statistics',
            code: 'LOCATION_STATS_ERROR'
        });
    }
});

module.exports = router;
