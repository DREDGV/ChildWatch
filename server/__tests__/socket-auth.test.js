const AuthManager = require('../auth/AuthManager');
const SocketAuthMiddleware = require('../middleware/SocketAuthMiddleware');

function createSocket(handshake = {}) {
    return { handshake };
}

describe('Socket.IO authentication', () => {
    let logSpy;

    beforeEach(() => {
        jest.useFakeTimers();
        logSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
    });

    afterEach(() => {
        logSpy.mockRestore();
        jest.clearAllTimers();
        jest.useRealTimers();
    });

    test('accepts and attaches identity for a valid handshake token', () => {
        const manager = new AuthManager();
        const registration = manager.registerDevice({
            deviceId: 'parent-device-ws-001',
            deviceName: 'Parent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        const socket = createSocket({ auth: { token: registration.authToken } });
        const next = jest.fn();

        new SocketAuthMiddleware(manager, { required: true })
            .authenticate()(socket, next);

        expect(next).toHaveBeenCalledWith();
        expect(socket.authMode).toBe('authenticated');
        expect(socket.authenticatedDeviceId).toBe('parent-device-ws-001');
        expect(socket.authenticatedDeviceData.authToken).toBe(registration.authToken);
    });

    test('accepts a standard Bearer authorization handshake value', () => {
        const manager = new AuthManager();
        const registration = manager.registerDevice({
            deviceId: 'child-device-ws-001',
            deviceName: 'Child phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        const socket = createSocket({
            auth: { authorization: `Bearer ${registration.authToken}` }
        });
        const next = jest.fn();

        new SocketAuthMiddleware(manager, { required: true })
            .authenticate()(socket, next);

        expect(next).toHaveBeenCalledWith();
        expect(socket.authenticatedDeviceId).toBe('child-device-ws-001');
    });

    test('allows a missing token only in compatibility mode', () => {
        const manager = new AuthManager();
        const socket = createSocket();
        const next = jest.fn();

        new SocketAuthMiddleware(manager, { required: false })
            .authenticate()(socket, next);

        expect(next).toHaveBeenCalledWith();
        expect(socket.authMode).toBe('legacy');
        expect(socket.authenticatedDeviceId).toBeNull();
    });

    test('reports invalid provided tokens without disconnecting compatibility clients', () => {
        const manager = new AuthManager();
        const socket = createSocket({ auth: { token: 'a'.repeat(64) } });
        const next = jest.fn();

        new SocketAuthMiddleware(manager, { required: false })
            .authenticate()(socket, next);

        expect(next).toHaveBeenCalledWith();
        expect(socket.authMode).toBe('legacy');
        expect(socket.authFailureCode).toBe('WS_INVALID_TOKEN');
    });

    test.each([
        [createSocket(), 'WS_MISSING_TOKEN'],
        [createSocket({ auth: { token: 'short' } }), 'WS_INVALID_TOKEN_FORMAT'],
        [createSocket({ auth: { token: 'a'.repeat(64) } }), 'WS_INVALID_TOKEN']
    ])('rejects unauthorized socket in required mode', (socket, expectedCode) => {
        const manager = new AuthManager();
        const next = jest.fn();

        new SocketAuthMiddleware(manager, { required: true })
            .authenticate()(socket, next);

        const error = next.mock.calls[0][0];
        expect(error).toBeInstanceOf(Error);
        expect(error.data.code).toBe(expectedCode);
        expect(socket.authenticatedDeviceId).toBeNull();
    });
});
