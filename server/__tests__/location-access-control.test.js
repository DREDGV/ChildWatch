const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const locationRoutes = require("../routes/location");

const parentDeviceId = "parent-location-device";
const childDeviceId = "child-location-device";
const strangerDeviceId = "stranger-location-device";

function requestJson(server, requestPath, deviceId, { method = "GET", body = null } = {}) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path: requestPath,
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
          let parsed = null;
          try {
            parsed = JSON.parse(raw);
          } catch {
            parsed = null;
          }
          resolve({ status: response.statusCode, body: parsed });
        });
      }
    );
    request.on("error", reject);
    request.end(body ? JSON.stringify(body) : undefined);
  });
}

describe("location access control", () => {
  let db;
  let server;
  let logSpy;
  let errorSpy;
  let warnSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});

    db = new DatabaseManager(":memory:");
    await db.initialize();
    await db.registerDevice(parentDeviceId, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(strangerDeviceId, {
      device_name: "Stranger",
      device_type: "android",
      app_version: "7.3.0",
    });

    // The child's own position, i.e. the data a parent is allowed to read.
    await db.saveLocation(childDeviceId, {
      latitude: 55.01,
      longitude: 82.93,
      accuracy: 12,
      timestamp: Date.now(),
    });

    locationRoutes.init(db);

    const app = express();
    app.use(express.json());
    app.use((req, res, next) => {
      if (req.headers["x-test-device-id"]) {
        req.deviceId = req.headers["x-test-device-id"];
      }
      next();
    });
    app.use("/api/location", locationRoutes);
    app.use((error, req, res, next) => {
      res.status(500).json({ error: error.message });
    });

    server = http.createServer(app);
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
    warnSpy.mockRestore();
  });

  test("requires authentication for every location read", async () => {
    const latest = await requestJson(server, `/api/location/latest/${childDeviceId}`, null);
    expect(latest).toMatchObject({
      status: 401,
      body: { code: "AUTH_REQUIRED" },
    });

    const history = await requestJson(server, `/api/location/history/${childDeviceId}`, null);
    expect(history.status).toBe(401);

    const pair = await requestJson(
      server,
      `/api/location/pair?parentId=${parentDeviceId}&childId=${childDeviceId}`,
      null
    );
    expect(pair.status).toBe(401);
  });

  test("denies an unrelated device the position and history of a child", async () => {
    const latest = await requestJson(
      server,
      `/api/location/latest/${childDeviceId}`,
      strangerDeviceId
    );
    expect(latest).toMatchObject({
      status: 403,
      body: { code: "DEVICE_ACCESS_DENIED" },
    });

    const history = await requestJson(
      server,
      `/api/location/history/${childDeviceId}`,
      strangerDeviceId
    );
    expect(history.status).toBe(403);

    const stats = await requestJson(
      server,
      `/api/location/stats/${childDeviceId}`,
      strangerDeviceId
    );
    expect(stats.status).toBe(403);
  });

  test("denies an unrelated device a location pair", async () => {
    const response = await requestJson(
      server,
      `/api/location/pair?parentId=${parentDeviceId}&childId=${childDeviceId}`,
      strangerDeviceId
    );
    expect(response.status).toBe(403);
    expect(response.body.code).toBe("DEVICE_ACCESS_DENIED");
  });

  test("lets a device read its own latest position", async () => {
    const response = await requestJson(
      server,
      `/api/location/latest/${childDeviceId}`,
      childDeviceId
    );
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      success: true,
      deviceId: childDeviceId,
      location: { latitude: 55.01, longitude: 82.93 },
    });
  });

  test("lets a linked parent read the child position and history", async () => {
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });

    const latest = await requestJson(
      server,
      `/api/location/latest/${childDeviceId}`,
      parentDeviceId
    );
    expect(latest.status).toBe(200);
    expect(latest.body.deviceId).toBe(childDeviceId);

    const history = await requestJson(
      server,
      `/api/location/history/${childDeviceId}`,
      parentDeviceId
    );
    expect(history.status).toBe(200);
    expect(history.body.count).toBe(1);
  });

  test("lets the child upload its own position but not someone else's", async () => {
    const own = await requestJson(server, `/api/location/parent/${childDeviceId}`, childDeviceId, {
      method: "POST",
      body: { latitude: 55.02, longitude: 82.94, accuracy: 10, timestamp: Date.now() },
    });
    expect(own.status).toBe(200);

    const spoofed = await requestJson(
      server,
      `/api/location/parent/${strangerDeviceId}`,
      childDeviceId,
      {
        method: "POST",
        body: { latitude: 1.1, longitude: 2.2, timestamp: Date.now() },
      }
    );
    expect(spoofed.status).toBe(403);
  });

  test("lets a linked parent read the parent position it uploaded", async () => {
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });

    const upload = await requestJson(server, `/api/location/parent/${parentDeviceId}`, parentDeviceId, {
      method: "POST",
      body: { latitude: 55.03, longitude: 82.95, accuracy: 8, timestamp: Date.now() },
    });
    expect(upload.status).toBe(200);

    const latest = await requestJson(
      server,
      `/api/location/parent/latest/${parentDeviceId}`,
      parentDeviceId
    );
    expect(latest.status).toBe(200);
    expect(latest.body.location).toMatchObject({ latitude: 55.03, longitude: 82.95 });

    // The child app reads the parent position of its linked parent.
    const childRead = await requestJson(
      server,
      `/api/location/parent/latest/${parentDeviceId}`,
      childDeviceId
    );
    expect(childRead.status).toBe(200);

    // Both sides are allowed to read the other, which keeps the parent and the
    // child map screens consistent.
    const parentReadsChild = await requestJson(
      server,
      `/api/location/latest/${childDeviceId}`,
      parentDeviceId
    );
    expect(parentReadsChild.status).toBe(200);
  });
});
