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
    
    constructor(options = {}) {
        this.deviceTokens = new Map();
        this.refreshTokens = new Map();
        this.blacklistedTokens = new Set();
        this.deviceSessions = new Map();
        this.tokenExpiryTime = 3600 * 1000; // 1 hour
        this.refreshTokenExpiryTime = 7 * 24 * 60 * 60 * 1000; // 7 days
        this.sessionStorePath = options.sessionStorePath ||
            process.env.CW_AUTH_SESSION_PATH ||
            path.join(__dirname, '..', 'data', 'auth-sessions.json');
        this.persistenceEnabled = options.persistenceEnabled !== undefined
            ? options.persistenceEnabled === true
            : process.env.NODE_ENV !== 'test';
        
        // Load or generate secret key
        this.secretKey = this.loadOrGenerateSecretKey();

        // Restore device credentials before accepting HTTP or Socket.IO traffic.
        this.loadPersistedSessions();
        
        // Cleanup interval
        this.cleanupTimer = setInterval(
            () => this.cleanupExpiredTokens(),
            5 * 60 * 1000
        );
        this.cleanupTimer.unref?.();
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
            if (
                Date.now() < existingToken.expiresAt &&
                existingToken.authToken &&
                existingToken.refreshToken
            ) {
                return {
                    success: true,
                    authToken: existingToken.authToken,
                    refreshToken: existingToken.refreshToken,
                    expiresIn: Math.floor((existingToken.expiresAt - Date.now()) / 1000)
                };
            }

            if (existingToken.refreshTokenHash) {
                this.refreshTokens.delete(existingToken.refreshTokenHash);
            }
        }
        
        // Generate new tokens
        const authToken = this.generateSecureToken(32);
        const refreshToken = this.generateSecureToken(32);
        const expiresAt = Date.now() + this.tokenExpiryTime;
        const refreshExpiresAt = Date.now() + this.refreshTokenExpiryTime;
        const authTokenHash = this.hashToken(authToken);
        const refreshTokenHash = this.hashToken(refreshToken);
        
        // Store tokens
        this.deviceTokens.set(deviceId, {
            authToken,
            refreshToken,
            authTokenHash,
            refreshTokenHash,
            expiresAt,
            refreshExpiresAt,
            deviceName: deviceName || 'Unknown Device',
            deviceType: deviceType || 'android',
            appVersion: appVersion || '1.0.0',
            registeredAt: Date.now(),
            lastActivity: Date.now()
        });
        
        // Store refresh token
        this.refreshTokens.set(refreshTokenHash, {
            deviceId,
            expiresAt: refreshExpiresAt
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
        this.savePersistedSessions();
        
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
        const currentRefreshTokenHash = this.hashToken(refreshToken);
        // Check if refresh token exists and is valid
        const refreshData = this.refreshTokens.get(currentRefreshTokenHash);
        if (!refreshData || refreshData.deviceId !== deviceId) {
            return {
                success: false,
                error: 'Invalid refresh token'
            };
        }
        
        if (Date.now() > refreshData.expiresAt) {
            this.refreshTokens.delete(currentRefreshTokenHash);
            this.savePersistedSessions();
            return {
                success: false,
                error: 'Refresh token expired'
            };
        }
        
        // Generate new tokens
        const newAuthToken = this.generateSecureToken(32);
        const newRefreshToken = this.generateSecureToken(32);
        const expiresAt = Date.now() + this.tokenExpiryTime;
        const refreshExpiresAt = Date.now() + this.refreshTokenExpiryTime;
        const newAuthTokenHash = this.hashToken(newAuthToken);
        const newRefreshTokenHash = this.hashToken(newRefreshToken);
        
        // Update stored tokens
        const deviceData = this.deviceTokens.get(deviceId);
        if (deviceData) {
            deviceData.authToken = newAuthToken;
            deviceData.refreshToken = newRefreshToken;
            deviceData.authTokenHash = newAuthTokenHash;
            deviceData.refreshTokenHash = newRefreshTokenHash;
            deviceData.expiresAt = expiresAt;
            deviceData.refreshExpiresAt = refreshExpiresAt;
            deviceData.lastActivity = Date.now();
        }
        
        // Update refresh token
        this.refreshTokens.delete(currentRefreshTokenHash);
        this.refreshTokens.set(newRefreshTokenHash, {
            deviceId,
            expiresAt: refreshExpiresAt
        });
        
        console.log(`Token refreshed for device: ${deviceId}`);
        this.savePersistedSessions();
        
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
        const authTokenHash = this.hashToken(authToken);
        // Check if token is blacklisted
        if (this.blacklistedTokens.has(authTokenHash)) {
            return {
                valid: false,
                error: 'Token is blacklisted'
            };
        }
        
        // Find device by token
        for (const [deviceId, deviceData] of this.deviceTokens.entries()) {
            if (deviceData.authTokenHash === authTokenHash) {
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
        this.blacklistedTokens.add(this.hashToken(authToken));
        this.savePersistedSessions();
        console.log(`Token blacklisted: ${authToken.substring(0, 8)}...`);
    }
    
    /**
     * Revoke device access
     */
    revokeDeviceAccess(deviceId) {
        const deviceData = this.deviceTokens.get(deviceId);
        if (deviceData) {
            // Blacklist current token
            this.blacklistedTokens.add(deviceData.authTokenHash);
            
            // Remove device tokens
            this.deviceTokens.delete(deviceId);
            
            // Remove refresh token
            this.refreshTokens.delete(deviceData.refreshTokenHash);
            
            // Remove device session
            this.deviceSessions.delete(deviceId);
            
            console.log(`Device access revoked: ${deviceId}`);
            this.savePersistedSessions();
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
     * Store only a one-way digest. Raw bearer and refresh tokens never leave
     * process memory, while clients can still be recognized after a restart.
     */
    hashToken(token) {
        return crypto
            .createHash('sha256')
            .update(String(token || ''), 'utf8')
            .digest('hex');
    }

    loadPersistedSessions() {
        if (!this.persistenceEnabled || !this.sessionStorePath) {
            return;
        }

        try {
            if (!fs.existsSync(this.sessionStorePath)) {
                return;
            }

            const payload = JSON.parse(
                fs.readFileSync(this.sessionStorePath, 'utf8')
            );
            const sessions = Array.isArray(payload.sessions)
                ? payload.sessions
                : [];
            const now = Date.now();
            let restored = 0;

            for (const stored of sessions) {
                const deviceId = String(stored?.deviceId || '').trim();
                const authTokenHash = String(stored?.authTokenHash || '').trim();
                const refreshTokenHash = String(stored?.refreshTokenHash || '').trim();
                const expiresAt = Number(stored?.expiresAt || 0);
                const refreshExpiresAt = Number(stored?.refreshExpiresAt || 0);

                if (
                    !deviceId ||
                    !/^[a-f0-9]{64}$/.test(authTokenHash) ||
                    !/^[a-f0-9]{64}$/.test(refreshTokenHash) ||
                    !Number.isFinite(expiresAt) ||
                    !Number.isFinite(refreshExpiresAt) ||
                    refreshExpiresAt <= now
                ) {
                    continue;
                }

                const deviceData = {
                    authToken: null,
                    refreshToken: null,
                    authTokenHash,
                    refreshTokenHash,
                    expiresAt,
                    refreshExpiresAt,
                    deviceName: stored.deviceName || 'Unknown Device',
                    deviceType: stored.deviceType || 'android',
                    appVersion: stored.appVersion || '1.0.0',
                    registeredAt: Number(stored.registeredAt || now),
                    lastActivity: Number(stored.lastActivity || now)
                };
                this.deviceTokens.set(deviceId, deviceData);
                this.refreshTokens.set(refreshTokenHash, {
                    deviceId,
                    expiresAt: refreshExpiresAt
                });
                this.deviceSessions.set(deviceId, {
                    deviceId,
                    deviceName: deviceData.deviceName,
                    deviceType: deviceData.deviceType,
                    appVersion: deviceData.appVersion,
                    registeredAt: deviceData.registeredAt,
                    lastActivity: deviceData.lastActivity,
                    totalRequests: 0,
                    lastLocation: null,
                    suspiciousActivity: [],
                    latestStatus: null
                });
                restored++;
            }

            const blacklisted = Array.isArray(payload.blacklistedTokenHashes)
                ? payload.blacklistedTokenHashes
                : [];
            for (const tokenHash of blacklisted) {
                if (/^[a-f0-9]{64}$/.test(String(tokenHash || ''))) {
                    this.blacklistedTokens.add(tokenHash);
                }
            }

            if (restored > 0) {
                console.log(`Restored ${restored} authentication session(s)`);
            }
        } catch (error) {
            // A damaged optional cache must not prevent the server from starting.
            console.warn(
                'Could not restore authentication sessions:',
                error.message
            );
        }
    }

    savePersistedSessions() {
        if (!this.persistenceEnabled || !this.sessionStorePath) {
            return;
        }

        try {
            const sessions = [];
            for (const [deviceId, deviceData] of this.deviceTokens.entries()) {
                sessions.push({
                    deviceId,
                    authTokenHash: deviceData.authTokenHash,
                    refreshTokenHash: deviceData.refreshTokenHash,
                    expiresAt: deviceData.expiresAt,
                    refreshExpiresAt: deviceData.refreshExpiresAt,
                    deviceName: deviceData.deviceName,
                    deviceType: deviceData.deviceType,
                    appVersion: deviceData.appVersion,
                    registeredAt: deviceData.registeredAt,
                    lastActivity: deviceData.lastActivity
                });
            }

            const payload = JSON.stringify({
                version: 1,
                savedAt: Date.now(),
                sessions,
                blacklistedTokenHashes: Array.from(this.blacklistedTokens)
            }, null, 2);
            const directory = path.dirname(this.sessionStorePath);
            fs.mkdirSync(directory, { recursive: true });

            const temporaryPath = `${this.sessionStorePath}.${process.pid}.tmp`;
            fs.writeFileSync(temporaryPath, payload, {
                encoding: 'utf8',
                mode: 0o600
            });

            try {
                fs.renameSync(temporaryPath, this.sessionStorePath);
            } catch (renameError) {
                // Windows may refuse replacing an existing destination. The
                // fallback updates the destination and removes only our temp file.
                fs.copyFileSync(temporaryPath, this.sessionStorePath);
                fs.unlinkSync(temporaryPath);
            }
        } catch (error) {
            console.warn(
                'Could not persist authentication sessions:',
                error.message
            );
        }
    }

    dispose() {
        if (this.cleanupTimer) {
            clearInterval(this.cleanupTimer);
            this.cleanupTimer = null;
        }
    }

    /**
     * Clean up expired tokens
     */
    cleanupExpiredTokens() {
        const now = Date.now();
        let changed = false;
        
        // Keep an expired access token record while its refresh token remains
        // valid. This lets a device recover after being offline for hours.
        for (const [deviceId, deviceData] of this.deviceTokens.entries()) {
            if (now > deviceData.refreshExpiresAt) {
                this.deviceTokens.delete(deviceId);
                this.refreshTokens.delete(deviceData.refreshTokenHash);
                this.deviceSessions.delete(deviceId);
                changed = true;
                console.log(`Cleaned up expired tokens for device: ${deviceId}`);
            }
        }
        
        // Clean up expired refresh tokens
        for (const [refreshToken, refreshData] of this.refreshTokens.entries()) {
            if (now > refreshData.expiresAt) {
                this.refreshTokens.delete(refreshToken);
                changed = true;
            }
        }
        
        // Clean up old blacklisted tokens (older than 24 hours)
        const maxBlacklistAge = 24 * 60 * 60 * 1000;
        // Note: Set doesn't have timestamp info, so we'll clear it periodically
        if (this.blacklistedTokens.size > 1000) {
            this.blacklistedTokens.clear();
            changed = true;
            console.log('Cleared blacklisted tokens cache');
        }

        if (changed) {
            this.savePersistedSessions();
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
