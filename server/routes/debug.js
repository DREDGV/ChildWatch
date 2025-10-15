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
            deviceId = 'unknown-device',
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
