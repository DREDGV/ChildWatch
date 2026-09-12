const fs = require("fs");
const path = require("path");
const WebSocketManager = require("../managers/WebSocketManager");

/**
 * Regression guard for the remote-photo request deadline.
 *
 * The server used to expire a photo request after 25 seconds while the parent
 * client kept waiting (and even restarted its own timer on every child
 * acknowledgement). The server therefore abandoned requests the parent was
 * legitimately waiting for, and a capture finishing after that deadline was
 * received and then discarded by the UI.
 *
 * These tests pin the relationship instead of one magic number, so the two
 * sides cannot drift apart silently again.
 */
describe("photo request deadline", () => {
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

  /**
   * Minimal Socket.IO double. Pass real socket objects so the registry holds
   * the very objects the assertions inspect.
   */
  function createFakeIo(sockets = []) {
    const map = new Map();
    for (const socket of sockets) {
      map.set(socket.id, socket);
    }
    return { sockets: { sockets: map } };
  }

  /** Reads the parent client's constants so both sides stay comparable. */
  function readParentPhotoTimeouts() {
    const source = fs.readFileSync(
      path.join(
        __dirname,
        "..",
        "..",
        "app",
        "src",
        "main",
        "java",
        "ru",
        "example",
        "childwatch",
        "RemoteCameraActivity.kt"
      ),
      "utf8"
    );
    const read = (name) => {
      const match = source.match(new RegExp(`${name}\\s*=\\s*([0-9_]+)L`));
      if (!match) {
        throw new Error(`RemoteCameraActivity no longer declares ${name}`);
      }
      return Number(match[1].replace(/_/g, ""));
    };
    return {
      responseTimeoutMs: read("PHOTO_RESPONSE_TIMEOUT_MS"),
      lateDeliveryGraceMs: read("PHOTO_LATE_DELIVERY_GRACE_MS"),
    };
  }

  test("the server deadline leaves room for a real capture on a slow device", () => {
    const manager = new WebSocketManager(createFakeIo());
    // A capture chain on the project's Moto G 5 Plus routinely needs tens of
    // seconds: service start, Camera2 init, capture, JPEG encode, base64.
    expect(manager.PHOTO_REQUEST_TTL_MS).toBeGreaterThanOrEqual(60_000);
  });

  test("the parent waits longer than the server deadline", () => {
    const manager = new WebSocketManager(createFakeIo());
    const { responseTimeoutMs } = readParentPhotoTimeouts();

    expect(responseTimeoutMs).toBeGreaterThan(manager.PHOTO_REQUEST_TTL_MS);
  });

  test("the late-delivery window covers the server deadline", () => {
    const manager = new WebSocketManager(createFakeIo());
    const { lateDeliveryGraceMs } = readParentPhotoTimeouts();

    // A photo that arrives after the UI timeout must still be usable.
    expect(lateDeliveryGraceMs).toBeGreaterThanOrEqual(manager.PHOTO_REQUEST_TTL_MS);
  });

  test("acknowledgement re-arms the deadline instead of leaving it to expire", () => {
    const requestId = "req-rearm";
    const deviceId = "child-device";
    const parentSocket = {
      id: "parent-socket",
      connected: true,
      deviceId: "parent-device",
      emitted: [],
      emit(event, payload) {
        this.emitted.push({ event, payload });
      },
    };
    const childSocket = {
      id: "child-socket",
      connected: true,
      deviceId,
      emitted: [],
      emit(event, payload) {
        this.emitted.push({ event, payload });
      },
    };
    const io = createFakeIo([parentSocket, childSocket]);
    const manager = new WebSocketManager(io);

    manager.pendingPhotoRequests.set(requestId, {
      parentSocketId: parentSocket.id,
      childSocketId: childSocket.id,
      deviceId,
      createdAt: Date.now() - 80_000,
    });
    manager.activePhotoRequests.set(deviceId, {
      requestId,
      deviceId,
      createdAt: Date.now() - 80_000,
    });
    manager.schedulePhotoRequestExpiry(requestId);

    const armedBefore = manager.pendingPhotoRequests.get(requestId).expiresAt;
    const timerBefore = manager.pendingPhotoRequests.get(requestId).expiryTimer;

    manager.handlePhotoRequestReceived(childSocket, { requestId });

    const rearmed = manager.pendingPhotoRequests.get(requestId);
    // The deadline is recomputed from "now" when the child confirms it started,
    // so it can only move forward, and a fresh timer replaces the old one.
    expect(rearmed.expiresAt).toBeGreaterThanOrEqual(armedBefore);
    expect(rearmed.expiryTimer).not.toBe(timerBefore);
    expect(parentSocket.emitted.map((entry) => entry.event)).toContain(
      "photo_request_received"
    );
  });

  test("completing a request clears its expiry timer", () => {
    const io = createFakeIo([{ id: "parent-socket" }]);
    const manager = new WebSocketManager(io);
    const requestId = "req-complete";
    manager.pendingPhotoRequests.set(requestId, {
      parentSocketId: "parent-socket",
      deviceId: "child-device",
      createdAt: Date.now(),
    });
    manager.schedulePhotoRequestExpiry(requestId);
    const timer = manager.pendingPhotoRequests.get(requestId).expiryTimer;
    expect(timer).toBeDefined();

    manager.completePhotoRequest(requestId);

    expect(manager.pendingPhotoRequests.has(requestId)).toBe(false);
    expect(manager.activePhotoRequests.has("child-device")).toBe(false);
  });
});
