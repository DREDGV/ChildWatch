/**
 * Command Manager
 * Manages commands sent from parent app to child device.
 *
 * Multi-parent note:
 * - transport can fan-out audio chunks to several parent sockets
 * - control is currently exclusive: one parent owns the live stream session
 */

class CommandManager {
    constructor() {
        // Command queue: { deviceId: [commands] }
        this.commandQueue = new Map();

        // Active streaming sessions: { deviceId: session }
        this.streamingSessions = new Map();

        // Audio buffer for streaming: { deviceId: [chunks] }
        this.audioBuffers = new Map();

        // Command types
        this.COMMANDS = {
            START_STREAM: "start_audio_stream",
            STOP_STREAM: "stop_audio_stream",
            START_RECORDING: "start_recording",
            STOP_RECORDING: "stop_recording",
            TAKE_PHOTO: "take_photo",
        };

        // Default timeout for streaming sessions (30 minutes)
        this.DEFAULT_TIMEOUT = 30 * 60 * 1000;
        // Grace period after owner disconnect / reconnect jitter before another parent can take over.
        this.STREAM_OWNER_STALE_MS = 45 * 1000;
    }

    normalizeParentId(value) {
        if (value === null || value === undefined) return "parent";
        const normalized = String(value).trim();
        return normalized || "parent";
    }

    buildSessionSnapshot(deviceId, session) {
        if (!session) return null;
        const startTime = Number(session.startTime || Date.now());
        const ownerParentId = this.normalizeParentId(
            session.ownerParentId || session.parentId
        );
        const lastOwnerSeenAt = Number(session.lastOwnerSeenAt || startTime);
        return {
            deviceId,
            parentId: ownerParentId,
            ownerParentId,
            ownerDisplayName: session.ownerDisplayName || null,
            startTime,
            durationMs: Math.max(0, Date.now() - startTime),
            recording: Boolean(session.recording),
            chunks: Number(session.chunks || 0),
            timeout: Number(session.timeout || this.DEFAULT_TIMEOUT),
            sampleRate: session.sampleRate || null,
            lastOwnerSeenAt,
            ownerStale: this.isSessionOwnerStale(session),
        };
    }

    isSessionOwnerStale(session, now = Date.now()) {
        if (!session) return false;
        const lastOwnerSeenAt = Number(session.lastOwnerSeenAt || session.startTime || 0);
        if (!Number.isFinite(lastOwnerSeenAt) || lastOwnerSeenAt <= 0) return false;
        return (now - lastOwnerSeenAt) > this.STREAM_OWNER_STALE_MS;
    }

    touchStreamingOwner(deviceId, parentId) {
        const normalizedParentId = this.normalizeParentId(parentId);
        const session = this.streamingSessions.get(deviceId);
        if (!session) {
            return null;
        }
        if (
            this.normalizeParentId(session.ownerParentId || session.parentId) !==
            normalizedParentId
        ) {
            return this.buildSessionSnapshot(deviceId, session);
        }
        session.ownerParentId = normalizedParentId;
        session.parentId = normalizedParentId;
        session.lastOwnerSeenAt = Date.now();
        return this.buildSessionSnapshot(deviceId, session);
    }

    buildBusyResult(code, deviceId, session) {
        return {
            ok: false,
            busy: true,
            code,
            session: this.buildSessionSnapshot(deviceId, session),
        };
    }

    /**
     * Add command to queue for specific device
     */
    addCommand(deviceId, command, data = {}) {
        if (!this.commandQueue.has(deviceId)) {
            this.commandQueue.set(deviceId, []);
        }

        const commandObj = {
            id: this.generateCommandId(),
            type: command,
            data,
            timestamp: Date.now(),
            status: "pending",
        };

        this.commandQueue.get(deviceId).push(commandObj);
        console.log(`рџ“¤ Command added for ${deviceId}: ${command}`, data);

        return commandObj;
    }

