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

  test("unconfirmed dispatch retains STOP authority to safe horizon then cleans up without fake expiry", async () => {
    const requestId = "request-expiry-0001";
    await attentionManager.handleRequest(
      requester,
      request({ requestId, expiresAt: now + 1_000 })
    );
    now += 1_001;
    await expect(attentionManager.expirePending(requestId)).resolves.toBe(false);
    expect(attentionManager.pendingSignals.has(requestId)).toBe(true);
    target.emit.mockClear();requester.emit.mockClear();
    now += 20_000;
    await expect(attentionManager.expirePending(requestId)).resolves.toBe(true);
    expect(attentionManager.pendingSignals.has(requestId)).toBe(false);
    expect(await db.getAttentionSignalByRequestId(requestId)).toMatchObject({
      status: "QUEUED",
    });
    expect(target.emit).not.toHaveBeenCalled();expect(requester.emit).not.toHaveBeenCalled();
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
      request({ requestId: "request-schema-duration", durationMs: 1_999 }),
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

  test("accepts a two-second attention signal", async () => {
    const result = await attentionManager.handleRequest(
      requester,
      request({ requestId: "request-duration-2000", durationMs: 2_000 })
    );

    expect(result).toMatchObject({ status: "QUEUED" });
    expect(target.emit).toHaveBeenCalledWith(
      "attention_signal_start",
      expect.objectContaining({ durationMs: 2_000 })
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

  async function recoveryInput(requestId) {
    const row=await db.get('SELECT family_id,requester_member_id FROM attention_signals WHERE request_id=?',[requestId]);
    return {callerDeviceId:requesterId,requestId,targetDeviceId:targetId,
      familyId:row.family_id,actorMemberId:row.requester_member_id};
  }

  test('read recovery replays persisted terminal status without emitting or altering its timestamp',async()=>{
    const requestId='recovery-terminal-0001';await attentionManager.handleRequest(requester,request({requestId}));
    await attentionManager.handleStatus(target,{requestId,targetDeviceId:targetId,status:'COMPLETED',timestamp:now});
    const input=await recoveryInput(requestId), before=await db.getAttentionSignalByRequestId(requestId);
    target.emit.mockClear();requester.emit.mockClear();now+=10000;
    const result=await attentionManager.readRequestStatus(input);
    expect(result).toMatchObject({allowed:true,outcome:'KNOWN',requestId,targetDeviceId:targetId,
      status:{status:'COMPLETED',timestamp:before.updatedAt}});
    expect(await db.getAttentionSignalByRequestId(requestId)).toEqual(before);
    expect(target.emit).not.toHaveBeenCalled();expect(requester.emit).not.toHaveBeenCalled();
  });

  test('live pending is observable; restart and elapsed deadlines remain unknown without fake completion',async()=>{
    const requestId='recovery-restart-0001';await attentionManager.handleRequest(requester,request({requestId}));
    const input=await recoveryInput(requestId);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'KNOWN',status:{status:'QUEUED'}});
    attentionManager.shutdown();
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'UNKNOWN',status:null,
      reason:'SERVER_STATE_UNAVAILABLE',lastObservedStatus:'QUEUED'});
    now+=120001;
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'UNKNOWN',status:null,
      reason:'DEADLINE_PASSED_UNCONFIRMED',lastObservedStatus:'QUEUED'});
    expect((await db.getAttentionSignalByRequestId(requestId)).status).toBe('QUEUED');
  });

  test('recovery requires original device and current exact family actor target scope',async()=>{
    const requestId='recovery-scope-0001';await attentionManager.handleRequest(requester,request({requestId}));
    const input=await recoveryInput(requestId);
    for(const change of [{callerDeviceId:otherParentId},{familyId:'foreign'},
      {actorMemberId:'foreign'},{targetDeviceId:otherTargetId}])
      expect(await attentionManager.readRequestStatus({...input,...change})).toMatchObject({allowed:false,httpStatus:403});
    const sameMemberDevice='second-phone-same-parent';
    await db.registerDevice(sameMemberDevice,{device_name:'Other phone',device_type:'android',app_version:'test'});
    await db.run(`INSERT INTO family_devices(id,family_id,member_id,device_id,display_name,member_binding_source)
      VALUES('second-parent-binding',?,?,?,'Other phone','EXPLICIT')`,[input.familyId,input.actorMemberId,sameMemberDevice]);
    expect(await attentionManager.readRequestStatus({...input,callerDeviceId:sameMemberDevice}))
      .toMatchObject({allowed:false,httpStatus:403});
    expect(await attentionManager.readRequestStatus({...input,requestId:'unknown-request-0001'}))
      .toMatchObject({allowed:false,httpStatus:404});
    expect(await attentionManager.readRequestStatus({...input,requestId:'bad'}))
      .toMatchObject({allowed:false,httpStatus:400});
  });

  test('recovery rechecks permission, canonical target membership and revoked device',async()=>{
    const requestId='recovery-revoke-0001';await attentionManager.handleRequest(requester,request({requestId}));
    const input=await recoveryInput(requestId);
    await db.run("UPDATE family_permissions SET allowed=0 WHERE family_id=? AND actor_member_id=? AND feature='SEND_ATTENTION_SIGNAL'",
      [input.familyId,input.actorMemberId]);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({allowed:false,httpStatus:403});
    await db.run("UPDATE family_permissions SET allowed=1 WHERE family_id=? AND actor_member_id=? AND feature='SEND_ATTENTION_SIGNAL'",
      [input.familyId,input.actorMemberId]);
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?',[targetId]);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({allowed:false,httpStatus:403});
    await db.run('UPDATE family_devices SET is_active=1 WHERE device_id=?',[targetId]);
    await db.run('UPDATE devices SET is_active=0 WHERE device_id=?',[requesterId]);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({allowed:false,httpStatus:403});
  });

  test('HTTP recovery route delegates only a protected read operation',()=>{
    const source=require('fs').readFileSync(require.resolve('../index.js'),'utf8');
    const start=source.indexOf("app.get('/api/attention-signal/:requestId/status'");
    const route=source.slice(start,source.indexOf('// Durable cumulative',start));
    expect(start).toBeGreaterThan(0);
    expect(route).toContain('authMiddleware.authenticate()');
    expect(route).toContain('attentionSignalManager.readRequestStatus(');
    expect(route).not.toMatch(/handleRequest|handleStopRequest|emitToExactDevice/);
  });

  test('60 second playback stays stoppable and accepts completion after 30 second start deadline',async()=>{
    const requestId='long-playback-0001';const startedAt=now;
    await attentionManager.handleRequest(requester,request({requestId,durationMs:60000}));
    await attentionManager.handleStatus(target,{requestId,targetDeviceId:targetId,status:'STARTED',timestamp:now});
    const input=await recoveryInput(requestId);
    now=startedAt+31000;
    expect(await attentionManager.expirePending(requestId)).toBe(false);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'KNOWN',status:{status:'STARTED'}});
    target.emit.mockClear();
    expect(await attentionManager.handleStopRequest(requester,{requestId,targetDeviceId:targetId,
      requesterDeviceId:requesterId,createdAt:now})).toBe(true);
    expect(target.emit).toHaveBeenCalledWith('attention_signal_stop',expect.objectContaining({requestId}));
    now=startedAt+61000;
    expect(await attentionManager.handleStatus(target,{requestId,targetDeviceId:targetId,status:'COMPLETED',timestamp:now})).toBe(true);
    expect((await attentionManager.readRequestStatus(input)).status.status).toBe('COMPLETED');
    expect(attentionManager.pendingSignals.has(requestId)).toBe(false);
  });

  test('dispatched queued request becomes unknown at TTL and bounded cleanup does not persist EXPIRED',async()=>{
    const requestId='unknown-dispatch-0001';const startedAt=now;
    await attentionManager.handleRequest(requester,request({requestId,durationMs:60000}));
    const input=await recoveryInput(requestId);target.emit.mockClear();requester.emit.mockClear();
    now=startedAt+30001;
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'UNKNOWN',status:null,lastObservedStatus:'QUEUED'});
    expect(await attentionManager.expirePending(requestId)).toBe(false);
    now=startedAt+95001;
    expect(await attentionManager.expirePending(requestId)).toBe(true);
    expect(await attentionManager.readRequestStatus(input)).toMatchObject({outcome:'UNKNOWN',status:null,lastObservedStatus:'QUEUED'});
    expect((await db.getAttentionSignalByRequestId(requestId)).status).toBe('QUEUED');
    expect(target.emit).not.toHaveBeenCalled();expect(requester.emit).not.toHaveBeenCalled();
  });

  test('offline STOP is an operation failure, not original signal rejection or completion',async()=>{
    const requestId='offline-stop-0001';await attentionManager.handleRequest(requester,request({requestId,durationMs:60000}));
    await attentionManager.handleStatus(target,{requestId,targetDeviceId:targetId,status:'STARTED',timestamp:now});
    const before=await db.getAttentionSignalByRequestId(requestId);
    wsManager.unregisterDeviceSocket(target);
    const result=await attentionManager.handleStopRequest(requester,{requestId,targetDeviceId:targetId,
      requesterDeviceId:requesterId,createdAt:now});
    expect(result).toMatchObject({operation:'STOP',status:'STARTED',reason:'STOP_FAILED',errorCode:'TARGET_NOT_CONNECTED'});
    expect(attentionManager.pendingSignals.has(requestId)).toBe(true);
    expect(await db.getAttentionSignalByRequestId(requestId)).toEqual(before);
    attentionManager.shutdown();
    const afterRestart=await attentionManager.handleStopRequest(requester,{requestId,targetDeviceId:targetId,
      requesterDeviceId:requesterId,createdAt:now});
    expect(afterRestart).toMatchObject({operation:'STOP',status:'REJECTED',reason:'NOT_ACTIVE'});
    expect(await db.getAttentionSignalByRequestId(requestId)).toEqual(before);
  });
});
