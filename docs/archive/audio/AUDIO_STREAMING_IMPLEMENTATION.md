# 🎙️ Audio Streaming Implementation - Technical Description

## Описание проекта

**ChildWatch** - Android приложение родительского контроля для мониторинга детского устройства.

**Компоненты:**
- **ParentWatch** (детский телефон) - записывает и отправляет аудио
- **ChildWatch** (родительский телефон) - принимает и воспроизводит аудио
- **Node.js сервер** (Railway cloud) - промежуточный буфер для audio chunks

---

## 🏗️ Архитектура Audio Streaming

### Общий принцип работы:

```
ParentWatch (Child Phone)          Server (Node.js)           ChildWatch (Parent Phone)
     [Microphone]                    [REST API]                   [Speaker]
          |                               |                             |
    1. Record audio              2. Store chunks              3. Fetch chunks
    2-3 sec chunks                  in buffer                   every 2 seconds
          |                               |                             |
    POST /api/streaming/chunk -----> [Buffer Queue] <----- GET /api/streaming/chunks
       (PCM 16-bit)                   (in memory)                  (PCM 16-bit)
          |                               |                             |
    Upload every 2-3s              Max 30 chunks             Play via AudioTrack
                                    per device
```

---

## 📱 Текущая реализация (v3.3.1 / v3.2.4)

### ParentWatch (Child Device) - Recording Side

**Класс:** `AudioStreamRecorder.kt`

**Параметры:**
```kotlin
CHUNK_DURATION_MS = 3000L        // 3 seconds per chunk
SAMPLE_RATE = 44100              // 44.1 kHz
CHANNEL_CONFIG = MONO            // 1 channel
AUDIO_FORMAT = PCM_16BIT         // 16-bit PCM
```

**Как работает:**
```kotlin
fun startStreaming(deviceId: String, serverUrl: String) {
    // Бесконечный цикл записи chunks
    while (isRecording) {
        // 1. Record audio chunk using AudioRecord API
        val audioData = recordChunk()  // ~264,600 bytes (3 seconds)

        // 2. Upload chunk to server via HTTP POST
        networkHelper.uploadAudioChunk(
            serverUrl = serverUrl,
            deviceId = deviceId,
            audioData = audioData,
            sequence = sequence++
        )

        // 3. Wait 3 seconds before next chunk
        delay(3000)
    }
}

private fun recordChunk(): ByteArray {
    // Initialize AudioRecord
    audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE = 44100,
        CHANNEL_IN_MONO,
        ENCODING_PCM_16BIT,
        bufferSize * 4
    )

    // Record for 3 seconds
    audioRecord.startRecording()
    val buffer = ByteArray(264600)  // 3s * 44100Hz * 2 bytes
    audioRecord.read(buffer, 0, buffer.size)
    audioRecord.stop()

    return buffer
}
```

**Upload endpoint:**
```
POST /api/streaming/chunk
Content-Type: multipart/form-data

Fields:
- deviceId: child-XXXXXXXX
- sequence: 0, 1, 2, 3...
- audio: binary PCM data (264,600 bytes)
```

---

### Server (Node.js) - Buffer Layer

**Файл:** `server/managers/CommandManager.js`

**Как работает:**
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

        // Add chunk with metadata
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
        if (!this.audioBuffers.has(deviceId)) {
            return [];
        }

        const buffer = this.audioBuffers.get(deviceId);

        // Remove and return chunks (ВАЖНО: splice, не slice!)
        const chunks = buffer.splice(0, Math.min(count, buffer.length));
        return chunks;
    }
}
```

**Endpoints:**
```
POST /api/streaming/chunk
- Receives chunk from ParentWatch
- Stores in memory buffer
- Returns 200 OK

