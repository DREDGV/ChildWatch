const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const FamilyIdentityService = require("../services/FamilyIdentityService");
const FamilyOnboardingService = require("../services/FamilyOnboardingService");
const createFamilyOnboardingRoutes = require("../routes/family-onboarding");

/**
 * The main case: a person who is already in the family joins with a new phone.
 *
 * Every step below goes through the real HTTP routes, in the order the two
 * applications perform them, because the interesting failures live in the
 * sequence rather than in one call: the invitation must name an existing person,
 * the new phone must be bound to that same person and not to a new record, and a
 * repeat, a revocation or an expiry must not leave a second person behind.
 */
const dadPhone = "device_dad_phone_0001";
const momOldPhone = "device_mom_phone_0001";
const momNewPhone = "device_mom_phone_0002";
const childPhone = "child_device_0001";
const strangerPhone = "device_stranger_0001";

function request(server, requestPath, deviceId, { method = "GET", body = null } = {}) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const payload = body === null ? null : JSON.stringify(body);
    const call = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path: requestPath,
        method,
        headers: {
          ...(deviceId ? { "x-test-device-id": deviceId } : {}),
          ...(payload ? { "content-type": "application/json", "content-length": Buffer.byteLength(payload) } : {}),
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
    call.on("error", reject);
    call.end(payload || undefined);
  });
}

