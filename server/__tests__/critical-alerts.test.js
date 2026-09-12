const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const alertsRoutes = require("../routes/alerts");
const WebSocketManager = require("../managers/WebSocketManager");

/**
 * Minimal Socket.IO double: the exact-device registry looks socket ids up in
 * `io.sockets.sockets` and skips anything that is not `connected`.
 */
function createFakeIo(socketEntries = []) {
  const sockets = new Map();
  for (const entry of socketEntries) {
    sockets.set(entry.id, {
      id: entry.id,
      connected: entry.connected !== false,
      emitted: [],
      emit(event, payload) {
        this.emitted.push({ event, payload });
      },
    });
  }
  return { sockets: { sockets } };
}

function requestJson(server, path, deviceId, { method = "GET", body = null } = {}) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path,
        method,
        headers: {
          ...(deviceId ? { "x-test-device-id": deviceId } : {}),
          ...(body ? { "content-type": "application/json" } : {}),
        },
      },
      (response) => {
        let raw = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          raw += chunk;
        });
        response.on("end", () => {
          resolve({ status: response.statusCode, body: JSON.parse(raw) });
        });
      }
    );
    request.on("error", reject);
    request.end(body ? JSON.stringify(body) : undefined);
  });
}

/**
 * Stands in for WebSocketManager. `emitCriticalAlert` mirrors the real
 * contract: it reports true only when the exact device has a live socket.
 */
function createFakeWsManager(onlineDeviceIds = []) {
  const online = new Set(onlineDeviceIds);
  const emitted = [];
  return {
    emitted,
    emitCriticalAlert(deviceId, payload) {
      emitted.push({ deviceId, event: "critical_alert", payload });
      return online.has(deviceId);
    },
  };
}

async function createHarness(onlineDeviceIds) {
  const db = new DatabaseManager(":memory:");
  await db.initialize();
  const wsManager = createFakeWsManager(onlineDeviceIds);
  // `routes/alerts.js` exports the router itself and receives its dependencies
  // through init(), exactly as index.js wires it.
  alertsRoutes.init(db, wsManager);

  const app = express();
  app.use(express.json());
  app.use((req, res, next) => {
    if (req.headers["x-test-device-id"]) {
      req.deviceId = req.headers["x-test-device-id"];
    }
    next();
  });
  app.use("/api/alerts", alertsRoutes);
  app.use((error, req, res, next) => {
    res.status(500).json({ error: error.message });
  });

  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { db, wsManager, server };
}

async function closeHarness(harness) {
  await new Promise((resolve) => harness.server.close(resolve));
  await harness.db.close();
}

const parentDeviceId = "parent-alert-device";
const childDeviceId = "child-alert-device";
const strangerDeviceId = "stranger-alert-device";

