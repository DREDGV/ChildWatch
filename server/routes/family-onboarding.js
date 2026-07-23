const express = require("express");

function createFamilyOnboardingRoutes(onboardingService) {
  if (!onboardingService) {
    throw new Error("Family onboarding routes require an onboarding service");
  }
  const router = express.Router();

  const handle = (work) => async (req, res) => {
    try {
      await work(req, res);
    } catch (error) {
      const conflictCodes = new Set([
        "DEVICE_ALREADY_ONBOARDED",
        "DEVICE_IN_ANOTHER_FAMILY",
        "INVITATION_ALREADY_USED",
        "INVITATION_EXPIRED",
        "INVITATION_REVOKED",
      ]);
      const missingCodes = new Set([
        "INVITATION_NOT_FOUND",
        "FAMILY_MEMBER_NOT_FOUND",
        "LEGACY_PROFILE_NOT_FOUND",
        "DEVICE_TRANSFER_TARGET_NOT_FOUND",
      ]);
      const status =
        Number(error.status) ||
        (conflictCodes.has(error.code)
          ? 409
          : missingCodes.has(error.code)
            ? 404
            : 500);
      res.status(status).json({
        error: error.message || "Family onboarding failed",
        code: error.code || "FAMILY_ONBOARDING_ERROR",
      });
    }
  };

  router.post(
    "/bootstrap",
    handle(async (req, res) => {
      const result = await onboardingService.bootstrapFamily(
        req.deviceId,
        req.body || {}
      );
      res.status(201).json({ success: true, ...result });
    })
  );

  router.post(
    "/families/:familyId/confirm-self",
    handle(async (req, res) => {
      const result = await onboardingService.confirmOwnProfile(
        req.deviceId,
        req.params.familyId,
        req.body || {}
      );
      res.json({ success: true, ...result });
    })
  );

  router.post(
    "/invitations",
    handle(async (req, res) => {
      const invitation = await onboardingService.createInvitation(
        req.deviceId,
        req.body || {}
      );
      res.status(201).json({ success: true, invitation });
    })
  );

  router.get(
    "/families/:familyId/invitations",
    handle(async (req, res) => {
      const invitations = await onboardingService.listActiveInvitations(
        req.deviceId,
        req.params.familyId
      );
      res.json({ success: true, invitations });
    })
  );

  router.get(
    "/families/:familyId/legacy-candidates",
    handle(async (req, res) => {
      const candidates = await onboardingService.listLegacyMigrationCandidates(
        req.deviceId,
        req.params.familyId
      );
      res.json({
        success: true,
        familyId: req.params.familyId,
        candidates,
      });
    })
  );

  router.post(
    "/families/:familyId/legacy-candidates/:memberId/confirm",
    handle(async (req, res) => {
      const result = await onboardingService.confirmLegacyProfile(
        req.deviceId,
        req.params.familyId,
        req.params.memberId,
        req.body || {}
      );
      res.json({ success: true, ...result });
    })
  );

  router.post(
    "/families/:familyId/devices/:deviceId/transfer",
    handle(async (req, res) => {
      const result = await onboardingService.transferFamilyDevice(
        req.deviceId,
        req.params.familyId,
        req.params.deviceId,
        req.body || {}
      );
      res.json({ success: true, ...result });
    })
  );

  router.get(
    "/invitations/:token",
    handle(async (req, res) => {
      const invitation = await onboardingService.previewInvitation(
        req.params.token
      );
      res.json({ success: true, invitation });
    })
  );

  router.post(
    "/invitations/:token/accept",
    handle(async (req, res) => {
      const result = await onboardingService.acceptInvitation(
        req.deviceId,
        req.params.token,
        req.body || {}
      );
      res.json({ success: true, ...result });
    })
  );

  router.delete(
    "/families/:familyId/invitations/:invitationId",
    handle(async (req, res) => {
      await onboardingService.revokeInvitation(
        req.deviceId,
        req.params.familyId,
        req.params.invitationId
      );
      res.json({ success: true });
    })
  );

  return router;
}

module.exports = createFamilyOnboardingRoutes;