    /**
     * Get pending commands for device
     */
    getCommands(deviceId) {
        if (!this.commandQueue.has(deviceId)) {
            return [];
        }

        const commands = this.commandQueue.get(deviceId);
        commands.forEach((cmd) => {
            cmd.status = "delivered";
        });

        const result = [...commands];
        this.commandQueue.delete(deviceId);

        return result;
    }

    requestStreamingStart(deviceId, parentId, timeoutMinutes = 30, options = {}) {
        const normalizedParentId = this.normalizeParentId(parentId);
        const existingSession = this.streamingSessions.get(deviceId);
        const ownerDisplayName =
            typeof options.ownerDisplayName === "string" && options.ownerDisplayName.trim()
                ? options.ownerDisplayName.trim().slice(0, 100)
                : null;

        if (
            existingSession &&
            !this.isSessionOwnerStale(existingSession) &&
            this.normalizeParentId(existingSession.ownerParentId || existingSession.parentId) !==
                normalizedParentId
        ) {
            return this.buildBusyResult("STREAM_BUSY", deviceId, existingSession);
        }

        const timeout = timeoutMinutes * 60 * 1000;
        const sampleRate =
            options && Number.isFinite(Number(options.sampleRate))
                ? Number(options.sampleRate)
                : null;
        const reused =
            Boolean(existingSession) &&
            this.normalizeParentId(existingSession.ownerParentId || existingSession.parentId) ===
                normalizedParentId &&
            !this.isSessionOwnerStale(existingSession);

        const nextSession = {
            parentId: normalizedParentId,
            ownerParentId: normalizedParentId,
            ownerDisplayName: ownerDisplayName || existingSession?.ownerDisplayName || null,
            startTime: reused ? existingSession.startTime : Date.now(),
            recording: false,
            chunks: reused ? Number(existingSession.chunks || 0) : 0,
            timeout,
            sampleRate,
            lastOwnerSeenAt: Date.now(),
        };

        this.streamingSessions.set(deviceId, nextSession);

        if (!this.audioBuffers.has(deviceId)) {
            this.audioBuffers.set(deviceId, []);
        }

        this.addCommand(deviceId, this.COMMANDS.START_STREAM, {
            parentId: normalizedParentId,
            sampleRate,
        });

        console.log(
            `рџЋ™пёЏ Audio streaming started for ${deviceId} by ${normalizedParentId}`
        );
        return {
            ok: true,
            reused,
            session: this.buildSessionSnapshot(deviceId, nextSession),
        };
    }

    requestStreamingStop(deviceId, parentId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session) {
            return { ok: false, code: "NO_ACTIVE_SESSION" };
        }

        const normalizedParentId = this.normalizeParentId(parentId);
        if (
            !this.isSessionOwnerStale(session) &&
            this.normalizeParentId(session.ownerParentId || session.parentId) !== normalizedParentId
        ) {
            return this.buildBusyResult("STREAM_CONTROL_FORBIDDEN", deviceId, session);
        }

        this.addCommand(deviceId, this.COMMANDS.STOP_STREAM);
        this.streamingSessions.delete(deviceId);
        this.audioBuffers.delete(deviceId);

