const express = require('express');
const DatabaseManager = require('../database/DatabaseManager');
const path = require('path');
const fs = require('fs');

const router = express.Router();

/**
 * Media API Routes
 * Handles audio and photo files metadata and serving
 */

// Get audio files for a device
router.get('/audio/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { limit = 50, offset = 0 } = req.query;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const audioFiles = await dbManager.getAudioFiles(deviceId, parseInt(limit), parseInt(offset));
        
        await dbManager.close();
        
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
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const photoFiles = await dbManager.getPhotoFiles(deviceId, parseInt(limit), parseInt(offset));
        
        await dbManager.close();
        
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
        const { fileId } = req.params;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const audioFile = await dbManager.get(
            'SELECT * FROM audio_files WHERE id = ?',
            [parseInt(fileId)]
        );
        
        await dbManager.close();
        
        if (!audioFile) {
            return res.status(404).json({
                error: 'Audio file not found',
                code: 'AUDIO_FILE_NOT_FOUND'
            });
        }
        
        const filePath = path.join(__dirname, '..', audioFile.file_path);
        
        if (!fs.existsSync(filePath)) {
            return res.status(404).json({
                error: 'Audio file not found on disk',
                code: 'AUDIO_FILE_MISSING'
            });
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
        const { fileId } = req.params;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const photoFile = await dbManager.get(
            'SELECT * FROM photo_files WHERE id = ?',
            [parseInt(fileId)]
        );
        
        await dbManager.close();
        
        if (!photoFile) {
            return res.status(404).json({
                error: 'Photo file not found',
                code: 'PHOTO_FILE_NOT_FOUND'
            });
        }
        
        const filePath = path.join(__dirname, '..', photoFile.file_path);
        
        if (!fs.existsSync(filePath)) {
            return res.status(404).json({
                error: 'Photo file not found on disk',
                code: 'PHOTO_FILE_MISSING'
            });
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

// Get media statistics
router.get('/stats/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        const { days = 7 } = req.query;
        
        const dbManager = new DatabaseManager();
        await dbManager.initialize();
        
        const fromTimestamp = Date.now() - (parseInt(days) * 24 * 60 * 60 * 1000);
        
        // Audio statistics
        const audioStats = await dbManager.get(`
            SELECT 
                COUNT(*) as count,
                SUM(file_size) as total_size,
                AVG(duration) as avg_duration
            FROM audio_files 
            WHERE device_id = ? AND timestamp >= ?
        `, [deviceId, fromTimestamp]);
        
        // Photo statistics
        const photoStats = await dbManager.get(`
            SELECT 
                COUNT(*) as count,
                SUM(file_size) as total_size,
                AVG(width * height) as avg_pixels
            FROM photo_files 
            WHERE device_id = ? AND timestamp >= ?
        `, [deviceId, fromTimestamp]);
        
        await dbManager.close();
        
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
