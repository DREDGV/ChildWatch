const express = require('express');
const multer = require('multer');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(morgan('combined'));
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Create uploads directory if it doesn't exist
const uploadsDir = path.join(__dirname, 'uploads');
if (!fs.existsSync(uploadsDir)) {
    fs.mkdirSync(uploadsDir);
}

// Configure multer for file uploads
const storage = multer.diskStorage({
    destination: function (req, file, cb) {
        cb(null, uploadsDir);
    },
    filename: function (req, file, cb) {
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
        const deviceId = req.body.deviceId || 'unknown';
        const ext = path.extname(file.originalname);
        cb(null, `${deviceId}_${timestamp}${ext}`);
    }
});

const upload = multer({ 
    storage: storage,
    limits: {
        fileSize: 50 * 1024 * 1024 // 50MB limit
    }
});

// Logging function
function logData(type, data) {
    const timestamp = new Date().toISOString();
    const logEntry = {
        timestamp,
        type,
        data
    };
    
    console.log(`[${timestamp}] ${type.toUpperCase()}:`, JSON.stringify(data, null, 2));
    
    // Optionally save to file
    const logFile = path.join(__dirname, 'server.log');
    fs.appendFileSync(logFile, JSON.stringify(logEntry) + '\n');
}

// Health check endpoint
app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'OK', 
        timestamp: new Date().toISOString(),
        server: 'ChildWatch Test Server'
    });
});

// Location endpoint - receives GPS coordinates
app.post('/api/loc', (req, res) => {
    try {
        const locationData = {
            latitude: req.body.latitude,
            longitude: req.body.longitude,
            accuracy: req.body.accuracy,
            timestamp: req.body.timestamp,
            deviceId: req.body.deviceId,
            receivedAt: Date.now()
        };
        
        // Validate required fields
        if (!locationData.latitude || !locationData.longitude) {
            return res.status(400).json({ 
                error: 'Missing required fields: latitude, longitude' 
            });
        }
        
        logData('location', locationData);
        
        res.json({ 
            success: true, 
            message: 'Location data received',
            receivedAt: locationData.receivedAt
        });
        
    } catch (error) {
        console.error('Error processing location data:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            message: error.message 
        });
    }
});

// Audio endpoint - receives audio files
app.post('/api/audio', upload.single('audio'), (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ 
                error: 'No audio file uploaded' 
            });
        }
        
        const audioData = {
            filename: req.file.filename,
            originalName: req.file.originalname,
            size: req.file.size,
            mimetype: req.file.mimetype,
            deviceId: req.body.deviceId,
            timestamp: req.body.timestamp,
            duration: req.body.duration,
            receivedAt: Date.now()
        };
        
        logData('audio', audioData);
        
        res.json({ 
            success: true, 
            message: 'Audio file received',
            filename: req.file.filename,
            size: req.file.size,
            receivedAt: audioData.receivedAt
        });
        
    } catch (error) {
        console.error('Error processing audio file:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            message: error.message 
        });
    }
});

// Photo endpoint - receives photo files (for future use)
app.post('/api/photo', upload.single('photo'), (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ 
                error: 'No photo file uploaded' 
            });
        }
        
        const photoData = {
            filename: req.file.filename,
            originalName: req.file.originalname,
            size: req.file.size,
            mimetype: req.file.mimetype,
            deviceId: req.body.deviceId,
            timestamp: req.body.timestamp,
            receivedAt: Date.now()
        };
        
        logData('photo', photoData);
        
        res.json({ 
            success: true, 
            message: 'Photo file received',
            filename: req.file.filename,
            size: req.file.size,
            receivedAt: photoData.receivedAt
        });
        
    } catch (error) {
        console.error('Error processing photo file:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            message: error.message 
        });
    }
});

// Get recent data endpoint (for debugging)
app.get('/api/data', (req, res) => {
    try {
        const logFile = path.join(__dirname, 'server.log');
        
        if (!fs.existsSync(logFile)) {
            return res.json({ data: [] });
        }
        
        const logContent = fs.readFileSync(logFile, 'utf8');
        const lines = logContent.trim().split('\n').filter(line => line);
        const recentData = lines.slice(-50).map(line => {
            try {
                return JSON.parse(line);
            } catch {
                return null;
            }
        }).filter(item => item);
        
        res.json({ data: recentData });
        
    } catch (error) {
        console.error('Error reading log data:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            message: error.message 
        });
    }
});

// Serve uploaded files (for debugging)
app.use('/uploads', express.static(uploadsDir));

// Root endpoint
app.get('/', (req, res) => {
    res.json({
        message: 'ChildWatch Test Server',
        version: '1.0.0',
        endpoints: {
            'GET /api/health': 'Health check',
            'POST /api/loc': 'Receive location data',
            'POST /api/audio': 'Receive audio files',
            'POST /api/photo': 'Receive photo files',
            'GET /api/data': 'Get recent received data',
            'GET /uploads/:filename': 'Access uploaded files'
        }
    });
});

// Error handling middleware
app.use((error, req, res, next) => {
    if (error instanceof multer.MulterError) {
        if (error.code === 'LIMIT_FILE_SIZE') {
            return res.status(400).json({ 
                error: 'File too large',
                maxSize: '50MB'
            });
        }
    }
    
    console.error('Unhandled error:', error);
    res.status(500).json({ 
        error: 'Internal server error',
        message: error.message 
    });
});

// 404 handler
app.use('*', (req, res) => {
    res.status(404).json({ 
        error: 'Not found',
        path: req.originalUrl 
    });
});

// Start server
app.listen(PORT, () => {
    console.log(`ChildWatch Test Server running on port ${PORT}`);
    console.log(`Health check: http://localhost:${PORT}/api/health`);
    console.log(`Uploads directory: ${uploadsDir}`);
    
    // Log startup
    logData('server', { 
        event: 'startup', 
        port: PORT,
        pid: process.pid
    });
});

// Graceful shutdown
process.on('SIGTERM', () => {
    console.log('SIGTERM received, shutting down gracefully');
    logData('server', { event: 'shutdown', reason: 'SIGTERM' });
    process.exit(0);
});

process.on('SIGINT', () => {
    console.log('SIGINT received, shutting down gracefully');
    logData('server', { event: 'shutdown', reason: 'SIGINT' });
    process.exit(0);
});
