const DatabaseManager = require("../database/DatabaseManager");
const FamilyPermissionService = require("../services/FamilyPermissionService");

async function registerPair(db, suffix) {
  const parentDeviceId = `parent-device-${suffix}`;
  const childDeviceId = `child-device-${suffix}`;
  await db.registerDevice(parentDeviceId, {
    device_name: `Parent ${suffix}`,
    device_type: "android",
    app_version: "7.2.0",
  });
  await db.registerDevice(childDeviceId, {
    device_name: `Child ${suffix}`,
    device_type: "android",
    app_version: "7.2.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId,
    childDeviceId,
    parentDisplayName: `Parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "test",
  });
  return { parentDeviceId, childDeviceId };
}

describe("family model bootstrap", () => {
  let db;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
  });

  afterEach(async () => {
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("bootstraps a family from a legacy device link", async () => {
    const pair = await registerPair(db, "alpha");
    const families = await db.getFamiliesForDevice(pair.parentDeviceId);

    expect(families).toHaveLength(1);
    const members = await db.getFamilyMembers(families[0].id);
    const devices = await db.getFamilyDevices(families[0].id);

    expect(members).toHaveLength(2);
    expect(devices).toHaveLength(2);
    expect(devices.map((device) => device.deviceId).sort()).toEqual(
      [pair.childDeviceId, pair.parentDeviceId].sort()
    );
  });

  test("repeated migration is idempotent", async () => {
    await registerPair(db, "repeat");
    await db.bootstrapFamiliesFromDeviceLinks();
    const before = {
      families: (await db.get("SELECT COUNT(*) AS count FROM families")).count,
      members: (await db.get("SELECT COUNT(*) AS count FROM family_members")).count,
      devices: (await db.get("SELECT COUNT(*) AS count FROM family_devices")).count,
      permissions: (
        await db.get("SELECT COUNT(*) AS count FROM family_permissions")
      ).count,
    };

    await db.bootstrapFamiliesFromDeviceLinks();
    await db.bootstrapFamiliesFromDeviceLinks();

    expect({
      families: (await db.get("SELECT COUNT(*) AS count FROM families")).count,
      members: (await db.get("SELECT COUNT(*) AS count FROM family_members")).count,
      devices: (await db.get("SELECT COUNT(*) AS count FROM family_devices")).count,
      permissions: (
        await db.get("SELECT COUNT(*) AS count FROM family_permissions")
      ).count,
    }).toEqual(before);
  });

  test("skips a full legacy rebuild when every active link is already projected", async () => {
    await registerPair(db, "projection-current");
    const rebuildSpy = jest.spyOn(db, "performFamilyBootstrap");

    await expect(db.bootstrapFamiliesFromDeviceLinks()).resolves.toMatchObject({
      skipped: true,
      activeLinks: 1,
    });
    expect(rebuildSpy).not.toHaveBeenCalled();

    rebuildSpy.mockRestore();
  });

  test("keeps canonical family member names in sync with an existing link", async () => {
    const pair = await registerPair(db, "rename");
    const before = await db.getSharedFamilyMembership(
      pair.parentDeviceId,
      pair.childDeviceId
    );
    expect(before).toMatchObject({
      actorDisplayName: "Parent rename",
      targetDisplayName: "Child rename",
    });
    const conversation = await db.ensureFamilyConversation(before.familyId);
    const legacyLabelMessage = await db.insertChatMessageV2({
      conversationId: conversation.id,
      senderMemberId: before.targetMemberId,
      senderDeviceId: pair.childDeviceId,
      senderRoleSnapshot: "CHILD",
      senderDisplayName: "Old phone label",
      clientMessageId: "rename-message",
      text: "Проверка имени",
      clientSentAt: Date.now(),
    });

    await db.run(
      "UPDATE family_members SET display_name = 'Old phone label' WHERE id IN (?, ?)",
      [before.actorMemberId, before.targetMemberId]
    );

    await db.upsertDeviceLink({
      parentDeviceId: pair.parentDeviceId,
      childDeviceId: pair.childDeviceId,
      parentDisplayName: "Марина",
      childDisplayName: "Лёва",
      createdBy: "rename-test",
    });

    await expect(
      db.getSharedFamilyMembership(pair.parentDeviceId, pair.childDeviceId)
    ).resolves.toMatchObject({
      actorDisplayName: "Марина",
      targetDisplayName: "Лёва",
    });
    await expect(
      db.getChatMessageV2ById(legacyLabelMessage.message.id)
    ).resolves.toMatchObject({ senderDisplayName: "Лёва" });
  });

  test("keeps people and their physical devices as separate records", async () => {
    const pair = await registerPair(db, "separate");
    const [family] = await db.getFamiliesForDevice(pair.parentDeviceId);
    const members = await db.getFamilyMembers(family.id);
    const devices = await db.getFamilyDevices(family.id);
    const memberIds = new Set(members.map((member) => member.id));

    expect(members.every((member) => !member.id.includes("parent-device"))).toBe(
      true
    );
    expect(devices.every((device) => memberIds.has(device.memberId))).toBe(true);
    expect(devices.every((device) => device.id !== device.memberId)).toBe(true);
  });

  test("denies feature access across unrelated families", async () => {
    const first = await registerPair(db, "one");
    const second = await registerPair(db, "two");
    const permissions = new FamilyPermissionService(db);

    await expect(
      permissions.authorizeFeature(
        first.parentDeviceId,
        first.childDeviceId,
        "AUDIO_LISTENING"
      )
    ).resolves.toMatchObject({ allowed: true });

    await expect(
      permissions.authorizeFeature(
        first.parentDeviceId,
        second.childDeviceId,
        "AUDIO_LISTENING"
      )
    ).resolves.toEqual({ allowed: false, code: "CROSS_FAMILY_DENIED" });
  });

  test("additional parent sharing a child joins the existing family", async () => {
    const pair = await registerPair(db, "shared");
    const secondParentId = "parent-device-second";
    await db.registerDevice(secondParentId, {
      device_name: "Second parent",
      device_type: "android",
      app_version: "7.2.0",
    });
    await db.upsertDeviceLink({
      parentDeviceId: secondParentId,
      childDeviceId: pair.childDeviceId,
      createdBy: "test",
    });

    const firstFamilies = await db.getFamiliesForDevice(pair.parentDeviceId);
    const secondFamilies = await db.getFamiliesForDevice(secondParentId);
    expect(secondFamilies[0].id).toBe(firstFamilies[0].id);
    expect(await db.getFamilyMembers(firstFamilies[0].id)).toHaveLength(3);
  });

  test("hides stale provisional reinstall identities without deleting history", async () => {
    const pair = await registerPair(db, "visible-current");
    const staleParentId = "parent-device-stale-reinstall";
    await db.registerDevice(staleParentId, {
      device_name: "Old reinstalled phone",
      device_type: "android",
      app_version: "6.0.0",
    });
    await db.upsertDeviceLink({
      parentDeviceId: staleParentId,
      childDeviceId: pair.childDeviceId,
      parentDisplayName: "Old phone identity",
      createdBy: "legacy-test",
    });

    const [family] = await db.getFamiliesForDevice(pair.parentDeviceId);
    const staleMembership = await db.getFamilyDeviceMembership(
      family.id,
      staleParentId
    );
    await db.run(
      `UPDATE device_links
       SET updated_at = strftime('%s', 'now') - (45 * 24 * 60 * 60)
       WHERE parent_device_id = ? AND child_device_id = ?`,
      [staleParentId, pair.childDeviceId]
    );

    expect(
      (await db.get("SELECT COUNT(*) AS count FROM family_members")).count
    ).toBe(3);
    expect(await db.getFamilyMembers(family.id)).toHaveLength(2);
    expect(await db.getFamilyDevices(family.id)).toHaveLength(2);
    expect(await db.getChatFamilyMembers(family.id)).toHaveLength(2);
    expect(await db.getFamiliesForDevice(staleParentId)).toHaveLength(0);
    expect(await db.getFamilyIdentityMembershipsForDevice(staleParentId)).toHaveLength(0);

    await db.updateFamilyMemberProfile({
      familyId: family.id,
      memberId: staleMembership.memberId,
      displayName: "Grandmother",
    });

    expect(await db.getFamilyMembers(family.id)).toHaveLength(3);
    expect(await db.getFamilyDevices(family.id)).toHaveLength(3);
    expect(await db.getFamiliesForDevice(staleParentId)).toHaveLength(1);
  });

  test("bootstrap preserves an explicit permission denial", async () => {
    const pair = await registerPair(db, "denied");
    const membership = await db.getSharedFamilyMembership(
      pair.parentDeviceId,
      pair.childDeviceId
    );
    await db.upsertFamilyPermission({
      familyId: membership.familyId,
      actorMemberId: membership.actorMemberId,
      targetMemberId: membership.targetMemberId,
      feature: "REMOTE_PHOTO",
      allowed: false,
    });

    await db.bootstrapFamiliesFromDeviceLinks();
    const decision = await new FamilyPermissionService(db).authorizeFeature(
      pair.parentDeviceId,
      pair.childDeviceId,
      "REMOTE_PHOTO"
    );

    expect(decision).toMatchObject({
      allowed: false,
      code: "FAMILY_PERMISSION_DENIED",
    });
  });

  test("a bridge link consolidates two legacy components without deleting history", async () => {
    const first = await registerPair(db, "bridge-one");
    const second = await registerPair(db, "bridge-two");

    await db.upsertDeviceLink({
      parentDeviceId: first.parentDeviceId,
      childDeviceId: second.childDeviceId,
      createdBy: "test-bridge",
    });

    const firstFamilies = await db.getFamiliesForDevice(first.childDeviceId);
    const secondFamilies = await db.getFamiliesForDevice(second.childDeviceId);
    expect(firstFamilies).toHaveLength(1);
    expect(secondFamilies).toHaveLength(1);
    expect(secondFamilies[0].id).toBe(firstFamilies[0].id);
    expect(
      (await db.get("SELECT COUNT(*) AS count FROM families")).count
    ).toBe(2);
    expect(
      (
        await db.get(
          "SELECT COUNT(*) AS count FROM families WHERE is_active = 1"
        )
      ).count
    ).toBe(1);
  });
});
