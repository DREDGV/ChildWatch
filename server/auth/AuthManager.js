const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

/**
 * Authentication and authorization manager for ChildWatch server
 * 
 * Features:
 * - JWT token management
 * - Device authentication
 * - Session management
 * - Permission checking
 * - Token blacklisting
 */
class AuthManager {
    
    constructor() {
        this.deviceTokens = new Map();
        this.refreshTokens = new Map();
        this.blacklistedTokens = new Set();
        this.deviceSessions = new Map();
        this.tokenExpiryTime = 3600 * 1000; // 1 hour
        this.refreshTokenExpiryTime = 7 * 24 * 60 * 60 * 1000; // 7 days
        
        // Load or generate secret key
        this.secretKey = this.loadOrGenerateSecretKey();
        
        // Cleanup interval
        setInterval(() => this.cleanupExpiredTokens(), 5 * 60 * 1000); // Every 5 minutes
    }
    
    /**
     * Load secret key from file or generate new one
     */
    loadOrGenerateSecretKey() {
        const keyPath = path.join(__dirname, '..', 'keys', 'secret.key');
        
        try {
            if (fs.existsSync(keyPath)) {
                return fs.readFileSync(keyPath, 'utf8');
            }
        } catch (error) {
            console.warn('Could not load secret key:', error.message);
        }
        
        // Generate new secret key
        const newKey = crypto.randomBytes(64).toString('hex');
        
        try {
            // Ensure keys directory exists
            const keysDir = path.dirname(keyPath);
            if (!fs.existsSync(keysDir)) {
                fs.mkdirSync(keysDir, { recursive: true });
            }
            
            fs.writeFileSync(keyPath, newKey, { mode: 0o600 });
            console.log('Generated new secret key');
        } catch (error) {
            console.warn('Could not save secret key:', error.message);
        }
        
        return newKey;
    }
    
    /**
     * Register a new device
     */
    registerDevice(deviceData) {
        const { deviceId, deviceName, deviceType, appVersion } = deviceData;
        
        // Check if device already exists
        if (this.deviceTokens.has(deviceId)) {
            const existingToken = this.deviceTokens.get(deviceId);
            
            // If token is still valid, return existing token
            if (Date.now() < existingToken.expiresAt) {
                return {
                    success: true,
                    authToken: existingToken.authToken,
                    refreshToken: existingToken.refreshToken,
                    expiresIn: Math.floor((existingToken.expiresAt - Date.now()) / 1000)
                };
            }
        }
        
        // Generate new tokens
        const authToken = this.generateSecureToken(32);
        const refreshToken = this.generateSecureToken(32);
        const expiresAt = Date.now() + this.tokenExpiryTime;
        
        // Store tokens
        this.deviceTokens.set(deviceId, {
            authToken,
            refreshToken,
            expiresAt,
            deviceName: deviceName || 'Unknown Device',
            deviceType: deviceType || 'android',
            appVersion: appVersion || '1.0.0',
            registeredAt: Date.now(),
            lastActivity: Date.now()
        });
        
        // Store refresh token
        this.refreshTokens.set(refreshToken, {
            deviceId,
            expiresAt: Date.now() + this.refreshTokenExpiryTime
        });
        
        // Create device session
        this.deviceSessions.set(deviceId, {
            deviceId,
            deviceName,
            deviceType,
            appVersion,
            registeredAt: Date.now(),
            lastActivity: Date.now(),
            totalRequests: 0,
            lastLocation: null,
            suspiciousActivity: [],
            latestStatus: null
        });
        
        console.log(`Device registered: ${deviceId} (${deviceName})`);
        
        return {
            success: true,
            authToken,
            refreshToken,
            expiresIn: Math.floor(this.tokenExpiryTime / 1000)
        };
    }
    
    /**
     * Refresh authentication token
     */
    refreshToken(refreshToken, deviceId) {
        // Check if refresh token exists and is valid
        const refreshData = this.refreshTokens.get(refreshToken);
        if (!refreshData || refreshData.deviceId !== deviceId) {
            return {
                success: false,
                error: 'Invalid refresh token'
            };
        }
        
        if (Date.now() > refreshData.expiresAt) {
            this.refreshTokens.delete(refreshToken);
            return {
                success: false,
                error: 'Refresh token expired'
            };
        }
        
        // Generate new tokens
        const newAuthToken = this.generateSecureToken(32);
        const newRefreshToken = this.generateSecureToken(32);
        const expiresAt = Date.now() + this.tokenExpiryTime;
        
        // Update stored tokens
        const deviceData = this.deviceTokens.get(deviceId);
        if (deviceData) {
            deviceData.authToken = newAuthToken;
            deviceData.refreshToken = newRefreshToken;
            deviceData.expiresAt = expiresAt;
            deviceData.lastActivity = Date.now();
        }
        
        // Update refresh token
        this.refreshTokens.delete(refreshToken);
        this.refreshTokens.set(newRefreshToken, {
            deviceId,
            expiresAt: Date.now() + this.refreshTokenExpiryTime
        });
        
        console.log(`Token refreshed for device: ${deviceId}`);
        
        return {
            success: true,
            authToken: newAuthToken,
            refreshToken: newRefreshToken,
            expiresIn: Math.floor(this.tokenExpiryTime / 1000)
        };
    }
    
    /**
     * Validate authentication token
     */
    validateToken(authToken) {
        // Check if token is blacklisted
        if (this.blacklistedTokens.has(authToken)) {
            return {
                valid: false,
                error: 'Token is blacklisted'
            };
        }
        
        // Find device by token
        for (const [deviceId, deviceData] of this.deviceTokens.entries()) {
            if (deviceData.authToken === authToken) {
                // Check if token is expired
                if (Date.now() > deviceData.expiresAt) {
                    return {
                        valid: false,
                        error: 'Token expired'
                    };
                }
                
                // Update last activity
                deviceData.lastActivity = Date.now();
                
                return {
                    valid: true,
                    deviceId: deviceId,
                    deviceData: deviceData
                };
            }
        }
        
        return {
            valid: false,
            error: 'Invalid token'
        };
    }
    