GET /api/streaming/chunks/:deviceId
- Returns up to 5 chunks
- Removes returned chunks from buffer
- Returns JSON: { chunks: [...] }
```

---

### ChildWatch (Parent Device) - Playback Side

**Класс:** `AudioStreamingActivity.kt`

**Параметры:**
```kotlin
UPDATE_INTERVAL_MS = 2000L       // Poll server every 2 seconds
MIN_BUFFER_CHUNKS = 7            // Wait for 7 chunks before playback
AudioTrack bufferSize = minBufferSize * 8
```

**Как работает:**

#### Два потока:

**Thread 1: Fetching chunks from server**
```kotlin
updateJob = lifecycleScope.launch {
    while (isStreaming) {
        // 1. Request chunks from server
        val chunks = networkClient.getAudioChunks(serverUrl, deviceId)

        // 2. Add to local queue
        chunks.forEach { chunk ->
            chunkQueue.offer(chunk.data)  // ConcurrentLinkedQueue
        }

        // 3. Start playback when buffered enough
        if (isBuffering && chunkQueue.size >= MIN_BUFFER_CHUNKS) {
            isBuffering = false
            statusText = "Воспроизведение..."
        }

        // 4. Wait 2 seconds
        delay(2000)
    }
}
```

**Thread 2: Continuous playback**
```kotlin
playbackJob = lifecycleScope.launch(Dispatchers.IO) {
    while (isStreaming) {
        if (!isBuffering && chunkQueue.isNotEmpty()) {
            // 1. Get chunk from queue
            val chunk = chunkQueue.poll()

            // 2. Play via AudioTrack (BLOCKING mode)
            audioTrack.write(chunk, 0, chunk.size, WRITE_BLOCKING)
        } else {
            // Queue empty - wait for more chunks
            statusText = "Буферизация..."
            delay(100)
        }
    }
}
```

**AudioTrack setup:**
```kotlin
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
    .setBufferSizeInBytes(minBufferSize * 8)  // Увеличенный буфер
    .setTransferMode(MODE_STREAM)
    .build()

audioTrack.play()
```

---

## ⚠️ Проблема: Прерывания звука

### Симптомы:
Звук воспроизводится **волнами**:
- 2-5 секунд звук есть
- 2-3 секунды тишина
- Снова 2-5 секунд звук
- Повторяется постоянно

### Когда происходит:
- На **реальных устройствах** (Android телефоны)
- Через **Railway сервер** (cloud hosting)
- При **стабильном WiFi** соединении

### Что уже проверили:
✅ Device ID совпадают (child-9AED3250 на обоих)
✅ Server URL одинаковый (https://childwatch-production.up.railway.app)
✅ Разрешение RECORD_AUDIO выдано
✅ Фрагменты приходят (счетчик растет: 1, 2, 3...)
✅ Поворот экрана не прерывает streaming

### Текущие параметры:

**ParentWatch:**
- Записывает chunks по 3 секунды (264,600 bytes каждый)
- Загружает на сервер каждые 3 секунды
- Использует AudioRecord API

**ChildWatch:**
- Запрашивает chunks каждые 2 секунды
- Буферизует 7 chunks перед началом (~21 секунда аудио)
- Воспроизводит через AudioTrack непрерывно

**Server:**
- Хранит до 30 chunks в памяти на устройство
- Отдает до 5 chunks за один запрос
- Удаляет отданные chunks из буфера

---

## 🤔 Возможные причины прерываний

### Гипотеза 1: Сетевые задержки (Railway)
**Признаки:**
- HTTP request/response занимает > 1 секунду
- Railway находится далеко географически
- WiFi стабилен, но latency высокая

**Как проверить:**
- Запустить сервер локально на ПК
- Протестировать с локальным IP (http://192.168.X.X:3000)

### Гипотеза 2: AudioRecord пропускает запись
**Признаки:**
- ParentWatch не успевает записывать chunks с нужной частотой
- Время между uploads > 3 секунды
- Система Android throttling фоновых процессов

**Как проверить:**
- Logcat: `adb logcat | grep AudioStreamRecorder`
- Смотреть timestamps между "Chunk uploaded"

### Гипотеза 3: Queue становится пустой
**Признаки:**
- ChildWatch воспроизводит быстрее чем получает
- chunkQueue.size часто = 0
- Статус постоянно "Буферизация..."

**Как проверить:**
- Logcat: `adb logcat | grep "queue size"`
- Если часто = 0 → chunks приходят слишком медленно

### Гипотеза 4: AudioTrack underrun
**Признаки:**
- Внутренний буфер AudioTrack заканчивается
- write() возвращает раньше времени
- playState меняется на STOPPED/PAUSED

**Как проверить:**
- Logcat: искать "underrun" или playState изменения
- Мониторить audioTrack.getPlaybackHeadPosition()

---

## 📊 Timing Analysis

**Идеальный поток:**
```
Time   ParentWatch                Server                  ChildWatch
----   -----------                ------                  ----------
0s     Start recording            Empty buffer            Start polling
3s     Upload chunk #0       --> Store chunk #0
4s                                                        Poll (empty)
6s     Upload chunk #1       --> Store chunk #1 + #0     Poll → get 2 chunks
                                                          Queue: [0, 1]
