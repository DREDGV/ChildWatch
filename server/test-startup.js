// Test server startup
console.log('🚀 Starting ChildWatch Server...');

try {
    const express = require('express');
    console.log('✅ Express loaded');
    
    const DatabaseManager = require('./database/DatabaseManager');
    console.log('✅ DatabaseManager loaded');
    
    const AuthManager = require('./auth/AuthManager');
    console.log('✅ AuthManager loaded');
    
    const AuthMiddleware = require('./middleware/AuthMiddleware');
    console.log('✅ AuthMiddleware loaded');
    
    const DataValidator = require('./validators/DataValidator');
    console.log('✅ DataValidator loaded');
    
    console.log('✅ All modules loaded successfully!');
    
    // Test database initialization
    const dbManager = new DatabaseManager();
    dbManager.initialize().then(() => {
        console.log('✅ Database initialized successfully!');
        dbManager.close();
        console.log('✅ Test completed successfully!');
        process.exit(0);
    }).catch(error => {
        console.error('❌ Database initialization failed:', error);
        process.exit(1);
    });
    
} catch (error) {
    console.error('❌ Module loading failed:', error);
    process.exit(1);
}
