const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

// Import our custom modules
const AuthManager = require('./auth/AuthManager');
const AuthMiddleware = require('./middleware/AuthMiddleware');
const DataValidator = require('./validators/DataValidator');
const DatabaseManager = require('./database/DatabaseManager');
const CommandManager = require('./managers/CommandManager');
const WebSocketManager = require('./managers/WebSocketManager');

// Import route modules
const chatRoutes = require('./routes/chat');
const locationRoutes = require('./routes/location');
const mediaRoutes = require('./routes/media');
const streamingRoutes = require('./routes/streaming');
const alertsRoutes = require('./routes/alerts');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"],
        credentials: true
    },
    pingTimeout: 60000,
    pingInterval: 25000,
    maxHttpBufferSize: 1e7, // 10MB for audio chunks
    transports: ['websocket', 'polling'] // WebSocket preferred, polling fallback
});

const PORT = process.env.PORT || 3000;

// Initialize managers
const authManager = new AuthManager();
const authMiddleware = new AuthMiddleware(authManager);
const validator = new DataValidator();
const dbManager = new DatabaseManager();
const commandManager = new CommandManager();
const wsManager = new WebSocketManager(io);

// Initialize database
let isDbInitialized = false;
async function initializeDatabase() {
    try {
        await dbManager.initialize();
        isDbInitialized = true;
        console.log('✅ Database initialized successfully');
    } catch (error) {
        console.error('❌ Database initialization failed:', error);
        process.exit(1);
    }
}

// Initialize database on startup
initializeDatabase();

// Initialize WebSocket handlers
wsManager.initialize();

// Middleware
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));
app.use(express.static('public'));

// Apply security middleware
app.use(authMiddleware.securityHeaders());
app.use(authMiddleware.cors());
app.use(authMiddleware.requestLogger());
app.use(authMiddleware.errorHandler());

// Configure multer for file uploads
const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        const uploadDir = 'uploads';
        if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
        }
        cb(null, uploadDir);
    },
    filename: (req, file, cb) => {
        const timestamp = Date.now();
        const ext = path.extname(file.originalname);
        cb(null, `${file.fieldname}_${timestamp}${ext}`);
    }
});

const upload = multer({ 
    storage: storage,
    limits: {
        fileSize: 10 * 1024 * 1024 // 10MB limit
    }
});

// In-memory token storage (in production, use a database)
const deviceTokens = new Map();
const refreshTokens = new Map();

// Generate secure tokens
function generateToken() {
    return crypto.randomBytes(32).toString('hex');
}

function generateRefreshToken() {
    return crypto.randomBytes(32).toString('hex');
}

// Authentication middleware
function authenticateToken(req, res, next) {
    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token) {
        return res.status(401).json({ error: 'Access token required' });
    }

    // Check if token exists and is valid
    const deviceId = Array.from(deviceTokens.keys()).find(id => 
        deviceTokens.get(id).authToken === token
    );

    if (!deviceId) {
        return res.status(403).json({ error: 'Invalid token' });
    }

    const tokenData = deviceTokens.get(deviceId);
    
    // Check if token is expired
    if (Date.now() > tokenData.expiresAt) {
        return res.status(401).json({ error: 'Token expired' });
    }

    req.deviceId = deviceId;
    req.tokenData = tokenData;
    next();
}

// Initialize streaming routes with managers
streamingRoutes.init(commandManager, dbManager, wsManager);
alertsRoutes.init(dbManager, wsManager);

// API Routes
app.use('/api/chat', chatRoutes);
app.use('/api/location', locationRoutes);
app.use('/api/media', mediaRoutes);
app.use('/api/streaming', streamingRoutes);
app.use('/api/alerts', authenticateToken, alertsRoutes);

// Routes

// Health check
app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'OK', 
        timestamp: new Date().toISOString(),
        version: '1.0.0'
    });
});

// Device registration
app.post('/api/auth/register', 
    authMiddleware.validateRequest({
        body: {
            deviceId: { required: true, type: 'string', minLength: 10, maxLength: 100 },
            deviceName: { required: true, type: 'string', maxLength: 100 },
            deviceType: { required: true, type: 'string', pattern: /^(android|ios)$/ },
            appVersion: { required: true, type: 'string', pattern: /^\d+\.\d+\.\d+$/ }
        }
    }),
    authMiddleware.rateLimit(60000, 10), // 10 requests per minute for registration
    (req, res) => {
        try {
            const { deviceId, deviceName, deviceType, appVersion } = req.body;

            // Validate device ID format
            if (!validator.validateDeviceIdFormat(deviceId)) {
                return res.status(400).json({ 
                    error: 'Invalid device ID format',
                    code: 'INVALID_DEVICE_ID'
                });
            }

            // Register device
            const result = authManager.registerDevice({
                deviceId,
                deviceName: validator.sanitizeString(deviceName),
                deviceType,
                appVersion
            });

            if (result.success) {
                res.json({
                    success: true,
                    authToken: result.authToken,
                    refreshToken: result.refreshToken,
                    expiresIn: result.expiresIn
                });
            } else {
                res.status(400).json({
                    error: result.error,
                    code: 'REGISTRATION_FAILED'
                });
            }

        } catch (error) {
            console.error('Registration error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'REGISTRATION_ERROR'
            });
        }
    }
);

