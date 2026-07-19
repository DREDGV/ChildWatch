const http = require("http");
const express = require("express");
const AuthManager = require("../auth/AuthManager");
const AuthMiddleware = require("../middleware/AuthMiddleware");
const DatabaseManager = require("../database/DatabaseManager");
const FamilyIdentityService = require("../services/FamilyIdentityService");
const createMeRoutes = require("../routes/me");

function requestJson(server, token = null) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path: "/api/me",
        method: "GET",
        headers: token ? { authorization: `Bearer ${token}` } : {},
      },
      (response) => {
        let rawBody = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          rawBody += chunk;
        });
        response.on("end", () => {
          resolve({
            status: response.statusCode,
            rawBody,
            body: JSON.parse(rawBody),
          });
        });
      }
    );
    request.on("error", reject);
    request.end();
  });
}

describe("authenticated device identity API", () => {
  let authManager;
  let db;
  let server;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    authManager = new AuthManager({ persistenceEnabled: false });
    db = new DatabaseManager(":memory:");
    await db.initialize();

    const app = express();
    const authMiddleware = new AuthMiddleware(authManager);
    app.use(
      "/api/me",
      authMiddleware.authenticate(),
      createMeRoutes(new FamilyIdentityService(db))
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
    authManager.dispose();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  async function registerAuthenticatedDevice({
    deviceId,
    deviceName,
    deviceType = "android",
    appVersion = "7.3.0",
  }) {
    const credentials = authManager.registerDevice({
      deviceId,
      deviceName,
      deviceType,
      appVersion,
    });
    await db.registerDevice(deviceId, {
      device_name: deviceName,
      device_type: deviceType,
      app_version: appVersion,
    });
    return credentials;
  }

  test("requires a valid bearer device credential", async () => {
    const response = await requestJson(server);

    expect(response).toMatchObject({
      status: 401,
      body: {
        error: "Access token required",
        code: "MISSING_TOKEN",
      },
    });
  });

  test("returns the device principal and its canonical member profile", async () => {
    const parentDeviceId = "identity-parent-device";
    const childDeviceId = "identity-child-device";
    const parentCredentials = await registerAuthenticatedDevice({
      deviceId: parentDeviceId,
      deviceName: "Parent phone",
    });
    await registerAuthenticatedDevice({
      deviceId: childDeviceId,
      deviceName: "Child phone",
    });
    await db.upsertDeviceLink({
      parentDeviceId,
      childDeviceId,
      parentDisplayName: "Марина",
      childDisplayName: "Лёва",
      createdBy: parentDeviceId,
    });

    const [family] = await db.getFamiliesForDevice(parentDeviceId);
    const membership = await db.getFamilyDeviceMembership(
      family.id,
      parentDeviceId
    );
    await db.run(
      "UPDATE family_members SET avatar_key = ? WHERE id = ?",
      ["avatar-parent-01", membership.memberId]
    );

    const response = await requestJson(
      server,
      parentCredentials.authToken
    );

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      success: true,
      device: {
        deviceId: parentDeviceId,
        displayName: "Parent phone",
        platform: "android",
        appVersion: "7.3.0",
      },
      memberships: [
        expect.objectContaining({
          familyId: family.id,
          memberId: membership.memberId,
          family: expect.objectContaining({
            id: family.id,
            name: expect.any(String),
          }),
          member: expect.objectContaining({
            id: membership.memberId,
            familyId: family.id,
            displayName: "Марина",
            role: "PARENT",
            avatarKey: "avatar-parent-01",
            isActive: true,
          }),
          binding: expect.objectContaining({
            familyId: family.id,
            memberId: membership.memberId,
            deviceId: parentDeviceId,
            memberBindingSource: "LEGACY_BOOTSTRAP",
            isActive: true,
          }),
        }),
      ],
    });
    expect(response.rawBody).not.toContain(parentCredentials.authToken);
    expect(response.rawBody).not.toContain(parentCredentials.refreshToken);
    expect(response.rawBody).not.toMatch(
      /authToken|refreshToken|auth_token|refresh_token|tokenHash/i
    );
  });

  test("returns a safe session-backed principal when the device row is absent", async () => {
    const deviceId = "identity-unbound-device";
    const credentials = authManager.registerDevice({
      deviceId,
      deviceName: "Unbound phone",
      deviceType: "android",
      appVersion: "7.3.0",
    });

    const response = await requestJson(server, credentials.authToken);

    expect(response).toEqual({
      status: 200,
      rawBody: expect.any(String),
      body: {
        success: true,
        device: {
          deviceId,
          displayName: "Unbound phone",
          platform: "android",
          appVersion: "7.3.0",
        },
        memberships: [],
      },
    });
  });

  test("returns every active membership for a device in multiple families", async () => {
    const deviceId = "identity-multi-family-device";
    const credentials = await registerAuthenticatedDevice({
      deviceId,
      deviceName: "Multi-family phone",
    });
    const fixtures = [
      {
        familyId: "family_identity_first",
        familyName: "First family",
        memberId: "member_identity_first",
        memberName: "First profile",
      },
      {
        familyId: "family_identity_second",
        familyName: "Second family",
        memberId: "member_identity_second",
        memberName: "Second profile",
      },
    ];

    for (const [index, fixture] of fixtures.entries()) {
      await db.run(
        `INSERT INTO families (id, name, is_active, created_at, updated_at)
         VALUES (?, ?, 1, ?, ?)`,
        [fixture.familyId, fixture.familyName, index + 1, index + 1]
      );
      await db.run(
        `INSERT INTO family_members (
           id, family_id, display_name, role, is_active, created_at, updated_at
         ) VALUES (?, ?, ?, 'GUARDIAN', 1, ?, ?)`,
        [
          fixture.memberId,
          fixture.familyId,
          fixture.memberName,
          index + 1,
          index + 1,
        ]
      );
      await db.run(
        `INSERT INTO family_devices (
           id, family_id, member_id, device_id, display_name, platform,
           member_binding_source, is_active, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, 'android', 'EXPLICIT', 1, ?, ?)`,
        [
          `binding_identity_${index + 1}`,
          fixture.familyId,
          fixture.memberId,
          deviceId,
          "Multi-family phone",
          index + 1,
          index + 1,
        ]
      );
    }

    const response = await requestJson(server, credentials.authToken);

    expect(response.status).toBe(200);
    expect(
      response.body.memberships.map((membership) => ({
        familyId: membership.familyId,
        memberId: membership.memberId,
        memberName: membership.member.displayName,
      }))
    ).toEqual(
      fixtures.map((fixture) => ({
        familyId: fixture.familyId,
        memberId: fixture.memberId,
        memberName: fixture.memberName,
      }))
    );
  });

  test("excludes inactive family, member, and binding records", async () => {
    const parentDeviceId = "identity-inactive-parent";
    const childDeviceId = "identity-inactive-child";
    const credentials = await registerAuthenticatedDevice({
      deviceId: parentDeviceId,
      deviceName: "Inactive parent phone",
    });
    await registerAuthenticatedDevice({
      deviceId: childDeviceId,
      deviceName: "Inactive child phone",
    });
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });
    const [family] = await db.getFamiliesForDevice(parentDeviceId);
    await db.run(
      "UPDATE family_devices SET is_active = 0 WHERE family_id = ? AND device_id = ?",
      [family.id, parentDeviceId]
    );

    let response = await requestJson(server, credentials.authToken);

    expect(response.status).toBe(200);
    expect(response.body.memberships).toEqual([]);

    await db.run(
      "UPDATE family_devices SET is_active = 1 WHERE family_id = ? AND device_id = ?",
      [family.id, parentDeviceId]
    );
    await db.run(
      `UPDATE family_members
       SET is_active = 0
       WHERE id = (
         SELECT member_id FROM family_devices
         WHERE family_id = ? AND device_id = ?
       )`,
      [family.id, parentDeviceId]
    );
    response = await requestJson(server, credentials.authToken);

    expect(response.status).toBe(200);
    expect(response.body.memberships).toEqual([]);

    await db.run(
      `UPDATE family_members
       SET is_active = 1
       WHERE id = (
         SELECT member_id FROM family_devices
         WHERE family_id = ? AND device_id = ?
       )`,
      [family.id, parentDeviceId]
    );
    await db.run("UPDATE families SET is_active = 0 WHERE id = ?", [family.id]);
    response = await requestJson(server, credentials.authToken);

    expect(response.status).toBe(200);
    expect(response.body.memberships).toEqual([]);
  });
});