    /**
     * Blacklist a token
     */
    blacklistToken(authToken) {
        this.blacklistedTokens.add(authToken);
        console.log(`Token blacklisted: ${authToken.substring(0, 8)}...`);
    }
    
    /**
     * Revoke device access
     */
    revokeDeviceAccess(deviceId) {
        const deviceData = this.deviceTokens.get(deviceId);
        if (deviceData) {
            // Blacklist current token
            this.blacklistedTokens.add(deviceData.authToken);
            
            // Remove device tokens
            this.deviceTokens.delete(deviceId);
            
            // Remove refresh token
            this.refreshTokens.delete(deviceData.refreshToken);
            
            // Remove device session
            this.deviceSessions.delete(deviceId);
            
            console.log(`Device access revoked: ${deviceId}`);
            return true;
        }
        
        return false;
    }
    
    /**
     * Get device information
     */
    getDeviceInfo(deviceId) {
        const deviceData = this.deviceTokens.get(deviceId);
        const sessionData = this.deviceSessions.get(deviceId);
        
        if (!deviceData || !sessionData) {
            return null;
        }
        
        return {
            deviceId: deviceId,
            deviceName: deviceData.deviceName,
            deviceType: deviceData.deviceType,
            appVersion: deviceData.appVersion,
            registeredAt: deviceData.registeredAt,
            lastActivity: deviceData.lastActivity,
            totalRequests: sessionData.totalRequests,
            lastLocation: sessionData.lastLocation,
            suspiciousActivity: sessionData.suspiciousActivity,
            latestStatus: sessionData.latestStatus || null
        };
    }
    
    /**
     * Update device activity
     */
    updateDeviceActivity(deviceId, activityType, data = null) {
        const sessionData = this.deviceSessions.get(deviceId);
        if (sessionData) {
            sessionData.lastActivity = Date.now();
            sessionData.totalRequests++;
            
            if (activityType === 'location' && data) {
                sessionData.lastLocation = data;
            }
            
            if (activityType === 'suspicious' && data) {
                sessionData.suspiciousActivity.push({
                    timestamp: Date.now(),
                    type: data.type,
                    description: data.description
                });
                
                // Keep only last 10 suspicious activities
                if (sessionData.suspiciousActivity.length > 10) {
                    sessionData.suspiciousActivity = sessionData.suspiciousActivity.slice(-10);
                }
            }
        }
    }
    
    /**
     * Update latest device status snapshot
     */
    updateDeviceStatus(deviceId, status) {
        const sessionData = this.deviceSessions.get(deviceId);
        if (sessionData) {
            sessionData.latestStatus = {
                ...status,
                updatedAt: Date.now()
            };
            sessionData.lastActivity = Date.now();
        }
    }

    /**
     * Get latest device status snapshot
     */
    getDeviceStatus(deviceId) {
        const sessionData = this.deviceSessions.get(deviceId);
        if (!sessionData || !sessionData.latestStatus) {
            return null;
        }
        return sessionData.latestStatus;
    }
    
    /**
     * Check device permissions
     */
    checkDevicePermissions(deviceId, permission) {
        const deviceData = this.deviceTokens.get(deviceId);
        if (!deviceData) {
            return false;
        }
        
        // Basic permission checking
        const permissions = {
            'location': true,
            'audio': true,
            'photo': true,
            'admin': false // Only for special devices
        };
        
        return permissions[permission] || false;
    }
    
    /**
     * Generate secure random token
     */
    generateSecureToken(length = 32) {
        return crypto.randomBytes(length).toString('hex');
    }
    
    /**
     * Clean up expired tokens
     */
    cleanupExpiredTokens() {
        const now = Date.now();
        
        // Clean up expired device tokens
        for (const [deviceId, deviceData] of this.deviceTokens.entries()) {
            if (now > deviceData.expiresAt) {
                this.deviceTokens.delete(deviceId);
                this.refreshTokens.delete(deviceData.refreshToken);
                this.deviceSessions.delete(deviceId);
                console.log(`Cleaned up expired tokens for device: ${deviceId}`);
            }
        }
        
        // Clean up expired refresh tokens
        for (const [refreshToken, refreshData] of this.refreshTokens.entries()) {
            if (now > refreshData.expiresAt) {
                this.refreshTokens.delete(refreshToken);
            }
        }
        
        // Clean up old blacklisted tokens (older than 24 hours)
        const maxBlacklistAge = 24 * 60 * 60 * 1000;
        // Note: Set doesn't have timestamp info, so we'll clear it periodically
        if (this.blacklistedTokens.size > 1000) {
            this.blacklistedTokens.clear();
            console.log('Cleared blacklisted tokens cache');
        }
    }
    
    /**
     * Get authentication statistics
     */
    getAuthStats() {
        const now = Date.now();
        const activeDevices = Array.from(this.deviceTokens.values()).filter(
            device => now < device.expiresAt
        ).length;
        
        const totalDevices = this.deviceTokens.size;
        const totalRefreshTokens = this.refreshTokens.size;
        const blacklistedTokens = this.blacklistedTokens.size;
        
        return {
            activeDevices,
            totalDevices,
            totalRefreshTokens,
            blacklistedTokens,
            tokenExpiryTime: this.tokenExpiryTime,
            refreshTokenExpiryTime: this.refreshTokenExpiryTime
        };
    }
}

module.exports = AuthManager;
