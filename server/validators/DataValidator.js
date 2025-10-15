const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

/**
 * Data validation utilities for ChildWatch server
 * 
 * Features:
 * - Input validation and sanitization
 * - Rate limiting
 * - Request validation
 * - Data integrity checks
 */
class DataValidator {
    
    constructor() {
        this.rateLimitMap = new Map();
        this.maxRequestsPerMinute = 60;
        this.maxRequestsPerHour = 1000;
    }
    
    /**
     * Validate device registration data
     */
    validateDeviceRegistration(data) {
        const errors = [];
        
        if (!data.deviceId || typeof data.deviceId !== 'string') {
            errors.push('Device ID is required and must be a string');
        } else if (data.deviceId.length < 10 || data.deviceId.length > 100) {
            errors.push('Device ID must be between 10 and 100 characters');
        } else if (!/^[a-zA-Z0-9_-]+$/.test(data.deviceId)) {
            errors.push('Device ID contains invalid characters');
        }
        
        if (!data.deviceName || typeof data.deviceName !== 'string') {
            errors.push('Device name is required and must be a string');
        } else if (data.deviceName.length > 100) {
            errors.push('Device name must be less than 100 characters');
        }
        
        if (!data.deviceType || typeof data.deviceType !== 'string') {
            errors.push('Device type is required and must be a string');
        } else if (!['android', 'ios'].includes(data.deviceType)) {
            errors.push('Device type must be android or ios');
        }
        
        if (!data.appVersion || typeof data.appVersion !== 'string') {
            errors.push('App version is required and must be a string');
        } else if (!/^\d+\.\d+\.\d+$/.test(data.appVersion)) {
            errors.push('App version must be in format x.y.z');
        }
        
        if (data.timestamp && typeof data.timestamp !== 'number') {
            errors.push('Timestamp must be a number');
        }
        
        return {
            isValid: errors.length === 0,
            errors: errors
        };
    }
    
    /**
     * Validate location data
     */
    validateLocationData(data) {
        const errors = [];
        
        if (typeof data.latitude !== 'number' || isNaN(data.latitude)) {
            errors.push('Latitude must be a valid number');
        } else if (data.latitude < -90 || data.latitude > 90) {
            errors.push('Latitude must be between -90 and 90');
        }
        
        if (typeof data.longitude !== 'number' || isNaN(data.longitude)) {
            errors.push('Longitude must be a valid number');
        } else if (data.longitude < -180 || data.longitude > 180) {
            errors.push('Longitude must be between -180 and 180');
        }
        
        if (typeof data.accuracy !== 'number' || isNaN(data.accuracy)) {
            errors.push('Accuracy must be a valid number');
        } else if (data.accuracy < 0 || data.accuracy > 1000) {
            errors.push('Accuracy must be between 0 and 1000 meters');
        }
        
        if (typeof data.timestamp !== 'number' || isNaN(data.timestamp)) {
            errors.push('Timestamp must be a valid number');
        } else {
            const now = Date.now();
            const timeDiff = Math.abs(now - data.timestamp);
            if (timeDiff > 24 * 60 * 60 * 1000) { // 24 hours
                errors.push('Timestamp is too old or in the future');
            }
        }
        
        if (!data.deviceId || typeof data.deviceId !== 'string') {
            errors.push('Device ID is required');
        }
        
        return {
            isValid: errors.length === 0,
            errors: errors
        };
    }
    
    /**
     * Validate file upload
     */
    validateFileUpload(file, allowedTypes = ['audio', 'image']) {
        const errors = [];
        
        if (!file) {
            errors.push('File is required');
            return { isValid: false, errors };
        }
        
        if (!file.mimetype) {
            errors.push('File MIME type is required');
        } else {
            const allowedMimeTypes = {
                audio: ['audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/aac', 'audio/ogg'],
                image: ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
            };
            
            const isValidType = allowedTypes.some(type => 
                allowedMimeTypes[type].includes(file.mimetype)
            );
            
            if (!isValidType) {
                errors.push(`File type ${file.mimetype} is not allowed`);
            }
        }
        
        if (file.size > 10 * 1024 * 1024) { // 10MB
            errors.push('File size must be less than 10MB');
        }
        
        if (file.size < 100) { // 100 bytes
            errors.push('File size must be at least 100 bytes');
        }
        
        return {
            isValid: errors.length === 0,
            errors: errors
        };
    }
    
