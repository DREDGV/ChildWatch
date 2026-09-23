const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const locationRoutes = require("../routes/location");

/**
 * One phone, two spellings of its identifier.
 *
 * The installed clients upload the same device's position as both
 * `device_<androidId>` and `<androidId>`, and both spellings are in the table.
 * The family map already reads across both, but the older per-device routes
 * named a single spelling - so the child application was shown a four-hour-old
 * position for a parent whose phone reports every thirty seconds, because the
 * fresh rows sat under the other spelling.
 */
const parentPrefixed = "device_15e991bb5d8f906d";
const parentBare = "15e991bb5d8f906d";
const childDeviceId = "child-c7d50e08";

function requestJson(server, requestPath, deviceId) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path: requestPath,
        method: "GET",
        headers: deviceId ? { "x-test-device-id": deviceId } : {},
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
    request.end();
  });
}

describe("a parent position stored under either spelling", () => {
  const staleTimestamp = Date.now() - 4 * 60 * 60 * 1000;
  const freshTimestamp = Date.now() - 30 * 1000;
  let db;
  let server;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

    db = new DatabaseManager(":memory:");
    await db.initialize();
    await db.registerDevice(parentPrefixed, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.upsertDeviceLink({
      parentDeviceId: parentPrefixed,
      childDeviceId,
    });
    await db.saveLocation(childDeviceId, {
      latitude: 55.98,
      longitude: 92.8,
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

    // Asking once creates the table the router owns, so the rows below can be
    // written without a second copy of its schema living in this test.
    await requestJson(server, `/api/location/parent/latest/${parentPrefixed}`, childDeviceId);
    await db.run(
      `INSERT INTO parent_locations (parent_id, latitude, longitude, accuracy, timestamp)
       VALUES (?, ?, ?, ?, ?)`,
      [parentPrefixed, 55.1, 92.1, 100, staleTimestamp]
    );
    await db.run(
      `INSERT INTO parent_locations (parent_id, latitude, longitude, accuracy, timestamp)
       VALUES (?, ?, ?, ?, ?)`,
      [parentBare, 55.2, 92.2, 15, freshTimestamp]
    );
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("the pair carries the freshest row, not the row of one spelling", async () => {
    const response = await requestJson(
      server,
      `/api/location/pair?parentId=${parentPrefixed}&childId=${childDeviceId}`,
      childDeviceId
    );

    expect(response.status).toBe(200);
    expect(response.body.pair.parent.timestamp).toBe(freshTimestamp);
    expect(response.body.pair.parent.latitude).toBeCloseTo(55.2, 5);
  });

  test("the latest parent position is the freshest of both spellings", async () => {
    const response = await requestJson(
      server,
      `/api/location/parent/latest/${parentPrefixed}`,
      childDeviceId
    );

    expect(response.status).toBe(200);
    expect(response.body.location.timestamp).toBe(freshTimestamp);
  });

  test("history covers both spellings", async () => {
    const response = await requestJson(
      server,
      `/api/location/parent/history/${parentPrefixed}?limit=10`,
      childDeviceId
    );

    expect(response.status).toBe(200);
    const timestamps = (response.body.locations || []).map((row) => row.timestamp);
    expect(timestamps).toContain(freshTimestamp);
    expect(timestamps).toContain(staleTimestamp);
  });

  test("a device the caller is not linked to is still refused", async () => {
    const response = await requestJson(
      server,
      `/api/location/parent/latest/device_0123456789abcdef`,
      childDeviceId
    );

    expect(response.status).toBe(403);
  });
});
