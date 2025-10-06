/**
 * Audio Streaming Routes
 * Handles real-time audio streaming and recording commands
 */

const express = require('express');
const router = express.Router();
const multer = require('multer');
const path = require('path');
const fs = require('fs');

// Configure multer for audio chunk uploads
const storage = multer.memoryStorage();
const upload = multer({
    storage: storage,
    limits: { fileSize: 5 * 1024 * 1024 } // 5MB max per chunk
});

/**
 * Initialize with managers
 */
let commandManager, dbManager;

router.init = (cmdMgr, dbMgr) => {
    commandManager = cmdMgr;
    dbManager = dbMgr;
};

/**
 * POST /api/streaming/commands/:deviceId
 * Get pending commands for child device
 */
router.get('/commands/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Get pending commands
        const commands = commandManager.getCommands(deviceId);

        res.json({
            success: true,
            deviceId: deviceId,
            commands: commands,
            count: commands.length,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Get commands error:', error);
        res.status(500).json({
            error: 'Failed to get commands',
            code: 'GET_COMMANDS_ERROR'
        });
    }
});

/**
 * POST /api/streaming/start
 * Start audio streaming session (called by parent)
 */
router.post('/start', async (req, res) => {
    try {
        const { deviceId, parentId, timeoutMinutes } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Start streaming session with optional timeout (default 10 minutes)
        const timeout = timeoutMinutes || 10;
        const result = commandManager.startStreaming(deviceId, parentId || 'parent', timeout);

        if (result) {
            res.json({
                success: true,
                message: 'Audio streaming started',
                deviceId: deviceId,
                sessionId: `stream_${Date.now()}`,
                timestamp: Date.now()
            });
        } else {
            res.status(500).json({
                error: 'Failed to start streaming',
                code: 'START_STREAM_ERROR'
            });
        }

    } catch (error) {
        console.error('Start streaming error:', error);
        res.status(500).json({
            error: 'Failed to start streaming',
            code: 'START_STREAM_ERROR'
        });
    }
});

/**
 * POST /api/streaming/stop
 * Stop audio streaming session (called by parent)
 */
router.post('/stop', async (req, res) => {
    try {
        const { deviceId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Stop streaming session
        const result = commandManager.stopStreaming(deviceId);

        if (result) {
            res.json({
                success: true,
                message: 'Audio streaming stopped',
                deviceId: deviceId,
                timestamp: Date.now()
            });
        } else {
            res.status(404).json({
                error: 'No active streaming session',
                code: 'NO_ACTIVE_SESSION'
            });
        }

    } catch (error) {
        console.error('Stop streaming error:', error);
        res.status(500).json({
            error: 'Failed to stop streaming',
            code: 'STOP_STREAM_ERROR'
        });
    }
});

/**
 * POST /api/streaming/record/start
 * Start recording during active streaming (called by parent)
 */
router.post('/record/start', async (req, res) => {
    try {
        const { deviceId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Start recording
        const result = commandManager.startRecording(deviceId);

        if (result.error) {
            return res.status(400).json({
                error: result.error,
                code: 'START_RECORDING_ERROR'
            });
        }

        res.json({
            success: true,
            message: 'Recording started',
            deviceId: deviceId,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Start recording error:', error);
        res.status(500).json({
            error: 'Failed to start recording',
            code: 'START_RECORDING_ERROR'
        });
    }
});

/**
 * POST /api/streaming/record/stop
 * Stop recording during streaming (called by parent)
 */
router.post('/record/stop', async (req, res) => {
    try {
        const { deviceId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Stop recording
        const result = commandManager.stopRecording(deviceId);

        if (result.error) {
            return res.status(400).json({
                error: result.error,
                code: 'STOP_RECORDING_ERROR'
            });
        }

        res.json({
            success: true,
            message: 'Recording stopped',
            deviceId: deviceId,
            duration: result.duration,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Stop recording error:', error);
        res.status(500).json({
            error: 'Failed to stop recording',
            code: 'STOP_RECORDING_ERROR'
        });
    }
});

/**
 * POST /api/streaming/chunk
 * Upload audio chunk from child device
 */
router.post('/chunk', upload.single('audio'), async (req, res) => {
    try {
        const { deviceId, sequence, recording } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        if (!req.file) {
            return res.status(400).json({
                error: 'No audio chunk provided',
                code: 'MISSING_AUDIO_CHUNK'
            });
        }

        // Add chunk to buffer
        const chunkCount = commandManager.addAudioChunk(deviceId, req.file.buffer);

        // If recording, save chunk to disk
        if (recording === 'true') {
            const uploadsDir = path.join(__dirname, '..', 'uploads', 'audio', 'chunks', deviceId);
            if (!fs.existsSync(uploadsDir)) {
                fs.mkdirSync(uploadsDir, { recursive: true });
            }

            const filename = `chunk_${sequence}_${Date.now()}.webm`;
            const filepath = path.join(uploadsDir, filename);
            fs.writeFileSync(filepath, req.file.buffer);
        }

        res.json({
            success: true,
            deviceId: deviceId,
            sequence: parseInt(sequence || 0),
            bufferSize: chunkCount,
            recording: recording === 'true',
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Upload chunk error:', error);
        res.status(500).json({
            error: 'Failed to upload chunk',
            code: 'UPLOAD_CHUNK_ERROR'
        });
    }
});

/**
 * GET /api/streaming/chunks/:deviceId
 * Get latest audio chunks for playback (called by parent)
 */
router.get('/chunks/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const count = parseInt(req.query.count || 5);

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        // Get latest chunks
        const chunks = commandManager.getAudioChunks(deviceId, count);

        res.json({
            success: true,
            deviceId: deviceId,
            chunks: chunks.map((chunk, index) => ({
                sequence: index,
                data: chunk.data.toString('base64'),
                timestamp: chunk.timestamp
            })),
            count: chunks.length,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Get chunks error:', error);
        res.status(500).json({
            error: 'Failed to get chunks',
            code: 'GET_CHUNKS_ERROR'
        });
    }
});

/**
 * GET /api/streaming/status/:deviceId
 * Get streaming session status
 */
router.get('/status/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const session = commandManager.getSessionInfo(deviceId);

        if (!session) {
            return res.json({
                success: true,
                streaming: false,
                recording: false,
                deviceId: deviceId
            });
        }

        res.json({
            success: true,
            streaming: true,
            recording: session.recording,
            deviceId: deviceId,
            parentId: session.parentId,
            startTime: session.startTime,
            duration: Date.now() - session.startTime,
            chunks: session.chunks,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Get status error:', error);
        res.status(500).json({
            error: 'Failed to get status',
            code: 'GET_STATUS_ERROR'
        });
    }
});

module.exports = router;
