const DataValidator = require('../validators/DataValidator');

/**
 * Authentication middleware for Express.js
 * 
 * Features:
 * - Token validation
 * - Rate limiting
 * - Request logging
 * - Security headers
 * - Error handling
 */
class AuthMiddleware {
    
    constructor(authManager) {
        this.authManager = authManager;
        this.validator = new DataValidator();
    }
    
    /**
     * Middleware to authenticate requests
     */
    authenticate() {
        return (req, res, next) => {
            try {
                const authHeader = req.headers['authorization'];
                const token = authHeader && authHeader.split(' ')[1];
                
                if (!token) {
                    return res.status(401).json({ 
                        error: 'Access token required',
                        code: 'MISSING_TOKEN'
                    });
                }
                
                // Validate token format
                if (!this.validator.validateTokenFormat(token)) {
                    return res.status(401).json({ 
                        error: 'Invalid token format',
                        code: 'INVALID_TOKEN_FORMAT'
                    });
                }
                
                // Validate token
                const validation = this.authManager.validateToken(token);
                if (!validation.valid) {
                    return res.status(401).json({ 
                        error: validation.error,
                        code: 'INVALID_TOKEN'
                    });
                }
                
                // Add device info to request
                req.deviceId = validation.deviceId;
                req.deviceData = validation.deviceData;
                
                next();
            } catch (error) {
                console.error('Authentication error:', error);
                res.status(500).json({ 
                    error: 'Internal server error',
                    code: 'AUTH_ERROR'
                });
            }
        };
    }
    
    /**
     * Middleware for rate limiting
     */
    rateLimit(windowMs = 60000, maxRequests = 60) {
        return (req, res, next) => {
            try {
                const identifier = req.deviceId || req.ip;
                const rateLimit = this.validator.checkRateLimit(identifier, windowMs);
                
                if (!rateLimit.allowed) {
                    res.set({
                        'X-RateLimit-Limit': maxRequests,
                        'X-RateLimit-Remaining': 0,
                        'X-RateLimit-Reset': new Date(rateLimit.resetTime).toISOString()
                    });
                    
                    return res.status(429).json({ 
                        error: 'Too many requests',
                        code: 'RATE_LIMIT_EXCEEDED',
                        retryAfter: Math.ceil((rateLimit.resetTime - Date.now()) / 1000)
                    });
                }
                
                res.set({
                    'X-RateLimit-Limit': maxRequests,
                    'X-RateLimit-Remaining': rateLimit.remaining,
                    'X-RateLimit-Reset': new Date(rateLimit.resetTime).toISOString()
                });
                
                next();
            } catch (error) {
                console.error('Rate limit error:', error);
                next(); // Continue on error
            }
        };
    }
    
    /**
     * Middleware for request logging
     */
    requestLogger() {
        return (req, res, next) => {
            const startTime = Date.now();
            
            // Log request
            console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} - Device: ${req.deviceId || 'Unknown'}`);
            
            // Override res.end to log response
            const originalEnd = res.end;
            res.end = function(chunk, encoding) {
                const duration = Date.now() - startTime;
                console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} - ${res.statusCode} - ${duration}ms`);
                
                // Update device activity
                if (req.deviceId) {
                    req.authManager?.updateDeviceActivity(req.deviceId, 'request', {
                        method: req.method,
                        path: req.path,
                        statusCode: res.statusCode,
                        duration: duration
                    });
                }
                
