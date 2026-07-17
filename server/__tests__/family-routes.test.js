const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const FamilyPermissionService = require("../services/FamilyPermissionService");
const createFamilyRoutes = require("../routes/families");

function requestJson(server, path, deviceId) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path,
        method: "GET",
        headers: { "x-test-device-id": deviceId },
      },
      (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          body += chunk;
        });
        response.on("end", () => {
          resolve({ status: response.statusCode, body: JSON.parse(body) });
        });
      }
    );
    request.on("error", reject);
    request.end();
  });
}

describe("family read API", () => {
  let db;
  let server;
  let logSpy;
  let errorSpy;
  const parentDeviceId = "parent-route-device";
  const childDeviceId = "child-route-device";

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    await db.registerDevice(parentDeviceId, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.2.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.2.0",
    });
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });

    const app = express();
    app.use((req, res, next) => {
      req.deviceId = req.headers["x-test-device-id"];
      next();
    });
    app.use(
      "/api/families",
      createFamilyRoutes(db, new FamilyPermissionService(db))
    );
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
  });

  test("returns family, member and device resources for a member device", async () => {
    const list = await requestJson(server, "/api/families", parentDeviceId);
    expect(list.status).toBe(200);
    expect(list.body.families).toHaveLength(1);
    const familyId = list.body.families[0].id;

    const family = await requestJson(
      server,
      `/api/families/${familyId}`,
      parentDeviceId
    );
    const members = await requestJson(
      server,
      `/api/families/${familyId}/members`,
      parentDeviceId
    );
    const devices = await requestJson(
      server,
      `/api/families/${familyId}/devices`,
      parentDeviceId
    );

    expect(family).toMatchObject({
      status: 200,
      body: { success: true, family: { id: familyId } },
    });
    expect(members.body.members).toHaveLength(2);
    expect(devices.body.devices).toHaveLength(2);
  });

  test("rejects a device outside the requested family", async () => {
    const [family] = await db.getFamiliesForDevice(parentDeviceId);
    const response = await requestJson(
      server,
      `/api/families/${family.id}/members`,
      "unrelated-device"
    );

    expect(response).toEqual({
      status: 403,
      body: {
        error: "Device is not a member of this family",
        code: "FAMILY_ACCESS_DENIED",
      },
    });
  });
});
