const sqlite3 = require('sqlite3').verbose();
const path = require('path');
const fs = require('fs');

/**
 * Database Manager for ChildWatch Server
 * 
 * Manages SQLite database operations for:
 * - Device registration and authentication
 * - Location history
 * - Audio/photo file metadata
 * - Chat messages
 * - Activity logs
 */
class DatabaseManager {
    constructor(dbPath = './childwatch.db') {
        this.dbPath = dbPath;
        this.db = null;
        this.isInitialized = false;
    }

    /**
     * Initialize database connection and create tables
     */
    async initialize() {
        return new Promise((resolve, reject) => {
            this.db = new sqlite3.Database(this.dbPath, (err) => {
                if (err) {
                    console.error('Error opening database:', err);
                    reject(err);
                    return;
                }
                
                console.log('Connected to SQLite database');
                this.createTables()
                    .then(() => {
                        this.isInitialized = true;
                        resolve();
                    })
                    .catch(reject);
            });
        });
    }

    /**
     * Create all necessary tables
     */
    async createTables() {
        const tables = [
            // Devices table
            `CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT UNIQUE NOT NULL,
                device_name TEXT NOT NULL,
                device_type TEXT NOT NULL,
                app_version TEXT NOT NULL,
                auth_token TEXT,
                refresh_token TEXT,
                token_expires_at INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                is_active INTEGER DEFAULT 1,
                permissions TEXT DEFAULT 'user'
            )`,

            // Location history
            `CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

            // Audio files metadata
            `CREATE TABLE IF NOT EXISTS audio_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                duration INTEGER,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

            // Photo files metadata
            `CREATE TABLE IF NOT EXISTS photo_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                width INTEGER,
                height INTEGER,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

            // Chat messages
            `CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                sender TEXT NOT NULL CHECK (sender IN ('parent', 'child')),
                message TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_read INTEGER DEFAULT 0,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

            // Activity logs
            `CREATE TABLE IF NOT EXISTS activity_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                activity_type TEXT NOT NULL,
                activity_data TEXT,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

            // Geofences (safe zones)
            `CREATE TABLE IF NOT EXISTS geofences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                name TEXT NOT NULL,
                center_lat REAL NOT NULL,
                center_lng REAL NOT NULL,
                radius REAL NOT NULL,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`
        ];

        for (const sql of tables) {
            await this.run(sql);
        }

        // Create indexes for better performance
        const indexes = [
            'CREATE INDEX IF NOT EXISTS idx_locations_device_timestamp ON locations (device_id, timestamp)',
            'CREATE INDEX IF NOT EXISTS idx_audio_device_timestamp ON audio_files (device_id, timestamp)',
            'CREATE INDEX IF NOT EXISTS idx_photo_device_timestamp ON photo_files (device_id, timestamp)',
            'CREATE INDEX IF NOT EXISTS idx_chat_device_timestamp ON chat_messages (device_id, timestamp)',
            'CREATE INDEX IF NOT EXISTS idx_activity_device_timestamp ON activity_logs (device_id, timestamp)',
            'CREATE INDEX IF NOT EXISTS idx_devices_auth_token ON devices (auth_token)'
        ];

        for (const sql of indexes) {
            await this.run(sql);
        }

        console.log('Database tables created successfully');
    }

    /**
     * Execute SQL query
     */
    run(sql, params = []) {
        return new Promise((resolve, reject) => {
            this.db.run(sql, params, function(err) {
                if (err) {
                    console.error('Database run error:', err);
                    reject(err);
                } else {
                    resolve({ id: this.lastID, changes: this.changes });
                }
            });
        });
    }

    /**
     * Get single row
     */
    get(sql, params = []) {
        return new Promise((resolve, reject) => {
            this.db.get(sql, params, (err, row) => {
                if (err) {
                    console.error('Database get error:', err);
                    reject(err);
                } else {
                    resolve(row);
                }
            });
        });
    }

    /**
     * Get multiple rows
     */
    all(sql, params = []) {
        return new Promise((resolve, reject) => {
            this.db.all(sql, params, (err, rows) => {
                if (err) {
                    console.error('Database all error:', err);
                    reject(err);
                } else {
                    resolve(rows);
                }
            });
        });
    }

    // Device operations
    async registerDevice(deviceData) {
        const { deviceId, deviceName, deviceType, appVersion, authToken, refreshToken, tokenExpiresAt } = deviceData;
        
        const sql = `INSERT OR REPLACE INTO devices 
                     (device_id, device_name, device_type, app_version, auth_token, refresh_token, token_expires_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))`;
        
        return await this.run(sql, [deviceId, deviceName, deviceType, appVersion, authToken, refreshToken, tokenExpiresAt]);
    }

    async getDeviceByToken(authToken) {
        const sql = 'SELECT * FROM devices WHERE auth_token = ? AND is_active = 1';
        return await this.get(sql, [authToken]);
    }

    async getDeviceById(deviceId) {
        const sql = 'SELECT * FROM devices WHERE device_id = ? AND is_active = 1';
        return await this.get(sql, [deviceId]);
    }

    async updateDeviceTokens(deviceId, authToken, refreshToken, tokenExpiresAt) {
        const sql = `UPDATE devices 
                     SET auth_token = ?, refresh_token = ?, token_expires_at = ?, updated_at = strftime('%s', 'now')
                     WHERE device_id = ?`;
        
        return await this.run(sql, [authToken, refreshToken, tokenExpiresAt, deviceId]);
    }

    // Location operations
    async saveLocation(deviceId, locationData) {
        const { latitude, longitude, accuracy, timestamp } = locationData;
        
        const sql = `INSERT INTO locations (device_id, latitude, longitude, accuracy, timestamp)
                     VALUES (?, ?, ?, ?, ?)`;
        
        return await this.run(sql, [deviceId, latitude, longitude, accuracy, timestamp]);
    }

    async getLocationHistory(deviceId, limit = 100, offset = 0) {
        const sql = `SELECT * FROM locations 
                     WHERE device_id = ? 
                     ORDER BY timestamp DESC 
                     LIMIT ? OFFSET ?`;
        
        return await this.all(sql, [deviceId, limit, offset]);
    }

    async getLatestLocation(deviceId) {
        const sql = `SELECT * FROM locations 
                     WHERE device_id = ? 
                     ORDER BY timestamp DESC 
                     LIMIT 1`;
        
        return await this.get(sql, [deviceId]);
    }

    // Audio file operations
    async saveAudioFile(deviceId, fileData) {
        const { filename, filePath, fileSize, mimeType, duration, timestamp } = fileData;
        
        const sql = `INSERT INTO audio_files (device_id, filename, file_path, file_size, mime_type, duration, timestamp)
                     VALUES (?, ?, ?, ?, ?, ?, ?)`;
        
        return await this.run(sql, [deviceId, filename, filePath, fileSize, mimeType, duration, timestamp]);
    }

    async getAudioFiles(deviceId, limit = 50, offset = 0) {
        const sql = `SELECT * FROM audio_files 
                     WHERE device_id = ? 
                     ORDER BY timestamp DESC 
                     LIMIT ? OFFSET ?`;
        
        return await this.all(sql, [deviceId, limit, offset]);
    }

    // Photo file operations
    async savePhotoFile(deviceId, fileData) {
        const { filename, filePath, fileSize, mimeType, width, height, timestamp } = fileData;
        
        const sql = `INSERT INTO photo_files (device_id, filename, file_path, file_size, mime_type, width, height, timestamp)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`;
        
        return await this.run(sql, [deviceId, filename, filePath, fileSize, mimeType, width, height, timestamp]);
    }

    async getPhotoFiles(deviceId, limit = 50, offset = 0) {
        const sql = `SELECT * FROM photo_files 
                     WHERE device_id = ? 
                     ORDER BY timestamp DESC 
                     LIMIT ? OFFSET ?`;
        
        return await this.all(sql, [deviceId, limit, offset]);
    }

    // Chat operations
    async saveChatMessage(deviceId, messageData) {
        const { sender, message, timestamp, isRead } = messageData;
        
        const sql = `INSERT INTO chat_messages (device_id, sender, message, timestamp, is_read)
                     VALUES (?, ?, ?, ?, ?)`;
        
        return await this.run(sql, [deviceId, sender, message, timestamp, isRead ? 1 : 0]);
    }

    async getChatMessages(deviceId, limit = 100, offset = 0) {
        const sql = `SELECT * FROM chat_messages 
                     WHERE device_id = ? 
                     ORDER BY timestamp ASC 
                     LIMIT ? OFFSET ?`;
        
        return await this.all(sql, [deviceId, limit, offset]);
    }

    async markMessageAsRead(messageId) {
        const sql = 'UPDATE chat_messages SET is_read = 1 WHERE id = ?';
        return await this.run(sql, [messageId]);
    }

    // Activity logging
    async logActivity(deviceId, activityType, activityData, timestamp) {
        const sql = `INSERT INTO activity_logs (device_id, activity_type, activity_data, timestamp)
                     VALUES (?, ?, ?, ?)`;
        
        return await this.run(sql, [deviceId, activityType, JSON.stringify(activityData), timestamp]);
    }

    async getActivityLogs(deviceId, limit = 100, offset = 0) {
        const sql = `SELECT * FROM activity_logs 
                     WHERE device_id = ? 
                     ORDER BY timestamp DESC 
                     LIMIT ? OFFSET ?`;
        
        return await this.all(sql, [deviceId, limit, offset]);
    }

    // Statistics
    async getDeviceStats(deviceId) {
        const stats = {};
        
        // Location count
        const locationCount = await this.get(
            'SELECT COUNT(*) as count FROM locations WHERE device_id = ?',
            [deviceId]
        );
        stats.locationCount = locationCount.count;

        // Audio files count
        const audioCount = await this.get(
            'SELECT COUNT(*) as count FROM audio_files WHERE device_id = ?',
            [deviceId]
        );
        stats.audioCount = audioCount.count;

        // Photo files count
        const photoCount = await this.get(
            'SELECT COUNT(*) as count FROM photo_files WHERE device_id = ?',
            [deviceId]
        );
        stats.photoCount = photoCount.count;

        // Chat messages count
        const chatCount = await this.get(
            'SELECT COUNT(*) as count FROM chat_messages WHERE device_id = ?',
            [deviceId]
        );
        stats.chatCount = chatCount.count;

        // Last activity
        const lastActivity = await this.get(
            'SELECT * FROM activity_logs WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1',
            [deviceId]
        );
        stats.lastActivity = lastActivity;

        return stats;
    }

    /**
     * Close database connection
     */
    close() {
        return new Promise((resolve) => {
            if (this.db) {
                this.db.close((err) => {
                    if (err) {
                        console.error('Error closing database:', err);
                    } else {
                        console.log('Database connection closed');
                    }
                    resolve();
                });
            } else {
                resolve();
            }
        });
    }
}

module.exports = DatabaseManager;
