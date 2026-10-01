/**
 * DeviceAccessService - shared device-level authorization for the media,
 * streaming and alert routers.
 *
 * The legacy routers trusted the `deviceId` that arrived in the body or the
 * path, so any caller who knew an identifier could read another family's
 * media or drive its audio stream. Those identifiers cannot simply be replaced
 * by the caller identity: a parent legitimately acts on the child device, and
 * the target therefore has to stay in the request while being verified.
 *
 * The rule is deliberately small and closed by default: a device may act on
 * itself, or on a device it is actively linked to as a parent. Family
 * membership alone is not enough here, because it would let one family member
 * reach another member's private device data without an explicit link.
 */
class DeviceAccessService {
  constructor(dbManager) {
    this.dbManager = dbManager;
  }

  normalizeDeviceId(value) {
    if (value === null || value === undefined) return "";
    return String(value).trim();
  }

  async hasActiveLink(parentDeviceId, childDeviceId) {
    const parent = this.normalizeDeviceId(parentDeviceId);
    const child = this.normalizeDeviceId(childDeviceId);
    if (!parent || !child) return false;
    const link = await this.dbManager.get(
      "SELECT 1 AS linked FROM device_links WHERE parent_device_id = ? AND child_device_id = ? AND is_active = 1 LIMIT 1",
      [parent, child]
    );
    return Boolean(link);
  }

  /**
   * True when two identifiers name the same physical device.
   *
   * The installed clients are inconsistent: a device token is registered as
   * `device_<androidId>` while ParentMonitor still uploads its own position as
   * `<androidId>`. Both spellings have been receiving writes in production, so
   * treating them as different devices does not protect anything - it only
   * refuses the device's own request.
   *
   * The equivalence is deliberately narrow: the prefix is only ignored when the
   * remainder looks like a raw Android id (16 hex characters). Invented ids
   * such as `child-c7d50e08` are never collapsed, so this cannot be used to
   * reach a device that merely has a similar name.
   */
  isSameDevice(firstDeviceId, secondDeviceId) {
    const first = this.normalizeDeviceId(firstDeviceId);
    const second = this.normalizeDeviceId(secondDeviceId);
    if (!first || !second) return false;
    if (first === second) return true;

    const firstBare = this.rawAndroidId(first);
    const secondBare = this.rawAndroidId(second);
    if (firstBare && firstBare === second) return true;
    if (secondBare && secondBare === first) return true;
    if (firstBare && secondBare && firstBare === secondBare) return true;
    return false;
  }

