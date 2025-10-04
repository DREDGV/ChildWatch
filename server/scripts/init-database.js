const DatabaseManager = require('../database/DatabaseManager');
const path = require('path');

/**
 * Initialize ChildWatch database
 * Creates all necessary tables and indexes
 */
async function initDatabase() {
    console.log('🚀 Initializing ChildWatch Database...');
    
    const dbManager = new DatabaseManager('./childwatch.db');
    
    try {
        await dbManager.initialize();
        console.log('✅ Database initialized successfully!');
        
        // Create a test device for development
        const testDevice = {
            deviceId: 'test-device-001',
            deviceName: 'Test Parent Device',
            deviceType: 'android',
            appVersion: '1.0.0',
            authToken: 'test-auth-token-123',
            refreshToken: 'test-refresh-token-456',
            tokenExpiresAt: Date.now() + (24 * 60 * 60 * 1000) // 24 hours
        };
        
        await dbManager.registerDevice(testDevice);
        console.log('✅ Test device created:', testDevice.deviceId);
        
        // Log some test activity
        await dbManager.logActivity(
            testDevice.deviceId, 
            'system', 
            { action: 'database_initialized' }, 
            Date.now()
        );
        
        console.log('✅ Database setup complete!');
        console.log('\n📊 Database file: ./childwatch.db');
        console.log('🔑 Test device ID:', testDevice.deviceId);
        console.log('🔐 Test auth token:', testDevice.authToken);
        
    } catch (error) {
        console.error('❌ Database initialization failed:', error);
        process.exit(1);
    } finally {
        await dbManager.close();
    }
}

// Run if called directly
if (require.main === module) {
    initDatabase();
}

module.exports = initDatabase;
