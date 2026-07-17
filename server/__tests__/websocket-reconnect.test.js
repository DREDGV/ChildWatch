const WebSocketManager = require('../managers/WebSocketManager');

function createSocket(id) {
    return {
        id,
        connected: true,
        emit: jest.fn(),
    };
}

function createManager() {
    const sockets = new Map();
    const io = { sockets: { sockets } };
    return {
        manager: new WebSocketManager(io),
        sockets,
    };
}

describe('WebSocket child reconnect routing', () => {
    let logSpy;
    let warnSpy;

    beforeEach(() => {
        logSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
        warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        logSpy.mockRestore();
        warnSpy.mockRestore();
    });

    test('old child disconnect does not remove replacement socket', async () => {
        const { manager, sockets } = createManager();
        const oldChild = createSocket('child-old');
        const newChild = createSocket('child-new');
        const parent = createSocket('parent-1');
        sockets.set(oldChild.id, oldChild);
        sockets.set(newChild.id, newChild);
        sockets.set(parent.id, parent);
        manager.parentSockets.set(parent.id, 'child-device');

        await manager.handleChildRegistration(oldChild, { deviceId: 'child-device' });
        await manager.handleChildRegistration(newChild, { deviceId: 'child-device' });
        oldChild.emit.mockClear();
        newChild.emit.mockClear();
        parent.emit.mockClear();

        manager.handleDisconnect(oldChild);

        expect(manager.childSockets.get('child-device')).toBe(newChild.id);
        expect(manager.isChildConnectedById('child-device')).toBe(true);
        expect(parent.emit).not.toHaveBeenCalledWith(
            'child_disconnected',
            expect.any(Object)
        );

        const command = { type: 'start_audio_stream', data: {} };
        expect(manager.sendCommandToChild('child-device', command)).toBe(true);
        expect(newChild.emit).toHaveBeenCalledWith('command', command);
    });

    test('current child disconnect removes mapping and notifies parent', async () => {
        const { manager, sockets } = createManager();
        const child = createSocket('child-current');
        const parent = createSocket('parent-1');
        sockets.set(child.id, child);
        sockets.set(parent.id, parent);
        manager.parentSockets.set(parent.id, 'child-device');

        await manager.handleChildRegistration(child, { deviceId: 'child-device' });
        parent.emit.mockClear();

        manager.handleDisconnect(child);

        expect(manager.childSockets.has('child-device')).toBe(false);
        expect(parent.emit).toHaveBeenCalledTimes(1);
        expect(parent.emit).toHaveBeenCalledWith(
            'child_disconnected',
            expect.objectContaining({ deviceId: 'child-device' })
        );
    });

    test('multiple stale disconnects preserve the newest socket', async () => {
        const { manager, sockets } = createManager();
        const first = createSocket('child-first');
        const second = createSocket('child-second');
        const newest = createSocket('child-newest');
        [first, second, newest].forEach((socket) => sockets.set(socket.id, socket));

        await manager.handleChildRegistration(first, { deviceId: 'child-device' });
        await manager.handleChildRegistration(second, { deviceId: 'child-device' });
        await manager.handleChildRegistration(newest, { deviceId: 'child-device' });

        manager.handleDisconnect(first);
        manager.handleDisconnect(second);

        expect(manager.childSockets.get('child-device')).toBe(newest.id);
        expect(manager.isChildConnectedById('child-device')).toBe(true);
    });

    test('repeated registration of the same socket still disconnects normally', async () => {
        const { manager, sockets } = createManager();
        const child = createSocket('child-current');
        sockets.set(child.id, child);

        await manager.handleChildRegistration(child, { deviceId: 'child-device' });
        await manager.handleChildRegistration(child, { deviceId: 'child-device' });
        manager.handleDisconnect(child);

        expect(manager.childSockets.has('child-device')).toBe(false);
    });
});
