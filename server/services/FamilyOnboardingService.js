const crypto = require("crypto");

const FAMILY_ROLES = new Set(["PARENT", "CHILD", "GUARDIAN"]);
const INVITATION_MODES = new Set(["NEW_MEMBER", "EXISTING_MEMBER"]);
const CLIENT_KINDS = new Set(["PARENT_MONITOR", "CHILD_DEVICE"]);
const AVATAR_PATTERN = /^preset:(sky|mint|sun|coral|lilac|ocean)$/;
const DEFAULT_INVITATION_TTL_MS = 15 * 60 * 1000;
const MAX_INVITATION_TTL_MS = 60 * 60 * 1000;

class FamilyOnboardingService {
  constructor(dbManager) {
    if (!dbManager) {
      throw new Error("FamilyOnboardingService requires a database manager");
    }
    this.dbManager = dbManager;
  }

  normalizeText(value, maxLength = 100) {
    return String(value || "").trim().replace(/\s+/g, " ").slice(0, maxLength);
  }

  normalizeRole(value, fallback = null) {
    const role = String(value || fallback || "").trim().toUpperCase();
    return FAMILY_ROLES.has(role) ? role : null;
  }

  normalizeAvatar(value) {
    if (value === null || value === undefined || value === "") return null;
    const avatarKey = String(value).trim();
    return AVATAR_PATTERN.test(avatarKey) ? avatarKey : null;
  }

  validationError(message, code) {
    const error = new Error(message);
    error.code = code;
    error.status = 400;
    return error;
  }

  async bootstrapFamily(deviceId, payload = {}) {
    const familyName = this.normalizeText(payload.familyName || "Моя семья");
    const displayName = this.normalizeText(payload.displayName);
    const role = this.normalizeRole(payload.role, "PARENT");
    const avatarKey = this.normalizeAvatar(payload.avatarKey);
    if (familyName.length < 2) {
      throw this.validationError("Family name is too short", "INVALID_FAMILY_NAME");
    }
    if (displayName.length < 2) {
      throw this.validationError("Member name is too short", "INVALID_DISPLAY_NAME");
    }
    if (!role || role === "CHILD") {
      throw this.validationError(
        "A new family must be started by an adult member",
        "INVALID_BOOTSTRAP_ROLE"
      );
    }
    if (payload.avatarKey && !avatarKey) {
      throw this.validationError("Unsupported profile avatar", "INVALID_AVATAR_KEY");
    }
    return this.dbManager.createExplicitFamilyForDevice({
      deviceId,
      familyName,
      displayName,
      role,
      avatarKey,
    });
  }

  async confirmOwnProfile(deviceId, familyId, payload = {}) {
    const normalizedFamilyId = this.normalizeText(familyId, 160);
    const displayName = this.normalizeText(payload.displayName);
    const avatarKey = this.normalizeAvatar(payload.avatarKey);
    if (!normalizedFamilyId) {
      throw this.validationError("Family is required", "FAMILY_REQUIRED");
    }
    if (displayName.length < 2) {
      throw this.validationError("Member name is too short", "INVALID_DISPLAY_NAME");
    }
    if (payload.avatarKey && !avatarKey) {
      throw this.validationError("Unsupported profile avatar", "INVALID_AVATAR_KEY");
    }

    const actor = await this.requireActorMembership(deviceId, normalizedFamilyId);
    if (!["PARENT", "GUARDIAN"].includes(actor.memberRole)) {
      const error = new Error("An adult must confirm this family profile");
      error.code = "FAMILY_PROFILE_CONFIRMATION_DENIED";
      error.status = 403;
      throw error;
    }
    return this.dbManager.confirmOwnProvisionalFamilyMembership({
      familyId: normalizedFamilyId,
      memberId: actor.memberId,
      deviceId,
      displayName,
      avatarKey,
    });
  }

  async requireActorMembership(deviceId, familyId) {
    const membership = await this.dbManager.getFamilyDeviceMembership(
      familyId,
      deviceId
    );
    if (!membership) {
      const error = new Error("Device is not a member of this family");
      error.code = "FAMILY_ACCESS_DENIED";
      error.status = 403;
      throw error;
    }
    return membership;
  }

  async requireAdultActor(deviceId, familyId) {
    const actor = await this.requireActorMembership(deviceId, familyId);
    if (!["PARENT", "GUARDIAN"].includes(actor.memberRole)) {
      const error = new Error("Only an adult family member can manage profiles");
      error.code = "FAMILY_PROFILE_MANAGE_DENIED";
      error.status = 403;
      throw error;
    }
    return actor;
  }

  async listLegacyMigrationCandidates(deviceId, familyIdValue) {
    const familyId = this.normalizeText(familyIdValue, 160);
    if (!familyId) {
      throw this.validationError("Family is required", "FAMILY_REQUIRED");
    }
    await this.requireAdultActor(deviceId, familyId);
    return this.dbManager.getFamilyLegacyMigrationCandidates(familyId);
  }