// Token refresh
app.post('/api/auth/refresh',
    authMiddleware.validateRequest({
        body: {
            refreshToken: { required: true, type: 'string', minLength: 64, maxLength: 64 },
            deviceId: { required: true, type: 'string', minLength: 10, maxLength: 100 }
        }
    }),
    authMiddleware.rateLimit(60000, 20), // 20 requests per minute for refresh
    (req, res) => {
        try {
            const { refreshToken, deviceId } = req.body;

            // Validate device ID format
            if (!validator.validateDeviceIdFormat(deviceId)) {
                return res.status(400).json({ 
                    error: 'Invalid device ID format',
                    code: 'INVALID_DEVICE_ID'
                });
            }

            // Refresh token
            const result = authManager.refreshToken(refreshToken, deviceId);

            if (result.success) {
                res.json({
                    success: true,
                    authToken: result.authToken,
                    refreshToken: result.refreshToken,
                    expiresIn: result.expiresIn
                });
            } else {
                res.status(401).json({
                    error: result.error,
                    code: 'REFRESH_FAILED'
                });
            }

        } catch (error) {
            console.error('Token refresh error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'REFRESH_ERROR'
            });
        }
    }
);

// Token validation
app.get('/api/auth/validate', authenticateToken, (req, res) => {
    res.json({ 
        valid: true, 
        deviceId: req.deviceId,
        expiresAt: req.tokenData.expiresAt
    });
});

// Location upload (protected)
app.post('/api/loc', 
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 120), // 120 requests per minute for location
    authMiddleware.validateRequest({
        body: {
            latitude: { required: true, type: 'number', min: -90, max: 90 },
            longitude: { required: true, type: 'number', min: -180, max: 180 },
            accuracy: { required: true, type: 'number', min: 0, max: 1000 },
            timestamp: { required: true, type: 'number', min: 0 }
        }
    }),
    async (req, res) => {
        try {
            const { latitude, longitude, accuracy, timestamp } = req.body;
            const deviceId = req.deviceId;

            // Validate location data
            const validation = validator.validateLocationData({
                latitude,
                longitude,
                accuracy,
                timestamp,
                deviceId
            });

            if (!validation.isValid) {
                return res.status(400).json({
                    error: 'Invalid location data',
                    code: 'INVALID_LOCATION_DATA',
                    details: validation.errors
                });
            }

            // Check for suspicious location (too fast movement)
            const deviceInfo = authManager.getDeviceInfo(deviceId);
            if (deviceInfo && deviceInfo.lastLocation) {
                const suspiciousCheck = validator.checkSuspiciousLocation(
                    { latitude, longitude, timestamp },
                    deviceInfo.lastLocation
                );

                if (suspiciousCheck.suspicious) {
                    authManager.updateDeviceActivity(deviceId, 'suspicious', {
                        type: 'suspicious_location',
                        description: suspiciousCheck.reason
                    });

                    console.warn(`Suspicious location from ${deviceId}: ${suspiciousCheck.reason}`);
                }
            }

            // Save location to database
            await dbManager.saveLocation(deviceId, {
                latitude,
                longitude,
                accuracy,
                timestamp
            });

            // Log activity
            await dbManager.logActivity(deviceId, {
                activity_type: 'location',
                activity_data: {
                    latitude,
                    longitude,
                    accuracy
                },
                timestamp
            });

            console.log(`[${new Date().toISOString()}] Location from ${deviceId}: Lat ${latitude}, Lng ${longitude}, Acc ${accuracy} at ${new Date(timestamp)}`);
            
            res.json({ 
                success: true,
                message: 'Location received and saved',
                deviceId: deviceId,
                timestamp: Date.now()
            });

        } catch (error) {
            console.error('Location upload error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'LOCATION_UPLOAD_ERROR'
            });
        }
    }
);

