const ATTENTION = Object.freeze({
  REQUEST: "attention_signal_request",
  START: "attention_signal_start",
  STOP_REQUEST: "attention_signal_stop_request",
  STOP: "attention_signal_stop",
  STATUS: "attention_signal_status",
  MIN_DURATION_MS: 2_000,
  MAX_DURATION_MS: 60_000,
  DEFAULT_TTL_MS: 30_000,
  MAX_TTL_MS: 120_000,
  RATE_WINDOW_MS: 60_000,
  RATE_MAX: 10,
  TARGET_COOLDOWN_MS: 5_000,
});

const TONES = new Set(["ATTENTION", "RINGTONE", "ALARM", "SIREN"]);
const VIBRATION_PATTERNS = new Set(["OFF", "PULSE", "URGENT", "SOS"]);
const TARGET_STATUSES = new Set([
  "DELIVERED",
  "STARTED",
  "COMPLETED",
  "STOPPED",
  "REJECTED",
  "FAILED",
  "EXPIRED",
]);
const TERMINAL_STATUSES = new Set([
  "COMPLETED",
  "STOPPED",
  "REJECTED",
  "FAILED",
  "EXPIRED",
]);
const REQUEST_KEYS = new Set([
  "requestId",
  "familyId",
  "targetMemberId",
  "targetDeviceId",
  "requesterMemberId",
  "requesterDeviceId",
  "requesterDisplayName",
  "tone",
  "durationMs",
  "volumePercent",
  "vibrate",
  "vibrationPattern",
  "createdAt",
  "expiresAt",
]);
const STOP_REQUEST_KEYS = new Set([
  "requestId",
  "targetDeviceId",
  "requesterDeviceId",
  "createdAt",
]);
const STATUS_KEYS = new Set([
  "requestId",
  "targetDeviceId",
  "status",
  "reason",
  "errorCode",
  "message",
  "timestamp",
]);

class AttentionSignalManager {
  constructor({ wsManager, dbManager, familyPermissionService, now = Date.now }) {
    if (!wsManager || !dbManager || !familyPermissionService) {
      throw new Error(
        "AttentionSignalManager requires websocket, database, and permission services"
      );
    }
    this.wsManager = wsManager;
    this.dbManager = dbManager;
    this.familyPermissionService = familyPermissionService;
    this.now = now;
    this.pendingSignals = new Map();
    this.requestRate = new Map();
    this.lastRequestForTarget = new Map();
  }

  normalizeId(value) {
    return value === null || value === undefined ? "" : String(value).trim();
  }

  getAuthenticatedDeviceId(socket) {
    return this.normalizeId(socket?.authenticatedDeviceId);
  }

  makeStatus(request, status, reason = null, errorCode = null, message = null) {
    return {
      requestId: this.normalizeId(request?.requestId),
      targetDeviceId: this.normalizeId(request?.targetDeviceId),
      status,
      reason,
      errorCode,
      message: message ? String(message).slice(0, 500) : null,
      timestamp: this.now(),
    };
  }

  emitStatusToSocket(socket, payload) {
    if (socket?.connected !== false) {
      socket.emit(ATTENTION.STATUS, payload);
    }
  }

  emitStatusToRequester(entry, payload) {
    const delivered = this.wsManager.emitToExactDevice(
      entry.request.requesterDeviceId,
      ATTENTION.STATUS,
      payload
    );
    if (delivered === 0) {
      this.emitStatusToSocket(entry.requesterSocket, payload);
    }
  }

  validateAndNormalizeRequest(raw, authenticatedDeviceId) {
    const now = this.now();
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
      return { error: "INVALID_REQUEST_BODY" };
    }
    const unknownKey = Object.keys(raw).find((key) => !REQUEST_KEYS.has(key));
    if (unknownKey) return { error: "UNKNOWN_REQUEST_FIELD" };

    const requestId = this.normalizeId(raw.requestId);
    const targetDeviceId = this.normalizeId(raw.targetDeviceId);
    const claimedRequesterId = this.normalizeId(raw.requesterDeviceId);
    const requesterDisplayName = this.normalizeId(raw.requesterDisplayName);
    const familyId = raw.familyId == null ? null : this.normalizeId(raw.familyId);
    const targetMemberId =
      raw.targetMemberId == null ? null : this.normalizeId(raw.targetMemberId);
    const requesterMemberId =
      raw.requesterMemberId == null
        ? null
        : this.normalizeId(raw.requesterMemberId);
    const tone = this.normalizeId(raw.tone).toUpperCase();
    const vibrationPattern = this.normalizeId(raw.vibrationPattern).toUpperCase();
    const durationMs = raw.durationMs;
    const volumePercent = raw.volumePercent;
    const createdAt = raw.createdAt;
    const expiresAt = raw.expiresAt ?? createdAt + ATTENTION.DEFAULT_TTL_MS;

