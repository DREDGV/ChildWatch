/**
 * WebSocketManager - Manages WebSocket connections for real-time audio streaming
 *
 * Architecture:
 * - ParentWatch (child device) connects and sends audio chunks
 * - ChildWatch (parent device) connects and receives audio chunks
 * - Server routes chunks from child → parent in real-time
 */

class WebSocketManager {
    constructor(io) {
        this.io = io;

        // Map: deviceId (child) → socket.id
        this.childSockets = new Map();

        // Map: deviceId (child) → parentSocketId
        this.activeStreams = new Map();

        // Map: parentSocketId → deviceId (child being monitored)
        this.parentSockets = new Map();

        console.log('🔌 WebSocketManager initialized');
    }

    /**
     * Initialize WebSocket event handlers
     */
    initialize() {
        this.io.on('connection', (socket) => {
            console.log(`🔌 Client connected: ${socket.id}`);

            // Handle child device (ParentWatch) connection
            socket.on('register_child', (data) => {
                this.handleChildRegistration(socket, data);
            });

            // Handle parent device (ChildWatch) connection
            socket.on('register_parent', (data) => {
                this.handleParentRegistration(socket, data);
            });

            // Handle audio chunk from child device
            // Receives: metadata (JSON), binaryData (Buffer)
            socket.on('audio_chunk', (metadata, binaryData) => {
                this.handleAudioChunk(socket, metadata, binaryData);
            });

            // Handle heartbeat/ping
            socket.on('ping', () => {
                socket.emit('pong', { timestamp: Date.now() });
            });

            // Handle disconnection
            socket.on('disconnect', () => {
                this.handleDisconnect(socket);
            });

            // Handle errors
            socket.on('error', (error) => {
                console.error(`❌ Socket error (${socket.id}):`, error);
            });
        });

        console.log('✅ WebSocket event handlers registered');
    }

    /**
     * Register child device (ParentWatch)
     */
    handleChildRegistration(socket, data) {
        const { deviceId } = data;

        if (!deviceId) {
            console.error('❌ Child registration failed: missing deviceId');
            socket.emit('error', { message: 'Missing deviceId' });
            return;
        }

        // Store child socket mapping
        this.childSockets.set(deviceId, socket.id);
        socket.deviceId = deviceId;
        socket.deviceType = 'child';

        console.log(`📱 Child device registered: ${deviceId} (socket: ${socket.id})`);

        socket.emit('registered', {
            deviceId,
            role: 'child',
            timestamp: Date.now()
        });

        // SIMPLIFIED ARCHITECTURE - Auto-link with parent if exists
        // Check if there's already a parent listening for this device
        const parentSockets = this.activeStreams.get(deviceId);
        if (parentSockets && parentSockets.size > 0) {
            parentSockets.forEach(parentSocketId => {
                this.io.to(parentSocketId).emit('child_connected', {
                    deviceId,
                    timestamp: Date.now()
                });
            });
            console.log(`🔗 Auto-linked child ${socket.id} with ${parentSockets.size} existing parent(s) for ${deviceId}`);
        } else {
            console.log(`⚠️ No parent listening for ${deviceId} yet - will auto-link when parent connects`);
        }
    }

    /**
     * Register parent device (ChildWatch)
     */
    handleParentRegistration(socket, data) {
        const { childDeviceId } = data;

        if (!childDeviceId) {
            console.error('❌ Parent registration failed: missing childDeviceId');
            socket.emit('error', { message: 'Missing childDeviceId' });
            return;
        }

        // Store parent socket mapping
        this.parentSockets.set(socket.id, childDeviceId);
        socket.childDeviceId = childDeviceId;
        socket.deviceType = 'parent';

        // Track listening parent sockets
        if (!this.activeStreams.has(childDeviceId)) {
            this.activeStreams.set(childDeviceId, new Set());
        }
        this.activeStreams.get(childDeviceId).add(socket.id);

        console.log(`👨‍👩‍👧 Parent device registered: monitoring ${childDeviceId} (socket: ${socket.id})`);

        socket.emit('registered', {
            childDeviceId,
            role: 'parent',
            timestamp: Date.now()
        });

        // SIMPLIFIED ARCHITECTURE - Auto-link devices
        // Notify child device that parent is listening
        const childSocketId = this.childSockets.get(childDeviceId);
        if (childSocketId) {
            this.io.to(childSocketId).emit('parent_connected', {
                timestamp: Date.now()
            });
            console.log(`🔗 Auto-linked parent ${socket.id} with child ${childSocketId} for ${childDeviceId}`);
        } else {
            console.log(`⚠️ Child device ${childDeviceId} not connected yet - will auto-link when it connects`);
        }
    }

