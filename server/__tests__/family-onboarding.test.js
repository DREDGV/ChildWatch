const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const FamilyOnboardingService = require("../services/FamilyOnboardingService");
const createFamilyOnboardingRoutes = require("../routes/family-onboarding");

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
          "x-test-device-id": deviceId,
          ...(body ? { "content-type": "application/json" } : {}),
        },
      },
      (response) => {
        let responseBody = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          responseBody += chunk;
        });
        response.on("end", () =>
          resolve({
            status: response.statusCode,
            body: responseBody ? JSON.parse(responseBody) : null,
          })
        );
      }
    );
    request.on("error", reject);
    request.end(body ? JSON.stringify(body) : undefined);
  });
}

describe("family onboarding", () => {
  let db;
  let server;
  let logSpy;
  let errorSpy;

  const parentDeviceId = "onboarding-parent-device";
  const childDeviceId = "onboarding-child-device";
  const secondChildDeviceId = "onboarding-child-device-two";

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    for (const [deviceId, name] of [
      [parentDeviceId, "Parent phone"],
      [childDeviceId, "Child phone"],
      [secondChildDeviceId, "Child tablet"],
    ]) {
      await db.registerDevice(deviceId, {
        device_name: name,
        device_type: "android",
        app_version: "8.0.0",
      });
    }

    const app = express();
    app.use(express.json());
    app.use((req, res, next) => {
      req.deviceId = req.headers["x-test-device-id"];
      next();
    });
    app.use(
      "/api/family-onboarding",
      createFamilyOnboardingRoutes(new FamilyOnboardingService(db))
    );
    server = http.createServer(app);
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  async function bootstrapParent() {
    return requestJson(
      server,
      "/api/family-onboarding/bootstrap",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyName: "Семья Ивановых",
          displayName: "Марина",
          role: "PARENT",
          avatarKey: "preset:mint",
        },
      }
    );
  }

  test("creates one confirmed person and keeps the phone as a separate device", async () => {
    const response = await bootstrapParent();
    expect(response).toMatchObject({
      status: 201,
      body: {
        success: true,
        family: { name: "Семья Ивановых" },
        member: { displayName: "Марина", role: "PARENT" },
        binding: { deviceId: parentDeviceId },
      },
    });
    expect(response.body.member.id).not.toBe(parentDeviceId);
    expect(response.body.binding.memberBindingSource).toBe("EXPLICIT");

    const duplicate = await bootstrapParent();
    expect(duplicate).toMatchObject({
      status: 409,
      body: { code: "DEVICE_ALREADY_ONBOARDED" },
    });
  });

  test("confirms only the adult's own legacy profile without deleting history", async () => {
    await db.upsertDeviceLink({
      parentDeviceId,
      childDeviceId,
      parentDisplayName: "Старое имя телефона",
      childDisplayName: "Лёва",
      createdBy: "legacy-test",
    });
    const [family] = await db.getFamiliesForDevice(parentDeviceId);
    const before = await db.getFamilyDeviceMembership(family.id, parentDeviceId);
    expect(before.memberBindingSource).toBe("LEGACY_BOOTSTRAP");

    const confirmed = await requestJson(
      server,
      `/api/family-onboarding/families/${family.id}/confirm-self`,
      parentDeviceId,
      {
        method: "POST",
        body: { displayName: "Марина", avatarKey: "preset:mint" },
      }
    );

    expect(confirmed).toMatchObject({
      status: 200,
      body: {
        success: true,
        family: { id: family.id },
        member: {
          id: before.memberId,
          displayName: "Марина",
          role: "PARENT",
          avatarKey: "preset:mint",
        },
        binding: {
          deviceId: parentDeviceId,
          memberBindingSource: "EXPLICIT",
        },
      },
    });
    const childBinding = await db.getFamilyDeviceMembership(
      family.id,
      childDeviceId
    );
    expect(childBinding.memberBindingSource).toBe("LEGACY_BOOTSTRAP");
    expect(
      (await db.get("SELECT COUNT(*) AS count FROM device_links")).count
    ).toBe(1);
  });

  test("does not let a child self-confirm a provisional adult profile", async () => {
    await db.upsertDeviceLink({
      parentDeviceId,
      childDeviceId,
      parentDisplayName: "Марина",
      childDisplayName: "Лёва",
      createdBy: "legacy-test",
    });
    const [family] = await db.getFamiliesForDevice(childDeviceId);
    const denied = await requestJson(
      server,
      `/api/family-onboarding/families/${family.id}/confirm-self`,
      childDeviceId,
      {
        method: "POST",
        body: { displayName: "Лёва", avatarKey: "preset:sky" },
      }
    );
    expect(denied).toMatchObject({
      status: 403,
      body: { code: "FAMILY_PROFILE_CONFIRMATION_DENIED" },
    });
  });

  test("adds a new person through a single-use invitation", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const created = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "NEW_MEMBER",
          displayName: "Лёва",
          role: "CHILD",
          avatarKey: "preset:sky",
        },
      }
    );
    expect(created.status).toBe(201);
    expect(created.body.invitation.token).toMatch(/^[a-f0-9]{64}$/);
    expect(created.body.invitation.invitationUri).toContain("childwatch://family/join");

    const token = created.body.invitation.token;
    const preview = await requestJson(
      server,
      `/api/family-onboarding/invitations/${token}`,
      childDeviceId
    );
    expect(preview).toMatchObject({
      status: 200,
      body: {
        invitation: {
          family: { id: familyId, name: "Семья Ивановых" },
          member: { displayName: "Лёва", role: "CHILD" },
          invitedBy: "Марина",
        },
      },
    });
    expect(JSON.stringify(preview.body)).not.toContain(parentDeviceId);

    const accepted = await requestJson(
      server,
      `/api/family-onboarding/invitations/${token}/accept`,
      childDeviceId,
      {
        method: "POST",
        body: { deviceName: "Телефон Лёвы", clientKind: "CHILD_DEVICE" },
      }
    );
    expect(accepted).toMatchObject({
      status: 200,
      body: {
        member: { displayName: "Лёва", role: "CHILD" },
        binding: { deviceId: childDeviceId },
      },
    });
    expect(await db.getFamilyMembers(familyId)).toHaveLength(2);
    await expect(
      db.getSharedFamilyMembership(parentDeviceId, childDeviceId)
    ).resolves.toMatchObject({
      actorDisplayName: "Марина",
      targetDisplayName: "Лёва",
    });

    const reused = await requestJson(
      server,
      `/api/family-onboarding/invitations/${token}/accept`,
      secondChildDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );
    expect(reused).toMatchObject({
      status: 409,
      body: { code: "INVITATION_ALREADY_USED" },
    });
  });

  test("adds a second device to an existing person without creating a duplicate", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const childInvitation = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "NEW_MEMBER",
          displayName: "Лёва",
          role: "CHILD",
        },
      }
    );
    const firstAcceptance = await requestJson(
      server,
      `/api/family-onboarding/invitations/${childInvitation.body.invitation.token}/accept`,
      childDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );
    const childMemberId = firstAcceptance.body.member.id;

    const deviceInvitation = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "EXISTING_MEMBER",
          targetMemberId: childMemberId,
        },
      }
    );
    await requestJson(
      server,
      `/api/family-onboarding/invitations/${deviceInvitation.body.invitation.token}/accept`,
      secondChildDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );

    expect(await db.getFamilyMembers(familyId)).toHaveLength(2);
    const childDevices = (await db.getFamilyDevices(familyId)).filter(
      (device) => device.memberId === childMemberId
    );
    expect(childDevices.map((device) => device.deviceId).sort()).toEqual(
      [childDeviceId, secondChildDeviceId].sort()
    );
  });

  test("does not assign one physical device to a second confirmed person", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const firstInvitation = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "NEW_MEMBER",
          displayName: "Лёва",
          role: "CHILD",
        },
      }
    );
    const firstAcceptance = await requestJson(
      server,
      `/api/family-onboarding/invitations/${firstInvitation.body.invitation.token}/accept`,
      childDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );
    expect(firstAcceptance.status).toBe(200);

    const secondInvitation = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "NEW_MEMBER",
          displayName: "Друг",
          role: "CHILD",
        },
      }
    );
    const repeatedDeviceAcceptance = await requestJson(
      server,
      `/api/family-onboarding/invitations/${secondInvitation.body.invitation.token}/accept`,
      childDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );

    expect(repeatedDeviceAcceptance).toMatchObject({
      status: 409,
      body: { code: "DEVICE_ALREADY_ONBOARDED" },
    });
    expect(await db.getFamilyMembers(familyId)).toHaveLength(2);
    const explicitBindings = await db.all(
      `SELECT member_id AS memberId
       FROM family_devices
       WHERE device_id = ? AND is_active = 1
         AND member_binding_source = 'EXPLICIT'`,
      [childDeviceId]
    );
    expect(explicitBindings).toHaveLength(1);
    expect(explicitBindings[0].memberId).toBe(firstAcceptance.body.member.id);
  });

  test("does not let a child create another person", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const invite = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: { familyId, mode: "NEW_MEMBER", displayName: "Лёва", role: "CHILD" },
      }
    );
    await requestJson(
      server,
      `/api/family-onboarding/invitations/${invite.body.invitation.token}/accept`,
      childDeviceId,
      { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
    );

    const denied = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      childDeviceId,
      {
        method: "POST",
        body: { familyId, mode: "NEW_MEMBER", displayName: "Друг", role: "CHILD" },
      }
    );
    expect(denied).toMatchObject({
      status: 403,
      body: { code: "INVITATION_CREATE_DENIED" },
    });
  });

  test("keeps only the newest repeated invitation for the same person", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const body = {
      familyId,
      mode: "NEW_MEMBER",
      displayName: "Лёва",
      role: "CHILD",
    };
    const first = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      { method: "POST", body }
    );
    const second = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      { method: "POST", body }
    );

    const oldPreview = await requestJson(
      server,
      `/api/family-onboarding/invitations/${first.body.invitation.token}`,
      childDeviceId
    );
    const newPreview = await requestJson(
      server,
      `/api/family-onboarding/invitations/${second.body.invitation.token}`,
      childDeviceId
    );
    expect(oldPreview.body.invitation.isRevoked).toBe(true);
    expect(newPreview.body.invitation.isRevoked).toBe(false);
  });

  test("lets an adult list and revoke active invitations", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;
    const created = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId,
          mode: "NEW_MEMBER",
          displayName: "Лёва",
          role: "CHILD",
        },
      }
    );

    const listed = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/invitations`,
      parentDeviceId
    );
    expect(listed).toMatchObject({
      status: 200,
      body: {
        success: true,
        invitations: [
          {
            id: created.body.invitation.id,
            member: { displayName: "Лёва" },
            isRevoked: false,
          },
        ],
      },
    });
    expect(listed.body.invitations[0].token).toBeUndefined();

    const revoked = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/invitations/${created.body.invitation.id}`,
      parentDeviceId,
      { method: "DELETE" }
    );
    expect(revoked).toMatchObject({ status: 200, body: { success: true } });

    const after = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/invitations`,
      parentDeviceId
    );
    expect(after.body.invitations).toEqual([]);
  });

  test("rejects an invitation opened in the wrong ChildWatch application", async () => {
    const bootstrap = await bootstrapParent();
    const created = await requestJson(
      server,
      "/api/family-onboarding/invitations",
      parentDeviceId,
      {
        method: "POST",
        body: {
          familyId: bootstrap.body.family.id,
          mode: "NEW_MEMBER",
          displayName: "Лёва",
          role: "CHILD",
        },
      }
    );
    const rejected = await requestJson(
      server,
      `/api/family-onboarding/invitations/${created.body.invitation.token}/accept`,
      childDeviceId,
      { method: "POST", body: { clientKind: "PARENT_MONITOR" } }
    );
    expect(rejected).toMatchObject({
      status: 400,
      body: { code: "APP_ROLE_MISMATCH" },
    });
  });

  test("confirms legacy device profiles in place without losing their identities", async () => {
    await db.upsertDeviceLink({
      parentDeviceId,
      childDeviceId,
      parentDisplayName: "Old parent phone",
      childDisplayName: "Old child phone",
      createdBy: "legacy-onboarding-test",
    });
    const [family] = await db.getFamiliesForDevice(parentDeviceId);
    const membershipsBefore = {
      parent: await db.getFamilyDeviceMembership(family.id, parentDeviceId),
      child: await db.getFamilyDeviceMembership(family.id, childDeviceId),
    };

    const candidates = await requestJson(
      server,
      `/api/family-onboarding/families/${family.id}/legacy-candidates`,
      parentDeviceId
    );
    expect(candidates.status).toBe(200);
    expect(candidates.body.candidates).toHaveLength(2);

    const childDenied = await requestJson(
      server,
      `/api/family-onboarding/families/${family.id}/legacy-candidates/${membershipsBefore.parent.memberId}/confirm`,
      childDeviceId,
      {
        method: "POST",
        body: {
          displayName: "Марина",
          role: "PARENT",
          avatarKey: "preset:mint",
        },
      }
    );
    expect(childDenied).toMatchObject({
      status: 403,
      body: { code: "FAMILY_PROFILE_MANAGE_DENIED" },
    });

    for (const profile of [
      {
        deviceId: parentDeviceId,
        memberId: membershipsBefore.parent.memberId,
        displayName: "Марина",
        role: "PARENT",
        avatarKey: "preset:mint",
      },
      {
        deviceId: childDeviceId,
        memberId: membershipsBefore.child.memberId,
        displayName: "Лёва",
        role: "CHILD",
        avatarKey: "preset:sky",
      },
    ]) {
      const confirmed = await requestJson(
        server,
        `/api/family-onboarding/families/${family.id}/legacy-candidates/${profile.memberId}/confirm`,
        parentDeviceId,
        {
          method: "POST",
          body: {
            displayName: profile.displayName,
            role: profile.role,
            avatarKey: profile.avatarKey,
          },
        }
      );
      expect(confirmed).toMatchObject({
        status: 200,
        body: {
          success: true,
          member: {
            id: profile.memberId,
            displayName: profile.displayName,
            role: profile.role,
          },
          devices: [
            {
              deviceId: profile.deviceId,
              memberBindingSource: "EXPLICIT",
            },
          ],
        },
      });
    }

    const after = await requestJson(
      server,
      `/api/family-onboarding/families/${family.id}/legacy-candidates`,
      parentDeviceId
    );
    expect(after.body.candidates).toEqual([]);
    expect(await db.getFamilyMembers(family.id)).toHaveLength(2);
    expect(
      await db.get(
        `SELECT COUNT(*) AS count
         FROM family_devices
         WHERE family_id = ? AND member_binding_source = 'EXPLICIT'`,
        [family.id]
      )
    ).toMatchObject({ count: 2 });
  });

  test("transfers a confirmed phone between compatible profiles only after adult confirmation", async () => {
    const bootstrap = await bootstrapParent();
    const familyId = bootstrap.body.family.id;

    const createChild = async (deviceId, displayName) => {
      const invitation = await requestJson(
        server,
        "/api/family-onboarding/invitations",
        parentDeviceId,
        {
          method: "POST",
          body: {
            familyId,
            mode: "NEW_MEMBER",
            displayName,
            role: "CHILD",
          },
        }
      );
      return requestJson(
        server,
        `/api/family-onboarding/invitations/${invitation.body.invitation.token}/accept`,
        deviceId,
        { method: "POST", body: { clientKind: "CHILD_DEVICE" } }
      );
    };

    const firstChild = await createChild(childDeviceId, "Лёва");
    const secondChild = await createChild(secondChildDeviceId, "Старый профиль");
    expect(await db.getFamilyMembers(familyId)).toHaveLength(3);

    const withoutConfirmation = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/devices/${secondChildDeviceId}/transfer`,
      parentDeviceId,
      {
        method: "POST",
        body: { targetMemberId: firstChild.body.member.id, confirmed: false },
      }
    );
    expect(withoutConfirmation).toMatchObject({
      status: 400,
      body: { code: "DEVICE_TRANSFER_CONFIRMATION_REQUIRED" },
    });

    const childDenied = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/devices/${secondChildDeviceId}/transfer`,
      childDeviceId,
      {
        method: "POST",
        body: { targetMemberId: firstChild.body.member.id, confirmed: true },
      }
    );
    expect(childDenied).toMatchObject({
      status: 403,
      body: { code: "FAMILY_PROFILE_MANAGE_DENIED" },
    });

    const transferred = await requestJson(
      server,
      `/api/family-onboarding/families/${familyId}/devices/${secondChildDeviceId}/transfer`,
      parentDeviceId,
      {
        method: "POST",
        body: { targetMemberId: firstChild.body.member.id, confirmed: true },
      }
    );
    expect(transferred).toMatchObject({
      status: 200,
      body: {
        success: true,
        member: { id: firstChild.body.member.id, displayName: "Лёва" },
        binding: {
          deviceId: secondChildDeviceId,
          memberId: firstChild.body.member.id,
          memberBindingSource: "EXPLICIT",
        },
      },
    });
    expect(await db.getFamilyMembers(familyId)).toHaveLength(2);
    expect(
      await db.get("SELECT COUNT(*) AS count FROM family_members WHERE family_id = ?", [
        familyId,
      ])
    ).toMatchObject({ count: 3 });
    expect(secondChild.body.member.id).not.toBe(firstChild.body.member.id);
  });
});
