# Audio Streaming System - Complete Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Configuration](#configuration)
4. [Implementation Details](#implementation-details)
5. [Diagnostics & Troubleshooting](#diagnostics--troubleshooting)
6. [Testing Guide](#testing-guide)
7. [Improvements & Roadmap](#improvements--roadmap)
8. [API Reference](#api-reference)

---

## Overview

### What is the Audio Streaming System?

The ChildWatch audio streaming system enables real-time audio monitoring from a child's device (ParentWatch) to a parent's device (ChildWatch). The system operates in two modes:

#### Mode A: Real-time Listening (No Recording)
- Automatic covert activation on the child's device
- Parent listens to audio in real-time
- Audio is NOT saved on the server
- Buffer stores only the last 30 seconds for stability
- Designed for live monitoring without creating permanent records

#### Mode B: Listening with Recording
- During live listening, parent can press "Record" button
- Audio begins saving to server storage
- After stopping, file is available for download and playback
- Creates permanent audio records for later review

### Key Features
- Real-time audio streaming with minimal latency
- Chunk-based transmission (2-3 second segments)
- Buffer queue management for smooth playback
- Automatic session management
- Cloud-based relay server (Railway/Render)
- PCM 16-bit / Opus codec support
- Configurable quality settings

### System Components
- **ParentWatch** (child's phone) - Records and transmits audio
- **ChildWatch** (parent's phone) - Receives and plays audio
- **Node.js Server** (cloud) - Intermediate buffer for audio chunks

---

## Architecture

### High-Level Data Flow

```
┌─────────────────────┐         ┌─────────────────────┐         ┌─────────────────────┐
│  PARENT             │         │  SERVER (Cloud)     │         │  CHILD              │
│  ChildWatch         │         │  Railway/Render     │         │  ParentWatch        │
└─────────────────────┘         └─────────────────────┘         └─────────────────────┘

1. Press "Start        ────────► POST /api/streaming/start
   Listening"                     deviceId: child-XXXXXXXX

                                 Command saved in queue

                                                                2. Poll every 30s
                                                                   GET /api/streaming/commands

                                                                3. Receive START command
                                                                   Enable microphone COVERTLY

                                                                4. Record audio chunks
                                                                   2-3 second segments

                                 ◄──────────────────────────── POST /api/streaming/chunk
                                                                   + audio file (PCM/WebM)

                                 Buffer: [chunk1, chunk2, ...]
                                 (stores last 30 chunks)

5. Request audio       ──────► GET /api/streaming/chunks
   every 2 seconds              deviceId: child-XXXXXXXX

   ◄────────────────── Returns last 5 chunks
                       in Base64 format

6. Play audio in
   real-time

7. (OPTIONAL)          ──────► POST /api/streaming/record/start
   Press "Record"

                                 Flag recording = true
                                 Save chunks to disk file

8. Continue listening  ◄──────► Chunks continue streaming
   + recording                   + saved to file

9. Press "Stop         ──────► POST /api/streaming/record/stop
   Recording"

                                 Finalize audio file
                                 Save to database

10. Press "Stop        ──────► POST /api/streaming/stop
    Listening"

                                 STOP command sent

                                                                11. Receive STOP command
                                                                    Disable microphone

                                 Clear buffer
```

### Component Architecture

#### ParentWatch (Child Device) - Recording Side

**File:** `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt`

**Recording Process:**
```
┌─────────────┐
│ AudioRecord │
│   API       │
└──────┬──────┘
       │ Records PCM audio
       ▼
┌─────────────────┐
│ Record Chunk    │ 3 seconds @ 44100 Hz
│ 264,600 bytes   │ Mono, PCM 16-bit
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│ Upload to       │ POST /api/streaming/chunk
│ Server          │ Multipart form data
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│ Wait 3s         │ Repeat cycle
└─────────────────┘
```

#### Server (Node.js) - Buffer Layer

**File:** `server/managers/CommandManager.js`

**Buffer Management:**
```javascript
class CommandManager {
    constructor() {
        this.audioBuffers = new Map(); // deviceId -> [chunks]
    }

    // Store uploaded chunk
    addAudioChunk(deviceId, chunkData, sequence) {
        if (!this.audioBuffers.has(deviceId)) {
            this.audioBuffers.set(deviceId, []);
        }

        const buffer = this.audioBuffers.get(deviceId);

        buffer.push({
            sequence: sequence,
            data: chunkData,      // Buffer (264,600 bytes)
            timestamp: Date.now()
        });

        // Limit buffer size (max 30 chunks = 90 seconds)
        if (buffer.length > 30) {
            buffer.shift(); // Remove oldest chunk
        }
    }

    // Get chunks for playback
    getAudioChunks(deviceId, count = 5) {
        const buffer = this.audioBuffers.get(deviceId);
        const chunks = buffer.splice(0, Math.min(count, buffer.length));
        return chunks;
    }
}
```

**Key Features:**
- In-memory circular buffer (max 30 chunks)
- FIFO queue (splice removes returned chunks)
- Automatic cleanup of old sessions
- Per-device isolation

#### ChildWatch (Parent Device) - Playback Side

**File:** `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`

**Dual-Thread Playback Architecture:**

```
Thread 1: Fetching Chunks               Thread 2: Continuous Playback
┌──────────────────────┐                ┌──────────────────────┐
│ Poll server every    │                │ While streaming      │
│ 2 seconds            │                │                      │
└──────┬───────────────┘                └──────┬───────────────┘
       │                                       │
       ▼                                       ▼
┌──────────────────────┐                ┌──────────────────────┐
│ GET /api/streaming/  │                │ Check queue          │
│ chunks               │                │ chunkQueue.poll()    │
└──────┬───────────────┘                └──────┬───────────────┘
       │                                       │
       ▼                                       ▼
┌──────────────────────┐                ┌──────────────────────┐
│ Add to queue:        │◄───────────────│ If !empty:           │
│ chunkQueue.offer()   │   Concurrent   │ audioTrack.write()   │
└──────┬───────────────┘   LinkedQueue  └──────┬───────────────┘
       │                                       │
       ▼                                       ▼
┌──────────────────────┐                ┌──────────────────────┐
│ Check buffer:        │                │ If empty:            │
│ If size >= 7         │                │ Wait 100ms           │
│ Start playback       │                │ "Буферизация..."     │
└──────────────────────┘                └──────────────────────┘
```

**Buffering Strategy:**
- Pre-buffering: Wait for 7 chunks (21 seconds) before starting playback
- Continuous polling: Fetch new chunks every 2 seconds
- Queue management: ConcurrentLinkedQueue for thread-safe operations
- Adaptive status: "Буферизация..." when queue empty

---

## Configuration

### Audio Format Parameters

#### ParentWatch (Recording)
```kotlin
// Current Implementation (v3.3.1 / v3.2.4)
private const val CHUNK_DURATION_MS = 3000L    // 3 seconds per chunk
private const val SAMPLE_RATE = 44100          // 44.1 kHz
private const val CHANNEL_CONFIG = CHANNEL_IN_MONO
private const val AUDIO_FORMAT = ENCODING_PCM_16BIT

// Calculated values:
// Chunk size = 3s * 44100 Hz * 2 bytes (16-bit) * 1 channel = 264,600 bytes
// Bitrate = 44100 * 16 * 1 = 705.6 kbps (uncompressed PCM)
```

#### ChildWatch (Playback)
```kotlin
// Polling & Buffering
private const val UPDATE_INTERVAL_MS = 2000L   // Poll server every 2 seconds
private const val MIN_BUFFER_CHUNKS = 7        // Wait for 7 chunks before playback

// AudioTrack Configuration
audioTrack = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(USAGE_MEDIA)
            .setContentType(CONTENT_TYPE_SPEECH)
            .build()
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(44100)
            .setChannelMask(CHANNEL_OUT_MONO)
            .setEncoding(ENCODING_PCM_16BIT)
            .build()
    )
    .setBufferSizeInBytes(minBufferSize * 8)  // 8x buffer for smooth playback
    .setTransferMode(MODE_STREAM)
    .build()
```

### Server Configuration

#### Buffer Settings
```javascript
// CommandManager.js
MAX_CHUNKS_PER_DEVICE = 30;    // 90 seconds total buffer
CHUNKS_PER_REQUEST = 5;         // Return up to 5 chunks per poll
SESSION_TIMEOUT = 300000;       // 5 minutes inactive → cleanup
```

#### Network Settings
```javascript
// Railway.app environment
PORT = 3000
NODE_ENV = production
MAX_REQUEST_SIZE = 10mb         // For audio chunk uploads
RATE_LIMIT = 100 req/min        // Per IP address
```

### Quality Presets (Planned)

#### Low Quality (22050 Hz)
- Lower traffic consumption
- Suitable for slow networks
- Chunk size: ~132,300 bytes
- Bitrate: ~352.8 kbps

#### Medium Quality (44100 Hz) - Current
- Default setting
- Good balance of quality/traffic
- Chunk size: ~264,600 bytes
- Bitrate: ~705.6 kbps

#### High Quality (48000 Hz)
- Best audio quality
- Higher traffic consumption
- Chunk size: ~288,000 bytes
- Bitrate: ~768 kbps

---

## Implementation Details

### ParentWatch Recording Implementation

**AudioStreamRecorder.kt - Core Recording Logic:**

```kotlin
class AudioStreamRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var sequence = 0

    fun startStreaming(deviceId: String, serverUrl: String) {
        isRecording = true

        lifecycleScope.launch(Dispatchers.IO) {
            while (isRecording) {
                // 1. Record audio chunk
                val audioData = recordChunk()

                if (audioData != null) {
                    // 2. Upload to server
                    networkHelper.uploadAudioChunk(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        audioData = audioData,
                        sequence = sequence++
                    )

                    Log.d(TAG, "Chunk $sequence uploaded (${audioData.size} bytes)")
                } else {
                    Log.e(TAG, "No audio data recorded")
                }

                // 3. Wait before next chunk
                delay(CHUNK_DURATION_MS)
            }
        }
    }

    private fun recordChunk(): ByteArray? {
        // Initialize AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return null
        }

        // Record for CHUNK_DURATION_MS
        audioRecord?.startRecording()

        val chunkSize = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000 * 2).toInt() // 264,600 bytes
        val buffer = ByteArray(chunkSize)

        var totalRead = 0
        while (totalRead < chunkSize && isRecording) {
            val read = audioRecord?.read(buffer, totalRead, chunkSize - totalRead) ?: 0
            if (read > 0) {
                totalRead += read
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return if (totalRead > 0) buffer else null
    }

    fun stopStreaming() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
```

### ChildWatch Playback Implementation

**AudioStreamingActivity.kt - Dual-Thread Architecture:**

```kotlin
class AudioStreamingActivity : AppCompatActivity() {
    private val chunkQueue = ConcurrentLinkedQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false
    private var isBuffering = true
    private var updateJob: Job? = null
    private var playbackJob: Job? = null

    private fun startStreaming() {
        isStreaming = true
        isBuffering = true

        initializeAudioTrack()
        audioTrack?.play()

        // Thread 1: Fetch chunks from server
        updateJob = lifecycleScope.launch {
            while (isStreaming) {
                try {
                    val chunks = networkClient.getAudioChunks(serverUrl, deviceId)

                    chunks.forEach { chunk ->
                        val audioData = Base64.decode(chunk.data, Base64.DEFAULT)
                        chunkQueue.offer(audioData)
                    }

                    updateUI(
                        totalChunks = totalChunks + chunks.size,
                        queueSize = chunkQueue.size
                    )

                    // Start playback when buffered enough
                    if (isBuffering && chunkQueue.size >= MIN_BUFFER_CHUNKS) {
                        isBuffering = false
                        statusText = "Воспроизведение..."
                        Log.d(TAG, "Buffer filled, starting playback")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching chunks", e)
                }

                delay(UPDATE_INTERVAL_MS)
            }
        }

        // Thread 2: Continuous playback
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isStreaming) {
                if (!isBuffering && chunkQueue.isNotEmpty()) {
                    val chunk = chunkQueue.poll()

                    if (chunk != null) {
                        // Blocking write - waits until chunk is played
                        val written = audioTrack?.write(
                            chunk,
                            0,
                            chunk.size,
                            AudioTrack.WRITE_BLOCKING
                        ) ?: 0

                        if (written < 0) {
                            Log.e(TAG, "AudioTrack write error: $written")
                        }

                        Log.d(TAG, "Played chunk: $written bytes (queue size: ${chunkQueue.size})")
                    }
                } else {
                    // Queue empty - wait for more chunks
                    if (!isBuffering) {
                        isBuffering = true
                        withContext(Dispatchers.Main) {
                            statusText = "Буферизация..."
                        }
                    }
                    delay(100)
                }
            }
        }
    }

    private fun initializeAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_OUT_MONO,
            ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 8)  // 8x for smooth playback
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun stopStreaming() {
        isStreaming = false
        updateJob?.cancel()
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        chunkQueue.clear()
    }
}
```

---

## Diagnostics & Troubleshooting

### Common Issue: Audio Interruptions (Wave Pattern)

**Symptoms:**
- Audio plays for 2-5 seconds
- Silence for 2-3 seconds
- Audio resumes for 2-5 seconds
- Pattern repeats continuously

**When it occurs:**
- On real Android devices
- Through Railway cloud server
- With stable WiFi connection

### Root Cause Analysis

#### Problem: Timing Mismatch

**Ideal Flow (Target):**
```
Time   ParentWatch                Server                  ChildWatch
----   -----------                ------                  ----------
0s     Start recording            Empty buffer            Start polling
3s     Upload chunk #0       --> Store chunk #0
4s                                                        Poll (empty)
6s     Upload chunk #1       --> Store chunks            Poll -> get 2 chunks
                                                          Queue: [0, 1]
9s     Upload chunk #2       --> Store chunk             Poll -> get 1 chunk
                                                          Queue: [0, 1, 2]
```

**Problematic Scenario (Current Issue):**
```
Time   ParentWatch                Server                  ChildWatch
----   -----------                ------                  ----------
0s     Start recording            Empty buffer            Start polling
3s     Upload starts...
5s     Upload complete       --> Store chunk #0          Poll (empty)
                                                          Queue: []
7s                                                        Poll -> get 1 chunk
                                                          Queue: [0]
                                                          Playing chunk #0
8s                                                        Queue empty!
                                                          SILENCE
10s    Upload chunk #1       --> Store chunk #1          Poll -> get 1 chunk
                                                          Queue: [1]
                                                          Resume playback
13s                                                       Queue empty again!
                                                          SILENCE
```

**Bottlenecks:**
1. Upload latency: 3s recording + 2s upload = 5s between chunks
2. Polling interval: 2s between requests
3. Network jitter: Railway latency varies 500-2000ms
4. Playback speed: 3s chunk plays in 3s, but next chunk arrives in 5s

### Diagnostic Procedures

#### Step 1: Check ParentWatch Recording Speed

**Connect ParentWatch via USB and run logcat:**
```bash
adb logcat | grep -E "AudioStreamRecorder|Chunk.*uploaded"
```

**Expected output:**
```
AudioStreamRecorder: Chunk 0 uploaded successfully (264600 bytes)
AudioStreamRecorder: Chunk 1 uploaded successfully (264600 bytes)
AudioStreamRecorder: Chunk 2 uploaded successfully (264600 bytes)
```

**Interval between chunks should be ~3 seconds!**

If interval > 4 seconds → ParentWatch not recording fast enough!

#### Step 2: Check ChildWatch Queue Size

**Connect ChildWatch via USB and run logcat:**
```bash
adb logcat | grep -E "AudioStreamingActivity|queue size"
```

**Expected output:**
```
AudioStreamingActivity: Added 1 chunks to queue (total: 5, queue size: 3)
AudioStreamingActivity: Played chunk: 264600 bytes (queue size: 2)
AudioStreamingActivity: Added 1 chunks to queue (total: 6, queue size: 3)
```

**Queue size should be >= 1 constantly!**

If queue size often = 0 → Chunks arrive slower than playback!

#### Step 3: Check Network Latency

**In ChildWatch logs, search for:**
```bash
adb logcat | grep -E "NetworkClient|getAudioChunks"
```

Watch time between request and receiving chunks.

**Normal:** < 500ms
**Bad:** > 1000ms

### Quick Tests

#### Test 1: Local Server

1. Run server on PC: `npm run dev`
2. Find PC IP address: `ipconfig` (Windows) or `ifconfig` (Mac/Linux)
3. In ParentWatch and ChildWatch, set Server URL: `http://192.168.X.X:3000`
4. Start listening

**If sound improved** → Problem is Railway (network latency)
**If no change** → Problem is recording/playback

#### Test 2: Increase Buffering

**Temporarily change constants in code:**

**ChildWatch (AudioStreamingActivity.kt):**
```kotlin
private const val MIN_BUFFER_CHUNKS = 10  // was 7
```

**ParentWatch (AudioStreamRecorder.kt):**
```kotlin
private const val CHUNK_DURATION_MS = 4000L  // was 3000L
```

Rebuild and test.

**If sound improved** → Need more buffering!

### Troubleshooting Guide

#### Problem: Fragments = 0, No Sound

**Possible Causes:**

1. **Device ID Mismatch**
   - Check: ID in ParentWatch and ID in ChildWatch Settings must be IDENTICAL
   - Solution: Copy-paste Device ID from ParentWatch to ChildWatch Settings

2. **Different Server URLs**
   - Check: URL in ParentWatch and URL in ChildWatch Settings must be IDENTICAL
   - Railway: `https://childwatch-production.up.railway.app`

3. **RECORD_AUDIO Permission Not Granted**
   - Check: Settings → Apps → ParentWatch → Permissions → Microphone = Enabled
   - Solution: Manually enable microphone permission

4. **Monitoring Not Started on ParentWatch**
   - Check: Status should be "Работает" (green)
   - Solution: Press "Запустить мониторинг" button

#### Problem: Only Hissing Sound (2 seconds)

**Diagnosis:**
- ChildWatch receives empty audio chunks (silence)
- ParentWatch not sending real audio data

**Solutions:**
1. Check RECORD_AUDIO permission on ParentWatch
2. Verify AudioRecord initialization in logs
3. Test microphone with another app
4. Check for audio source conflicts

#### Problem: AudioRecord Not Initialized

**Error in logs:**
```
AudioStreamRecorder: AudioRecord not initialized
AudioStreamRecorder: No audio data recorded
```

**Solutions:**
1. Grant RECORD_AUDIO permission
2. Close other apps using microphone
3. Reboot device
4. Check audio source availability:
   ```kotlin
   // Try different audio sources
   MediaRecorder.AudioSource.MIC
   MediaRecorder.AudioSource.VOICE_COMMUNICATION
   MediaRecorder.AudioSource.CAMCORDER
   ```

#### Problem: AudioTrack Underrun

**Error in logs:**
```
AudioTrack: underrun occurred
```

**Solutions:**
1. Increase AudioTrack buffer size:
   ```kotlin
   .setBufferSizeInBytes(minBufferSize * 16)  // was * 8
   ```

2. Use performance mode:
   ```kotlin
   .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
   ```

3. Increase MIN_BUFFER_CHUNKS to prevent queue depletion

---

## Testing Guide

### Installation & Setup

#### ParentWatch (Child's Phone)

1. Install **ParentWatch v3.2.4** APK
2. Open application
3. First launch should show Toast: **"Новый ID устройства: child-XXXXXXXX"**
4. Check Device ID on main screen - format: `child-XXXXXXXX` (14 characters)
5. **Press "Copy" button** to copy Device ID to clipboard

#### ChildWatch (Parent's Phone)

1. Install **ChildWatch v3.3.1** APK
2. Open application
3. Go to **Settings**
4. **Paste copied Device ID** from ParentWatch into "ID устройства" field
5. Select server:
   - **Railway Cloud**: `https://childwatch-production.up.railway.app`
   - **Local**: `http://10.0.2.2:3000` (emulators only)
6. **Save settings**

### Permission Verification

#### ParentWatch Permissions Required:

1. Open **Android Settings** → **Apps** → **ParentWatch** → **Permissions**
2. Verify granted permissions:
   - **Microphone** (mandatory!)
   - **Location** → "Always allow"
   - **Notifications**
3. If microphone permission NOT granted - enable it manually!

### Running the Test

#### 1. Start Monitoring (ParentWatch)

1. Return to ParentWatch app
2. Verify Server URL matches ChildWatch
3. Press **"Запустить мониторинг"** button (green)
4. Check status displays: **"Статус: Работает"** (green text)

#### 2. Start Listening (ChildWatch)

1. On main screen, find device child-XXXXXXXX
2. Press **"Прослушка"** button (microphone icon)
3. "Аудио прослушка" window opens
4. Press **"Начать прослушку"** button (purple)

#### 3. Expected Results

- Timer starts counting: 00:00:01, 00:00:02, ...
- **Fragments** increment: 1, 2, 3, ... (NOT staying at 0!)
- On ParentWatch status bar: **microphone icon** appears
- Parent hears child device environment through speaker

### Log Analysis (Debugging)

#### Railway Server Logs:

1. Open https://railway.app/project/childwatch-production
2. Navigate to "Deployments" → "Logs"
3. Search for:
   ```
   Device registered: child-XXXXXXXX
   Audio streaming started for child-XXXXXXXX
   POST /api/streaming/chunk - 200
   ```

#### Android Logcat (USB connected):

**ParentWatch logs:**
```bash
adb logcat | grep -E "AudioStreamRecorder|LocationService|NetworkHelper"
```

Look for:
```
AudioStreamRecorder: Starting audio streaming
AudioStreamRecorder: Chunk 0 uploaded successfully (264600 bytes)
AudioStreamRecorder: Chunk 1 uploaded successfully (264600 bytes)
```

**ChildWatch logs:**
```bash
adb logcat | grep -E "AudioStreamingActivity|NetworkClient"
```

Look for:
```
AudioStreamingActivity: Added 3 chunks to queue (queue size: 5)
AudioStreamingActivity: Played chunk: 264600 bytes (queue size: 4)
```

### Test Scenarios

#### Scenario 1: Basic Functionality Test

1. Complete installation and setup
2. Start monitoring on ParentWatch
3. Start listening on ChildWatch
4. Speak near ParentWatch device
5. Verify audio heard on ChildWatch within 5-10 seconds

**Pass criteria:**
- Fragments > 0 within 10 seconds
- Audio recognizable (not just noise)
- Timer continues incrementing

#### Scenario 2: Network Latency Test

1. Start listening with Railway server
2. Note fragment increment rate and audio quality
3. Switch to local server (if available)
4. Compare fragment increment rate and audio quality

**Expected:**
- Local server: Lower latency, smoother playback
- Railway server: Higher latency, possible interruptions

#### Scenario 3: Buffer Recovery Test

1. Start listening normally
2. Temporarily disable WiFi on ParentWatch for 10 seconds
3. Re-enable WiFi
4. Verify playback resumes

**Pass criteria:**
- ChildWatch shows "Буферизация..." during disconnect
- Playback resumes after reconnect
- No crash or stuck state

#### Scenario 4: Long Duration Test

1. Start listening
2. Let run for 30+ minutes
3. Monitor for:
   - Memory leaks
   - Buffer overflow
   - Connection drops
   - Battery drain

**Pass criteria:**
- Continuous operation without crashes
- Memory usage stable
- Battery drain acceptable

### Version Information

- **ParentWatch**: v3.2.4 (versionCode 9)
- **ChildWatch**: v3.3.1 (versionCode 8)
- **Server**: v1.1.0

### Device ID Format

- **New format**: `child-XXXXXXXX` (14 characters, e.g. `child-A1B2C3D4`)
- **Old format**: `child-c6d2c18b3632b5ac` (21 characters) - **DOES NOT WORK!**

If you have old format - reinstall ParentWatch v3.2.4+!

---

## Improvements & Roadmap

### Current Status (v3.3.1 / v3.2.4)

- Device ID synchronization working
- Audio streaming transmits sound from child to parent phone
- Fragment counter works correctly
- Timer counts listening duration
- Screen rotation doesn't interrupt streaming
- Buffer queue implementation complete

### Critical Issues (Require Immediate Attention)

#### 1. HIGH PRIORITY: Audio Interruptions

**Problem:** Sound interrupts every few seconds, then resumes
**Cause:** Buffering issues with audio chunks in ChildWatch
**Solutions:**
- Increase AudioTrack buffer size
- Implement chunk queue for smooth playback (COMPLETED)
- Pre-buffering (accumulate 2-3 chunks before starting) (COMPLETED - now 7 chunks)
- Interpolation when chunks are skipped

**Files:**
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`
- Playback logic redesigned with queue (COMPLETED)

**Status:** Partially resolved - buffering implemented, but interruptions persist on slow networks

---

#### 2. HIGH PRIORITY: Screen Rotation Handling

**Problem:** When phone rotates, Activity recreates and streaming stops
**Cause:** Android recreates Activity on configuration changes
**Solutions:**
- Add `android:configChanges="orientation|screenSize"` in AndroidManifest (COMPLETED)
- OR move audio streaming to Service (more reliable)
- Save streaming state in `onSaveInstanceState()`

**Files:**
- `app/src/main/AndroidManifest.xml` - add configChanges (COMPLETED)
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt` - handle rotation (COMPLETED)

**Status:** RESOLVED in v3.3.1

---

#### 3. MEDIUM PRIORITY: Background Noise and Hissing

**Problem:** Excessive background noise and hissing during listening
**Causes:**
- No noise suppression
- PCM format without processing
- Possibly low quality microphone on child device

**Solutions:**
- Noise Gate (cut off quiet sounds below threshold)
- Low-pass filter (remove high-frequency noise)
- AGC (Automatic Gain Control) for volume normalization
- Use `AcousticEchoCanceler` and `NoiseSuppressor` from Android API

**Implementation example:**
```kotlin
class AudioProcessor {
    fun applyNoiseGate(audioData: ByteArray, threshold: Short = 500): ByteArray {
        val samples = audioData.toShortArray()

        for (i in samples.indices) {
            // If amplitude below threshold - replace with silence
            if (abs(samples[i]) < threshold) {
                samples[i] = 0
            }
        }

        return samples.toByteArray()
    }

    fun applyLowPassFilter(audioData: ByteArray, cutoffFreq: Int): ByteArray {
        // Simple RC low-pass filter implementation
        // TODO: Implement
    }
}
```

**Files:**
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt`
- Create new class `AudioProcessor.kt` for filtering

**Status:** PLANNED for v3.4.0

---

### Functional Improvements (Medium/Low Priority)

#### 4. Listening Modes and Quality Settings

**Features:**
- **Volume:** Adjustable gain (1x, 2x, 3x)
- **Quality:**
  - Low (22050 Hz, less traffic)
  - Medium (44100 Hz, current)
  - High (48000 Hz, best quality)
- **Modes:**
  - Normal mode (no processing)
  - Noise reduction mode
  - Voice mode (optimized for speech)

**Files:**
- `app/src/main/res/layout/activity_audio_streaming.xml` - add controls
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt` - UI controls
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt` - quality modes

**Status:** PLANNED for v3.4.0

---

#### 5. Visual Equalizer / Sound Level Indicator

**Features:**
- Real-time waveform visualization
- Volume meter (level bar)
- Frequency bars (equalizer like music players)
- Show "Тишина" when no sound detected

**Technology:**
- Analyze amplitude of audio chunks
- Canvas/Custom View for rendering
- Update every 100-200ms

**Files:**
- Create `app/src/main/java/ru/example/childwatch/ui/AudioVisualizer.kt`
- Update layout `activity_audio_streaming.xml`

**Status:** PLANNED for v3.5.0

---

#### 6. Recording Function

**Current state:** Only "Record audio" toggle exists (doesn't work)

**Features to implement:**
- Toast notification "Recording started"
- REC indicator on listening screen
- Save audio chunks to file on server
- Endpoint for downloading recording
- List of recordings in ChildWatch
- Playback of recordings
- Delete old recordings

**Files:**
- `server/routes/streaming.js` - endpoint for saving recording
- `server/routes/recordings.js` - NEW: manage recordings
- `app/src/main/java/ru/example/childwatch/RecordingsActivity.kt` - NEW: list recordings

**Status:** PLANNED for v3.6.0

---

#### 7. Auto-Stop Timer

**Feature:** Automatically stop listening after N minutes

**Settings:**
- Default: 30 minutes
- Configurable time: 10, 20, 30, 60 minutes
- Notification 1 minute before stop: "Listening will stop in 1 minute"

**Files:**
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`
- Settings for configuring time

**Status:** PLANNED for v3.4.0

---

### Alternative Approaches (Long-term)

#### Approach 1: WebRTC (Real-time Streaming)

**Pros:**
- Specifically designed for real-time audio
- Automatic adaptation to network conditions
- Built-in noise suppression
- Industry standard for voice/video

**Cons:**
- Complex implementation
- Requires WebRTC server (not Node.js REST API)
- Learning curve

**Implementation complexity:** HIGH
**Expected improvement:** Significant latency reduction, smoother playback

---

#### Approach 2: WebSocket Streaming

**Pros:**
- Persistent connection (no HTTP request overhead)
- Push model (server sends chunks immediately)
- Lower latency than polling
- Bi-directional communication

**Cons:**
- Need to redesign server for WebSocket
- More complex reconnection logic
- Additional library dependencies

**Implementation complexity:** MEDIUM
**Expected improvement:** 30-50% latency reduction

**Architecture:**
```
ParentWatch --WebSocket--> Server --WebSocket--> ChildWatch
   (upload)                (forward)               (receive)
```

**Status:** RECOMMENDED for v3.5.0

---

#### Approach 3: Opus Codec with Compression

**Pros:**
- Smaller chunk size (50-60% savings)
- Faster network transmission
- Better quality at same bitrate
- Designed for voice

**Cons:**
- Need encoder/decoder (Opus library)
- Small encoding/decoding latency (~50-100ms)
- Additional dependency

**Implementation:**
```
ParentWatch                                ChildWatch
AudioRecord → PCM                          Opus → PCM → AudioTrack
           ↓ Encode                        ↑ Decode
         Opus (50-60% smaller)            Opus
           ↓ Upload                        ↑ Download
         Server (store Opus)
```

**Chunk size reduction:**
- Current PCM: 264,600 bytes
- With Opus: ~100,000 bytes (60% reduction)

**Status:** RECOMMENDED for v3.5.0

---

#### Approach 4: Adaptive Bitrate

**Pros:**
- Automatically reduces quality on poor network
- Dynamically changes CHUNK_DURATION_MS
- Better user experience on varying networks

**Cons:**
- Complex adaptation logic
- Variable sound quality
- Requires network quality monitoring

**Implementation:**
```kotlin
fun updateBufferThreshold() {
    val avgLatency = measureAverageLatency()

    when {
        avgLatency < 500 -> {
            MIN_BUFFER_CHUNKS = 3
            SAMPLE_RATE = 48000  // High quality
        }
        avgLatency < 1000 -> {
            MIN_BUFFER_CHUNKS = 5
            SAMPLE_RATE = 44100  // Medium quality
        }
        avgLatency < 2000 -> {
            MIN_BUFFER_CHUNKS = 7
            SAMPLE_RATE = 22050  // Low quality
        }
        else -> {
            MIN_BUFFER_CHUNKS = 10
            SAMPLE_RATE = 16000  // Minimum quality
        }
    }
}
```

**Status:** PLANNED for v3.6.0

---

### Implementation Priority Roadmap

#### Phase 1: Critical Fixes (Immediate - v3.3.x)
1. **Fix audio interruptions** (buffering queue) - COMPLETED
2. **Fix screen rotation** (configChanges) - COMPLETED
3. **Basic noise suppression** (noise gate) - NEXT

#### Phase 2: Quality Improvements (Near-term - v3.4.0)
4. **Quality modes and volume control**
5. **Visual sound indicator**
6. **Auto-stop timer**

#### Phase 3: Additional Features (Mid-term - v3.5.0)
7. **WebSocket streaming** (better architecture)
8. **Opus codec** (compression)
9. **Recording function**

#### Phase 4: Advanced Features (Long-term - v3.6.0)
10. **Advanced audio filters**
11. **Adaptive bitrate**
12. **WebRTC migration** (optional)

---

## API Reference

### Server Endpoints

#### For Parent (ChildWatch)

##### Start Audio Streaming
```
POST /api/streaming/start
Content-Type: application/json

Request Body:
{
    "deviceId": "child-XXXXXXXX",
    "parentId": "parent-xyz"
}

Response:
{
    "success": true,
    "message": "Audio streaming started",
    "sessionId": "session-123"
}
```

##### Stop Audio Streaming
```
POST /api/streaming/stop
Content-Type: application/json

Request Body:
{
    "deviceId": "child-XXXXXXXX"
}

Response:
{
    "success": true,
    "message": "Audio streaming stopped"
}
```

##### Start Recording
```
POST /api/streaming/record/start
Content-Type: application/json

Request Body:
{
    "deviceId": "child-XXXXXXXX"
}

Response:
{
    "success": true,
    "message": "Recording started",
    "recordingId": "rec-456"
}
```

##### Stop Recording
```
POST /api/streaming/record/stop
Content-Type: application/json

Request Body:
{
    "deviceId": "child-XXXXXXXX"
}

Response:
{
    "success": true,
    "message": "Recording stopped",
    "recordingId": "rec-456",
    "fileUrl": "/api/recordings/rec-456"
}
```

##### Get Audio Chunks
```
GET /api/streaming/chunks/:deviceId?count=5

Query Parameters:
- count: Number of chunks to retrieve (default: 5, max: 10)

Response:
{
    "chunks": [
        {
            "sequence": 0,
            "data": "base64_encoded_audio_data",
            "timestamp": 1635789012345
        },
        {
            "sequence": 1,
            "data": "base64_encoded_audio_data",
            "timestamp": 1635789015345
        }
    ],
    "totalChunks": 2
}
```

##### Get Streaming Status
```
GET /api/streaming/status/:deviceId

Response:
{
    "streaming": true,
    "recording": false,
    "duration": 125,  // seconds
    "chunkCount": 42,
    "bufferSize": 3,
    "startTime": 1635789012345
}
```

#### For Child (ParentWatch)

##### Get Commands
```
GET /api/streaming/commands/:deviceId

Response:
{
    "commands": [
        {
            "type": "start_audio_stream",
            "timestamp": 1635789012345
        },
        {
            "type": "start_recording",
            "timestamp": 1635789015345
        }
    ]
}

Command Types:
- "start_audio_stream"
- "stop_audio_stream"
- "start_recording"
- "stop_recording"
```

##### Upload Audio Chunk
```
POST /api/streaming/chunk
Content-Type: multipart/form-data

Form Fields:
- deviceId: child-XXXXXXXX
- sequence: 0, 1, 2, 3...
- recording: "true" or "false"
- audio: Binary audio file (PCM or WebM)

Response:
{
    "success": true,
    "sequence": 0,
    "stored": true
}
```

### Network Client (Android)

#### ChildWatch Network Client Methods

```kotlin
class NetworkClient {
    suspend fun startAudioStream(deviceId: String): ApiResponse
    suspend fun stopAudioStream(deviceId: String): ApiResponse
    suspend fun startRecording(deviceId: String): ApiResponse
    suspend fun stopRecording(deviceId: String): ApiResponse
    suspend fun getAudioChunks(serverUrl: String, deviceId: String, count: Int = 5): List<AudioChunk>
    suspend fun getStreamingStatus(deviceId: String): StreamingStatus
}

data class AudioChunk(
    val sequence: Int,
    val data: String,  // Base64 encoded
    val timestamp: Long
)

data class StreamingStatus(
    val streaming: Boolean,
    val recording: Boolean,
    val duration: Int,
    val chunkCount: Int,
    val bufferSize: Int
)
```

#### ParentWatch Network Helper Methods

```kotlin
class NetworkHelper {
    suspend fun getStreamingCommands(deviceId: String): CommandResponse
    suspend fun uploadAudioChunk(
        serverUrl: String,
        deviceId: String,
        audioData: ByteArray,
        sequence: Int,
        isRecording: Boolean = false
    ): UploadResponse
}

data class CommandResponse(
    val commands: List<Command>
)

data class Command(
    val type: String,
    val timestamp: Long
)

data class UploadResponse(
    val success: Boolean,
    val sequence: Int
)
```

---

## File References

### ParentWatch (Child Device)
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt` - Audio recording and upload
- `parentwatch/src/main/java/ru/example/parentwatch/service/LocationService.kt` - Background service
- `parentwatch/src/main/java/ru/example/parentwatch/network/NetworkHelper.kt` - Server communication

### ChildWatch (Parent Device)
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt` - Audio playback UI and logic
- `app/src/main/java/ru/example/childwatch/network/NetworkClient.kt` - Server communication
- `app/src/main/res/layout/activity_audio_streaming.xml` - UI layout

### Server (Node.js)
- `server/routes/streaming.js` - Streaming endpoints
- `server/managers/CommandManager.js` - Command and buffer management
- `server/config/railway.js` - Railway deployment configuration

---

## Deployment

### Railway.app (Recommended)

**Advantages:**
- Free plan: 500 hours/month
- Automatic SSL (HTTPS)
- Simple deploy from GitHub
- PostgreSQL included free
- Public URL automatic

**Steps:**
1. Create account at [Railway.app](https://railway.app)
2. Connect GitHub repository (New Project → Deploy from GitHub)
3. Configure environment variables:
   ```
   PORT=3000
   NODE_ENV=production
   DATABASE_URL=(automatically created by Railway)
   ```
4. Automatic deployment on every GitHub push!
5. Get URL: `https://childwatch-production.up.railway.app`

### Alternative: Render.com

**Advantages:**
- Completely free plan (with limitations)
- Automatic SSL
- PostgreSQL free

**Disadvantages:**
- "Sleeps" after 15 minutes inactivity
- First request slow (~30 sec cold start)

---

## Security Considerations

**Important for Production:**

1. **Add authentication to streaming endpoints**
   - JWT tokens for parent/child authentication
   - API keys per device

2. **Rate limiting already implemented**
   - 100 requests/minute per IP
   - Prevents abuse

3. **Encrypt audio chunks**
   - SSL/HTTPS already provided by Railway
   - Optional: End-to-end encryption for chunks

4. **Log all listening sessions**
   - Audit trail
   - Parental accountability

5. **Implement access controls**
   - Parents can only access their child devices
   - Children cannot disable monitoring without parent approval

---

## Performance Metrics

### Target Performance:
- **Latency:** < 5 seconds end-to-end
- **Buffer time:** 5-10 seconds for smooth playback
- **Network usage:** ~2-5 MB per minute (PCM uncompressed)
- **Battery impact:** ~5-10% per hour (child device)
- **Memory usage:** < 50 MB (both apps)

### Current Performance (v3.3.1):
- **Latency:** 8-12 seconds (Railway server)
- **Buffer time:** 21 seconds (7 chunks × 3s)
- **Network usage:** ~5 MB per minute
- **Battery impact:** ~8% per hour
- **Memory usage:** ~35 MB (stable)

---

**Document Version:** 1.0
**Last Updated:** 2025-11-01
**Maintainer:** ChildWatch Development Team

**Related Documentation:**
- Device Synchronization Guide
- Location Tracking System
- Chat System Documentation
- Privacy & Security Policy
