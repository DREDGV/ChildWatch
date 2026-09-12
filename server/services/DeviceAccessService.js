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
    if (requested === caller) {
      return { allowed: true, deviceId: requested };
    }
    if (await this.hasActiveLink(caller, requested)) {
      return { allowed: true, deviceId: requested };
    }
    return { allowed: false, deviceId: "", code: "DEVICE_ACCESS_DENIED" };
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
    if (requested === caller) {
      return { allowed: true, deviceId: requested };
    }
    if (await this.hasAnyActiveLink(caller, requested)) {
      return { allowed: true, deviceId: requested };
    }
    return { allowed: false, deviceId: "", code: "DEVICE_ACCESS_DENIED" };
  }

  /** True when either device is an active parent of the other. */
  async hasAnyActiveLink(firstDeviceId, secondDeviceId) {
    if (await this.hasActiveLink(firstDeviceId, secondDeviceId)) {
      return true;
    }
    return this.hasActiveLink(secondDeviceId, firstDeviceId);
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
