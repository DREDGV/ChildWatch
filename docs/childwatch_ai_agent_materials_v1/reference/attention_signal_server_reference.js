/**
 * Референс для server/managers/WebSocketManager.js.
 * Требует адаптации к реальной auth/family permission системе.
 */

const ATTENTION = Object.freeze({
  REQUEST: "attention_signal_request",
  START: "attention_signal_start",
  STOP_REQUEST: "attention_signal_stop_request",
  STOP: "attention_signal_stop",
  STATUS: "attention_signal_status",
  MIN_DURATION_MS: 5_000,
  MAX_DURATION_MS: 60_000,
  DEFAULT_TTL_MS: 30_000,
  MAX_TTL_MS: 120_000,
  RATE_WINDOW_MS: 60_000,
  RATE_MAX: 10,
  TARGET_COOLDOWN_MS: 5_000,
});

function installAttentionSignalSupport(manager) {
  manager.deviceSockets ||= new Map();
  manager.pendingAttentionSignals ||= new Map();
  manager.attentionRate ||= new Map();
  manager.lastAttentionForTarget ||= new Map();

  manager.registerDeviceSocket = function (
    socket,
    deviceId
  ) {
    const id = this.normalizeDeviceId(deviceId);
    if (!id || !socket?.id) return;

    if (!this.deviceSockets.has(id)) {
      this.deviceSockets.set(id, new Set());
    }

    this.deviceSockets.get(id).add(socket.id);
    socket.registeredDeviceId = id;
  };

  manager.unregisterDeviceSocket = function (
    socket
  ) {
    const id = this.normalizeDeviceId(
      socket?.registeredDeviceId ||
      socket?.authenticatedDeviceId ||
      socket?.parentDeviceId ||
      socket?.deviceId
    );

    if (!id) return;

    const sockets = this.deviceSockets.get(id);
    if (!sockets) return;

    sockets.delete(socket.id);

    if (sockets.size === 0) {
      this.deviceSockets.delete(id);
    }
  };

  manager.getConnectedSocketIdsForDevice =
    function (deviceId) {
      const id = this.normalizeDeviceId(deviceId);
      const sockets = this.deviceSockets.get(id);

      if (!sockets) return [];

      const live = [];

      for (const socketId of Array.from(sockets)) {
        const socket =
          this.io.sockets.sockets.get(socketId);

        if (socket?.connected) {
          live.push(socketId);
        } else {
          sockets.delete(socketId);
        }
      }

      if (sockets.size === 0) {
        this.deviceSockets.delete(id);
      }

      return live;
    };

  manager.emitToExactDevice = function (
    deviceId,
    event,
    payload
  ) {
    let count = 0;

    for (
      const socketId of
      this.getConnectedSocketIdsForDevice(
        deviceId
      )
    ) {
      const socket =
        this.io.sockets.sockets.get(socketId);

      if (!socket?.connected) continue;

      socket.emit(event, payload);
      count += 1;
    }

    return count;
  };

  manager.normalizeAttention = function (
    data,
    requesterDeviceId
  ) {
    const now = Date.now();
    const createdAt =
      Number(data?.createdAt) || now;

    const expiresAt = Math.min(
      Number(data?.expiresAt) ||
        createdAt + ATTENTION.DEFAULT_TTL_MS,
      createdAt + ATTENTION.MAX_TTL_MS
    );

    return {
      requestId:
        String(data?.requestId || "").trim(),
      familyId:
        data?.familyId
          ? String(data.familyId).trim()
          : null,
      targetMemberId:
        data?.targetMemberId
          ? String(data.targetMemberId).trim()
          : null,
      targetDeviceId:
        this.normalizeDeviceId(
          data?.targetDeviceId
        ),
      requesterMemberId:
        data?.requesterMemberId
          ? String(
              data.requesterMemberId
            ).trim()
          : null,
      requesterDeviceId:
        this.normalizeDeviceId(
          requesterDeviceId
        ),
      requesterDisplayName:
        String(
          data?.requesterDisplayName ||
          "Член семьи"
        ).trim().slice(0, 100),
      tone:
        [
          "ATTENTION",
          "RINGTONE",
          "ALARM",
          "SIREN",
        ].includes(data?.tone)
          ? data.tone
          : "ATTENTION",
      durationMs: Math.max(
        ATTENTION.MIN_DURATION_MS,
        Math.min(
          ATTENTION.MAX_DURATION_MS,
          Number(data?.durationMs) ||
            15_000
        )
      ),
      volumePercent: Math.max(
        0,
        Math.min(
          100,
          Number(data?.volumePercent) || 0
        )
      ),
      vibrate: Boolean(data?.vibrate),
      vibrationPattern:
        [
          "OFF",
          "PULSE",
          "URGENT",
          "SOS",
        ].includes(data?.vibrationPattern)
          ? data.vibrationPattern
          : "PULSE",
      createdAt,
      expiresAt,
    };
  };

  manager.consumeAttentionRate = function (
    requesterId,
    targetId
  ) {
    const now = Date.now();
    const targetLast =
      this.lastAttentionForTarget.get(
        targetId
      ) || 0;

    if (
      now - targetLast <
      ATTENTION.TARGET_COOLDOWN_MS
    ) {
      return {
        ok: false,
        code: "TARGET_COOLDOWN",
      };
    }

    const old =
      this.attentionRate.get(requesterId) ||
      [];

    const fresh = old.filter(
      (value) =>
        now - value <
        ATTENTION.RATE_WINDOW_MS
    );

    if (fresh.length >= ATTENTION.RATE_MAX) {
      this.attentionRate.set(
        requesterId,
        fresh
      );

      return {
        ok: false,
        code: "RATE_LIMITED",
      };
    }

    fresh.push(now);
    this.attentionRate.set(
      requesterId,
      fresh
    );
    this.lastAttentionForTarget.set(
      targetId,
      now
    );

    return { ok: true };
  };

  manager.sendAttentionStatusToRequester =
    function (entry, payload) {
      const requester =
        this.io.sockets.sockets.get(
          entry.requesterSocketId
        );

      if (requester?.connected) {
        requester.emit(
          ATTENTION.STATUS,
          payload
        );
      }
    };

  manager.handleAttentionRequest =
    async function (socket, raw) {
      const authenticatedRequester =
        this.normalizeDeviceId(
          socket.authenticatedDeviceId ||
          socket.parentDeviceId ||
          socket.deviceId
        );

      const request =
        this.normalizeAttention(
          raw,
          authenticatedRequester
        );

      const fail = (
        status,
        reason,
        errorCode
      ) => {
        socket.emit(ATTENTION.STATUS, {
          requestId: request.requestId,
          targetDeviceId:
            request.targetDeviceId,
          status,
          reason,
          errorCode,
          timestamp: Date.now(),
        });
      };

      if (
        !request.requestId ||
        request.requestId.length < 8
      ) {
        return fail(
          "REJECTED",
          "INVALID_REQUEST",
          "INVALID_REQUEST_ID"
        );
      }

      if (!request.requesterDeviceId) {
        return fail(
          "REJECTED",
          "UNAUTHENTICATED",
          "MISSING_REQUESTER"
        );
      }

      if (!request.targetDeviceId) {
        return fail(
          "REJECTED",
          "INVALID_TARGET",
          "MISSING_TARGET"
        );
      }

      if (
        request.targetDeviceId ===
        request.requesterDeviceId
      ) {
        return fail(
          "REJECTED",
          "INVALID_TARGET",
          "TARGET_EQUALS_REQUESTER"
        );
      }

      if (request.expiresAt <= Date.now()) {
        return fail(
          "EXPIRED",
          "TTL_EXPIRED",
          null
        );
      }

      // Реализовать через настоящую
      // familyPermissionService проекта.
      const allowed =
        await this.familyPermissionService
          .canSendAttentionSignal({
            requesterDeviceId:
              request.requesterDeviceId,
            targetDeviceId:
              request.targetDeviceId,
            familyId: request.familyId,
          });

      if (!allowed) {
        return fail(
          "REJECTED",
          "FORBIDDEN",
          "FAMILY_PERMISSION_DENIED"
        );
      }

      const rate =
        this.consumeAttentionRate(
          request.requesterDeviceId,
          request.targetDeviceId
        );

      if (!rate.ok) {
        return fail(
          "REJECTED",
          rate.code,
          rate.code
        );
      }

      const entry = {
        request,
        requesterSocketId: socket.id,
        createdAt: Date.now(),
        expiresAt: request.expiresAt,
      };

      this.pendingAttentionSignals.set(
        request.requestId,
        entry
      );

      const sentCount =
        this.emitToExactDevice(
          request.targetDeviceId,
          ATTENTION.START,
          request
        );

      if (sentCount === 0) {
        this.pendingAttentionSignals.delete(
          request.requestId
        );

        await this.dbManager
          ?.saveAttentionSignalEvent?.({
            ...request,
            status: "FAILED",
            reason: "TARGET_OFFLINE",
          });

        return fail(
          "FAILED",
          "TARGET_OFFLINE",
          "TARGET_NOT_CONNECTED"
        );
      }

      socket.emit(ATTENTION.STATUS, {
        requestId: request.requestId,
        targetDeviceId:
          request.targetDeviceId,
        status: "QUEUED",
        timestamp: Date.now(),
      });

      await this.dbManager
        ?.saveAttentionSignalEvent?.({
          ...request,
          status: "QUEUED",
        });

      const delay = Math.max(
        0,
        request.expiresAt - Date.now()
      );

      setTimeout(() => {
        const pending =
          this.pendingAttentionSignals.get(
            request.requestId
          );

        if (!pending) return;

        this.pendingAttentionSignals.delete(
          request.requestId
        );

        this.sendAttentionStatusToRequester(
          pending,
          {
            requestId:
              request.requestId,
            targetDeviceId:
              request.targetDeviceId,
            status: "EXPIRED",
            reason: "TTL_EXPIRED",
            timestamp: Date.now(),
          }
        );
      }, delay + 50);
    };

  manager.handleAttentionStatus =
    async function (socket, raw) {
      const requestId =
        String(
          raw?.requestId || ""
        ).trim();

      const entry =
        this.pendingAttentionSignals.get(
          requestId
        );

      if (!entry) return;

      const senderDeviceId =
        this.normalizeDeviceId(
          socket.authenticatedDeviceId ||
          socket.registeredDeviceId ||
          socket.deviceId
        );

      if (
        senderDeviceId !==
        entry.request.targetDeviceId
      ) return;

      const status =
        String(
          raw?.status || ""
        ).trim().toUpperCase();

      const allowedStatuses = new Set([
        "DELIVERED",
        "STARTED",
        "COMPLETED",
        "STOPPED",
        "REJECTED",
        "FAILED",
        "EXPIRED",
      ]);

      if (!allowedStatuses.has(status)) {
        return;
      }

      const payload = {
        requestId,
        targetDeviceId:
          entry.request.targetDeviceId,
        status,
        reason: raw?.reason || null,
        errorCode:
          raw?.errorCode || null,
        message: raw?.message || null,
        timestamp:
          Number(raw?.timestamp) ||
          Date.now(),
      };

      this.sendAttentionStatusToRequester(
        entry,
        payload
      );

      await this.dbManager
        ?.updateAttentionSignalStatus?.(
          payload
        );

      if (
        [
          "COMPLETED",
          "STOPPED",
          "REJECTED",
          "FAILED",
          "EXPIRED",
        ].includes(status)
      ) {
        this.pendingAttentionSignals.delete(
          requestId
        );
      }
    };

  manager.handleAttentionStopRequest =
    function (socket, raw) {
      const requestId =
        String(
          raw?.requestId || ""
        ).trim();

      const entry =
        this.pendingAttentionSignals.get(
          requestId
        );

      if (!entry) {
        socket.emit(ATTENTION.STATUS, {
          requestId,
          status: "REJECTED",
          reason: "NOT_ACTIVE",
          errorCode:
            "SIGNAL_NOT_ACTIVE",
          timestamp: Date.now(),
        });
        return;
      }

      const requesterId =
        this.normalizeDeviceId(
          socket.authenticatedDeviceId ||
          socket.parentDeviceId ||
          socket.deviceId
        );

      if (
        requesterId !==
        entry.request.requesterDeviceId
      ) {
        socket.emit(ATTENTION.STATUS, {
          requestId,
          status: "REJECTED",
          reason: "FORBIDDEN",
          errorCode:
            "NOT_SIGNAL_OWNER",
          timestamp: Date.now(),
        });
        return;
      }

      this.emitToExactDevice(
        entry.request.targetDeviceId,
        ATTENTION.STOP,
        {
          requestId,
          targetDeviceId:
            entry.request.targetDeviceId,
          requesterDeviceId:
            requesterId,
          createdAt: Date.now(),
        }
      );
    };

  /*
  В initialize():

  socket.on(
    ATTENTION.REQUEST,
    data =>
      this.handleAttentionRequest(
        socket,
        data
      )
  );

  socket.on(
    ATTENTION.STATUS,
    data =>
      this.handleAttentionStatus(
        socket,
        data
      )
  );

  socket.on(
    ATTENTION.STOP_REQUEST,
    data =>
      this.handleAttentionStopRequest(
        socket,
        data
      )
  );

  После успешной регистрации:
  this.registerDeviceSocket(
    socket,
    REAL_AUTHENTICATED_DEVICE_ID
  );

  При disconnect:
  this.unregisterDeviceSocket(socket);
  */
}

module.exports = {
  ATTENTION,
  installAttentionSignalSupport,
};