  /** Returns the bare Android id behind `device_<androidId>`, or "" otherwise. */
  rawAndroidId(deviceId) {
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized.startsWith("device_")) return "";
    const remainder = normalized.slice("device_".length);
    return /^[0-9a-fA-F]{16}$/.test(remainder) ? remainder.toLowerCase() : "";
  }

  /**
   * True when this device's access has been taken away.
   *
   * Revoking a session on its own lasted about a minute: the installed clients
   * register themselves again whenever a request comes back 401, so a phone
   * whose access was revoked walked straight back in with a fresh token.
   * Registration asks this first. The device row is only flagged, never deleted,
   * so the decision stays visible and can be undone.
   *
   * Every spelling of the identifier has to be revoked before the device counts
   * as revoked: a row written under `device_<androidId>` and one under the bare
   * id describe the same phone, and clearing only one of them would leave a way
   * back in.
   */
  async isDeviceRevoked(deviceId) {
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized) return false;
    const forms = this.idForms(normalized);
    const placeholders = forms.map(() => "?").join(", ");
    const row = await this.dbManager.get(
      `SELECT COUNT(*) AS total,
              SUM(CASE WHEN is_active = 0 THEN 1 ELSE 0 END) AS revoked
       FROM devices WHERE device_id IN (${placeholders})`,
      forms
    );
    const total = Number(row?.total || 0);
    if (total === 0) return false;
    return Number(row?.revoked || 0) === total;
  }

  /**
   * @returns {Promise<{allowed: boolean, deviceId: string, code?: string}>}
   *   `deviceId` is the verified target, safe to act on.
   */
  async authorizeDeviceAccess(callerDeviceId, requestedDeviceId) {
    const caller = this.normalizeDeviceId(callerDeviceId);
    const requested = this.normalizeDeviceId(requestedDeviceId);

    if (!caller) {
      return { allowed: false, deviceId: "", code: "AUTH_REQUIRED" };
    }
    if (!requested) {
      return { allowed: false, deviceId: "", code: "MISSING_DEVICE_ID" };
    }
    if (this.isSameDevice(requested, caller)) {
      return { allowed: true, deviceId: requested };
    }
    if (await this.sharesLinkWithAnySpelling(caller, requested)) {
      return { allowed: true, deviceId: requested };
    }
    return { allowed: false, deviceId: "", code: "DEVICE_ACCESS_DENIED" };
  }

  /**
   * Link lookup that tolerates the two identifier spellings on either side of
   * the relationship, so an existing link keeps working regardless of which
   * form each client sends.
   */
  async sharesLinkWithAnySpelling(callerDeviceId, requestedDeviceId) {
    const callerForms = this.idForms(callerDeviceId);
    const requestedForms = this.idForms(requestedDeviceId);
    for (const caller of callerForms) {
      for (const requested of requestedForms) {
        if (caller === requested) continue;
        if (await this.hasActiveLink(caller, requested)) return true;
        if (await this.hasActiveLink(requested, caller)) return true;
      }
    }
    return false;
  }

  /** All spellings of one device id that may appear in stored rows. */
  idForms(deviceId) {
    const normalized = this.normalizeDeviceId(deviceId);
    if (!normalized) return [];
    const bare = this.rawAndroidId(normalized);
    if (bare) return [normalized, bare];
    if (/^[0-9a-fA-F]{16}$/.test(normalized)) {
      return [normalized, `device_${normalized.toLowerCase()}`];
    }
    return [normalized];
  }

  /**
   * Express-friendly wrapper. Responds and returns null when access is
   * refused, otherwise returns the verified target device id.
   */
  async requireDeviceAccess(req, res, requestedDeviceId) {
    const decision = await this.authorizeDeviceAccess(req.deviceId, requestedDeviceId);
    if (decision.allowed) {
      return decision.deviceId;
    }
    return this.respondToDenial(res, decision.code);
  }

  async isAudioTargetChild(deviceId) {
    const forms = this.idForms(deviceId);
    if (!forms.length) return false;
    const rows = await this.dbManager.all(
      `SELECT fm.role FROM family_devices fd
       JOIN family_members fm ON fm.id = fd.member_id
       JOIN families f ON f.id = fd.family_id
       WHERE fd.device_id IN (${forms.map(() => "?").join(",")})
         AND fd.is_active = 1 AND fm.is_active = 1 AND f.is_active = 1`,
      forms
    );
    return rows.length > 0 && rows.every((row) => String(row.role).toUpperCase() === "CHILD");
  }

  async authorizeAudioAccess(callerDeviceId, requestedDeviceId) {
    const caller = this.normalizeDeviceId(callerDeviceId);
    const target = this.normalizeDeviceId(requestedDeviceId);
    if (!caller) return { allowed: false, code: "AUTH_REQUIRED" };
    if (!target) return { allowed: false, code: "MISSING_DEVICE_ID" };
    if (!(await this.isAudioTargetChild(target))) {
      return { allowed: false, code: "AUDIO_TARGET_NOT_CHILD" };
    }
    // The child itself may upload its own stream. Listening requires an adult
    // in the same family and an explicit AUDIO_LISTENING permission.
    if (this.isSameDevice(caller, target)) return { allowed: true, deviceId: target };
    for (const actorId of this.idForms(caller)) {
      for (const targetId of this.idForms(target)) {
        const membership = await this.dbManager.getSharedFamilyMembership(actorId, targetId);
        if (!membership || !["PARENT", "GUARDIAN"].includes(membership.actorRole) ||
            membership.targetRole !== "CHILD") continue;
        const permission = await this.dbManager.getFamilyPermission({
          familyId: membership.familyId,
          actorMemberId: membership.actorMemberId,
          targetMemberId: membership.targetMemberId,
          feature: "AUDIO_LISTENING",
        });
        if (permission?.allowed === 1) return { allowed: true, deviceId: target };
      }
    }
    return { allowed: false, code: "AUDIO_PERMISSION_DENIED" };
  }

  async authorizePhotoAccess(callerDeviceId, requestedDeviceId) {
    const caller = this.normalizeDeviceId(callerDeviceId);
    const target = this.normalizeDeviceId(requestedDeviceId);
    if (!caller) return { allowed: false, code: "AUTH_REQUIRED" };
    if (!target) return { allowed: false, code: "MISSING_DEVICE_ID" };
    if (!(await this.isAudioTargetChild(target))) {
      return { allowed: false, code: "PHOTO_TARGET_NOT_CHILD" };
    }
    // The child itself may read its own photos. Remote capture requires an adult
    // in the same family and an explicit REMOTE_PHOTO permission.
    if (this.isSameDevice(caller, target)) return { allowed: true, deviceId: target };
    for (const actorId of this.idForms(caller)) {
      for (const targetId of this.idForms(target)) {
        const membership = await this.dbManager.getSharedFamilyMembership(actorId, targetId);
        if (!membership || !["PARENT", "GUARDIAN"].includes(membership.actorRole) ||
            membership.targetRole !== "CHILD") continue;
        const permission = await this.dbManager.getFamilyPermission({
          familyId: membership.familyId,
          actorMemberId: membership.actorMemberId,
          targetMemberId: membership.targetMemberId,
          feature: "REMOTE_PHOTO",
        });
        if (permission?.allowed === 1) return { allowed: true, deviceId: target };
      }
    }
    return { allowed: false, code: "PHOTO_PERMISSION_DENIED" };
  }

  async requirePhotoAccess(req, res, target) {
    const decision = await this.authorizePhotoAccess(req.deviceId,target);
    if (decision.allowed) return decision.deviceId;
    this.respondToDenial(res, decision.code); return null;
  }

  async authorizeFileAccess(callerDeviceId, fileDeviceId) {
    return this.authorizeDeviceAccess(callerDeviceId, fileDeviceId);
  }

  /**
   * Symmetric variant for read-only endpoints.
   *
   * A parent reads its child's position, and the child app reads its linked
   * parent's position, so a directed parent->child check would deny the second
   * case. Only the existence of an active link is required here, and callers
   * must use it for reads only: writes stay directed so a child can never
   * publish a position on behalf of a parent.
   */
  async authorizeRelatedDeviceRead(callerDeviceId, requestedDeviceId) {
    const caller = this.normalizeDeviceId(callerDeviceId);
    const requested = this.normalizeDeviceId(requestedDeviceId);

    if (!caller) {
      return { allowed: false, deviceId: "", code: "AUTH_REQUIRED" };
    }
    if (!requested) {
      return { allowed: false, deviceId: "", code: "MISSING_DEVICE_ID" };
    }
    if (this.isSameDevice(requested, caller)) {
      return { allowed: true, deviceId: requested };
    }
    if (await this.sharesLinkWithAnySpelling(caller, requested)) {
      return { allowed: true, deviceId: requested };
    }
    return { allowed: false, deviceId: "", code: "DEVICE_ACCESS_DENIED" };
  }

  /** Express-friendly wrapper for {@link authorizeRelatedDeviceRead}. */
  async requireRelatedDeviceRead(req, res, requestedDeviceId) {
    const decision = await this.authorizeRelatedDeviceRead(req.deviceId, requestedDeviceId);
    if (decision.allowed) {
      return decision.deviceId;
    }
    return this.respondToDenial(res, decision.code);
  }

  respondToDenial(res, code) {
    if (code === "AUTH_REQUIRED") {
      res.status(401).json({
        error: "Authentication required",
        code: "AUTH_REQUIRED"
      });
      return null;
    }
    if (code === "MISSING_DEVICE_ID") {
      res.status(400).json({
        error: "deviceId is required",
        code: "MISSING_DEVICE_ID"
      });
      return null;
    }
    res.status(403).json({
      error: "Device access denied",
      code: "DEVICE_ACCESS_DENIED"
    });
    return null;
  }
}

module.exports = DeviceAccessService;
