const express = require("express");

const FAMILY_ID_PATTERN = /^[A-Za-z0-9_-]{8,100}$/;

function createFamilyRoutes(dbManager, permissionService) {
  if (!dbManager || !permissionService) {
    throw new Error("Family routes require database and permission services");
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

module.exports = createFamilyRoutes;