                originalEnd.call(this, chunk, encoding);
            };
            
            next();
        };
    }
    
    /**
     * Middleware for security headers
     */
    securityHeaders() {
        return (req, res, next) => {
            // Set security headers
            res.set({
                'X-Content-Type-Options': 'nosniff',
                'X-Frame-Options': 'DENY',
                'X-XSS-Protection': '1; mode=block',
                'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
                'Content-Security-Policy': "default-src 'self'",
                'Referrer-Policy': 'strict-origin-when-cross-origin',
                'Permissions-Policy': 'geolocation=(), microphone=(), camera=()'
            });
            
            next();
        };
    }
    
    /**
     * Middleware for CORS
     */
    cors() {
        return (req, res, next) => {
            const origin = req.headers.origin;
            const allowedOrigins = [
                'http://localhost:3000',
                'https://your-domain.com' // Add your production domain
            ];
            
            if (allowedOrigins.includes(origin)) {
                res.set('Access-Control-Allow-Origin', origin);
            }
            
            res.set({
                'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Requested-With',
                'Access-Control-Allow-Credentials': 'true',
                'Access-Control-Max-Age': '86400'
            });
            
            if (req.method === 'OPTIONS') {
                return res.status(200).end();
            }
            
            next();
        };
    }
    
    /**
     * Middleware for request validation
     */
    validateRequest(validationRules) {
        return (req, res, next) => {
            try {
                const errors = [];
                
                // Validate body
                if (validationRules.body) {
                    const bodyValidation = this.validateData(req.body, validationRules.body);
                    if (!bodyValidation.isValid) {
                        errors.push(...bodyValidation.errors);
                    }
                }
                
                // Validate query parameters
                if (validationRules.query) {
                    const queryValidation = this.validateData(req.query, validationRules.query);
                    if (!queryValidation.isValid) {
                        errors.push(...queryValidation.errors);
                    }
                }
                
                // Validate path parameters
                if (validationRules.params) {
                    const paramsValidation = this.validateData(req.params, validationRules.params);
                    if (!paramsValidation.isValid) {
                        errors.push(...paramsValidation.errors);
                    }
                }
                
                if (errors.length > 0) {
                    return res.status(400).json({ 
                        error: 'Validation failed',
                        code: 'VALIDATION_ERROR',
                        details: errors
                    });
                }
                
                next();
            } catch (error) {
                console.error('Validation error:', error);
                res.status(500).json({ 
                    error: 'Internal server error',
                    code: 'VALIDATION_ERROR'
                });
            }
        };
    }
    
    /**
     * Validate data against rules
     */
    validateData(data, rules) {
        const errors = [];
        
        for (const [field, rule] of Object.entries(rules)) {
            const value = data[field];
            
            if (rule.required && (value === undefined || value === null || value === '')) {
                errors.push(`${field} is required`);
                continue;
            }
            
            if (value !== undefined && value !== null) {
                if (rule.type && typeof value !== rule.type) {
                    errors.push(`${field} must be of type ${rule.type}`);
                }
                
                if (rule.minLength && value.length < rule.minLength) {
                    errors.push(`${field} must be at least ${rule.minLength} characters`);
                }
                
                if (rule.maxLength && value.length > rule.maxLength) {
                    errors.push(`${field} must be no more than ${rule.maxLength} characters`);
                }
                
                if (rule.pattern && !rule.pattern.test(value)) {
                    errors.push(`${field} format is invalid`);
                }
                
                if (rule.min !== undefined && value < rule.min) {
                    errors.push(`${field} must be at least ${rule.min}`);
                }
                
                if (rule.max !== undefined && value > rule.max) {
                    errors.push(`${field} must be no more than ${rule.max}`);
                }
            }
        }
        
        return {
            isValid: errors.length === 0,
            errors: errors
        };
    }
    
    /**
     * Error handling middleware
     */
    errorHandler() {
        return (error, req, res, next) => {
            console.error('Unhandled error:', error);
            
            // Don't leak error details in production
            const isDevelopment = process.env.NODE_ENV === 'development';
            
            res.status(500).json({
                error: 'Internal server error',
                code: 'INTERNAL_ERROR',
                ...(isDevelopment && { details: error.message })
            });
        };
    }
    
    /**
     * 404 handler
     */
    notFoundHandler() {
        return (req, res) => {
            res.status(404).json({
                error: 'Not found',
                code: 'NOT_FOUND',
                path: req.path
            });
        };
    }
}

module.exports = AuthMiddleware;