    /**
     * Handle audio chunk from child device
     * Receives metadata (JSON) and binary data (Buffer) as separate arguments
     */
    handleAudioChunk(socket, metadata, binaryData) {
        const deviceId = socket.deviceId;

        if (!deviceId) {
            console.error('❌ Audio chunk rejected: socket not registered as child');
            return;
        }

        // Extract metadata
        const { sequence, timestamp, recording } = metadata || {};

        // Validate binary data
        if (!binaryData || !Buffer.isBuffer(binaryData)) {
            console.error('❌ Audio chunk rejected: missing or invalid binary data');
            return;
        }

        // Get parent sockets for this child device
        const parentSockets = this.activeStreams.get(deviceId);

        if (!parentSockets || parentSockets.size === 0) {
            // No parent listening - just acknowledge receipt
            console.log(`⚠️ No parent listening for ${deviceId} - chunk ${sequence} ignored`);
            return;
        }

        // Forward chunk to ALL parent devices in real-time
        // Send metadata and binary data separately
        parentSockets.forEach(parentSocketId => {
            this.io.to(parentSocketId).emit('audio_chunk', {
                deviceId,
                sequence,
                timestamp: timestamp || Date.now(),
                recording,
                receivedAt: Date.now()
            }, binaryData); // Binary data as second argument
        });

        // Log every chunk for debugging
        console.log(`🎙️ Forwarded chunk ${sequence} from ${deviceId} to ${parentSockets.size} parent(s) (${binaryData.length} bytes)`);
    }

    emitCriticalAlert(deviceId, alertPayload) {
        const parentSockets = this.activeStreams.get(deviceId);
        if (parentSockets && parentSockets.size > 0) {
            parentSockets.forEach(socketId => {
                this.io.to(socketId).emit('critical_alert', alertPayload);
            });
            return true;
        }
        return false;
    }


    /**
     * Handle client disconnection
     */
    handleDisconnect(socket) {
        const socketId = socket.id;
        const deviceType = socket.deviceType;

        console.log(`🔌 Client disconnected: ${socketId} (type: ${deviceType || 'unknown'})`);

        if (deviceType === 'child') {
            const deviceId = socket.deviceId;

            // Remove child socket mapping
            this.childSockets.delete(deviceId);

            // Notify ALL parents that child disconnected
            const parentSockets = this.activeStreams.get(deviceId);
            if (parentSockets && parentSockets.size > 0) {
                parentSockets.forEach(parentSocketId => {
                    this.io.to(parentSocketId).emit('child_disconnected', {
                        deviceId,
                        timestamp: Date.now()
                    });
                });
            }

            // Remove streaming session
            this.activeStreams.delete(deviceId);

            console.log(`📱 Child device disconnected: ${deviceId}`);
        }
        else if (deviceType === 'parent') {
            const childDeviceId = socket.childDeviceId;

            // Remove parent socket mapping
            this.parentSockets.delete(socketId);

            // Remove this parent from streaming session (but keep others)
            const parentSockets = this.activeStreams.get(childDeviceId);
            if (parentSockets) {
                parentSockets.delete(socketId);
                
                // If no more parents listening, remove the session
                if (parentSockets.size === 0) {
                    this.activeStreams.delete(childDeviceId);
                }
            }

            // Notify child that parent stopped listening
            const childSocketId = this.childSockets.get(childDeviceId);
            if (childSocketId) {
                this.io.to(childSocketId).emit('parent_disconnected', {
                    timestamp: Date.now()
                });
            }

            console.log(`👨‍👩‍👧 Parent device disconnected: was monitoring ${childDeviceId}`);
        }
    }

    /**
     * Get connection statistics
     */
    getStats() {
        return {
            childDevices: this.childSockets.size,
            parentDevices: this.parentSockets.size,
            activeStreams: this.activeStreams.size,
            totalConnections: this.io.engine.clientsCount
        };
    }

    /**
     * Check if child device is connected
     */
    isChildConnected(deviceId) {
        return this.childSockets.has(deviceId);
    }

    /**
     * Check if parent is listening to child device
     */
    hasActiveListener(deviceId) {
        return this.activeStreams.has(deviceId);
    }

    /**
     * Force disconnect a device
     */
    disconnectDevice(socketId) {
        const socket = this.io.sockets.sockets.get(socketId);
        if (socket) {
            socket.disconnect(true);
            return true;
        }
        return false;
    }
}

module.exports = WebSocketManager;