    if (!authenticatedDeviceId) return { error: "MISSING_AUTHENTICATED_REQUESTER" };
    if (requestId.length < 8 || requestId.length > 100) {
      return { error: "INVALID_REQUEST_ID" };
    }
    if (!/^[A-Za-z0-9_-]+$/.test(requestId)) {
      return { error: "INVALID_REQUEST_ID" };
    }
    if (!targetDeviceId || targetDeviceId.length > 200) {
      return { error: "INVALID_TARGET_DEVICE" };
    }
    if (!claimedRequesterId || claimedRequesterId !== authenticatedDeviceId) {
      return { error: "REQUESTER_DEVICE_MISMATCH" };
    }
    if (targetDeviceId === authenticatedDeviceId) {
      return { error: "TARGET_EQUALS_REQUESTER" };
    }
    if (!requesterDisplayName || requesterDisplayName.length > 100) {
      return { error: "INVALID_REQUESTER_NAME" };
    }
    for (const optionalId of [familyId, targetMemberId, requesterMemberId]) {
      if (optionalId !== null && (!optionalId || optionalId.length > 100)) {
        return { error: "INVALID_CONTEXT_ID" };
      }
    }
    if (!TONES.has(tone)) return { error: "INVALID_TONE" };
    if (!Number.isInteger(durationMs) || durationMs < ATTENTION.MIN_DURATION_MS || durationMs > ATTENTION.MAX_DURATION_MS) {
      return { error: "INVALID_DURATION" };
    }
    if (!Number.isInteger(volumePercent) || volumePercent < 0 || volumePercent > 100) {
      return { error: "INVALID_VOLUME" };
    }
    if (typeof raw.vibrate !== "boolean") return { error: "INVALID_VIBRATION" };
    if (!VIBRATION_PATTERNS.has(vibrationPattern)) {
      return { error: "INVALID_VIBRATION_PATTERN" };
    }
    if (!Number.isInteger(createdAt) || createdAt < 1 || createdAt > now + 5_000) {
      return { error: "INVALID_CREATED_AT" };
    }
    if (!Number.isInteger(expiresAt) || expiresAt < 1) {
      return { error: "INVALID_EXPIRES_AT" };
    }
    if (expiresAt - createdAt > ATTENTION.MAX_TTL_MS) {
      return { error: "TTL_TOO_LONG" };
    }

