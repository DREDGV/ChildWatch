const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');

function findFreePort() {
    return new Promise((resolve, reject) => {
        const probe = net.createServer();
        probe.unref();
        probe.on('error', reject);
        probe.listen(0, '127.0.0.1', () => {
            const port = probe.address().port;
            probe.close(() => resolve(port));
        });
    });
}

function requestJson(port, method, requestPath, body, token) {
    return new Promise((resolve, reject) => {
        const payload = body ? JSON.stringify(body) : null;
        const headers = {};
        if (payload) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(payload);
        }
        if (token) {
            headers.Authorization = `Bearer ${token}`;
        }

        const request = http.request({
            hostname: '127.0.0.1',
            port,
            path: requestPath,
            method,
            headers,
            timeout: 2000
        }, (response) => {
            let responseText = '';
            response.setEncoding('utf8');
            response.on('data', (chunk) => {
                responseText += chunk;
            });
            response.on('end', () => {
                let parsed = {};
                try {
                    parsed = responseText ? JSON.parse(responseText) : {};
                } catch (error) {
                    return reject(error);
                }
                resolve({ statusCode: response.statusCode, body: parsed });
            });
        });

        request.on('timeout', () => request.destroy(new Error('Request timed out')));
        request.on('error', reject);
        if (payload) request.write(payload);
        request.end();
    });
}

async function waitUntilReady(port) {
    const deadline = Date.now() + 10000;
    let lastError;
    while (Date.now() < deadline) {
        try {
            const response = await requestJson(port, 'GET', '/api/health');
            if (response.statusCode === 200) return;
        } catch (error) {
            lastError = error;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw lastError || new Error('Server did not become ready');
}

function startServer(port, sessionStorePath) {
    return spawn(process.execPath, ['index.js'], {
        cwd: path.join(__dirname, '..'),
        env: {
            ...process.env,
            NODE_ENV: 'production',
            PORT: String(port),
            CW_DB_PATH: ':memory:',
            CW_AUTH_SESSION_PATH: sessionStorePath,
            CW_REQUIRE_WS_AUTH: '0'
        },
        stdio: 'ignore',
        windowsHide: true
    });
}

function stopServer(child) {
    return new Promise((resolve) => {
        if (!child || child.exitCode !== null) {
            resolve();
            return;
        }

        const timeout = setTimeout(resolve, 3000);
        timeout.unref?.();
        child.once('exit', () => {
            clearTimeout(timeout);
            resolve();
        });
        child.kill();
    });
}

describe('server process restart recovery', () => {
    let testDirectory;
    let sessionStorePath;
    let serverProcess;

    beforeEach(() => {
        testDirectory = fs.mkdtempSync(
            path.join(os.tmpdir(), 'childwatch-server-restart-')
        );
        sessionStorePath = path.join(testDirectory, 'auth-sessions.json');
    });

    afterEach(async () => {
        await stopServer(serverProcess);
        fs.rmSync(testDirectory, { recursive: true, force: true });
    });

    test('keeps a registered phone authorized across a full process restart', async () => {
        const port = await findFreePort();
        serverProcess = startServer(port, sessionStorePath);
        await waitUntilReady(port);

        const registration = await requestJson(
            port,
            'POST',
            '/api/auth/register',
            {
                deviceId: 'restart-integration-device-001',
                deviceName: 'Restart integration phone',
                deviceType: 'android',
                appVersion: '7.1.0'
            }
        );
        expect(registration.statusCode).toBe(200);
        expect(registration.body.authToken).toMatch(/^[a-f0-9]{64}$/);

        const token = registration.body.authToken;
        await stopServer(serverProcess);
        serverProcess = startServer(port, sessionStorePath);
        await waitUntilReady(port);

        const validation = await requestJson(
            port,
            'GET',
            '/api/auth/validate',
            null,
            token
        );

        expect(validation.statusCode).toBe(200);
        expect(validation.body.valid).toBe(true);
        expect(validation.body.deviceId).toBe('restart-integration-device-001');
    }, 20000);
});
