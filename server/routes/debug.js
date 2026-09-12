const express = require('express');
const router = express.Router();

/**
 * Lightweight endpoint to capture diagnostic logs from devices.
 * Logs are printed to the server console so they can be inspected via Railway.
 *
 * Request body:
 * {
 *   deviceId: string,
 *   source: string,
 *   level: 'INFO' | 'WARN' | 'ERROR',
 *   message: string,
 *   meta: object (optional)
 * }
 */
router.post('/log', (req, res) => {
    try {
        const {
            source = 'unknown-source',
            level = 'INFO',
            message,
            meta
        } = req.body || {};

        if (!message) {
            return res.status(400).json({
                success: false,
                error: 'message is required'
            });
        }

        // The device identity comes from the authenticated request. It used to
        // be read from the body, which let any caller write log lines that
        // looked like they came from another phone.
        const deviceId = req.deviceId || 'unauthenticated-device';

        const timestamp = new Date().toISOString();
        const normalizedLevel = (level || 'INFO').toUpperCase();
        const logPrefix = `[RemoteLog][${timestamp}][${normalizedLevel}][${deviceId}][${source}]`;

        if (meta && Object.keys(meta).length > 0) {
            console.log(`${logPrefix} ${message}`, meta);
        } else {
            console.log(`${logPrefix} ${message}`);
        }

        res.json({ success: true });
    } catch (error) {
        console.error('[RemoteLog][ERROR] Failed to handle remote log:', error);
        res.status(500).json({
            success: false,
            error: 'Failed to process remote log'
        });
    }
});

module.exports = router;
