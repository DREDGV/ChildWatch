/**
 * Command Manager
 * Manages commands sent from parent app to child device
 * Supports audio streaming, recording, and other remote commands
 */

class CommandManager {
    constructor() {
        // Command queue: { deviceId: [commands] }
        this.commandQueue = new Map();

        // Active streaming sessions: { deviceId: { parentId, startTime, recording } }
        this.streamingSessions = new Map();

        // Audio buffer for streaming: { deviceId: [chunks] }
        this.audioBuffers = new Map();

        // Command types
        this.COMMANDS = {
            START_STREAM: 'start_audio_stream',
            STOP_STREAM: 'stop_audio_stream',
            START_RECORDING: 'start_recording',
            STOP_RECORDING: 'stop_recording',
            TAKE_PHOTO: 'take_photo'
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
            data: data,
            timestamp: Date.now(),
            status: 'pending'
        };

        this.commandQueue.get(deviceId).push(commandObj);
        console.log(`📤 Command added for ${deviceId}: ${command}`, data);

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
        // Mark all as delivered
        commands.forEach(cmd => cmd.status = 'delivered');

        // Clear queue after retrieval
        const result = [...commands];
        this.commandQueue.delete(deviceId);

        return result;
    }

    /**
     * Start audio streaming session
     */
    startStreaming(deviceId, parentId) {
        this.streamingSessions.set(deviceId, {
            parentId: parentId,
            startTime: Date.now(),
            recording: false,
            chunks: 0
        });

        // Initialize audio buffer
        if (!this.audioBuffers.has(deviceId)) {
            this.audioBuffers.set(deviceId, []);
        }

        this.addCommand(deviceId, this.COMMANDS.START_STREAM, { parentId });

        console.log(`🎙️ Audio streaming started for ${deviceId} by ${parentId}`);
        return true;
    }

    /**
     * Stop audio streaming session
     */
    stopStreaming(deviceId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session) {
            return false;
        }

        this.addCommand(deviceId, this.COMMANDS.STOP_STREAM);

        // Clean up
        this.streamingSessions.delete(deviceId);
        this.audioBuffers.delete(deviceId);

        console.log(`🛑 Audio streaming stopped for ${deviceId}`);
        return true;
    }

    /**
     * Start recording during streaming
     */
    startRecording(deviceId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session) {
            return { error: 'No active streaming session' };
        }

        session.recording = true;
        session.recordingStartTime = Date.now();

        this.addCommand(deviceId, this.COMMANDS.START_RECORDING);

        console.log(`⏺️ Recording started for ${deviceId}`);
        return true;
    }

    /**
     * Stop recording during streaming
     */
    stopRecording(deviceId) {
        const session = this.streamingSessions.get(deviceId);
        if (!session || !session.recording) {
            return { error: 'No active recording session' };
        }

        session.recording = false;
        const duration = Date.now() - session.recordingStartTime;

        this.addCommand(deviceId, this.COMMANDS.STOP_RECORDING, { duration });

        console.log(`⏹️ Recording stopped for ${deviceId}, duration: ${duration}ms`);
        return { duration };
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
            timestamp: Date.now()
        });

        // Keep only last 30 seconds of chunks (assuming 2 sec per chunk)
        if (buffer.length > 15) {
            buffer.shift();
        }

        // Update session stats
        const session = this.streamingSessions.get(deviceId);
        if (session) {
            session.chunks++;
        }

        return buffer.length;
    }

    /**
     * Get latest audio chunks for streaming
     */
    getAudioChunks(deviceId, count = 5) {
        if (!this.audioBuffers.has(deviceId)) {
            return [];
        }

        const buffer = this.audioBuffers.get(deviceId);
        return buffer.slice(-count);
    }

    /**
     * Check if device is currently streaming
     */
    isStreaming(deviceId) {
        return this.streamingSessions.has(deviceId);
    }

    /**
     * Check if device is recording
     */
    isRecording(deviceId) {
        const session = this.streamingSessions.get(deviceId);
        return session ? session.recording : false;
    }

    /**
     * Get streaming session info
     */
    getSessionInfo(deviceId) {
        return this.streamingSessions.get(deviceId) || null;
    }

    /**
     * Generate unique command ID
     */
    generateCommandId() {
        return `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    }

    /**
     * Clean up old sessions (call periodically)
     */
    cleanup() {
        const now = Date.now();
        const TIMEOUT = 5 * 60 * 1000; // 5 minutes

        for (const [deviceId, session] of this.streamingSessions.entries()) {
            if (now - session.startTime > TIMEOUT) {
                console.log(`🧹 Cleaning up old session for ${deviceId}`);
                this.stopStreaming(deviceId);
            }
        }
    }
}

module.exports = CommandManager;
