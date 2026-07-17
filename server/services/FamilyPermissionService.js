const FAMILY_FEATURES = Object.freeze([
  "CHAT",
  "LOCATION",
  "LOCATION_HISTORY",
  "AUDIO_LISTENING",
  "REMOTE_PHOTO",
  "APP_USAGE",
  "SEND_ATTENTION_SIGNAL",
  "RECEIVE_ATTENTION_SIGNAL",
]);

class FamilyPermissionService {
  constructor(dbManager) {
    if (!dbManager) {
      throw new Error("FamilyPermissionService requires a database manager");
    }
    this.dbManager = dbManager;
  }

  normalizeId(value) {
    return value === null || value === undefined ? "" : String(value).trim();
  }

  normalizeFeature(value) {
    return this.normalizeId(value).toUpperCase();
  }

  async canAccessFamily(deviceId, familyId) {
    const normalizedDeviceId = this.normalizeId(deviceId);
    const normalizedFamilyId = this.normalizeId(familyId);
    if (!normalizedDeviceId || !normalizedFamilyId) return false;
    const membership = await this.dbManager.getFamilyDeviceMembership(
      normalizedFamilyId,
      normalizedDeviceId
    );
    return Boolean(membership);
  }

  async authorizeFeature(actorDeviceId, targetDeviceId, feature) {
    const actor = this.normalizeId(actorDeviceId);
    const target = this.normalizeId(targetDeviceId);
    const normalizedFeature = this.normalizeFeature(feature);

    if (!actor || !target || !normalizedFeature) {
      return { allowed: false, code: "INVALID_PERMISSION_REQUEST" };
    }
    if (!FAMILY_FEATURES.includes(normalizedFeature)) {
      return { allowed: false, code: "UNKNOWN_FAMILY_FEATURE" };
    }
    if (actor === target) {
      return { allowed: true, code: "SELF_DEVICE" };
    }

    const membership = await this.dbManager.getSharedFamilyMembership(
      actor,
      target
    );
    if (!membership) {
      return { allowed: false, code: "CROSS_FAMILY_DENIED" };
    }

    const permission = await this.dbManager.getFamilyPermission({
      familyId: membership.familyId,
      actorMemberId: membership.actorMemberId,
      targetMemberId: membership.targetMemberId,
      feature: normalizedFeature,
    });

    return {
      allowed: permission?.allowed === 1,
      code: permission?.allowed === 1 ? "FAMILY_PERMISSION_GRANTED" : "FAMILY_PERMISSION_DENIED",
      familyId: membership.familyId,
    };
  }
}

FamilyPermissionService.FAMILY_FEATURES = FAMILY_FEATURES;

module.exports = FamilyPermissionService;
