const express = require('express');
const path = require('path');
const fs = require('fs');
const DatabaseManager = require('../database/DatabaseManager');
const DeviceAccessService = require('../services/DeviceAccessService');

const router = express.Router();

/**
 * These handlers used to serve any file to any caller that could guess a
 * sequential file id. Each one now resolves the owning device first and
 * verifies that the authenticated caller is that device or a linked parent.
 *
 * An unknown id and a denied id deliberately answer with the same 404: an
 * enumeration attempt must not learn whether a file exists in another family.
 */
let sharedDatabase;
let deviceAccess;

router.init = (databaseManager) => {
    sharedDatabase = databaseManager;
    deviceAccess = new DeviceAccessService(databaseManager);
};

async function withDatabase(work) {
    // Prefer the shared manager when the router was initialized; fall back to a
    // short-lived connection so the routes still work when mounted standalone.
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

function resolveStoredMediaPath(filePath) {
    if (!filePath) {
        return null;
    }
    return path.join(__dirname, '..', filePath);
}

function denyNotFound(res, code, message) {
    return res.status(404).json({ error: message, code });
}

/**
 * Verifies access to one media file. Returns the file record, or null when a
 * response has already been sent.
 */
async function requireFileAccess(req, res, table, fileId, notFoundCode, notFoundMessage) {
    if (!deviceAccess) {
        res.status(503).json({
            error: 'Media access is not configured',
            code: 'MEDIA_NOT_INITIALIZED'
        });
        return null;
    }

    const numericId = parseInt(fileId, 10);
    if (!Number.isFinite(numericId)) {
        denyNotFound(res, notFoundCode, notFoundMessage);
        return null;
    }

    const record = await withDatabase((db) =>
        db.get(`SELECT * FROM ${table} WHERE id = ?`, [numericId])
    );
    if (!record) {
        denyNotFound(res, notFoundCode, notFoundMessage);
        return null;
    }

    const authorizedDeviceId = await deviceAccess.requireDeviceAccess(req, res, record.device_id);
    if (authorizedDeviceId === null) {
        return null;
    }

    return record;
}

/**
 * Media API Routes
 * Handles audio and photo files metadata and serving
 */

// Get audio files for a device
router.get('/audio/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { limit = 50, offset = 0 } = req.query;

        const authorizedDeviceId = await deviceAccess.requireDeviceAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const audioFiles = await withDatabase((db) =>
            db.getAudioFiles(authorizedDeviceId, parseInt(limit, 10), parseInt(offset, 10))
        );

        res.json({
            success: true,
            audioFiles: audioFiles.map(file => ({
                id: file.id,
                filename: file.filename,
                fileSize: file.file_size,
                mimeType: file.mime_type,
                duration: file.duration,
                timestamp: file.timestamp,
                createdAt: file.created_at,
                downloadUrl: `/api/media/download/audio/${file.id}`
            })),
            count: audioFiles.length
        });

    } catch (error) {
        console.error('Get audio files error:', error);
        res.status(500).json({
            error: 'Failed to get audio files',
            code: 'AUDIO_FILES_ERROR'
        });
    }
});

// Get photo files for a device
router.get('/photos/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { limit = 50, offset = 0 } = req.query;

        const authorizedDeviceId = await deviceAccess.requireDeviceAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const photoFiles = await withDatabase((db) =>
            db.getPhotoFiles(authorizedDeviceId, parseInt(limit, 10), parseInt(offset, 10))
        );

        res.json({
            success: true,
            photoFiles: photoFiles.map(file => ({
                id: file.id,
                filename: file.filename,
                fileSize: file.file_size,
                mimeType: file.mime_type,
                width: file.width,
                height: file.height,
                timestamp: file.timestamp,
                createdAt: file.created_at,
                downloadUrl: `/api/media/download/photo/${file.id}`,
                thumbnailUrl: `/api/media/thumbnail/${file.id}`
            })),
            count: photoFiles.length
        });

    } catch (error) {
        console.error('Get photo files error:', error);
        res.status(500).json({
            error: 'Failed to get photo files',
            code: 'PHOTO_FILES_ERROR'
        });
    }
});

