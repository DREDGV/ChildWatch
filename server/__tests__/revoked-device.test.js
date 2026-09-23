const DatabaseManager = require("../database/DatabaseManager");
const WebSocketManager = require("../managers/WebSocketManager");

/**
 * A revoked device must not be able to come back in.
 *
 * Two separate doors had to be closed. The registration route used to hand a
 * fresh token to any device that asked, and the socket handshake keeps a
 * compatibility mode that accepts a connection whose token no longer validates.
 * A phone whose access was taken away walked through both within a minute.
 */
describe("a device whose access was revoked", () => {
  const revokedId = "device_15e991bb5d8f906d";
  const revokedBare = "15e991bb5d8f906d";
  const activeId = "device_bac59e4ce151f3b0";
  const childId = "child-c7d50e08";

  let db;
  let manager;
  let logSpy;
  let warnSpy;

  function createSocket(id) {
    return {
      id,
      connected: true,
      authenticatedDeviceId: "",
      emit: jest.fn(),
      disconnect: jest.fn(),
    };
  }

  async function register(deviceId, isActive = 1) {
    await db.registerDevice(deviceId, {
      device_name: "Phone",
      device_type: "android",
      app_version: "7.3.0",
    });
    if (isActive !== 1) {
      await db.run("UPDATE devices SET is_active = ? WHERE device_id = ?", [
        isActive,
        deviceId,
      ]);
    }
  }

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    manager = new WebSocketManager(
      { sockets: { sockets: new Map() } },
      null,
      db
    );
  });

  afterEach(async () => {
    logSpy.mockRestore();
    warnSpy.mockRestore();
    await db.close();
  });

  test("cannot register as a parent over the socket", async () => {
    await register(revokedId, 0);
    const socket = createSocket("socket-1");

    await manager.handleParentRegistration(socket, {
      deviceId: childId,
      parentId: revokedId,
    });

    expect(socket.emit).toHaveBeenCalledWith(
      "registration_error",
      expect.objectContaining({ code: "DEVICE_REVOKED" })
    );
    expect(socket.disconnect).toHaveBeenCalledWith(true);
    expect(manager.parentSockets.has(socket.id)).toBe(false);
  });

  test("is refused under either spelling of its identifier", async () => {
    // The two spellings describe one phone; clearing only one would leave a way in.
    await register(revokedId, 0);
    expect(await manager.isRevokedDevice(revokedId)).toBe(true);
    expect(await manager.isRevokedDevice(revokedBare)).toBe(true);
  });

  test("cannot register as a child over the socket", async () => {
    await register(revokedId, 0);
    const socket = createSocket("socket-2");

    await manager.handleChildRegistration(socket, { deviceId: revokedId });

    expect(socket.emit).toHaveBeenCalledWith(
      "registration_error",
      expect.objectContaining({ code: "DEVICE_REVOKED" })
    );
    expect(socket.disconnect).toHaveBeenCalledWith(true);
  });

  test("leaves an active device alone", async () => {
    await register(activeId);
    const socket = createSocket("socket-3");

    expect(await manager.isRevokedDevice(activeId)).toBe(false);

    await manager.handleParentRegistration(socket, {
      deviceId: childId,
      parentId: activeId,
    });

    expect(socket.disconnect).not.toHaveBeenCalled();
    expect(manager.parentSockets.get(socket.id)).toBe(childId);
  });
});
