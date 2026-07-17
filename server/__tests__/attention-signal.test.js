const DatabaseManager = require("../database/DatabaseManager");
const WebSocketManager = require("../managers/WebSocketManager");
const AttentionSignalManager = require("../managers/AttentionSignalManager");
const FamilyPermissionService = require("../services/FamilyPermissionService");

function createSocket(id, authenticatedDeviceId) {
  return {
    id,
    authenticatedDeviceId,
    connected: true,
    emit: jest.fn(),
  };
}

describe("attention signal protocol", () => {
  let db;
  let wsManager;
  let attentionManager;
  let sockets;
  let now;
  let requester;
  let target;
  let otherTarget;
  let logSpy;
  let warnSpy;
  let errorSpy;

  const requesterId = "parent-attention-device";
  const targetId = "child-attention-device";
  const otherParentId = "parent-other-family";
  const otherTargetId = "child-other-family";

  function request(overrides = {}) {
    const requestId = overrides.requestId || "request-attention-0001";
    return {
      requestId,
      familyId: null,
      targetMemberId: null,
      targetDeviceId: targetId,
      requesterMemberId: null,
      requesterDeviceId: requesterId,
      requesterDisplayName: "Parent",
      tone: "ATTENTION",
      durationMs: 15_000,
      volumePercent: 80,
      vibrate: true,
      vibrationPattern: "PULSE",
      createdAt: now,
      expiresAt: now + 30_000,
      ...overrides,
    };
  }

  async function registerPair(parentDeviceId, childDeviceId) {
    await db.registerDevice(parentDeviceId, {
      device_name: parentDeviceId,
      device_type: "android",
      app_version: "7.2.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: childDeviceId,
      device_type: "android",
      app_version: "7.2.0",
    });
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });
  }

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    now = 1_800_000_000_000;
    db = new DatabaseManager(":memory:");
    await db.initialize();
    await registerPair(requesterId, targetId);
    await registerPair(otherParentId, otherTargetId);

    sockets = new Map();
    wsManager = new WebSocketManager({ sockets: { sockets } });
    attentionManager = new AttentionSignalManager({
      wsManager,
      dbManager: db,
      familyPermissionService: new FamilyPermissionService(db),
      now: () => now,
    });
    requester = createSocket("requester-socket", requesterId);
    target = createSocket("target-socket", targetId);
    otherTarget = createSocket("other-target-socket", otherTargetId);
    for (const socket of [requester, target, otherTarget]) {
      sockets.set(socket.id, socket);
      wsManager.registerDeviceSocket(socket, socket.authenticatedDeviceId);
    }
  });

  afterEach(async () => {
    attentionManager.shutdown();
    await db.close();
    logSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("routes start to the exact target and accepts status only from it", async () => {
    await attentionManager.handleRequest(requester, request());

    expect(target.emit).toHaveBeenCalledWith(
      "attention_signal_start",
      expect.objectContaining({ targetDeviceId: targetId })
    );
    expect(otherTarget.emit).not.toHaveBeenCalledWith(
      "attention_signal_start",
      expect.anything()
    );
    expect(requester.emit).toHaveBeenCalledWith(
      "attention_signal_status",
      expect.objectContaining({ status: "QUEUED" })
    );

    const wrongStatus = await attentionManager.handleStatus(otherTarget, {
      requestId: "request-attention-0001",
      targetDeviceId: targetId,
      status: "STARTED",
      timestamp: now,
    });
    expect(wrongStatus).toBe(false);

    const accepted = await attentionManager.handleStatus(target, {
      requestId: "request-attention-0001",
      targetDeviceId: targetId,
      status: "STARTED",
      timestamp: now,
    });
    expect(accepted).toBe(true);
    expect(
      await db.getAttentionSignalByRequestId("request-attention-0001")
    ).toMatchObject({ status: "STARTED", targetDeviceId: targetId });
  });

  test("fails immediately when the exact target is offline", async () => {
    wsManager.unregisterDeviceSocket(target);
    const result = await attentionManager.handleRequest(
      requester,
      request({ requestId: "request-offline-0001" })
    );

    expect(result).toMatchObject({
      status: "FAILED",
      reason: "TARGET_OFFLINE",
      errorCode: "TARGET_NOT_CONNECTED",
    });
    expect(attentionManager.pendingSignals.size).toBe(0);
    expect(
      await db.getAttentionSignalByRequestId("request-offline-0001")
    ).toMatchObject({ status: "FAILED" });
  });

  test("expires an accepted request and removes it from pending state", async () => {
    const requestId = "request-expiry-0001";
    await attentionManager.handleRequest(
      requester,
      request({ requestId, expiresAt: now + 1_000 })
    );
    now += 1_001;

    await expect(attentionManager.expirePending(requestId)).resolves.toBe(true);
    expect(attentionManager.pendingSignals.has(requestId)).toBe(false);
    expect(await db.getAttentionSignalByRequestId(requestId)).toMatchObject({
      status: "EXPIRED",
    });
  });

  test("routes owner stop request and cleans up after target STOPPED", async () => {
    const requestId = "request-stop-0001";
    await attentionManager.handleRequest(requester, request({ requestId }));
    target.emit.mockClear();

    await attentionManager.handleStopRequest(requester, {
      requestId,
      targetDeviceId: targetId,
      requesterDeviceId: requesterId,
      createdAt: now,
    });
    expect(target.emit).toHaveBeenCalledWith(
      "attention_signal_stop",
      expect.objectContaining({ requestId, targetDeviceId: targetId })
    );

    await attentionManager.handleStatus(target, {
      requestId,
      targetDeviceId: targetId,
      status: "STOPPED",
      reason: "REMOTE_REQUEST",
      timestamp: now,
    });
    expect(attentionManager.pendingSignals.has(requestId)).toBe(false);
    expect(await db.getAttentionSignalByRequestId(requestId)).toMatchObject({
      status: "STOPPED",
    });
  });

  test("rejects duplicate request IDs", async () => {
    const payload = request({ requestId: "request-duplicate-0001" });
    await attentionManager.handleRequest(requester, payload);
    requester.emit.mockClear();

    const result = await attentionManager.handleRequest(requester, payload);

    expect(result).toMatchObject({
      status: "REJECTED",
      reason: "DUPLICATE",
      errorCode: "DUPLICATE_REQUEST_ID",
    });
    expect(target.emit).toHaveBeenCalledTimes(1);
  });

  test("rejects cross-family target even when it is online", async () => {
    const result = await attentionManager.handleRequest(
      requester,
      request({
        requestId: "request-cross-family-0001",
        targetDeviceId: otherTargetId,
      })
    );

    expect(result).toMatchObject({
      status: "REJECTED",
      reason: "FORBIDDEN",
      errorCode: "CROSS_FAMILY_DENIED",
    });
    expect(otherTarget.emit).not.toHaveBeenCalledWith(
      "attention_signal_start",
      expect.anything()
    );
  });

  test("requires authenticated requester and matching claimed device", async () => {
    const unauthenticated = createSocket("unauthenticated", "");
    sockets.set(unauthenticated.id, unauthenticated);
    const result = await attentionManager.handleRequest(
      unauthenticated,
      request({ requestId: "request-unauthenticated-0001" })
    );

    expect(result).toMatchObject({
      status: "REJECTED",
      errorCode: "MISSING_AUTHENTICATED_REQUESTER",
    });
    expect(target.emit).not.toHaveBeenCalledWith(
      "attention_signal_start",
      expect.anything()
    );
  });

  test("applies target cooldown before accepting a second request", async () => {
    await attentionManager.handleRequest(
      requester,
      request({ requestId: "request-cooldown-0001" })
    );
    const result = await attentionManager.handleRequest(
      requester,
      request({ requestId: "request-cooldown-0002" })
    );

    expect(result).toMatchObject({
      status: "REJECTED",
      errorCode: "TARGET_COOLDOWN",
    });
  });

  test("enforces strict request bounds and rejects unknown schema fields", async () => {
    const invalidRequests = [
      request({ requestId: "request-schema-duration", durationMs: 4_999 }),
      request({ requestId: "request-schema-volume", volumePercent: 101 }),
      request({
        requestId: "request-schema-ttl",
        expiresAt: now + AttentionSignalManager.ATTENTION.MAX_TTL_MS + 1,
      }),
      request({ requestId: "request-schema-unknown", unexpected: true }),
    ];

    const results = [];
    for (const payload of invalidRequests) {
      results.push(await attentionManager.handleRequest(requester, payload));
    }

    expect(results.map((result) => result.errorCode)).toEqual([
      "INVALID_DURATION",
      "INVALID_VOLUME",
      "TTL_TOO_LONG",
      "UNKNOWN_REQUEST_FIELD",
    ]);
    expect(target.emit).not.toHaveBeenCalledWith(
      "attention_signal_start",
      expect.anything()
    );
  });

  test("limits one requester to ten accepted signals per minute", async () => {
    for (let index = 0; index < 10; index += 1) {
      const result = await attentionManager.handleRequest(
        requester,
        request({ requestId: `request-rate-${String(index).padStart(4, "0")}` })
      );
      expect(result.status).toBe("QUEUED");
      now += AttentionSignalManager.ATTENTION.TARGET_COOLDOWN_MS + 1;
    }

    const limited = await attentionManager.handleRequest(
      requester,
      request({ requestId: "request-rate-limited" })
    );

    expect(limited).toMatchObject({
      status: "REJECTED",
      reason: "RATE_LIMITED",
      errorCode: "RATE_LIMITED",
    });
    expect(target.emit).toHaveBeenCalledTimes(10);
  });

  test("does not let another authenticated family member stop a signal", async () => {
    const requestId = "request-owner-stop-0001";
    await attentionManager.handleRequest(requester, request({ requestId }));
    const attacker = createSocket("attacker-socket", otherParentId);
    sockets.set(attacker.id, attacker);
    wsManager.registerDeviceSocket(attacker, otherParentId);
    target.emit.mockClear();

    const result = await attentionManager.handleStopRequest(attacker, {
      requestId,
      targetDeviceId: targetId,
      requesterDeviceId: otherParentId,
      createdAt: now,
    });

    expect(result).toMatchObject({
      status: "REJECTED",
      reason: "FORBIDDEN",
      errorCode: "NOT_SIGNAL_OWNER",
    });
    expect(target.emit).not.toHaveBeenCalledWith(
      "attention_signal_stop",
      expect.anything()
    );
    expect(attentionManager.pendingSignals.has(requestId)).toBe(true);
  });
});
