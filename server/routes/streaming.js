/**
 * Audio Streaming Routes
 * Handles real-time audio streaming and recording commands
 */

const express = require('express');
const router = express.Router();
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const DeviceAccessService = require('../services/DeviceAccessService');

// Configure multer for audio chunk uploads
const storage = multer.memoryStorage();
const upload = multer({
    storage: storage,
    limits: { fileSize: 5 * 1024 * 1024 } // 5MB max per chunk
});

/**
 * Initialize with managers
 */
let commandManager, dbManager, wsManager, deviceAccess;

router.init = (cmdMgr, dbMgr, wsMgr) => {
    commandManager = cmdMgr;
    dbManager = dbMgr;
    wsManager = wsMgr;
    deviceAccess = new DeviceAccessService(dbMgr);
};

/**
 * Verifies that the authenticated caller may drive the given child device.
 * Returns the verified target device id, or null when a response was sent.
 */
async function requireStreamingAccess(req, res, requestedDeviceId) {
    if (!deviceAccess) {
        res.status(503).json({
            error: 'Streaming access is not configured',
            code: 'STREAMING_NOT_INITIALIZED'
        });
        return null;
    }
    return deviceAccess.requireDeviceAccess(req, res, requestedDeviceId);
}

/**
 * The session owner is always the authenticated caller. The `parentId` field in
 * the request body used to name the owner, which let any caller claim an
 * arbitrary identity and take over someone else's listening session.
 */
function resolveOwnerId(req, requestedParentId) {
    const caller = normalizeParentId(req.deviceId);
    if (caller) {
        return caller;
    }
    // No verified identity: fall back to the legacy shape only so the response
    // stays well-formed. Access is already refused before this point.
    return normalizeParentId(requestedParentId) || 'parent';
}

function normalizeParentId(value) {
    if (value === null || value === undefined) return "";
    return String(value).trim();
}

async function resolveOwnerDisplayName(parentDeviceId, fallback = null) {
    if (typeof fallback === "string" && fallback.trim()) {
        return fallback.trim();
    }
    const normalizedParentId = normalizeParentId(parentDeviceId);
    if (!normalizedParentId) return "";
    try {
        const device = await dbManager?.getDevice?.(normalizedParentId);
        return String(device?.device_name || normalizedParentId).trim();
    } catch (error) {
        console.warn("Failed to resolve owner display name:", error?.message || error);
        return normalizedParentId;
    }
}