  async confirmLegacyProfile(deviceId, familyIdValue, memberIdValue, payload = {}) {
    const familyId = this.normalizeText(familyIdValue, 160);
    const memberId = this.normalizeText(memberIdValue, 160);
    const displayName = this.normalizeText(payload.displayName);
    const role = this.normalizeRole(payload.role);
    const avatarKey = this.normalizeAvatar(payload.avatarKey);
    if (!familyId || !memberId) {
      throw this.validationError("Family member is required", "FAMILY_MEMBER_REQUIRED");
    }
    if (displayName.length < 2) {
      throw this.validationError("Member name is too short", "INVALID_DISPLAY_NAME");
    }
    if (!role) {
      throw this.validationError("Family role is required", "INVALID_FAMILY_ROLE");
    }
    if (payload.avatarKey && !avatarKey) {
      throw this.validationError("Unsupported profile avatar", "INVALID_AVATAR_KEY");
    }

    await this.requireAdultActor(deviceId, familyId);
    const result = await this.dbManager.confirmLegacyFamilyMemberProfile({
      familyId,
      memberId,
      displayName,
      role,
      avatarKey,
    });
    if (!result) {
      const error = new Error("Old profile is no longer available for confirmation");
      error.code = "LEGACY_PROFILE_NOT_FOUND";
      error.status = 404;
      throw error;
    }
    return result;
  }

  async createInvitation(deviceId, payload = {}) {
    const familyId = this.normalizeText(payload.familyId, 160);
    const mode = String(payload.mode || "").trim().toUpperCase();
    if (!familyId) {
      throw this.validationError("Family is required", "FAMILY_REQUIRED");
    }
    if (!INVITATION_MODES.has(mode)) {
      throw this.validationError("Unsupported invitation mode", "INVALID_INVITATION_MODE");
    }

    const actor = await this.requireActorMembership(deviceId, familyId);
    const actorIsAdult = ["PARENT", "GUARDIAN"].includes(actor.memberRole);
    let targetMemberId = null;
    let proposedDisplayName = null;
    let proposedRole = null;
    let proposedAvatarKey = null;

    if (mode === "EXISTING_MEMBER") {
      targetMemberId = this.normalizeText(payload.targetMemberId, 160);
      const target = targetMemberId
        ? await this.dbManager.get(
            `SELECT id, display_name AS displayName, role, avatar_key AS avatarKey
             FROM family_members
             WHERE id = ? AND family_id = ? AND is_active = 1 LIMIT 1`,
            [targetMemberId, familyId]
          )
        : null;
      if (!target) {
        throw this.validationError("Family member not found", "FAMILY_MEMBER_NOT_FOUND");
      }
      if (!actorIsAdult && actor.memberId !== target.id) {
        const error = new Error("A member can invite a device only for their own profile");
        error.code = "INVITATION_CREATE_DENIED";
        error.status = 403;
        throw error;
      }
      proposedDisplayName = target.displayName;
      proposedRole = target.role;
      proposedAvatarKey = target.avatarKey || null;
    } else {
      if (!actorIsAdult) {
        const error = new Error("Only an adult family member can add a new person");
        error.code = "INVITATION_CREATE_DENIED";
        error.status = 403;
        throw error;
      }
      proposedDisplayName = this.normalizeText(payload.displayName);
      proposedRole = this.normalizeRole(payload.role);
      proposedAvatarKey = this.normalizeAvatar(payload.avatarKey);
      if (proposedDisplayName.length < 2) {
        throw this.validationError("Member name is too short", "INVALID_DISPLAY_NAME");
      }
      if (!proposedRole) {
        throw this.validationError("Family role is required", "INVALID_FAMILY_ROLE");
      }
      if (payload.avatarKey && !proposedAvatarKey) {
        throw this.validationError("Unsupported profile avatar", "INVALID_AVATAR_KEY");
      }
    }

    const requestedTtl = Number(payload.ttlMs);
    const ttlMs = Number.isFinite(requestedTtl)
      ? Math.max(60_000, Math.min(MAX_INVITATION_TTL_MS, Math.round(requestedTtl)))
      : DEFAULT_INVITATION_TTL_MS;
    const token = crypto.randomBytes(32).toString("hex");
    const id = `invite_${crypto.randomBytes(12).toString("hex")}`;
    const invitation = await this.dbManager.insertFamilyInvitation({
      id,
      token,
      familyId,
      invitationMode: mode,
      targetMemberId,
      proposedDisplayName,
      proposedRole,
      proposedAvatarKey,
      createdByMemberId: actor.memberId,
      createdByDeviceId: deviceId,
      expiresAt: Date.now() + ttlMs,
    });
    return {
      ...this.toPublicInvitation(invitation),
      token,
      invitationUri: `childwatch://family/join?token=${token}`,
    };
  }

