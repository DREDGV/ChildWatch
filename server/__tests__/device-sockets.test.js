const WebSocketManager = require("../managers/WebSocketManager");

function createSocket(id, authenticatedDeviceId = "") {
  return {
    id,
    connected: true,
    authenticatedDeviceId,
    emit: jest.fn(),
  };
}

function createManager() {
  const sockets = new Map();
  const io = { sockets: { sockets } };
  return { manager: new WebSocketManager(io), sockets };
}

describe("exact device socket registry", () => {
  let logSpy;
  let warnSpy;

  beforeEach(() => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    logSpy.mockRestore();
    warnSpy.mockRestore();
  });

  test("routes to every live socket for the exact device only", () => {
    const { manager, sockets } = createManager();
    const first = createSocket("target-1");
    const second = createSocket("target-2");
    const other = createSocket("other-1");
    [first, second, other].forEach((socket) => sockets.set(socket.id, socket));
    manager.registerDeviceSocket(first, "device-target");
    manager.registerDeviceSocket(second, "device-target");
    manager.registerDeviceSocket(other, "device-other");

    const delivered = manager.emitToExactDevice("device-target", "attention", {
      message: "hello",
    });

    expect(delivered).toBe(2);
    expect(first.emit).toHaveBeenCalledWith("attention", { message: "hello" });
    expect(second.emit).toHaveBeenCalledWith("attention", { message: "hello" });
    expect(other.emit).not.toHaveBeenCalled();
  });

  test("never falls back to the only other connected device", () => {
    const { manager, sockets } = createManager();
    const other = createSocket("other-1");
    sockets.set(other.id, other);
    manager.registerDeviceSocket(other, "device-other");

    expect(
      manager.emitToExactDevice("missing-device", "attention", { value: 1 })
    ).toBe(0);
    expect(other.emit).not.toHaveBeenCalled();
  });

  test("stale reconnect cleanup preserves the newest device socket", async () => {
    const { manager, sockets } = createManager();
    const oldSocket = createSocket("child-old", "child-device");
    const newSocket = createSocket("child-new", "child-device");
    sockets.set(oldSocket.id, oldSocket);
    sockets.set(newSocket.id, newSocket);

    await manager.handleChildRegistration(oldSocket, {
      deviceId: "child-device",
    });
    await manager.handleChildRegistration(newSocket, {
      deviceId: "child-device",
    });
    manager.handleDisconnect(oldSocket);

    expect(manager.getConnectedSocketIdsForDevice("child-device")).toEqual([
      newSocket.id,
    ]);
    expect(manager.childSockets.get("child-device")).toBe(newSocket.id);
  });

  test("registers authenticated IDs for child and parent roles", async () => {
    const { manager, sockets } = createManager();
    const child = createSocket("child", "child-real-device");
    const parent = createSocket("parent", "parent-real-device");
    sockets.set(child.id, child);
    sockets.set(parent.id, parent);

    await manager.handleChildRegistration(child, {
      deviceId: "child-real-device",
    });
    await manager.handleParentRegistration(parent, {
      deviceId: "child-real-device",
      parentId: "parent-real-device",
    });

    expect(manager.getConnectedSocketIdsForDevice("child-real-device")).toEqual([
      child.id,
    ]);
    expect(manager.getConnectedSocketIdsForDevice("parent-real-device")).toEqual([
      parent.id,
    ]);
  });
});
