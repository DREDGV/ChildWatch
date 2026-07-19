const express = require("express");

const FAMILY_ID_PATTERN = /^[A-Za-z0-9_-]{8,100}$/;
const MEMBER_ID_PATTERN = /^[A-Za-z0-9_-]{8,160}$/;
const PROFILE_AVATAR_PATTERN = /^preset:(sky|mint|sun|coral|lilac|ocean)$/;

function createFamilyRoutes(dbManager, permissionService, identityService) {
  if (!dbManager || !permissionService || !identityService) {
    throw new Error(
      "Family routes require database, permission and identity services"
    );
  }

  const router = express.Router();

  const requireFamilyAccess = async (req, res, next) => {
    try {
      const familyId = String(req.params.familyId || "").trim();
      if (!FAMILY_ID_PATTERN.test(familyId)) {
        return res.status(400).json({
          error: "Invalid family id",
          code: "INVALID_FAMILY_ID",
        });
      }
      const allowed = await permissionService.canAccessFamily(
        req.deviceId,
        familyId
      );
      if (!allowed) {
        return res.status(403).json({
          error: "Device is not a member of this family",
          code: "FAMILY_ACCESS_DENIED",
        });
      }
      req.familyId = familyId;
      next();
    } catch (error) {
      next(error);
    }
  };

  router.get("/", async (req, res, next) => {
    try {
      const families = await dbManager.getFamiliesForDevice(req.deviceId);
      res.json({ success: true, families });
    } catch (error) {
      next(error);
    }
  });

  router.get("/:familyId", requireFamilyAccess, async (req, res, next) => {
    try {
      const family = await dbManager.getFamilyById(req.familyId);
      if (!family) {
        return res.status(404).json({
          error: "Family not found",
          code: "FAMILY_NOT_FOUND",
        });
      }
      res.json({ success: true, family });
    } catch (error) {
      next(error);
    }
  });

  router.get(
    "/:familyId/members",
    requireFamilyAccess,
    async (req, res, next) => {
      try {
        const members = await dbManager.getFamilyMembers(req.familyId);
        res.json({ success: true, familyId: req.familyId, members });
      } catch (error) {
        next(error);
      }
    }
  );

  router.patch(
    "/:familyId/members/:memberId",
    requireFamilyAccess,
    async (req, res, next) => {
      try {
        const memberId = String(req.params.memberId || "").trim();
        if (!MEMBER_ID_PATTERN.test(memberId)) {
          return res.status(400).json({
            error: "Invalid member id",
            code: "INVALID_MEMBER_ID",
          });
        }

        const hasDisplayName = Object.prototype.hasOwnProperty.call(
          req.body || {},
          "displayName"
        );
        const hasAvatarKey = Object.prototype.hasOwnProperty.call(
          req.body || {},
          "avatarKey"
        );
        if (!hasDisplayName && !hasAvatarKey) {
          return res.status(400).json({
            error: "At least one profile field is required",
            code: "PROFILE_UPDATE_EMPTY",
          });
        }

        let displayName;
        if (hasDisplayName) {
          displayName = String(req.body.displayName || "").trim();
          if (displayName.length < 2 || displayName.length > 100) {
            return res.status(400).json({
              error: "Display name must contain 2 to 100 characters",
              code: "INVALID_DISPLAY_NAME",
            });
          }
        }

        let avatarKey;
        if (hasAvatarKey) {
          avatarKey = req.body.avatarKey;
          if (avatarKey !== null) {
            avatarKey = String(avatarKey || "").trim();
            if (!PROFILE_AVATAR_PATTERN.test(avatarKey)) {
              return res.status(400).json({
                error: "Unsupported profile avatar",
                code: "INVALID_AVATAR_KEY",
              });
            }
          }
        }

        const member = await identityService.updateMemberProfile({
          actorDeviceId: req.deviceId,
          familyId: req.familyId,
          memberId,
          displayName,
          avatarKey,
        });
        res.json({ success: true, member });
      } catch (error) {
        if (error.code === "FAMILY_ACCESS_DENIED") {
          return res.status(403).json({ error: error.message, code: error.code });
        }
        if (error.code === "PROFILE_EDIT_DENIED") {
          return res.status(403).json({ error: error.message, code: error.code });
        }
        if (error.code === "FAMILY_MEMBER_NOT_FOUND") {
          return res.status(404).json({ error: error.message, code: error.code });
        }
        next(error);
      }
    }
  );

  router.get(
    "/:familyId/devices",
    requireFamilyAccess,
    async (req, res, next) => {
      try {
        const devices = await dbManager.getFamilyDevices(req.familyId);
        res.json({ success: true, familyId: req.familyId, devices });
      } catch (error) {
        next(error);
      }
    }
  );

  return router;
}

createFamilyRoutes.FAMILY_ID_PATTERN = FAMILY_ID_PATTERN;
createFamilyRoutes.MEMBER_ID_PATTERN = MEMBER_ID_PATTERN;
createFamilyRoutes.PROFILE_AVATAR_PATTERN = PROFILE_AVATAR_PATTERN;

module.exports = createFamilyRoutes;