9s     Upload chunk #2       --> Store chunk #2
10s                                                       Poll → get 1 chunk
                                                          Queue: [0, 1, 2]
12s    Upload chunk #3       --> Store chunk #3          Poll → get 1 chunk
                                                          Queue: [0, 1, 2, 3]
...continues
```

**Проблемный сценарий:**
```
Time   ParentWatch                Server                  ChildWatch
----   -----------                ------                  ----------
0s     Start recording            Empty buffer            Start polling
3s     Upload starts...
5s     Upload complete       --> Store chunk #0          Poll (empty)
                                                          Queue: []
7s                                                        Poll → get 1 chunk
                                                          Queue: [0]
                                                          ⚠️ Playing chunk #0
8s                                                        Queue empty!
                                                          ⏸️ SILENCE
10s    Upload chunk #1       --> Store chunk #1          Poll → get 1 chunk
                                                          Queue: [1]
                                                          ▶️ Resume playback
13s                                                       Queue empty again!
                                                          ⏸️ SILENCE
```

**Узкие места:**
1. **Upload latency:** 3s запись + 2s upload = 5s между chunks
2. **Polling interval:** 2s между запросами
3. **Network jitter:** Railway latency варьируется 500-2000ms
4. **Playback speed:** 3s chunk воспроизводится за 3s, но следующий приходит через 5s

---

## 🛠️ Что уже попробовали

### v3.2.0-3.3.0: Buffering Queue
**Изменения:**
- Добавили ConcurrentLinkedQueue для chunks
- Два отдельных потока (fetch vs play)
- Pre-buffering: 7 chunks перед началом

**Результат:**
❌ Прерывания остались, но начальная буферизация работает

### v3.3.1 / v3.2.4: Increased Buffering
**Изменения:**
- MIN_BUFFER_CHUNKS: 3 → 7
- AudioTrack buffer: ×4 → ×8
- CHUNK_DURATION_MS: 2000ms → 3000ms

**Результат:**
⏳ Тестируется...

---

## 💡 Возможные решения

### Решение 1: WebSocket вместо HTTP polling
**Идея:** Push-модель вместо pull-модели
```
ParentWatch --WebSocket--> Server --WebSocket--> ChildWatch
   (upload)                (forward)               (receive)
```

**Плюсы:**
- Постоянное соединение (нет overhead HTTP)
- Server может push chunks сразу после получения
- Меньше latency

**Минусы:**
- Нужно переделать сервер (socket.io или ws)
- Более сложная логика reconnect

### Решение 2: Opus codec (сжатие аудио)
**Идея:** Сжимать PCM → Opus перед отправкой
```
ParentWatch                                        ChildWatch
AudioRecord → PCM                                  Opus → PCM → AudioTrack
           ↓ Encode                                ↑ Decode
         Opus (50-60% меньше)                    Opus
           ↓ Upload                                ↑ Download
         Server (store Opus)
