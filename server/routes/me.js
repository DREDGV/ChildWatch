const express = require("express");

function createMeRoutes(identityService) {
  if (!identityService) {
    throw new Error("Me routes require a family identity service");
  }

  const router = express.Router();

  router.get("/", async (req, res) => {
    try {
      const identity = await identityService.resolveAuthenticatedDevice(
        req.deviceId,
        req.deviceData
      );
      res.json({ success: true, ...identity });
    } catch (error) {
      console.error("Resolve authenticated identity error:", error);
      res.status(500).json({
        error: "Failed to resolve authenticated identity",
        code: "IDENTITY_RESOLUTION_ERROR",
      });
    }
  });

  return router;
}

module.exports = createMeRoutes;
