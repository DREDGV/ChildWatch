const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const DatabaseManager = require('./database/DatabaseManager');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));
app.use(express.static('public'));

// CORS middleware
app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept, Authorization');
    if (req.method === 'OPTIONS') {
        res.sendStatus(200);
    } else {
        next();
    }
});

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

// Initialize database
const dbManager = new DatabaseManager();
let isDbInitialized = false;

// Simple authentication (for testing)
const deviceTokens = new Map();
deviceTokens.set('test-device-001', {
    authToken: 'test-auth-token-123',
    refreshToken: 'test-refresh-token-456',
    expiresAt: Date.now() + (24 * 60 * 60 * 1000),
    deviceId: 'test-device-001'
});

// Authentication middleware
function authenticateToken(req, res, next) {
    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token) {
        return res.status(401).json({ error: 'Access token required' });
    }

    const deviceId = Array.from(deviceTokens.keys()).find(id => 
        deviceTokens.get(id).authToken === token
    );

    if (!deviceId) {
        return res.status(403).json({ error: 'Invalid token' });
    }

    req.deviceId = deviceId;
    next();
}

// Routes

// Health check
app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'OK', 
        timestamp: new Date().toISOString(),
        version: '1.0.0',
        database: isDbInitialized ? 'connected' : 'disconnected'
    });
});

// Device registration (simplified)
app.post('/api/auth/register', (req, res) => {
    try {
        const { deviceId, deviceName, deviceType, appVersion } = req.body;
        
        console.log(`📱 Device registration: ${deviceId} (${deviceName})`);
        
        res.json({
            success: true,
            authToken: 'test-auth-token-123',
            refreshToken: 'test-refresh-token-456',
            expiresIn: 86400 // 24 hours
        });
    } catch (error) {
        console.error('Registration error:', error);
        res.status(500).json({ error: 'Internal server error' });
    }
});

// Location upload
app.post('/api/loc', authenticateToken, async (req, res) => {
    try {
        const { latitude, longitude, accuracy, timestamp } = req.body;
        const deviceId = req.deviceId;

        console.log(`📍 Location from ${deviceId}: Lat ${latitude}, Lng ${longitude}`);

        // Save to database if available
        if (isDbInitialized) {
            await dbManager.saveLocation(deviceId, {
                latitude,
                longitude,
                accuracy,
                timestamp
            });
        }

        res.json({ 
            success: true,
            message: 'Location received',
            deviceId: deviceId,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Location upload error:', error);
        res.status(500).json({ error: 'Internal server error' });
    }
});

// Audio upload
app.post('/api/audio', authenticateToken, upload.single('audio'), async (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ error: 'No audio file provided' });
        }

        const deviceId = req.deviceId;
        console.log(`🎵 Audio from ${deviceId}: ${req.file.filename} (${req.file.size} bytes)`);

        // Save to database if available
        if (isDbInitialized) {
            await dbManager.saveAudioFile(deviceId, {
                filename: req.file.filename,
                filePath: req.file.path,
                fileSize: req.file.size,
                mimeType: req.file.mimetype,
                duration: null,
                timestamp: Date.now()
            });
        }

        res.json({ 
            success: true,
            message: 'Audio received',
            filename: req.file.filename,
            deviceId: deviceId,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Audio upload error:', error);
        res.status(500).json({ error: 'Internal server error' });
    }
});

// Photo upload
app.post('/api/photo', authenticateToken, upload.single('photo'), async (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ error: 'No photo file provided' });
        }

        const deviceId = req.deviceId;
        console.log(`📸 Photo from ${deviceId}: ${req.file.filename} (${req.file.size} bytes)`);

        // Save to database if available
        if (isDbInitialized) {
            await dbManager.savePhotoFile(deviceId, {
                filename: req.file.filename,
                filePath: req.file.path,
                fileSize: req.file.size,
                mimeType: req.file.mimetype,
                width: null,
                height: null,
                timestamp: Date.now()
            });
        }

        res.json({ 
            success: true,
            message: 'Photo received',
            filename: req.file.filename,
            deviceId: deviceId,
            timestamp: Date.now()
        });

    } catch (error) {
        console.error('Photo upload error:', error);
        res.status(500).json({ error: 'Internal server error' });
    }
});

// Chat messages
app.get('/api/chat/messages/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        
        if (!isDbInitialized) {
            return res.json({ success: true, messages: [] });
        }

        const messages = await dbManager.getChatMessages(deviceId, 100, 0);
        
        res.json({
            success: true,
            messages: messages.map(msg => ({
                id: msg.id,
                sender: msg.sender,
                message: msg.message,
                timestamp: msg.timestamp,
                isRead: msg.is_read === 1
            }))
        });
        
    } catch (error) {
        console.error('Get chat messages error:', error);
        res.status(500).json({ error: 'Failed to get chat messages' });
    }
});

app.post('/api/chat/messages', async (req, res) => {
    try {
        const { deviceId, sender, message, timestamp } = req.body;
        
        console.log(`💬 Chat message from ${sender}: ${message}`);

        if (isDbInitialized) {
            await dbManager.saveChatMessage(deviceId, {
                sender,
                message: message.trim(),
                timestamp: timestamp || Date.now(),
                isRead: false
            });
        }

        res.json({
            success: true,
            messageId: Date.now(),
            message: 'Message sent successfully'
        });
        
    } catch (error) {
        console.error('Send chat message error:', error);
        res.status(500).json({ error: 'Failed to send message' });
    }
});

// Location history
app.get('/api/location/history/:deviceId', async (req, res) => {
    try {
        const { deviceId } = req.params;
        
        if (!isDbInitialized) {
            return res.json({ success: true, locations: [] });
        }

        const locations = await dbManager.getLocationHistory(deviceId, 100, 0);
        
        res.json({
            success: true,
            locations: locations.map(loc => ({
                id: loc.id,
                latitude: loc.latitude,
                longitude: loc.longitude,
                accuracy: loc.accuracy,
                timestamp: loc.timestamp
            }))
        });
        
    } catch (error) {
        console.error('Get location history error:', error);
        res.status(500).json({ error: 'Failed to get location history' });
    }
});

// Initialize database and start server
async function startServer() {
    try {
        console.log('🔄 Initializing database...');
        await dbManager.initialize();
        isDbInitialized = true;
        console.log('✅ Database initialized successfully');
    } catch (error) {
        console.error('❌ Database initialization failed:', error);
        console.log('⚠️  Server will run without database');
        isDbInitialized = false;
    }

    app.listen(PORT, () => {
        console.log(`🚀 ChildWatch Server running on http://localhost:${PORT}`);
        console.log(`📊 Health check: http://localhost:${PORT}/api/health`);
        console.log(`🔑 Test device ID: test-device-001`);
        console.log(`🔐 Test auth token: test-auth-token-123`);
        console.log(`✅ Server ready!`);
    });
}

startServer().catch(error => {
    console.error('❌ Server startup failed:', error);
    process.exit(1);
});

module.exports = app;

