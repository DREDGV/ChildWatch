const AuthManager = require('../auth/AuthManager');
const AuthMiddleware = require('../middleware/AuthMiddleware');

function createResponse() {
    return {
        statusCode: 200,
        body: null,
        status: jest.fn(function status(code) {
            this.statusCode = code;
            return this;
        }),
        json: jest.fn(function json(body) {
            this.body = body;
            return this;
        })
    };
}

describe('server authentication', () => {
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

    test('registered access token is accepted by AuthManager', () => {
        const manager = new AuthManager();
        const registration = manager.registerDevice({
            deviceId: 'parent-device-001',
            deviceName: 'Parent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });

        expect(registration.success).toBe(true);
        expect(registration.authToken).toMatch(/^[a-f0-9]{64}$/);
        expect(registration.refreshToken).toMatch(/^[a-f0-9]{64}$/);

        const validation = manager.validateToken(registration.authToken);
        expect(validation.valid).toBe(true);
        expect(validation.deviceId).toBe('parent-device-001');
    });

    test('refresh rotates both credentials and invalidates the old access token', () => {
        const manager = new AuthManager();
        const registration = manager.registerDevice({
            deviceId: 'child-device-001',
            deviceName: 'Child phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });

        const refreshed = manager.refreshToken(
            registration.refreshToken,
            'child-device-001'
        );

        expect(refreshed.success).toBe(true);
        expect(refreshed.authToken).not.toBe(registration.authToken);
        expect(refreshed.refreshToken).not.toBe(registration.refreshToken);
        expect(manager.validateToken(registration.authToken).valid).toBe(false);
        expect(manager.validateToken(refreshed.authToken).valid).toBe(true);
    });

    test('middleware accepts the same token returned during registration', () => {
        const manager = new AuthManager();
        const middleware = new AuthMiddleware(manager).authenticate();
        const registration = manager.registerDevice({
            deviceId: 'parent-device-002',
            deviceName: 'Parent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        const req = {
            headers: { authorization: `Bearer ${registration.authToken}` }
        };
        const res = createResponse();
        const next = jest.fn();

        middleware(req, res, next);

        expect(next).toHaveBeenCalledTimes(1);
        expect(req.deviceId).toBe('parent-device-002');
        expect(req.deviceData.authToken).toBe(registration.authToken);
        expect(res.json).not.toHaveBeenCalled();
    });

    test.each([
        [undefined, 'MISSING_TOKEN'],
        ['Basic ' + 'a'.repeat(64), 'INVALID_TOKEN_FORMAT'],
        ['Bearer not-a-token', 'INVALID_TOKEN_FORMAT'],
        ['Bearer ' + 'a'.repeat(64) + ' extra', 'INVALID_TOKEN_FORMAT']
    ])('middleware rejects invalid authorization header %p', (authorization, code) => {
        const manager = new AuthManager();
        const middleware = new AuthMiddleware(manager).authenticate();
        const req = { headers: {} };
        if (authorization !== undefined) {
            req.headers.authorization = authorization;
        }
        const res = createResponse();
        const next = jest.fn();

        middleware(req, res, next);

        expect(next).not.toHaveBeenCalled();
        expect(res.statusCode).toBe(401);
        expect(res.body.code).toBe(code);
    });
});