async function buildBusyPayload(deviceId, result, codeOverride = null) {
    const session = result?.session || null;
    const ownerParentId = session?.ownerParentId || "";
    const ownerDisplayName = await resolveOwnerDisplayName(
        ownerParentId,
        session?.ownerDisplayName || null
    );
    return {
        success: false,
        busy: true,
        code: codeOverride || result?.code || "STREAM_BUSY",
        deviceId,
        ownerParentId,
        ownerDisplayName,
        startedAt: session?.startTime || 0,
        durationMs: session?.durationMs || 0,
        timestamp: Date.now(),
    };
}

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

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        // Reading the queue is destructive: CommandManager marks the commands
        // delivered and removes them, so it is restricted to the device itself
        // or a linked parent instead of being open to any caller.
        const commands = commandManager.getCommands(authorizedDeviceId);

        res.json({
            success: true,
            deviceId: authorizedDeviceId,
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
 *
 * NOTE: Audio chunks are now transmitted via WebSocket for real-time streaming
 * This endpoint still uses HTTP to initiate the session and send commands
 */
router.post('/start', async (req, res) => {
    try {
        const { deviceId, parentId, timeoutMinutes, recording, sampleRate, requestTakeover } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const resolvedConnectedId =
            typeof wsManager?.resolveConnectedChildDeviceId === 'function'
                ? wsManager.resolveConnectedChildDeviceId(authorizedDeviceId)
                : '';
        const targetDeviceId = resolvedConnectedId || authorizedDeviceId;

        // Start streaming session with optional timeout (default 30 minutes)
        const parsedTimeout = Number(timeoutMinutes);
        const timeout =
            Number.isFinite(parsedTimeout) && parsedTimeout > 0
                ? parsedTimeout
                : 30;
        const parsedSampleRate = Number(sampleRate);
        const normalizedSampleRate =
            Number.isFinite(parsedSampleRate) && [24000, 32000, 48000].includes(parsedSampleRate)
                ? parsedSampleRate
                : null;
        const normalizedParentId = resolveOwnerId(req, parentId);
        const ownerDisplayName = await resolveOwnerDisplayName(normalizedParentId);
        const result = commandManager.requestStreamingStart(
            targetDeviceId,
            normalizedParentId,
            timeout,
            {
                sampleRate: normalizedSampleRate,
                ownerDisplayName
            }
        );

        if (result.ok) {
            // Try immediate WS delivery first. sendCommandToChild can remap target
            // to the only connected child when legacy contact IDs drift.
            const commandSent = wsManager.sendCommandToChild(targetDeviceId, {
                type: 'start_audio_stream',
                data: {
                    parentId: normalizedParentId,
                    sampleRate: normalizedSampleRate
                },
                timestamp: Date.now()
            });
            const childConnected = commandSent || wsManager.isChildConnected(targetDeviceId);
            console.log(`start_audio_stream command sent to ${targetDeviceId} via WebSocket: ${commandSent}`);
            if (!commandSent) {
                console.warn(`Child ${targetDeviceId} not connected - command queued and will be picked by polling`);
            }

            res.json({
                success: true,
                message: 'Audio streaming started',
                deviceId: targetDeviceId,
                requestedDeviceId: deviceId,
                sessionId: `stream_${Date.now()}`,
                parentId: normalizedParentId,
                ownerDisplayName,
                alreadyActive: result.reused === true,
                webSocketEnabled: true,
                childConnected: childConnected,
                timestamp: Date.now()
            });
        } else if (result.busy) {
            if (requestTakeover) {
                wsManager.notifyStreamTakeoverRequested(
                    targetDeviceId,
                    result.session?.ownerParentId,
                    normalizedParentId,
                    result.session
                );
            }
            const busyPayload = await buildBusyPayload(targetDeviceId, result, 'STREAM_BUSY');
            res.status(409).json({
                ...busyPayload,
                error: 'Streaming session is already owned by another parent',
                canRequestTakeover: true,
            });
        } else {
            res.status(500).json({
                error: 'Failed to start streaming',
                code: result.code || 'START_STREAM_ERROR'
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
        const { deviceId, parentId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const resolvedConnectedId =
            typeof wsManager?.resolveConnectedChildDeviceId === 'function'
                ? wsManager.resolveConnectedChildDeviceId(authorizedDeviceId)
                : '';
        const targetDeviceId = resolvedConnectedId || authorizedDeviceId;

        // Stop streaming session
        const normalizedParentId = resolveOwnerId(req, parentId);
        let result = commandManager.requestStreamingStop(targetDeviceId, normalizedParentId);
        if (!result.ok && result.code === 'NO_ACTIVE_SESSION' && targetDeviceId !== authorizedDeviceId) {
            result = commandManager.requestStreamingStop(authorizedDeviceId, normalizedParentId);
        }

        if (result.ok) {
            // Best-effort immediate WS stop. If offline, command remains queued.
            const stopSent = wsManager.sendCommandToChild(targetDeviceId, {
                type: 'stop_audio_stream',
                data: {},
                timestamp: Date.now()
            });
            if (stopSent) {
                console.log(`stop_audio_stream command sent to ${targetDeviceId} via WebSocket`);
            } else {
                console.warn(`stop_audio_stream for ${targetDeviceId} queued - child is offline`);
            }

            res.json({
                success: true,
                message: 'Audio streaming stopped',
                deviceId: targetDeviceId,
                requestedDeviceId: deviceId,
                timestamp: Date.now()
            });
        } else if (result.busy) {
            const busyPayload = await buildBusyPayload(targetDeviceId, result, result.code);
            res.status(409).json({
                ...busyPayload,
                error: 'Streaming session is controlled by another parent',
            });
        } else{
            res.status(404).json({
                error: 'No active streaming session',
                code: result.code || 'NO_ACTIVE_SESSION'
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
        const { deviceId, parentId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        // Start recording
        const resolvedConnectedId =
            typeof wsManager?.resolveConnectedChildDeviceId === 'function'
                ? wsManager.resolveConnectedChildDeviceId(authorizedDeviceId)
                : '';
        const targetDeviceId = resolvedConnectedId || authorizedDeviceId;
        const normalizedParentId = resolveOwnerId(req, parentId);
        const result = commandManager.requestRecordingStart(targetDeviceId, normalizedParentId);

        if (!result.ok && result.busy) {
            const busyPayload = await buildBusyPayload(targetDeviceId, result, result.code);
            return res.status(409).json({
                ...busyPayload,
                error: result.error || 'Streaming session is controlled by another parent',
            });
        }

        if (!result.ok) {
            return res.status(400).json({
                error: result.error,
                code: result.code || 'START_RECORDING_ERROR'
            });
        }

        res.json({
            success: true,
            message: 'Recording started',
            deviceId: targetDeviceId,
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
        const { deviceId, parentId } = req.body;

        if (!deviceId) {
            return res.status(400).json({
                error: 'Device ID required',
                code: 'MISSING_DEVICE_ID'
            });
        }

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        // Stop recording
        const resolvedConnectedId =
            typeof wsManager?.resolveConnectedChildDeviceId === 'function'
                ? wsManager.resolveConnectedChildDeviceId(authorizedDeviceId)
                : '';
        const targetDeviceId = resolvedConnectedId || authorizedDeviceId;
        const normalizedParentId = resolveOwnerId(req, parentId);
        const result = commandManager.requestRecordingStop(targetDeviceId, normalizedParentId);

        if (!result.ok && result.busy) {
            const busyPayload = await buildBusyPayload(targetDeviceId, result, result.code);
            return res.status(409).json({
                ...busyPayload,
                error: result.error || 'Recording session is controlled by another parent',
            });
        }

        if (!result.ok) {
            return res.status(400).json({
                error: result.error,
                code: result.code || 'STOP_RECORDING_ERROR'
            });
        }

        res.json({
            success: true,
            message: 'Recording stopped',
            deviceId: targetDeviceId,
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

        const callerDeviceId = normalizeParentId(req.deviceId);
        if (!callerDeviceId || callerDeviceId !== normalizeParentId(deviceId)) {
            return res.status(403).json({
                error: 'A device may only upload its own audio chunks',
                code: 'DEVICE_ACCESS_DENIED'
            });
        }

        if (!req.file) {
            return res.status(400).json({
                error: 'No audio chunk provided',
                code: 'MISSING_AUDIO_CHUNK'
            });
        }

        // Add chunk to buffer
        const chunkCount = commandManager.addAudioChunk(callerDeviceId, req.file.buffer);

        // If recording, save chunk to disk. The directory is derived from the
        // verified caller identity, never from the raw request value.
        if (recording === 'true') {
            const uploadsDir = path.join(__dirname, '..', 'uploads', 'audio', 'chunks', callerDeviceId);
            if (!fs.existsSync(uploadsDir)) {
                fs.mkdirSync(uploadsDir, { recursive: true });
            }

            const filename = `chunk_${sequence}_${Date.now()}.webm`;
            const filepath = path.join(uploadsDir, filename);
            fs.writeFileSync(filepath, req.file.buffer);
        }

        res.json({
            success: true,
            deviceId: callerDeviceId,
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

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        // Get latest chunks
        const chunks = commandManager.getAudioChunks(authorizedDeviceId, count);

        res.json({
            success: true,
            deviceId: authorizedDeviceId,
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
 * Get streaming session status including WebSocket connection state
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

        const authorizedDeviceId = await requireStreamingAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const session = commandManager.getSessionInfo(authorizedDeviceId);
        const wsStats = wsManager.getStats();
        const childConnected = wsManager.isChildConnected(authorizedDeviceId);
        const hasListener = wsManager.hasActiveListener(authorizedDeviceId);

        if (!session) {
            return res.json({
                success: true,
                streaming: false,
                active: false,
                recording: false,
                deviceId: authorizedDeviceId,
                webSocket: {
                    childConnected,
                    hasListener,
                    totalConnections: wsStats.totalConnections
                }
            });
        }

        res.json({
            success: true,
            streaming: true,
            active: true,
            recording: session.recording,
            deviceId: authorizedDeviceId,
            parentId: session.parentId,
            ownerParentId: session.ownerParentId,
            ownerDisplayName: await resolveOwnerDisplayName(
                session.ownerParentId,
                session.ownerDisplayName
            ),
            startTime: session.startTime,
            duration: session.durationMs || (Date.now() - session.startTime),
            durationMs: session.durationMs || (Date.now() - session.startTime),
            chunks: session.chunks,
            ownerStale: Boolean(session.ownerStale),
            webSocket: {
                enabled: true,
                childConnected,
                hasListener,
                totalConnections: wsStats.totalConnections,
                activeStreams: wsStats.activeStreams
            },
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