describe("an existing person joins with a new phone", () => {
  let db;
  let server;
  let identity;
  let onboarding;
  let family;
  let dadMember;
  let momMember;
  let logSpy;
  let errorSpy;
  let warnSpy;

  async function register(deviceId, name) {
    await db.registerDevice(deviceId, {
      device_name: name,
      device_type: "android",
      app_version: "7.3.0",
    });
  }

  async function members() {
    return db.getFamilyMembers(family.id);
  }

  async function inviteFor(memberId, actor = dadPhone) {
    return request(server, "/api/family-onboarding/invitations", actor, {
      method: "POST",
      body: {
        familyId: family.id,
        mode: "EXISTING_MEMBER",
        targetMemberId: memberId,
      },
    });
  }

  function accept(token, deviceId, clientKind = "PARENT_MONITOR") {
    return request(server, `/api/family-onboarding/invitations/${token}/accept`, deviceId, {
      method: "POST",
      body: { clientKind, deviceName: "Новый телефон" },
    });
  }

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});

    db = new DatabaseManager(":memory:");
    await db.initialize();
    await register(dadPhone, "Samsung отца");
    await register(momOldPhone, "Nokia мамы");
    await register(momNewPhone, "Samsung мамы");
    await register(childPhone, "moto ребёнка");
    await register(strangerPhone, "Чужой телефон");

    identity = new FamilyIdentityService(db);
    onboarding = new FamilyOnboardingService(db);

    // Dad starts the family; mum joins with her own phone as a new person.
    const created = await db.createExplicitFamilyForDevice({
      deviceId: dadPhone,
      familyName: "Семья",
      displayName: "Папа",
      role: "PARENT",
    });
    family = created.family;
    dadMember = created.member;

    const momInvitation = await onboarding.createInvitation(dadPhone, {
      familyId: family.id,
      mode: "NEW_MEMBER",
      displayName: "Мама",
      role: "PARENT",
    });
    const joined = await onboarding.acceptInvitation(momOldPhone, momInvitation.token, {
      clientKind: "PARENT_MONITOR",
      deviceName: "Nokia мамы",
    });
    momMember = joined.member;

    const app = express();
    app.use(express.json());
    app.use((req, res, next) => {
      if (req.headers["x-test-device-id"]) {
        req.deviceId = req.headers["x-test-device-id"];
      }
      next();
    });
    app.use("/api/family-onboarding", createFamilyOnboardingRoutes(onboarding));
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

  test("a new phone's legacy registration cannot rename the person it joined", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;
    expect((await accept(invitation.token, momNewPhone)).status).toBe(200);
    await db.upsertDeviceLink({
      parentDeviceId: momNewPhone,
      childDeviceId: childPhone,
      parentDisplayName: "Родитель",
      childDisplayName: "Ребёнок",
    });
    const person = (await members()).find((member) => member.id === momMember.id);
    expect(person.displayName).toBe("Мама");
  });

  test("the invitation names the existing person, not a new one", async () => {
    const response = await inviteFor(momMember.id);

    expect(response.status).toBe(201);
    expect(response.body.invitation.mode).toBe("EXISTING_MEMBER");
    expect(response.body.invitation.member.id).toBe(momMember.id);
    expect(response.body.invitation.member.displayName).toBe("Мама");
    expect(response.body.invitation.token).toMatch(/^[a-f0-9]{64}$/);
  });

  test("the new phone sees who it is joining as before it confirms", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;

    const preview = await request(
      server,
      `/api/family-onboarding/invitations/${invitation.token}`,
      momNewPhone
    );

    expect(preview.status).toBe(200);
    expect(preview.body.invitation.member.displayName).toBe("Мама");
    expect(preview.body.invitation.member.role).toBe("PARENT");
    expect(preview.body.invitation.isConsumed).toBe(false);
  });

  test("accepting binds the new phone to the same person and creates nobody", async () => {
    const before = await members();
    const invitation = (await inviteFor(momMember.id)).body.invitation;

    const accepted = await accept(invitation.token, momNewPhone);

    expect(accepted.status).toBe(200);
    expect(accepted.body.member.id).toBe(momMember.id);
    expect(accepted.body.binding.deviceId).toBe(momNewPhone);

    const after = await members();
    expect(after).toHaveLength(before.length);

    // Both of mum's phones now belong to one person, and that person is her.
    const bindings = await db.getFamilyDevices(family.id);
    const momBindings = bindings.filter((row) => row.memberId === momMember.id && row.isActive !== 0);
    expect(momBindings.map((row) => row.deviceId).sort()).toEqual(
      [momOldPhone, momNewPhone].sort()
    );
  });

  test("the server then tells the new phone which family and person it belongs to", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;
    await accept(invitation.token, momNewPhone);

    const resolved = await identity.resolveAuthenticatedDevice(momNewPhone, {
      deviceName: "Samsung мамы",
      deviceType: "android",
      appVersion: "7.3.0",
    });

    expect(resolved.memberships).toHaveLength(1);
    expect(resolved.memberships[0].familyId).toBe(family.id);
    expect(resolved.memberships[0].memberId).toBe(momMember.id);
    expect(resolved.memberships[0].member.displayName).toBe("Мама");
  });

  test("a repeated acceptance adds nothing and reports the used invitation", async () => {
    const before = await members();
    const invitation = (await inviteFor(momMember.id)).body.invitation;
    await accept(invitation.token, momNewPhone);

    const again = await accept(invitation.token, momNewPhone);

    expect(again.status).toBe(409);
    expect(again.body.code).toBe("INVITATION_ALREADY_USED");
    expect(await members()).toHaveLength(before.length);
  });

  test("a second phone cannot use the same invitation twice in a row", async () => {
    const before = await members();
    const invitation = (await inviteFor(momMember.id)).body.invitation;
    await accept(invitation.token, momNewPhone);

    // A different phone picking up the same code must not become mum as well.
    const stolen = await accept(invitation.token, strangerPhone);

    expect(stolen.status).toBe(409);
    expect(stolen.body.code).toBe("INVITATION_ALREADY_USED");
    expect(await members()).toHaveLength(before.length);
  });

  test("a revoked invitation is refused and leaves no person behind", async () => {
    const before = await members();
    const invitation = (await inviteFor(momMember.id)).body.invitation;

    const revoked = await request(
      server,
      `/api/family-onboarding/families/${family.id}/invitations/${invitation.id}`,
      dadPhone,
      { method: "DELETE" }
    );
    expect(revoked.status).toBe(200);

    const accepted = await accept(invitation.token, momNewPhone);
    expect(accepted.status).toBe(409);
    expect(accepted.body.code).toBe("INVITATION_REVOKED");
    expect(await members()).toHaveLength(before.length);
  });

  test("an expired invitation is refused", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;
    await db.run("UPDATE family_invitations SET expires_at = ? WHERE id = ?", [
      Date.now() - 1000,
      invitation.id,
    ]);

    const accepted = await accept(invitation.token, momNewPhone);

    expect(accepted.status).toBe(409);
    expect(accepted.body.code).toBe("INVITATION_EXPIRED");
  });

  test("the child application cannot use an adult invitation", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;

    const accepted = await accept(invitation.token, momNewPhone, "CHILD_DEVICE");

    expect(accepted.status).toBe(400);
    expect(accepted.body.code).toBe("APP_ROLE_MISMATCH");
  });

  test("a phone that already belongs to a confirmed person cannot join again", async () => {
    const invitation = (await inviteFor(momMember.id)).body.invitation;

    // Mum's first phone is already bound; picking up a code must not rebind it.
    const accepted = await accept(invitation.token, momOldPhone);

    expect(accepted.status).toBe(409);
    expect(accepted.body.code).toBe("DEVICE_ALREADY_ONBOARDED");
  });

  test("a child cannot hand out an invitation", async () => {
    const childInvitation = await onboarding.createInvitation(dadPhone, {
      familyId: family.id,
      mode: "NEW_MEMBER",
      displayName: "Лёва",
      role: "CHILD",
    });
    const child = await onboarding.acceptInvitation(childPhone, childInvitation.token, {
      clientKind: "CHILD_DEVICE",
      deviceName: "moto ребёнка",
    });

    const attempt = await inviteFor(momMember.id, childPhone);

    expect(attempt.status).toBe(403);
    expect(attempt.body.code).toBe("INVITATION_CREATE_DENIED");
  });

  test("one adult cannot invite a person of another family", async () => {
    const strangerFamily = await db.createExplicitFamilyForDevice({
      deviceId: strangerPhone,
      familyName: "Чужая семья",
      displayName: "Чужой",
      role: "PARENT",
    });

    const attempt = await request(server, "/api/family-onboarding/invitations", dadPhone, {
      method: "POST",
      body: {
        familyId: strangerFamily.family.id,
        mode: "EXISTING_MEMBER",
        targetMemberId: strangerFamily.member.id,
      },
    });

    expect(attempt.status).toBe(403);
    expect(attempt.body.code).toBe("FAMILY_ACCESS_DENIED");
  });

  test("a child invitation creates the person once and never twice", async () => {
    const before = await members();
    const invitation = await onboarding.createInvitation(dadPhone, {
      familyId: family.id,
      mode: "NEW_MEMBER",
      displayName: "Лёва",
      role: "CHILD",
    });

    const accepted = await accept(invitation.token, childPhone, "CHILD_DEVICE");
    expect(accepted.status).toBe(200);

    const after = await members();
    expect(after).toHaveLength(before.length + 1);
    expect(after.filter((member) => member.displayName === "Лёва")).toHaveLength(1);

    const again = await accept(invitation.token, childPhone, "CHILD_DEVICE");
    expect(again.status).toBe(409);
    expect((await members()).filter((member) => member.displayName === "Лёва")).toHaveLength(1);
  });

  test.each([["CHILD", "CHILD_DEVICE"], ["PARENT", "PARENT_MONITOR"], ["GUARDIAN", "PARENT_MONITOR"]])(
    "adult invites new %s: URI, preview, accept and replay preserve one identity",
    async (role, clientKind) => {
      const before = await members();
      const created = await request(server, "/api/family-onboarding/invitations", dadPhone, {
        method: "POST", body: { familyId: family.id, mode: "NEW_MEMBER", displayName: "Новый человек", role },
      });
      expect(created.status).toBe(201);
      const uri = new URL(created.body.invitation.invitationUri);
      expect(uri.protocol).toBe("childwatch:");
      expect(uri.hostname).toBe("family");
      expect(uri.pathname).toBe("/join");
      const token = uri.searchParams.get("token");
      const preview = await request(server, `/api/family-onboarding/invitations/${token}`, momNewPhone);
      expect(preview.body.invitation.member.role).toBe(role);
      expect(preview.body.invitation.family.id).toBe(family.id);
      expect(await members()).toHaveLength(before.length);
      const joined = await accept(token, momNewPhone, clientKind);
      expect(joined.status).toBe(200);
      expect(joined.body.member.role).toBe(role);
      expect(joined.body.member.displayName).toBe("Новый человек");
      expect(await members()).toHaveLength(before.length + 1);
      expect((await accept(token, strangerPhone, clientKind)).status).toBe(409);
      expect(await members()).toHaveLength(before.length + 1);
    }
  );

  test("wrong adult app does not consume a child invitation", async () => {
    const invitation = await onboarding.createInvitation(dadPhone, {
      familyId: family.id, mode: "NEW_MEMBER", displayName: "Ребёнок", role: "CHILD",
    });
    expect((await accept(invitation.token, childPhone, "PARENT_MONITOR")).body.code).toBe("APP_ROLE_MISMATCH");
    expect((await accept(invitation.token, childPhone, "CHILD_DEVICE")).status).toBe(200);
  });
});
