const fs = require('fs');
const os = require('os');
const path = require('path');
const AuthManager = require('../auth/AuthManager');

describe('persistent authentication sessions', () => {
    let testDirectory;
    let sessionStorePath;
    let logSpy;
    let warnSpy;
    const managers = [];

    function createManager() {
        const manager = new AuthManager({
            persistenceEnabled: true,
            sessionStorePath
        });
        managers.push(manager);
        return manager;
    }

    beforeEach(() => {
        testDirectory = fs.mkdtempSync(
            path.join(os.tmpdir(), 'childwatch-auth-')
        );
        sessionStorePath = path.join(testDirectory, 'auth-sessions.json');
        logSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
        warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        managers.splice(0).forEach((manager) => manager.dispose());
        logSpy.mockRestore();
        warnSpy.mockRestore();
        fs.rmSync(testDirectory, { recursive: true, force: true });
    });

    test('recognizes an existing access token after a server restart', () => {
        const firstServer = createManager();
        const registration = firstServer.registerDevice({
            deviceId: 'persistent-device-001',
            deviceName: 'Persistent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        firstServer.dispose();

        const secondServer = createManager();
        const validation = secondServer.validateToken(registration.authToken);

        expect(validation.valid).toBe(true);
        expect(validation.deviceId).toBe('persistent-device-001');
    });

    test('persists only token hashes, never raw credentials', () => {
        const manager = createManager();
        const registration = manager.registerDevice({
            deviceId: 'persistent-device-002',
            deviceName: 'Persistent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });

        const storedText = fs.readFileSync(sessionStorePath, 'utf8');
        const stored = JSON.parse(storedText);

        expect(storedText).not.toContain(registration.authToken);
        expect(storedText).not.toContain(registration.refreshToken);
        expect(stored.sessions[0].authTokenHash).toMatch(/^[a-f0-9]{64}$/);
        expect(stored.sessions[0].refreshTokenHash).toMatch(/^[a-f0-9]{64}$/);
    });

    test('refreshes credentials after restart and persists the rotation', () => {
        const firstServer = createManager();
        const registration = firstServer.registerDevice({
            deviceId: 'persistent-device-003',
            deviceName: 'Persistent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        firstServer.dispose();

        const secondServer = createManager();
        const refreshed = secondServer.refreshToken(
            registration.refreshToken,
            'persistent-device-003'
        );
        expect(refreshed.success).toBe(true);
        secondServer.dispose();

        const thirdServer = createManager();
        expect(thirdServer.validateToken(registration.authToken).valid).toBe(false);
        expect(thirdServer.validateToken(refreshed.authToken).valid).toBe(true);
    });

    test('keeps refresh recovery when the access token expired offline', () => {
        const firstServer = createManager();
        const registration = firstServer.registerDevice({
            deviceId: 'persistent-device-004',
            deviceName: 'Persistent phone',
            deviceType: 'android',
            appVersion: '7.1.0'
        });
        firstServer.dispose();

        const stored = JSON.parse(fs.readFileSync(sessionStorePath, 'utf8'));
        stored.sessions[0].expiresAt = Date.now() - 1000;
        fs.writeFileSync(sessionStorePath, JSON.stringify(stored), 'utf8');

        const secondServer = createManager();
        expect(secondServer.validateToken(registration.authToken).valid).toBe(false);
        expect(
            secondServer.refreshToken(
                registration.refreshToken,
                'persistent-device-004'
            ).success
        ).toBe(true);
    });

    test('starts with an empty cache when the session file is damaged', () => {
        fs.writeFileSync(sessionStorePath, '{not-json', 'utf8');

        const manager = createManager();

        expect(manager.getAuthStats().totalDevices).toBe(0);
        expect(warnSpy).toHaveBeenCalledWith(
            'Could not restore authentication sessions:',
            expect.any(String)
        );
    });
});