        console.log(`рџ›‘ Audio streaming stopped for ${deviceId}`);
        return { ok: true };
    }

    requestRecordingStart(deviceId, parentId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session) {
            return {
                ok: false,
                error: "No active streaming session",
                code: "NO_ACTIVE_SESSION",
            };
        }

        const normalizedParentId = this.normalizeParentId(parentId);
        if (
            !this.isSessionOwnerStale(session) &&
            this.normalizeParentId(session.ownerParentId || session.parentId) !== normalizedParentId
        ) {
            return {
                ...this.buildBusyResult("STREAM_CONTROL_FORBIDDEN", deviceId, session),
                error: "Streaming session is owned by another parent",
            };
        }

        session.recording = true;
        session.recordingStartTime = Date.now();
        session.ownerParentId = normalizedParentId;
        session.parentId = normalizedParentId;
        session.lastOwnerSeenAt = Date.now();

        this.addCommand(deviceId, this.COMMANDS.START_RECORDING);

        console.log(`вЏєпёЏ Recording started for ${deviceId}`);
        return {
            ok: true,
            session: this.buildSessionSnapshot(deviceId, session),
        };
    }

    requestRecordingStop(deviceId, parentId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session || !session.recording) {
            return {
                ok: false,
                error: "No active recording session",
                code: "NO_ACTIVE_RECORDING",
            };
        }

        const normalizedParentId = this.normalizeParentId(parentId);
        if (
            !this.isSessionOwnerStale(session) &&
            this.normalizeParentId(session.ownerParentId || session.parentId) !== normalizedParentId
        ) {
            return {
                ...this.buildBusyResult("STREAM_CONTROL_FORBIDDEN", deviceId, session),
                error: "Recording session is owned by another parent",
            };
        }

        session.recording = false;
        session.ownerParentId = normalizedParentId;
        session.parentId = normalizedParentId;
        session.lastOwnerSeenAt = Date.now();
        const duration = Date.now() - session.recordingStartTime;

        this.addCommand(deviceId, this.COMMANDS.STOP_RECORDING, { duration });

        console.log(
            `вЏ№пёЏ Recording stopped for ${deviceId}, duration: ${duration}ms`
        );
        return {
            ok: true,
            duration,
            session: this.buildSessionSnapshot(deviceId, session),
        };
    }

    /**
     * Backward-compatible wrappers.
     */
    startStreaming(deviceId, parentId, timeoutMinutes = 30, options = {}) {
        return this.requestStreamingStart(deviceId, parentId, timeoutMinutes, options).ok;
    }

    stopStreaming(deviceId) {
        return this.requestStreamingStop(deviceId, "parent").ok;
    }

    startRecording(deviceId) {
        const result = this.requestRecordingStart(deviceId, "parent");
        return result.ok ? true : { error: result.error || "No active streaming session" };
    }

    stopRecording(deviceId) {
        const result = this.requestRecordingStop(deviceId, "parent");
        return result.ok
            ? { duration: result.duration }
            : { error: result.error || "No active recording session" };
    }

    /**
     * Add audio chunk to buffer
     */
    addAudioChunk(deviceId, chunk) {
        if (!this.audioBuffers.has(deviceId)) {
            this.audioBuffers.set(deviceId, []);
        }

        const buffer = this.audioBuffers.get(deviceId);
        buffer.push({
            data: chunk,
            timestamp: Date.now(),
        });

        if (buffer.length > 15) {
            buffer.shift();
        }

        const session = this.streamingSessions.get(deviceId);
        if (session) {
            session.chunks++;
        }

        return buffer.length;
    }

    /**
     * Get latest audio chunks for streaming (WITHOUT removing from buffer)
     * Chunks are auto-cleaned after 60 seconds by TTL
     */
    getAudioChunks(deviceId, count = 5) {
        if (!this.audioBuffers.has(deviceId)) {
            return [];
        }

        const buffer = this.audioBuffers.get(deviceId);
        const now = Date.now();
        const validChunks = buffer.filter((chunk) => now - chunk.timestamp < 60000);

        this.audioBuffers.set(deviceId, validChunks);
        return validChunks.slice(-count);
    }

    isStreaming(deviceId) {
        return this.streamingSessions.has(deviceId);
    }

    isRecording(deviceId) {
        const session = this.streamingSessions.get(deviceId);
        return session ? session.recording : false;
    }

    getSessionInfo(deviceId) {
        return this.buildSessionSnapshot(deviceId, this.streamingSessions.get(deviceId));
    }

    generateCommandId() {
        return `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    }

    cleanup() {
        const now = Date.now();

        for (const [deviceId, session] of this.streamingSessions.entries()) {
            const timeout = session.timeout || this.DEFAULT_TIMEOUT;
            const elapsed = now - session.startTime;

            if (elapsed > timeout) {
                const minutes = Math.floor(elapsed / 60000);
                console.log(
                    `рџ§№ Cleaning up old session for ${deviceId} (${minutes} minutes)`
                );
                this.requestStreamingStop(
                    deviceId,
                    session.ownerParentId || session.parentId || "parent"
                );
            }
        }
    }
}

module.exports = CommandManager;