  toPublicInvitation(invitation) {
    if (!invitation) return null;
    return {
      id: invitation.id,
      family: {
        id: invitation.familyId,
        name: invitation.familyName,
      },
      mode: invitation.invitationMode,
      member: {
        id:
          invitation.invitationMode === "EXISTING_MEMBER"
            ? invitation.targetMemberId
            : null,
        displayName:
          invitation.targetDisplayName || invitation.proposedDisplayName,
        role: invitation.targetRole || invitation.proposedRole,
        avatarKey:
          invitation.targetAvatarKey || invitation.proposedAvatarKey || null,
      },
      invitedBy: invitation.createdByDisplayName,
      createdAt: invitation.createdAt,
      expiresAt: invitation.expiresAt,
      isExpired: Number(invitation.expiresAt) <= Date.now(),
      isConsumed: Boolean(invitation.consumedAt),
      isRevoked: Boolean(invitation.revokedAt),
    };
  }

  async listActiveInvitations(deviceId, familyIdValue) {
    const familyId = this.normalizeText(familyIdValue, 160);
    const actor = await this.requireActorMembership(deviceId, familyId);
    if (!["PARENT", "GUARDIAN"].includes(actor.memberRole)) {
      const error = new Error("Only an adult family member can manage invitations");
      error.code = "INVITATION_MANAGE_DENIED";
      error.status = 403;
      throw error;
    }
    const invitations = await this.dbManager.getActiveFamilyInvitations(familyId);
    return invitations.map((invitation) => this.toPublicInvitation(invitation));
  }

  async transferFamilyDevice(
    actorDeviceId,
    familyIdValue,
    targetDeviceIdValue,
    payload = {}
  ) {
    const familyId = this.normalizeText(familyIdValue, 160);
    const targetDeviceId = this.normalizeText(targetDeviceIdValue, 200);
    const targetMemberId = this.normalizeText(payload.targetMemberId, 160);
    if (!familyId || !targetDeviceId || !targetMemberId) {
      throw this.validationError(
        "Family, phone and target profile are required",
        "DEVICE_TRANSFER_FIELDS_REQUIRED"
      );
    }
    if (payload.confirmed !== true) {
      throw this.validationError(
        "Adult confirmation is required",
        "DEVICE_TRANSFER_CONFIRMATION_REQUIRED"
      );
    }
    await this.requireAdultActor(actorDeviceId, familyId);
    const result = await this.dbManager.transferFamilyDeviceToMember({
      familyId,
      deviceId: targetDeviceId,
      targetMemberId,
    });
    if (!result) {
      const error = new Error("Confirmed phone or target profile not found");
      error.code = "DEVICE_TRANSFER_TARGET_NOT_FOUND";
      error.status = 404;
      throw error;
    }
    return result;
  }

  async previewInvitation(token) {
    const normalizedToken = String(token || "").trim();
    if (!/^[a-f0-9]{64}$/i.test(normalizedToken)) {
      throw this.validationError("Invalid invitation", "INVALID_INVITATION_TOKEN");
    }
    const invitation = await this.dbManager.getFamilyInvitationByToken(normalizedToken);
    if (!invitation) {
      const error = new Error("Invitation not found");
      error.code = "INVITATION_NOT_FOUND";
      error.status = 404;
      throw error;
    }
    return this.toPublicInvitation(invitation);
  }

  async acceptInvitation(deviceId, token, payload = {}) {
    const invitation = await this.previewInvitation(token);
    const clientKind = String(payload.clientKind || "").trim().toUpperCase();
    if (!CLIENT_KINDS.has(clientKind)) {
      throw this.validationError("Client application is required", "CLIENT_KIND_REQUIRED");
    }
    const role = String(invitation?.member?.role || "").trim().toUpperCase();
    const appMatchesRole =
      (clientKind === "CHILD_DEVICE" && role === "CHILD") ||
      (clientKind === "PARENT_MONITOR" && ["PARENT", "GUARDIAN"].includes(role));
    if (!appMatchesRole) {
      throw this.validationError(
        "Invitation role does not match this ChildWatch application",
        "APP_ROLE_MISMATCH"
      );
    }
    return this.dbManager.consumeFamilyInvitation({
      token,
      deviceId,
      displayName: this.normalizeText(payload.deviceName),
    });
  }

  async revokeInvitation(deviceId, familyId, invitationId) {
    await this.requireAdultActor(deviceId, familyId);
    const revoked = await this.dbManager.revokeFamilyInvitation(
      invitationId,
      familyId
    );
    if (!revoked) {
      const error = new Error("Active invitation not found");
      error.code = "INVITATION_NOT_FOUND";
      error.status = 404;
      throw error;
    }
    return true;
  }
}

FamilyOnboardingService.FAMILY_ROLES = FAMILY_ROLES;
FamilyOnboardingService.INVITATION_MODES = INVITATION_MODES;
FamilyOnboardingService.DEFAULT_INVITATION_TTL_MS = DEFAULT_INVITATION_TTL_MS;

module.exports = FamilyOnboardingService;
