const DatabaseManager = require('../database/DatabaseManager');
describe('confirmed profile survives compatibility registration', () => {
  let db, identity;
  beforeEach(async () => {
    db = new DatabaseManager(':memory:');
    await db.initialize();
    for (const id of ['profile-replay-phone', 'profile-other-phone', 'profile-child-phone']) {
      await db.registerDevice(id, { device_name: 'Phone', device_type: 'android' });
    }
    const created = await db.createExplicitFamilyForDevice({ deviceId: 'profile-replay-phone', familyName: 'Family', displayName: 'Родитель', role: 'PARENT' });
    identity = { familyId: created.family.id, memberId: created.member.id };
    await db.updateFamilyMemberProfile({ ...identity, displayName: 'Баба Саша', avatarKey: 'preset:cat' });
  });
  afterEach(async () => { await db.close(); });
  async function current() { return db.get('SELECT display_name AS displayName, avatar_key AS avatarKey, role FROM family_members WHERE id=? AND family_id=?', [identity.memberId,identity.familyId]); }
  async function addLegacyPhone() {
    await db.run(`INSERT INTO family_devices (id,family_id,member_id,device_id,display_name,member_binding_source,is_active)
      VALUES (?,?,?,?,?,'LEGACY_BOOTSTRAP',1)`, ['legacy-test-phone',identity.familyId,identity.memberId,'profile-other-phone','Phone']);
  }
  test('stale onboarding replay keeps edited name and avatar', async () => {
    const replay = await db.confirmOwnProvisionalFamilyMembership({ ...identity, deviceId: 'profile-replay-phone', displayName: 'Родитель', avatarKey: null });
    expect(replay.member).toMatchObject({ displayName: 'Баба Саша', avatarKey: 'preset:cat', role: 'PARENT' });
  });
  test('disconnecting original confirmed phone does not unlock name overwrite', async () => {
    await addLegacyPhone();
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', ['profile-replay-phone']);
    await db.syncFamilyMemberDisplayName('profile-other-phone','Родитель');
    expect(await current()).toMatchObject({ displayName: 'Баба Саша', avatarKey: 'preset:cat' });
  });
  test('legacy confirmation cannot change an already confirmed person through another phone', async () => {
    await addLegacyPhone();
    await db.upsertDeviceLink({ parentDeviceId:'profile-other-phone',childDeviceId:'profile-child-phone',parentDisplayName:'Родитель' });
    const result = await db.confirmLegacyFamilyMemberProfile({ ...identity, displayName:'Родитель',role:'CHILD',avatarKey:null });
    expect(result).toBeNull();
    expect(await current()).toMatchObject({ displayName:'Баба Саша',role:'PARENT',avatarKey:'preset:cat' });
  });
  test('repeated registration retains canonical labels for legacy readers', async () => {
    await db.upsertDeviceLink({ parentDeviceId:'profile-replay-phone',childDeviceId:'profile-child-phone',parentDisplayName:'Родитель' });
    await db.upsertDeviceLink({ parentDeviceId:'profile-replay-phone',childDeviceId:'profile-child-phone',parentDisplayName:'Старое имя' });
    const link = await db.get('SELECT parent_display_name FROM device_links WHERE parent_device_id=? AND child_device_id=?',['profile-replay-phone','profile-child-phone']);
    expect(link.parent_display_name).toBe('Баба Саша');
    expect(await current()).toMatchObject({ displayName:'Баба Саша',avatarKey:'preset:cat' });
  });
});
