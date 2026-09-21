const DatabaseManager = require("../database/DatabaseManager");
const ChatConversationService = require("../services/ChatConversationService");

jest.setTimeout(30_000);

/**
 * Shared settings of a group chat.
 *
 * A group is shared, so one rename has to be visible to every participant, while
 * a direct conversation is personal and must not expose group settings at all.
 */
async function registerFamily(db, suffix) {
  const firstParentDeviceId = `parent-group-${suffix}-0001`;
  const secondParentDeviceId = `parent-group-${suffix}-0002`;
  const childDeviceId = `child-group-${suffix}-0001`;

  await db.registerDevice(firstParentDeviceId, {
    device_name: `First parent ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(secondParentDeviceId, {
    device_name: `Second parent ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(childDeviceId, {
    device_name: `Child ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId: firstParentDeviceId,
    childDeviceId,
    parentDisplayName: `First parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "group-test",
  });
  await db.upsertDeviceLink({
    parentDeviceId: secondParentDeviceId,
    childDeviceId,
    parentDisplayName: `Second parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "group-test",
  });

  const [family] = await db.getFamiliesForDevice(childDeviceId);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(
    devices.map((device) => [device.deviceId, device.memberId])
  );
  const conversation = await db.ensureFamilyConversation(family.id);
  return {
    family,
    conversation,
    firstParentDeviceId,
    secondParentDeviceId,
    childDeviceId,
    memberByDevice,
  };
}

describe("group chat administration", () => {
  let db;
  let service;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    service = new ChatConversationService(db);
  });

  afterEach(async () => {
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("a group chat reports its administrator and shared settings", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "a");

    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );

    expect(settings.familyId).toBeTruthy();
    expect(settings.adminMemberId).toBeTruthy();
    expect(typeof settings.canManage).toBe("boolean");
    expect(settings).toHaveProperty("avatarKey");
  });

  test("the administrator is a member of the family", async () => {
    const { conversation, family, childDeviceId } = await registerFamily(
      db,
      "b"
    );

    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    const members = await db.getFamilyMembers(family.id);

    expect(members.map((member) => member.id)).toContain(settings.adminMemberId);
  });

  test("renaming the group is visible to every participant", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "c");
    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );

    // Rename as whoever administers the group, then read it back as the child.
    const adminDeviceId = await findDeviceForMember(
      db,
      conversation.familyId,
      settings.adminMemberId,
      childDeviceId
    );
    await service.updateGroupTitle(adminDeviceId, conversation.id, {
      title: "Наша семья",
    });

    const childSettings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    expect(childSettings.title).toBe("Наша семья");

    // The conversation list must show the shared name as well.
    const listing = await service.listConversations(childDeviceId);
    const group = listing.conversations.find(
      (item) => item.conversationId === conversation.id
    );
    expect(group.title).toBe("Наша семья");
  });

  test("a non-administrator cannot rename the group", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "d");
    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    const adminDeviceId = await findDeviceForMember(
      db,
      conversation.familyId,
      settings.adminMemberId,
      childDeviceId
    );
    // The other device is not the administrator.
    const otherDeviceId =
      adminDeviceId === childDeviceId
        ? await findOtherDevice(db, conversation.familyId, adminDeviceId)
        : childDeviceId;

    await expect(
      service.updateGroupTitle(otherDeviceId, conversation.id, {
        title: "Захват",
      })
    ).rejects.toMatchObject({ code: "GROUP_ADMIN_REQUIRED" });
  });

  test("an empty group title is refused", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "e");
    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    const adminDeviceId = await findDeviceForMember(
      db,
      conversation.familyId,
      settings.adminMemberId,
      childDeviceId
    );

    await expect(
      service.updateGroupTitle(adminDeviceId, conversation.id, { title: "  " })
    ).rejects.toMatchObject({ code: "GROUP_TITLE_REQUIRED" });
  });

  test("the administrator sets a shared group avatar", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "f");
    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    const adminDeviceId = await findDeviceForMember(
      db,
      conversation.familyId,
      settings.adminMemberId,
      childDeviceId
    );

    const updated = await service.updateGroupAvatar(
      adminDeviceId,
      conversation.id,
      { avatarKey: "preset:corgi" }
    );
    expect(updated.avatarKey).toBe("preset:corgi");

    const childSettings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    expect(childSettings.avatarKey).toBe("preset:corgi");
  });

  test("an unsupported group avatar is refused", async () => {
    const { conversation, childDeviceId } = await registerFamily(db, "g");
    const settings = await service.getGroupSettings(
      childDeviceId,
      conversation.id
    );
    const adminDeviceId = await findDeviceForMember(
      db,
      conversation.familyId,
      settings.adminMemberId,
      childDeviceId
    );

    await expect(
      service.updateGroupAvatar(adminDeviceId, conversation.id, {
        avatarKey: "preset:not-a-preset",
      })
    ).rejects.toMatchObject({ code: "UNSUPPORTED_GROUP_AVATAR" });
  });

  test("a direct conversation has no group settings", async () => {
    const { conversation, family, childDeviceId, memberByDevice } =
      await registerFamily(db, "h");
    const childMemberId = memberByDevice.get(childDeviceId);
    const otherMemberId = (await db.getFamilyMembers(family.id)).find(
      (member) => member.id !== childMemberId
    ).id;

    const direct = await service.createDirectConversation(
      childDeviceId,
      otherMemberId
    );

    await expect(
      service.getGroupSettings(childDeviceId, direct.conversation.conversationId)
    ).rejects.toMatchObject({ code: "NOT_A_GROUP_CONVERSATION" });
    expect(direct.conversation.avatarKey).toBeNull();
    expect(direct.conversation.adminMemberId).toBeNull();
  });
});

/** Finds a device id that belongs to [memberId]; falls back to [fallback]. */
async function findDeviceForMember(db, familyId, memberId, fallback) {
  const devices = await db.getFamilyDevices(familyId);
  const match = devices.find((device) => device.memberId === memberId);
  if (!match) {
    throw new Error("No device found for the group administrator");
  }
  return match.deviceId || fallback;
}

async function findOtherDevice(db, familyId, excludedDeviceId) {
  const devices = await db.getFamilyDevices(familyId);
  const other = devices.find((device) => device.deviceId !== excludedDeviceId);
  if (!other) throw new Error("No second device in the family");
  return other.deviceId;
}
