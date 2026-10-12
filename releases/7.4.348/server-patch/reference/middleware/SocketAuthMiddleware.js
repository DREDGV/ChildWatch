const DataValidator = require('../validators/DataValidator');

/**
 * Socket.IO handshake authentication with a compatibility rollout mode.
 *
 * When required=false, legacy clients may connect without a token. A valid
 * token still attaches an authenticated device identity to the socket.
 * When required=true, every connection must present a valid token.
 */
class SocketAuthMiddleware {
    constructor(authManager, options = {}) {
        this.authManager = authManager;
        this.validator = new DataValidator();
        this.required = options.required === true;
    }

    authenticate() {
        return (socket, next) => {
            try {
                const credential = this.extractCredential(socket?.handshake);

                socket.authRequired = this.required;
                socket.authenticatedDeviceId = null;
                socket.authenticatedDeviceData = null;
                socket.authFailureCode = null;

                if (!credential) {
                    if (this.required) {
                        return next(this.createError(
                            'Socket authentication token required',
                            'WS_MISSING_TOKEN'
                        ));
                    }

                    socket.authMode = 'legacy';
                    return next();
                }

                if (!this.validator.validateTokenFormat(credential.token)) {
                    return this.handleInvalidCredential(
                        socket,
                        next,
                        'Invalid Socket.IO token format',
                        'WS_INVALID_TOKEN_FORMAT'
                    );
                }

                const validation = this.authManager.validateToken(credential.token);
                if (!validation.valid) {
                    return this.handleInvalidCredential(
                        socket,
                        next,
                        validation.error || 'Invalid Socket.IO token',
                        'WS_INVALID_TOKEN'
                    );
                }

                socket.authMode = 'authenticated';
                socket.authenticatedDeviceId = validation.deviceId;
                socket.authenticatedDeviceData = validation.deviceData;
                return next();
            } catch (error) {
                return next(this.createError(
                    'Socket authentication failed',
                    'WS_AUTH_ERROR'
                ));
            }
        };
    }

    extractCredential(handshake = {}) {
        const authToken = handshake?.auth?.token;
        if (typeof authToken === 'string' && authToken.trim()) {
            return { token: authToken.trim(), source: 'auth' };
        }

        const authAuthorization = handshake?.auth?.authorization;
        const headerAuthorization = handshake?.headers?.authorization;
        const authorization = authAuthorization || headerAuthorization;
        if (typeof authorization !== 'string' || !authorization.trim()) {
            return null;
        }

        const parts = authorization.trim().split(/\s+/);
        if (parts.length !== 2 || parts[0].toLowerCase() !== 'bearer') {
            return { token: '', source: 'authorization' };
        }

        return { token: parts[1], source: 'authorization' };
    }

    handleInvalidCredential(socket, next, message, code) {
        if (this.required) {
            return next(this.createError(message, code));
        }

        socket.authMode = 'legacy';
        socket.authFailureCode = code;
        return next();
    }

    createError(message, code) {
        const error = new Error(message);
        error.data = { code };
        return error;
    }
}

module.exports = SocketAuthMiddleware;
