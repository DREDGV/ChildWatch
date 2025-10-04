const express = require('express');
const DatabaseManager = require('./database/DatabaseManager');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(express.json({ limit: '10mb' }));

// Initialize database
const dbManager = new DatabaseManager();
let isDbInitialized = false;

async function initializeDatabase() {
    try {
        console.log('🔄 Initializing database...');
        await dbManager.initialize();
        isDbInitialized = true;
        console.log('✅ Database initialized successfully');
    } catch (error) {
        console.error('❌ Database initialization failed:', error);
        process.exit(1);
    }
}

// Health check
app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'OK', 
        timestamp: new Date().toISOString(),
        version: '1.0.0',
        database: isDbInitialized ? 'connected' : 'disconnected'
    });
});

// Test endpoint
app.get('/api/test', async (req, res) => {
    try {
        if (!isDbInitialized) {
            return res.status(503).json({ error: 'Database not initialized' });
        }
        
        const stats = await dbManager.getDeviceStats('test-device-001');
        res.json({ 
            success: true, 
            message: 'Server working with database!',
            stats 
        });
    } catch (error) {
        console.error('Test endpoint error:', error);
        res.status(500).json({ error: 'Internal server error' });
    }
});

// Start server after database initialization
async function startServer() {
    await initializeDatabase();
    
    app.listen(PORT, () => {
        console.log(`🚀 ChildWatch Server running on port ${PORT}`);
        console.log(`📊 Health check: http://localhost:${PORT}/api/health`);
        console.log(`🧪 Test endpoint: http://localhost:${PORT}/api/test`);
        console.log(`✅ Server ready!`);
    });
}

startServer().catch(error => {
    console.error('❌ Server startup failed:', error);
    process.exit(1);
});

module.exports = app;
