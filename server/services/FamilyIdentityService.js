class FamilyIdentityService {
  constructor(dbManager) {
    if (!dbManager) {
      throw new Error("FamilyIdentityService requires a database manager");
    }
    this.dbManager = dbManager;
  }

  normalizeId(value) {
    return value === null || value === undefined ? "" : String(value).trim();
  }

  normalizeOptional(value) {
    const normalized = this.normalizeId(value);
    return normalized || null;
  }

  toDevicePrincipal(deviceId, storedDevice, authenticatedDeviceData = {}) {
    return {
      deviceId,
      displayName:
        this.normalizeOptional(storedDevice?.device_name) ||
        this.normalizeOptional(authenticatedDeviceData?.deviceName) ||
        "Unknown Device",
      platform:
        this.normalizeOptional(storedDevice?.device_type) ||
        this.normalizeOptional(authenticatedDeviceData?.deviceType),
      appVersion:
        this.normalizeOptional(storedDevice?.app_version) ||
        this.normalizeOptional(authenticatedDeviceData?.appVersion),
    };
  }

  toMembership(row) {
    return {
      familyId: row.familyId,
      memberId: row.memberId,
      family: {
        id: row.familyId,
        name: row.familyName,
        createdAt: row.familyCreatedAt,
        updatedAt: row.familyUpdatedAt,
      },
      member: {
        id: row.memberId,
        familyId: row.familyId,
        displayName: row.memberDisplayName,
        role: row.memberRole,
        avatarKey: row.memberAvatarKey || null,
        isActive: true,
        createdAt: row.memberCreatedAt,
        updatedAt: row.memberUpdatedAt,
      },
      binding: {
        id: row.bindingId,
        familyId: row.familyId,
        memberId: row.memberId,
        deviceId: row.deviceId,
        displayName: row.bindingDisplayName,
        platform: row.bindingPlatform || null,
        lastSeenAt: row.bindingLastSeenAt || null,
        memberBindingSource: row.bindingSource,
        isActive: true,
        createdAt: row.bindingCreatedAt,
        updatedAt: row.bindingUpdatedAt,
      },
    };
  }

  async resolveAuthenticatedDevice(deviceId, authenticatedDeviceData = {}) {
    const normalizedDeviceId = this.normalizeId(deviceId);
    if (!normalizedDeviceId) {
      const error = new Error("Authenticated device is required");
      error.code = "AUTHENTICATED_DEVICE_REQUIRED";
      throw error;
    }

    const [storedDevice, membershipRows] = await Promise.all([
      this.dbManager.getDevice(normalizedDeviceId),
      this.dbManager.getFamilyIdentityMembershipsForDevice(normalizedDeviceId),
    ]);

    return {
      device: this.toDevicePrincipal(
        normalizedDeviceId,
        storedDevice,
        authenticatedDeviceData
      ),
      memberships: (membershipRows || []).map((row) =>
        this.toMembership(row)
      ),
    };
  }

  async updateMemberProfile({
    actorDeviceId,
    familyId,
    memberId,
    displayName,
    avatarKey,
  }) {
    const normalizedActorDeviceId = this.normalizeId(actorDeviceId);
    const normalizedFamilyId = this.normalizeId(familyId);
    const normalizedMemberId = this.normalizeId(memberId);
    const actorMembership = await this.dbManager.getFamilyDeviceMembership(
      normalizedFamilyId,
      normalizedActorDeviceId
    );
    if (!actorMembership) {
      const error = new Error("Device is not a member of this family");
      error.code = "FAMILY_ACCESS_DENIED";
      throw error;
    }

    const canEdit =
      actorMembership.memberId === normalizedMemberId ||
      ["PARENT", "GUARDIAN"].includes(actorMembership.memberRole);
    if (!canEdit) {
      const error = new Error("This family member cannot edit the requested profile");
      error.code = "PROFILE_EDIT_DENIED";
      throw error;
    }

    const member = await this.dbManager.updateFamilyMemberProfile({
      familyId: normalizedFamilyId,
      memberId: normalizedMemberId,
      displayName,
      avatarKey,
    });
    if (!member) {
      const error = new Error("Family member not found");
      error.code = "FAMILY_MEMBER_NOT_FOUND";
      throw error;
    }
    return member;
  }
}

module.exports = FamilyIdentityService;