```

**Плюсы:**
- Chunks в 2-3 раза меньше (быстрее upload/download)
- Лучше качество при том же bitrate
- Меньше network usage

**Минусы:**
- Нужна библиотека Opus для Android
- Encoding/decoding добавляет ~50-100ms latency

### Решение 3: Adaptive Buffering
**Идея:** Динамически менять MIN_BUFFER_CHUNKS
```kotlin
fun updateBufferThreshold() {
    val avgLatency = measureAverageLatency()

    MIN_BUFFER_CHUNKS = when {
        avgLatency < 500ms -> 3   // Fast network
        avgLatency < 1000ms -> 5  // Medium network
        avgLatency < 2000ms -> 7  // Slow network
        else -> 10                // Very slow network
    }
}
```

**Плюсы:**
- Автоматическая адаптация к сети
- Минимальная latency при хорошей сети

**Минусы:**
- Сложная логика измерения latency
- Может "прыгать" между режимами

### Решение 4: Batch Upload (отправка нескольких chunks)
**Идея:** Накапливать 2-3 chunks перед отправкой
```kotlin
// Instead of:
upload(chunk0)  // 3s
upload(chunk1)  // 3s
upload(chunk2)  // 3s

// Do:
upload([chunk0, chunk1, chunk2])  // 1 request
```

**Плюсы:**
- Меньше HTTP requests
- Меньше network overhead

**Минусы:**
- Больше latency (нужно ждать накопления)
- Больше размер одного request

---

## 🎯 Рекомендации для улучшения

### Краткосрочные (Quick Wins):
1. ✅ **Увеличить буферизацию** (уже сделано в v3.3.1)
2. ⏳ **Тестировать с локальным сервером** (проверить если Railway - bottleneck)
3. 🔄 **Уменьшить UPDATE_INTERVAL_MS** с 2000ms до 1500ms (чаще poll)

### Среднесрочные (Medium Effort):
4. 🌐 **WebSocket streaming** (лучшая архитектура для real-time)
5. 🔊 **Opus codec** (сжатие аудио, меньше трафик)
6. 📊 **Adaptive buffering** (подстройка под сеть)

### Долгосрочные (Big Refactor):
7. 🎥 **WebRTC** (industry standard для real-time audio/video)
8. 🔄 **Redundancy** (отправлять каждый chunk 2 раза для reliability)

---

## 📝 Вопросы для другого ИИ

1. **Почему звук прерывается волнами при такой архитектуре?**
   - Chunks по 3 секунды
   - Буферизация 7 chunks (21 секунда)
   - Но всё равно прерывается каждые 2-5 секунд

2. **Правильно ли использовать HTTP polling для real-time audio streaming?**
   - Или WebSocket обязателен?

3. **AudioTrack.write() с WRITE_BLOCKING достаточно для плавного воспроизведения?**
   - Или нужен другой подход?

4. **Как измерить где именно bottleneck?**
   - ParentWatch recording?
   - Network upload/download?
   - ChildWatch playback?

5. **Какие Android API лучше для low-latency audio streaming?**
   - AudioRecord + AudioTrack (текущий)
   - MediaRecorder + MediaPlayer?
   - OpenSL ES?
   - AAudio (Android 8.1+)?

6. **Opus codec решит проблему или это overkill?**
   - Или проблема не в размере chunks?

---

## 📂 Файлы кода

**ParentWatch:**
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt`
- `parentwatch/src/main/java/ru/example/parentwatch/service/LocationService.kt`
- `parentwatch/src/main/java/ru/example/parentwatch/network/NetworkHelper.kt`

**ChildWatch:**
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`
- `app/src/main/java/ru/example/childwatch/network/NetworkClient.kt`

**Server:**
- `server/routes/streaming.js`
- `server/managers/CommandManager.js`

**Версии:**
- ChildWatch: v3.3.1 (versionCode 8)
- ParentWatch: v3.2.4 (versionCode 9)
- Server: v1.1.0
