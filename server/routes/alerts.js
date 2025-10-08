const express = require('express');
const router = express.Router();

let dbManager;
let wsManager;

router.init = (databaseManager, webSocketManager) => {
    dbManager = databaseManager;
    wsManager = webSocketManager;
};

router.post('/', async (req, res) => {
    try {
        const { deviceId, eventType, severity, message, metadata } = req.body;

        if (!deviceId || !eventType || !severity || !message) {
            return res.status(400).json({
                error: 'deviceId, eventType, severity and message are required',
                code: 'INVALID_ALERT_PAYLOAD'
            });
        }

        const alertRecord = await dbManager.saveCriticalAlert({
            deviceId,
            eventType,
            severity,
            message,
            metadata: metadata || null
        });

        const alertPayload = {
            id: alertRecord.id,
            deviceId,
            eventType,
            severity,
            message,
            metadata: metadata || null,
            createdAt: Date.now()
        };

        let delivered = false;
        if (wsManager) {
            delivered = wsManager.emitCriticalAlert(deviceId, alertPayload) === true;
            if (delivered) {
                await dbManager.markAlertDelivered(alertRecord.id);
            }
        }

        res.json({
            success: true,
            alertId: alertRecord.id,
            delivered
        });
    } catch (error) {
        console.error('Critical alert creation error:', error);
        res.status(500).json({
            error: 'Failed to create alert',
            code: 'ALERT_CREATION_ERROR'
        });
    }
});

router.get('/pending/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const limit = parseInt(req.query.limit, 10) || 20;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const pendingAlerts = await dbManager.getPendingCriticalAlerts(deviceId, limit);

        res.json({
            success: true,
            deviceId,
            count: pendingAlerts.length,
            alerts: pendingAlerts
        });
    } catch (error) {
        console.error('Get pending alerts error:', error);
        res.status(500).json({
            error: 'Failed to get alerts',
            code: 'GET_ALERTS_ERROR'
        });
    }
});

router.post('/ack', async (req, res) => {
    try {
        const { deviceId, alertIds } = req.body;

        if (!deviceId || !Array.isArray(alertIds) || alertIds.length === 0) {
            return res.status(400).json({
                error: 'deviceId and alertIds are required',
                code: 'INVALID_ACK_PAYLOAD'
            });
        }

        await dbManager.acknowledgeCriticalAlerts(deviceId, alertIds);

        res.json({
            success: true,
            acknowledged: alertIds.length
        });
    } catch (error) {
        console.error('Acknowledge alerts error:', error);
        res.status(500).json({
            error: 'Failed to acknowledge alerts',
            code: 'ACK_ALERTS_ERROR'
        });
    }
});

module.exports = router;