    return {
      request: {
        requestId,
        familyId,
        targetMemberId,
        targetDeviceId,
        requesterMemberId,
        requesterDeviceId: authenticatedDeviceId,
        requesterDisplayName,
        tone,
        durationMs,
        volumePercent,
        vibrate: raw.vibrate,
        vibrationPattern: raw.vibrate ? vibrationPattern : "OFF",
        createdAt,
        expiresAt,
      },
    };
  }

  consumeRate(requesterDeviceId, targetDeviceId) {
    const now = this.now();
    const lastTargetRequest = this.lastRequestForTarget.get(targetDeviceId) || 0;
    if (now - lastTargetRequest < ATTENTION.TARGET_COOLDOWN_MS) {
      return { ok: false, code: "TARGET_COOLDOWN" };
    }

    const recent = (this.requestRate.get(requesterDeviceId) || []).filter(
      (timestamp) => now - timestamp < ATTENTION.RATE_WINDOW_MS
    );
    if (recent.length >= ATTENTION.RATE_MAX) {
      this.requestRate.set(requesterDeviceId, recent);
      return { ok: false, code: "RATE_LIMITED" };
    }
    recent.push(now);
    this.requestRate.set(requesterDeviceId, recent);
    this.lastRequestForTarget.set(targetDeviceId, now);
    return { ok: true };
  }

  async persistInitial(request, status, reason = null, errorCode = null) {
    return this.dbManager.saveAttentionSignalEvent({
      ...request,
      status,
      reason,
      errorCode,
    });
  }

  async reject(socket, request, status, reason, errorCode, persist = false) {
    const payload = this.makeStatus(request, status, reason, errorCode);
    this.emitStatusToSocket(socket, payload);
    if (persist && request?.requestId && request?.targetDeviceId) {
      await this.persistInitial(request, status, reason, errorCode);
    }
    return payload;
  }

  scheduleExpiry(entry) {
    const delay = Math.max(0, entry.request.expiresAt - this.now());
    entry.timer = setTimeout(() => {
      this.expirePending(entry.request.requestId).catch((error) => {
        console.error("Failed to persist attention expiry:", error);
      });
    }, delay + 25);
    entry.timer.unref?.();
  }

  async expirePending(requestId) {
    const entry = this.pendingSignals.get(requestId);
    if (!entry || entry.request.expiresAt > this.now()) return false;
    this.clearPending(requestId);
    const payload = this.makeStatus(
      entry.request,
      "EXPIRED",
      "TTL_EXPIRED",
      null
    );
    this.emitStatusToRequester(entry, payload);
    await this.dbManager.updateAttentionSignalStatus(payload);
    return true;
  }

  clearPending(requestId) {
    const entry = this.pendingSignals.get(requestId);
    if (!entry) return null;
    if (entry.timer) clearTimeout(entry.timer);
    this.pendingSignals.delete(requestId);
    return entry;
  }

  async handleRequest(socket, raw) {
    const authenticatedDeviceId = this.getAuthenticatedDeviceId(socket);
    const normalized = this.validateAndNormalizeRequest(raw, authenticatedDeviceId);
    const request = normalized.request || {
      requestId: this.normalizeId(raw?.requestId),
      targetDeviceId: this.normalizeId(raw?.targetDeviceId),
    };
    if (normalized.error) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "INVALID_REQUEST",
        normalized.error,
        false
      );
    }
    if (request.expiresAt <= this.now()) {
      return this.reject(
        socket,
        request,
        "EXPIRED",
        "TTL_EXPIRED",
        null,
        true
      );
    }
    if (
      this.pendingSignals.has(request.requestId) ||
      (await this.dbManager.getAttentionSignalByRequestId(request.requestId))
    ) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "DUPLICATE",
        "DUPLICATE_REQUEST_ID",
        false
      );
    }

    const permission = await this.familyPermissionService.authorizeFeature(
      request.requesterDeviceId,
      request.targetDeviceId,
      "SEND_ATTENTION_SIGNAL"
    );
    if (!permission.allowed) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "FORBIDDEN",
        permission.code || "FAMILY_PERMISSION_DENIED",
        true
      );
    }
    if (request.familyId && request.familyId !== permission.familyId) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "FORBIDDEN",
        "FAMILY_ID_MISMATCH",
        true
      );
    }
    if (
      request.requesterMemberId &&
      request.requesterMemberId !== permission.actorMemberId
    ) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "FORBIDDEN",
        "REQUESTER_MEMBER_MISMATCH",
        true
      );
    }
    if (
      request.targetMemberId &&
      request.targetMemberId !== permission.targetMemberId
    ) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "FORBIDDEN",
        "TARGET_MEMBER_MISMATCH",
        true
      );
    }

    request.familyId = permission.familyId;
    request.requesterMemberId = permission.actorMemberId;
    request.targetMemberId = permission.targetMemberId;
    request.requesterDisplayName =
      permission.actorDisplayName || request.requesterDisplayName;

    const rate = this.consumeRate(
      request.requesterDeviceId,
      request.targetDeviceId
    );
    if (!rate.ok) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        rate.code,
        rate.code,
        true
      );
    }

    const saved = await this.persistInitial(request, "QUEUED");
    if (saved.changes !== 1) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "DUPLICATE",
        "DUPLICATE_REQUEST_ID",
        false
      );
    }

    const entry = {
      request,
      requesterSocket: socket,
      lastStatus: "QUEUED",
      timer: null,
    };
    this.pendingSignals.set(request.requestId, entry);

    const delivered = this.wsManager.emitToExactDevice(
      request.targetDeviceId,
      ATTENTION.START,
      request
    );
    if (delivered === 0) {
      this.clearPending(request.requestId);
      const payload = this.makeStatus(
        request,
        "FAILED",
        "TARGET_OFFLINE",
        "TARGET_NOT_CONNECTED"
      );
      this.emitStatusToSocket(socket, payload);
      await this.dbManager.updateAttentionSignalStatus(payload);
      return payload;
    }

    const queued = this.makeStatus(request, "QUEUED");
    this.emitStatusToRequester(entry, queued);
    this.scheduleExpiry(entry);
    return queued;
  }

  async handleStatus(socket, raw) {
    if (
      !raw ||
      typeof raw !== "object" ||
      Array.isArray(raw) ||
      Object.keys(raw).some((key) => !STATUS_KEYS.has(key))
    ) {
      return false;
    }
    const requestId = this.normalizeId(raw?.requestId);
    const targetDeviceId = this.normalizeId(raw?.targetDeviceId);
    const reason = raw.reason == null ? null : this.normalizeId(raw.reason);
    const errorCode =
      raw.errorCode == null ? null : this.normalizeId(raw.errorCode);
    const message = raw.message == null ? null : String(raw.message);
    if (
      requestId.length < 8 ||
      requestId.length > 100 ||
      !targetDeviceId ||
      targetDeviceId.length > 200 ||
      (reason !== null && reason.length > 100) ||
      (errorCode !== null && errorCode.length > 100) ||
      (message !== null && message.length > 500) ||
      !Number.isInteger(raw.timestamp) ||
      raw.timestamp < 1
    ) {
      return false;
    }
    const entry = this.pendingSignals.get(requestId);
    if (!entry) return false;
    const senderDeviceId = this.getAuthenticatedDeviceId(socket);
    if (senderDeviceId !== entry.request.targetDeviceId) return false;
    if (targetDeviceId !== entry.request.targetDeviceId) {
      return false;
    }
    const status = this.normalizeId(raw?.status).toUpperCase();
    if (!TARGET_STATUSES.has(status) || status === entry.lastStatus) return false;

    const payload = this.makeStatus(
      entry.request,
      status,
      reason,
      errorCode,
      message
    );
    entry.lastStatus = status;
    this.emitStatusToRequester(entry, payload);
    await this.dbManager.updateAttentionSignalStatus(payload);
    if (TERMINAL_STATUSES.has(status)) {
      this.clearPending(requestId);
    }
    return true;
  }

  async handleStopRequest(socket, raw) {
    const requestId = this.normalizeId(raw?.requestId);
    const targetDeviceId = this.normalizeId(raw?.targetDeviceId);
    const claimedRequesterId = this.normalizeId(raw?.requesterDeviceId);
    const requesterDeviceId = this.getAuthenticatedDeviceId(socket);
    const entry = this.pendingSignals.get(requestId);
    const request = entry?.request || { requestId, targetDeviceId };

    if (
      !raw ||
      typeof raw !== "object" ||
      Array.isArray(raw) ||
      Object.keys(raw).some((key) => !STOP_REQUEST_KEYS.has(key)) ||
      requestId.length < 8 ||
      requestId.length > 100 ||
      !targetDeviceId ||
      targetDeviceId.length > 200 ||
      !Number.isInteger(raw.createdAt) ||
      raw.createdAt < 1 ||
      raw.createdAt > this.now() + 5_000
    ) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "INVALID_REQUEST",
        "INVALID_STOP_REQUEST"
      );
    }

    if (!requesterDeviceId || claimedRequesterId !== requesterDeviceId) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "UNAUTHENTICATED",
        "REQUESTER_DEVICE_MISMATCH"
      );
    }
    if (!entry) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "NOT_ACTIVE",
        "SIGNAL_NOT_ACTIVE"
      );
    }
    if (entry.request.requesterDeviceId !== requesterDeviceId) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "FORBIDDEN",
        "NOT_SIGNAL_OWNER"
      );
    }
    if (targetDeviceId !== entry.request.targetDeviceId) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "INVALID_TARGET",
        "TARGET_DEVICE_MISMATCH"
      );
    }

    const delivered = this.wsManager.emitToExactDevice(
      entry.request.targetDeviceId,
      ATTENTION.STOP,
      {
        requestId,
        targetDeviceId: entry.request.targetDeviceId,
        requesterDeviceId,
        createdAt: this.now(),
      }
    );
    if (delivered === 0) {
      return this.reject(
        socket,
        request,
        "REJECTED",
        "TARGET_OFFLINE",
        "TARGET_NOT_CONNECTED"
      );
    }
    return true;
  }

  shutdown() {
    for (const entry of this.pendingSignals.values()) {
      if (entry.timer) clearTimeout(entry.timer);
    }
    this.pendingSignals.clear();
  }
}

AttentionSignalManager.ATTENTION = ATTENTION;

module.exports = AttentionSignalManager;