// Audio upload (protected)
app.post('/api/audio', 
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 30), // 30 requests per minute for audio
    upload.single('audio'),
    async (req, res) => {
        try {
            if (!req.file) {
                return res.status(400).json({ 
                    error: 'No audio file provided',
                    code: 'NO_AUDIO_FILE'
                });
            }

            const deviceId = req.deviceId;
            const { timestamp } = req.body;

            // Validate file upload
            const fileValidation = validator.validateFileUpload(req.file, ['audio']);
            if (!fileValidation.isValid) {
                return res.status(400).json({
                    error: 'Invalid audio file',
                    code: 'INVALID_AUDIO_FILE',
                    details: fileValidation.errors
                });
            }

            const audioTimestamp = timestamp ? parseInt(timestamp) : Date.now();

            // Save audio file metadata to database
            await dbManager.saveAudioFile(deviceId, {
                filename: req.file.filename,
                filePath: req.file.path,
                fileSize: req.file.size,
                mimeType: req.file.mimetype,
                duration: null, // Could be extracted with ffprobe
                timestamp: audioTimestamp
            });

            // Log activity
            await dbManager.logActivity(deviceId, {
                activity_type: 'audio',
                activity_data: {
                    filename: req.file.filename,
                    size: req.file.size
                },
                timestamp: audioTimestamp
            });

            console.log(`[${new Date().toISOString()}] Audio from ${deviceId}: ${req.file.filename} (${req.file.size} bytes)`);

            res.json({ 
                success: true,
                message: 'Audio received and saved',
                filename: req.file.filename,
                deviceId: deviceId,
                timestamp: Date.now()
            });

        } catch (error) {
            console.error('Audio upload error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'AUDIO_UPLOAD_ERROR'
            });
        }
    }
);

// Photo upload (protected)
app.post('/api/photo', 
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 20), // 20 requests per minute for photos
    upload.single('photo'),
    async (req, res) => {
        try {
            if (!req.file) {
                return res.status(400).json({ 
                    error: 'No photo file provided',
                    code: 'NO_PHOTO_FILE'
                });
            }

            const deviceId = req.deviceId;
            const { timestamp } = req.body;

            // Validate file upload
            const fileValidation = validator.validateFileUpload(req.file, ['image']);
            if (!fileValidation.isValid) {
                return res.status(400).json({
                    error: 'Invalid photo file',
                    code: 'INVALID_PHOTO_FILE',
                    details: fileValidation.errors
                });
            }

            const photoTimestamp = timestamp ? parseInt(timestamp) : Date.now();

            // Save photo file metadata to database
            await dbManager.savePhotoFile(deviceId, {
                filename: req.file.filename,
                filePath: req.file.path,
                fileSize: req.file.size,
                mimeType: req.file.mimetype,
                width: null, // Could be extracted with image processing
                height: null,
                timestamp: photoTimestamp
            });

            // Log activity
            await dbManager.logActivity(deviceId, {
                activity_type: 'photo',
                activity_data: {
                    filename: req.file.filename,
                    size: req.file.size
                },
                timestamp: photoTimestamp
            });

            console.log(`[${new Date().toISOString()}] Photo from ${deviceId}: ${req.file.filename} (${req.file.size} bytes)`);

            res.json({ 
                success: true,
                message: 'Photo received and saved',
                filename: req.file.filename,
                deviceId: deviceId,
                timestamp: Date.now()
            });

        } catch (error) {
            console.error('Photo upload error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'PHOTO_UPLOAD_ERROR'
            });
        }
    }
);

// Get device info (protected)
app.get('/api/device/info',
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 60), // 60 requests per minute
    (req, res) => {
        try {
            const deviceId = req.deviceId;
            const deviceInfo = authManager.getDeviceInfo(deviceId);

            if (!deviceInfo) {
                return res.status(404).json({
                    error: 'Device not found',
                    code: 'DEVICE_NOT_FOUND'
                });
            }

            res.json({
                success: true,
                device: deviceInfo
            });

        } catch (error) {
            console.error('Get device info error:', error);
            res.status(500).json({
                error: 'Internal server error',
                code: 'DEVICE_INFO_ERROR'
            });
        }
    }
);

// Get latest location of a device (protected)
app.get('/api/location/latest/:deviceId?',
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 120), // 120 requests per minute
    async (req, res) => {
        try {
            // If deviceId is provided in params, use it; otherwise use authenticated device's own location
            const targetDeviceId = req.params.deviceId || req.deviceId;

            // Validate device ID
            if (!validator.validateDeviceIdFormat(targetDeviceId)) {
                return res.status(400).json({
                    error: 'Invalid device ID format',
                    code: 'INVALID_DEVICE_ID'
                });
            }

            // Get latest location from database
            const location = await dbManager.getLatestLocation(targetDeviceId);

            if (!location) {
                return res.status(404).json({
                    error: 'No location data found for this device',
                    code: 'LOCATION_NOT_FOUND',
                    deviceId: targetDeviceId
                });
            }

            res.json({
                success: true,
                deviceId: targetDeviceId,
                location: {
                    latitude: location.latitude,
                    longitude: location.longitude,
                    accuracy: location.accuracy,
                    timestamp: location.timestamp,
                    recordedAt: new Date(location.timestamp).toISOString()
                }
            });

        } catch (error) {
            console.error('Get latest location error:', error);
            res.status(500).json({
                error: 'Internal server error',
                code: 'LOCATION_FETCH_ERROR'
            });
        }
    }
);