    /**
     * Check rate limiting
     */
    checkRateLimit(identifier, windowMs = 60000) {
        const now = Date.now();
        const windowStart = now - windowMs;
        
        if (!this.rateLimitMap.has(identifier)) {
            this.rateLimitMap.set(identifier, []);
        }
        
        const requests = this.rateLimitMap.get(identifier);
        
        // Remove old requests outside the window
        const recentRequests = requests.filter(timestamp => timestamp > windowStart);
        this.rateLimitMap.set(identifier, recentRequests);
        
        // Check if limit exceeded
        const limit = windowMs === 60000 ? this.maxRequestsPerMinute : this.maxRequestsPerHour;
        if (recentRequests.length >= limit) {
            return {
                allowed: false,
                remaining: 0,
                resetTime: recentRequests[0] + windowMs
            };
        }
        
        // Add current request
        recentRequests.push(now);
        this.rateLimitMap.set(identifier, recentRequests);
        
        return {
            allowed: true,
            remaining: limit - recentRequests.length,
            resetTime: now + windowMs
        };
    }
    
    /**
     * Sanitize string input
     */
    sanitizeString(input, maxLength = 1000) {
        if (typeof input !== 'string') {
            return '';
        }
        
        return input
            .slice(0, maxLength)
            .replace(/[<>\"'&]/g, '') // Remove potentially dangerous characters
            .trim();
    }
    
    /**
     * Validate token format
     */
    validateTokenFormat(token) {
        if (!token || typeof token !== 'string') {
            return false;
        }
        
        // Token should be 64 characters hex string
        return /^[a-f0-9]{64}$/.test(token);
    }
    
    /**
     * Validate device ID format
     */
    validateDeviceIdFormat(deviceId) {
        if (!deviceId || typeof deviceId !== 'string') {
            return false;
        }
        
        // Device ID should be alphanumeric with underscores and hyphens
        return /^[a-zA-Z0-9_-]{10,100}$/.test(deviceId);
    }

    validateAppVersion(version) {
        if (!version || typeof version !== 'string') {
            return false;
        }

        // Accept semantic versions with optional pre-release/build suffixes (e.g., 5.2.0 or 5.2.0-debug)
        return /^\d+\.\d+\.\d+([-/][A-Za-z0-9._]+)?$/.test(version);
    }
    
    /**
     * Check if location is suspicious (too fast movement)
     */
    checkSuspiciousLocation(newLocation, lastLocation, maxSpeedKmh = 200) {
        if (!lastLocation) {
            return { suspicious: false, reason: null };
        }
        
        const distance = this.calculateDistance(
            lastLocation.latitude, lastLocation.longitude,
            newLocation.latitude, newLocation.longitude
        );
        
        const timeDiff = (newLocation.timestamp - lastLocation.timestamp) / 1000; // seconds
        const speedKmh = (distance / 1000) / (timeDiff / 3600);
        
        if (speedKmh > maxSpeedKmh) {
            return {
                suspicious: true,
                reason: `Suspicious speed: ${speedKmh.toFixed(2)} km/h`,
                speed: speedKmh,
                distance: distance,
                timeDiff: timeDiff
            };
        }
        
        return { suspicious: false, reason: null };
    }
    
    /**
     * Calculate distance between two coordinates (Haversine formula)
     */
    calculateDistance(lat1, lon1, lat2, lon2) {
        const R = 6371000; // Earth's radius in meters
        const dLat = this.toRadians(lat2 - lat1);
        const dLon = this.toRadians(lon2 - lon1);
        
        const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                  Math.cos(this.toRadians(lat1)) * Math.cos(this.toRadians(lat2)) *
                  Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
    
    /**
     * Convert degrees to radians
     */
    toRadians(degrees) {
        return degrees * (Math.PI / 180);
    }
    
    /**
     * Generate secure random token
     */
    generateSecureToken(length = 32) {
        return crypto.randomBytes(length).toString('hex');
    }
    
    /**
     * Hash sensitive data
     */
    hashData(data, salt = null) {
        const actualSalt = salt || crypto.randomBytes(16).toString('hex');
        const hash = crypto.createHash('sha256');
        hash.update(data + actualSalt);
        return {
            hash: hash.digest('hex'),
            salt: actualSalt
        };
    }
    
    /**
     * Verify hashed data
     */
    verifyHash(data, hash, salt) {
        const testHash = crypto.createHash('sha256');
        testHash.update(data + salt);
        return testHash.digest('hex') === hash;
    }
    
    /**
     * Clean up old rate limit entries
     */
    cleanupRateLimit() {
        const now = Date.now();
        const maxAge = 24 * 60 * 60 * 1000; // 24 hours
        
        for (const [identifier, requests] of this.rateLimitMap.entries()) {
            const recentRequests = requests.filter(timestamp => 
                now - timestamp < maxAge
            );
            
            if (recentRequests.length === 0) {
                this.rateLimitMap.delete(identifier);
            } else {
                this.rateLimitMap.set(identifier, recentRequests);
            }
        }
    }
}

module.exports = DataValidator;