describe("critical alerts", () => {
  let logSpy;
  let errorSpy;

  beforeEach(() => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("reports delivery when the alert subject has a live socket", async () => {
    const harness = await createHarness([childDeviceId]);
    try {
      await harness.db.registerDevice(parentDeviceId, {
        device_name: "Parent",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.registerDevice(childDeviceId, {
        device_name: "Child",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.upsertDeviceLink({ parentDeviceId, childDeviceId });

      const response = await requestJson(harness.server, "/api/alerts", childDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      expect(response.status).toBe(200);
      expect(response.body).toMatchObject({ success: true, delivered: true });
      expect(harness.wsManager.emitted).toHaveLength(1);
      expect(harness.wsManager.emitted[0].deviceId).toBe(childDeviceId);
      expect(harness.wsManager.emitted[0].payload).toMatchObject({
        deviceId: childDeviceId,
        eventType: "LOW_BATTERY",
        severity: "CRITICAL",
      });
    } finally {
      await closeHarness(harness);
    }
  });

  test("stores the alert but reports no delivery when the device is offline", async () => {
    const harness = await createHarness([]);
    try {
      // critical_alerts references devices(id), so the subject must be known.
      await harness.db.registerDevice(childDeviceId, {
        device_name: "Child",
        device_type: "android",
        app_version: "7.3.0",
      });

      const response = await requestJson(harness.server, "/api/alerts", childDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      expect(response.status).toBe(200);
      expect(response.body).toMatchObject({ success: true, delivered: false });

      const pending = await requestJson(
        harness.server,
        `/api/alerts/pending/${childDeviceId}`,
        childDeviceId
      );
      expect(pending.body.count).toBe(1);
    } finally {
      await closeHarness(harness);
    }
  });

  test("never delivers an alert to a different device", async () => {
    const harness = await createHarness([parentDeviceId]);
    try {
      await harness.db.registerDevice(parentDeviceId, {
        device_name: "Parent",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.registerDevice(childDeviceId, {
        device_name: "Child",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.upsertDeviceLink({ parentDeviceId, childDeviceId });

      const response = await requestJson(harness.server, "/api/alerts", childDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      expect(response.body.delivered).toBe(false);
      expect(harness.wsManager.emitted[0].deviceId).toBe(childDeviceId);
    } finally {
      await closeHarness(harness);
    }
  });

  test("lets a linked parent read and acknowledge the child alerts", async () => {
    const harness = await createHarness([]);
    try {
      await harness.db.registerDevice(parentDeviceId, {
        device_name: "Parent",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.registerDevice(childDeviceId, {
        device_name: "Child",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.upsertDeviceLink({ parentDeviceId, childDeviceId });

      await requestJson(harness.server, "/api/alerts", childDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      const pending = await requestJson(
        harness.server,
        `/api/alerts/pending/${childDeviceId}`,
        parentDeviceId
      );
      expect(pending.status).toBe(200);
      expect(pending.body.count).toBe(1);

      const ack = await requestJson(harness.server, "/api/alerts/ack", parentDeviceId, {
        method: "POST",
        body: { deviceId: childDeviceId, alertIds: pending.body.alerts.map((a) => a.id) },
      });
      expect(ack).toMatchObject({ status: 200, body: { success: true } });
    } finally {
      await closeHarness(harness);
    }
  });

  test("denies an unrelated device access to another device alerts", async () => {
    const harness = await createHarness([]);
    try {
      await harness.db.registerDevice(parentDeviceId, {
        device_name: "Parent",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.registerDevice(childDeviceId, {
        device_name: "Child",
        device_type: "android",
        app_version: "7.3.0",
      });
      await harness.db.upsertDeviceLink({ parentDeviceId, childDeviceId });

      await requestJson(harness.server, "/api/alerts", childDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      const read = await requestJson(
        harness.server,
        `/api/alerts/pending/${childDeviceId}`,
        strangerDeviceId
      );
      expect(read).toEqual({
        status: 403,
        body: { error: "Alert access denied", code: "ALERT_ACCESS_DENIED" },
      });

      const ack = await requestJson(harness.server, "/api/alerts/ack", strangerDeviceId, {
        method: "POST",
        body: { deviceId: childDeviceId, alertIds: [1] },
      });
      expect(ack.status).toBe(403);

      const spoofed = await requestJson(harness.server, "/api/alerts", strangerDeviceId, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Spoofed alert",
        },
      });
      expect(spoofed.status).toBe(403);
    } finally {
      await closeHarness(harness);
    }
  });

  test("fails closed when the request carries no authenticated device", async () => {
    const harness = await createHarness([]);
    try {
      const response = await requestJson(harness.server, "/api/alerts", null, {
        method: "POST",
        body: {
          deviceId: childDeviceId,
          eventType: "LOW_BATTERY",
          severity: "CRITICAL",
          message: "Battery is critically low",
        },
      });

      expect(response).toEqual({
        status: 401,
        body: { error: "Authentication required", code: "AUTH_REQUIRED" },
      });

      const pending = await requestJson(
        harness.server,
        `/api/alerts/pending/${childDeviceId}`,
        null
      );
      expect(pending.status).toBe(401);
    } finally {
      await closeHarness(harness);
    }
  });
});

describe("WebSocketManager.emitCriticalAlert", () => {
  let logSpy;

  beforeEach(() => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
  });

  afterEach(() => {
    logSpy.mockRestore();
  });

  test("returns true and emits only to the exact device socket", () => {
    const io = createFakeIo([
      { id: "socket-child" },
      { id: "socket-stranger" },
    ]);
    const manager = new WebSocketManager(io);
    manager.deviceSockets.set(childDeviceId, new Set(["socket-child"]));
    manager.deviceSockets.set(strangerDeviceId, new Set(["socket-stranger"]));

    const delivered = manager.emitCriticalAlert(childDeviceId, { id: 1 });

    expect(delivered).toBe(true);
    const childSocket = io.sockets.sockets.get("socket-child");
    const strangerSocket = io.sockets.sockets.get("socket-stranger");
    expect(childSocket.emitted).toEqual([
      { event: "critical_alert", payload: { id: 1 } },
    ]);
    // The alert of one phone must never surface on another connected phone.
    expect(strangerSocket.emitted).toHaveLength(0);
  });

  test("returns false when the device has no live socket", () => {
    const io = createFakeIo([{ id: "socket-stranger" }]);
    const manager = new WebSocketManager(io);
    manager.deviceSockets.set(strangerDeviceId, new Set(["socket-stranger"]));

    expect(manager.emitCriticalAlert(childDeviceId, { id: 2 })).toBe(false);
    expect(io.sockets.sockets.get("socket-stranger").emitted).toHaveLength(0);
  });

  test("returns false when the registered socket is disconnected", () => {
    const io = createFakeIo([{ id: "socket-child", connected: false }]);
    const manager = new WebSocketManager(io);
    manager.deviceSockets.set(childDeviceId, new Set(["socket-child"]));

    expect(manager.emitCriticalAlert(childDeviceId, { id: 3 })).toBe(false);
    expect(io.sockets.sockets.get("socket-child").emitted).toHaveLength(0);
  });
});