// Get location history of a device (protected)
app.get('/api/location/history/:deviceId?',
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 60), // 60 requests per minute
    async (req, res) => {
        try {
            const targetDeviceId = req.params.deviceId || req.deviceId;
            const limit = parseInt(req.query.limit) || 100;
            const offset = parseInt(req.query.offset) || 0;

            // Validate parameters
            if (!validator.validateDeviceIdFormat(targetDeviceId)) {
                return res.status(400).json({
                    error: 'Invalid device ID format',
                    code: 'INVALID_DEVICE_ID'
                });
            }

            if (limit < 1 || limit > 1000) {
                return res.status(400).json({
                    error: 'Limit must be between 1 and 1000',
                    code: 'INVALID_LIMIT'
                });
            }

            // Get location history from database
            const locations = await dbManager.getLocationHistory(targetDeviceId, limit, offset);

            res.json({
                success: true,
                deviceId: targetDeviceId,
                count: locations.length,
                limit: limit,
                offset: offset,
                locations: locations.map(loc => ({
                    latitude: loc.latitude,
                    longitude: loc.longitude,
                    accuracy: loc.accuracy,
                    timestamp: loc.timestamp,
                    recordedAt: new Date(loc.timestamp).toISOString()
                }))
            });

        } catch (error) {
            console.error('Get location history error:', error);
            res.status(500).json({
                error: 'Internal server error',
                code: 'LOCATION_HISTORY_ERROR'
            });
        }
    }
);

// Get server statistics (protected)
app.get('/api/admin/stats', 
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 10), // 10 requests per minute
    (req, res) => {
        try {
            const deviceId = req.deviceId;
            
            // Check if device has admin permissions
            if (!authManager.checkDevicePermissions(deviceId, 'admin')) {
                return res.status(403).json({
                    error: 'Admin access required',
                    code: 'ADMIN_ACCESS_REQUIRED'
                });
            }

            const authStats = authManager.getAuthStats();
            const validatorStats = {
                rateLimitEntries: validator.rateLimitMap.size,
                maxRequestsPerMinute: validator.maxRequestsPerMinute,
                maxRequestsPerHour: validator.maxRequestsPerHour
            };

            res.json({
                success: true,
                stats: {
                    auth: authStats,
                    validator: validatorStats,
                    server: {
                        uptime: process.uptime(),
                        memory: process.memoryUsage(),
                        version: '1.0.0'
                    }
                }
            });

        } catch (error) {
            console.error('Get stats error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'STATS_ERROR'
            });
        }
    }
);

// Revoke device access (protected)
app.post('/api/admin/revoke', 
    authMiddleware.authenticate(),
    authMiddleware.rateLimit(60000, 5), // 5 requests per minute
    authMiddleware.validateRequest({
        body: {
            targetDeviceId: { required: true, type: 'string', minLength: 10, maxLength: 100 }
        }
    }),
    (req, res) => {
        try {
            const adminDeviceId = req.deviceId;
            const { targetDeviceId } = req.body;
            
            // Check if device has admin permissions
            if (!authManager.checkDevicePermissions(adminDeviceId, 'admin')) {
                return res.status(403).json({
                    error: 'Admin access required',
                    code: 'ADMIN_ACCESS_REQUIRED'
                });
            }

            const success = authManager.revokeDeviceAccess(targetDeviceId);
            
            if (success) {
                res.json({
                    success: true,
                    message: 'Device access revoked',
                    deviceId: targetDeviceId
                });
            } else {
                res.status(404).json({
                    error: 'Device not found',
                    code: 'DEVICE_NOT_FOUND'
                });
            }

        } catch (error) {
            console.error('Revoke device error:', error);
            res.status(500).json({ 
                error: 'Internal server error',
                code: 'REVOKE_ERROR'
            });
        }
    }
);

// 404 handler
app.use(authMiddleware.notFoundHandler());

// Cleanup interval for streaming sessions
setInterval(() => {
    commandManager.cleanup();
}, 60000); // Every minute

// Start server (use server.listen instead of app.listen for Socket.IO)
server.listen(PORT, '0.0.0.0', () => {
    console.log(`ChildWatch Server running on port ${PORT}`);
    console.log(`Health check: http://localhost:${PORT}/api/health`);
    console.log(`Register device: POST http://localhost:${PORT}/api/auth/register`);
    console.log(`Audio streaming: POST http://localhost:${PORT}/api/streaming/start`);
    console.log(`WebSocket: ws://localhost:${PORT}`);
    console.log(`Server version: 1.2.0 (WebSocket enabled)`);
    console.log(`Environment: ${process.env.NODE_ENV || 'development'}`);
});

module.exports = app;