// Download audio file
router.get('/download/audio/:fileId', async (req, res) => {
    try {
        const audioFile = await requireFileAccess(
            req,
            res,
            'audio_files',
            req.params.fileId,
            'AUDIO_FILE_NOT_FOUND',
            'Audio file not found'
        );
        if (!audioFile) {
            return undefined;
        }

        const filePath = path.join(__dirname, '..', audioFile.file_path);

        if (!fs.existsSync(filePath)) {
            return denyNotFound(res, 'AUDIO_FILE_MISSING', 'Audio file not found on disk');
        }

        res.setHeader('Content-Type', audioFile.mime_type);
        res.setHeader('Content-Disposition', `attachment; filename="${audioFile.filename}"`);
        res.setHeader('Content-Length', audioFile.file_size);

        const fileStream = fs.createReadStream(filePath);
        fileStream.pipe(res);

    } catch (error) {
        console.error('Download audio file error:', error);
        res.status(500).json({
            error: 'Failed to download audio file',
            code: 'AUDIO_DOWNLOAD_ERROR'
        });
    }
});

// Download photo file
router.get('/download/photo/:fileId', async (req, res) => {
    try {
        const photoFile = await requireFileAccess(
            req,
            res,
            'photo_files',
            req.params.fileId,
            'PHOTO_FILE_NOT_FOUND',
            'Photo file not found'
        );
        if (!photoFile) {
            return undefined;
        }

        const filePath = resolveStoredMediaPath(photoFile.file_path);

        if (!fs.existsSync(filePath)) {
            return denyNotFound(res, 'PHOTO_FILE_MISSING', 'Photo file not found on disk');
        }

        res.setHeader('Content-Type', photoFile.mime_type);
        res.setHeader('Content-Disposition', `attachment; filename="${photoFile.filename}"`);
        res.setHeader('Content-Length', photoFile.file_size);

        const fileStream = fs.createReadStream(filePath);
        fileStream.pipe(res);

    } catch (error) {
        console.error('Download photo file error:', error);
        res.status(500).json({
            error: 'Failed to download photo file',
            code: 'PHOTO_DOWNLOAD_ERROR'
        });
    }
});

router.get('/thumbnail/:fileId', async (req, res) => {
    try {
        const photoFile = await requireFileAccess(
            req,
            res,
            'photo_files',
            req.params.fileId,
            'PHOTO_FILE_NOT_FOUND',
            'Photo file not found'
        );
        if (!photoFile) {
            return undefined;
        }

        const filePath = resolveStoredMediaPath(photoFile.file_path);
        if (!filePath || !fs.existsSync(filePath)) {
            return denyNotFound(res, 'PHOTO_FILE_MISSING', 'Photo file not found on disk');
        }

        res.setHeader('Content-Type', photoFile.mime_type || 'image/jpeg');
        res.setHeader('Content-Disposition', `inline; filename="${photoFile.filename}"`);
        res.setHeader('Cache-Control', 'private, max-age=60');

        const fileStream = fs.createReadStream(filePath);
        fileStream.pipe(res);
    } catch (error) {
        console.error('Get photo thumbnail error:', error);
        res.status(500).json({
            error: 'Failed to get photo thumbnail',
            code: 'PHOTO_THUMBNAIL_ERROR'
        });
    }
});

// Get media statistics
router.get('/stats/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { days = 7 } = req.query;

        const authorizedDeviceId = await deviceAccess.requireDeviceAccess(req, res, deviceId);
        if (authorizedDeviceId === null) {
            return undefined;
        }

        const fromTimestamp = Date.now() - (parseInt(days, 10) * 24 * 60 * 60 * 1000);

        const { audioStats, photoStats } = await withDatabase(async (db) => {
            const audio = await db.get(`
                SELECT
                    COUNT(*) as count,
                    SUM(file_size) as total_size,
                    AVG(duration) as avg_duration
                FROM audio_files
                WHERE device_id = ? AND timestamp >= ?
            `, [authorizedDeviceId, fromTimestamp]);

            const photo = await db.get(`
                SELECT
                    COUNT(*) as count,
                    SUM(file_size) as total_size,
                    AVG(width * height) as avg_pixels
                FROM photo_files
                WHERE device_id = ? AND timestamp >= ?
            `, [authorizedDeviceId, fromTimestamp]);

            return { audioStats: audio, photoStats: photo };
        });

        res.json({
            success: true,
            stats: {
                audio: {
                    count: audioStats.count || 0,
                    totalSize: audioStats.total_size || 0,
                    averageDuration: Math.round(audioStats.avg_duration || 0)
                },
                photo: {
                    count: photoStats.count || 0,
                    totalSize: photoStats.total_size || 0,
                    averagePixels: Math.round(photoStats.avg_pixels || 0)
                },
                total: {
                    files: (audioStats.count || 0) + (photoStats.count || 0),
                    size: (audioStats.total_size || 0) + (photoStats.total_size || 0)
                }
            }
        });

    } catch (error) {
        console.error('Get media stats error:', error);
        res.status(500).json({
            error: 'Failed to get media statistics',
            code: 'MEDIA_STATS_ERROR'
        });
    }
});

module.exports = router;
