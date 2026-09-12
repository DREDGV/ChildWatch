const express = require('express');
const router = express.Router();

let dbManager;
let wsManager;

router.init = (databaseManager, webSocketManager) => {
    dbManager = databaseManager;
    wsManager = webSocketManager;
};

/**
 * The authenticated device may only touch alerts it is entitled to:
 * its own (a child reporting about itself), or a child it is actively linked
 * to (ParentMonitor syncs the alerts of the monitored child).
 *
 * The device identifier travels in the body/params because the child device is
 * the alert subject, so it cannot be replaced by the caller identity without
 * losing that meaning. It is verified instead.
 */
async function isLinkedParentOf(parentDeviceId, childDeviceId) {
    if (!parentDeviceId || !childDeviceId) return false;
    const link = await dbManager.get(
        'SELECT 1 AS linked FROM device_links WHERE parent_device_id = ? AND child_device_id = ? AND is_active = 1 LIMIT 1',
        [parentDeviceId, childDeviceId]
    );
    return Boolean(link);
}

async function resolveAuthorizedAlertTarget(req, res, requestedDeviceId) {
    const callerDeviceId = String(req.deviceId || '').trim();
    if (!callerDeviceId) {
        // Defensive: this router must be mounted behind authenticate(). Failing
        // closed keeps a misconfiguration from turning into an open door.
        res.status(401).json({
            error: 'Authentication required',
            code: 'AUTH_REQUIRED'
        });
        return null;
    }

    const requested = String(requestedDeviceId || '').trim();
    if (!requested) {
        res.status(400).json({
            error: 'deviceId is required',
            code: 'MISSING_DEVICE_ID'
        });
        return null;
    }

    if (requested === callerDeviceId) {
        return requested;
    }

    if (await isLinkedParentOf(callerDeviceId, requested)) {
        return requested;
    }

    res.status(403).json({
        error: 'Alert access denied',
        code: 'ALERT_ACCESS_DENIED'
    });
    return null;
}

router.post('/', async (req, res) => {
    try {
        const { deviceId, eventType, severity, message, metadata } = req.body;

        if (!deviceId || !eventType || !severity || !message) {
            return res.status(400).json({
                error: 'deviceId, eventType, severity and message are required',
                code: 'INVALID_ALERT_PAYLOAD'
            });
        }

        const authorizedDeviceId = await resolveAuthorizedAlertTarget(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const alertRecord = await dbManager.saveCriticalAlert({
            deviceId: authorizedDeviceId,
            eventType,
            severity,
            message,
            metadata: metadata || null
        });

        const alertPayload = {
            id: alertRecord.id,
            deviceId: authorizedDeviceId,
            eventType,
            severity,
            message,
            metadata: metadata || null,
            createdAt: Date.now()
        };

        let delivered = false;
        if (wsManager) {
            delivered = wsManager.emitCriticalAlert(authorizedDeviceId, alertPayload) === true;
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

        const authorizedDeviceId = await resolveAuthorizedAlertTarget(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const pendingAlerts = await dbManager.getPendingCriticalAlerts(authorizedDeviceId, limit);

        res.json({
            success: true,
            deviceId: authorizedDeviceId,
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

        const authorizedDeviceId = await resolveAuthorizedAlertTarget(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        await dbManager.acknowledgeCriticalAlerts(authorizedDeviceId, alertIds);

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
