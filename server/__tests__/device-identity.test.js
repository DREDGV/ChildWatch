const DatabaseManager = require("../database/DatabaseManager");
const DeviceAccessService = require("../services/DeviceAccessService");

/**
 * The installed clients do not agree on one device-id spelling: a device token
 * is registered as `device_<androidId>` while ParentMonitor uploads its own
 * position as `<androidId>`. Treating the two spellings as different devices
 * refused the device's own request, which stopped the parent position from
 * being stored. These tests pin the equivalence and, just as importantly, pin
 * the narrowness that keeps it from becoming a way to reach another device.
 */
describe("device identity equivalence", () => {
  const parentPrefixed = "device_15e991bb5d8f906d";
  const parentBare = "15e991bb5d8f906d";
  const childId = "child-c7d50e08";
  let db;
  let service;

  beforeEach(async () => {
    db = new DatabaseManager(":memory:");
    await db.initialize();
    service = new DeviceAccessService(db);
  });

  afterEach(async () => {
    await db.close();
  });

  test("a token spelling and a bare android id are the same device", () => {
    expect(service.isSameDevice(parentPrefixed, parentBare)).toBe(true);
    expect(service.isSameDevice(parentBare, parentPrefixed)).toBe(true);
    expect(service.isSameDevice(parentPrefixed, parentPrefixed)).toBe(true);
  });

  test("different devices stay different", () => {
    expect(service.isSameDevice(parentPrefixed, childId)).toBe(false);
    // One character apart must not be treated as the same phone.
    expect(service.isSameDevice(parentPrefixed, "15e991bb5d8f906e")).toBe(false);
    // A foreign prefix is never collapsed.
    expect(service.isSameDevice(parentPrefixed, `child_${parentBare}`)).toBe(false);
    // A non android-id remainder is not a device prefix at all.
    expect(service.isSameDevice("device_zzzz", "zzzz")).toBe(false);
  });

  test("self access is granted even when the spellings differ", async () => {
    const decision = await service.authorizeDeviceAccess(parentPrefixed, parentBare);
    expect(decision).toMatchObject({ allowed: true, deviceId: parentBare });
  });

  test("a link is honoured regardless of the spelling on either side", async () => {
    await db.registerDevice(parentPrefixed, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.upsertDeviceLink({ parentDeviceId: parentPrefixed, childDeviceId: childId });

    // Parent writes under the bare spelling: still its own device.
    const write = await service.authorizeDeviceAccess(parentPrefixed, parentBare);
    expect(write.allowed).toBe(true);

    // Parent reads the child, and the child reads the parent.
    const parentReadsChild = await service.authorizeRelatedDeviceRead(parentBare, childId);
    expect(parentReadsChild.allowed).toBe(true);
    const childReadsParent = await service.authorizeRelatedDeviceRead(childId, parentBare);
    expect(childReadsParent.allowed).toBe(true);
  });

  test("an unrelated device is still refused, in every spelling", async () => {
    await db.registerDevice(parentPrefixed, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice("device_0123456789abcdef", {
      device_name: "Stranger",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.upsertDeviceLink({ parentDeviceId: parentPrefixed, childDeviceId: childId });

    for (const requested of [childId, parentBare, parentPrefixed]) {
      const decision = await service.authorizeDeviceAccess("device_0123456789abcdef", requested);
      expect(decision.allowed).toBe(false);
      expect(decision.code).toBe("DEVICE_ACCESS_DENIED");
    }
  });

  test("the id forms listed for a device cover both spellings", () => {
    expect(service.idForms(parentPrefixed).sort()).toEqual([parentBare, parentPrefixed].sort());
    expect(service.idForms(parentBare).sort()).toEqual([parentBare, parentPrefixed].sort());
    expect(service.idForms(childId)).toEqual([childId]);
  });
});
