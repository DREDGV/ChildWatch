# 🔍 Audio Streaming Diagnostics

## Проблема: Звук прерывается волнами

Звук воспроизводится несколько секунд → тишина → снова звук → повторяется.

## Возможные причины:

### 1. 🔴 ParentWatch не успевает записывать chunks
**Симптомы:**
- Фрагменты на ChildWatch растут медленно или нерегулярно
- В логах ParentWatch: "No audio data recorded" или "AudioRecord not initialized"

**Решение:**
- Увеличить CHUNK_DURATION_MS с 2000 до 3000-4000
- Использовать MediaRecorder вместо AudioRecord
- Проверить приоритет потока записи

### 2. 🟡 Сетевые задержки (Railway)
**Симптомы:**
- Фрагменты приходят с большими задержками
- В логах: долгое время между "Uploading chunk" и "Chunk uploaded"

**Решение:**
- Тестировать на локальном сервере (http://192.168.x.x:3000)
- Сжимать audio chunks (использовать Opus codec)
- Batch upload (отправлять несколько chunks сразу)

### 3. 🟡 ChildWatch воспроизводит быстрее чем получает
**Симптомы:**
- Queue size = 0 постоянно
- В логах: "Queue empty, waiting for chunks..."

**Решение:**
- Увеличить MIN_BUFFER_CHUNKS с 3 до 5-7
- Увеличить bufferSize AudioTrack
- Добавить adaptive buffering (dynamic MIN_BUFFER_CHUNKS)

### 4. 🟢 AudioTrack underrun (буфер заканчивается)
**Симптомы:**
- В логах: "AudioTrack underrun" или playState меняется
- Звук прерывается даже когда queue не пустая

**Решение:**
- Увеличить bufferSize AudioTrack с `bufferSize * 4` до `bufferSize * 8`
- Использовать AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

## Диагностика:

### Шаг 1: Проверить скорость записи chunks (ParentWatch)

Подключите ParentWatch через USB и запустите logcat:
```bash
adb logcat | grep -E "AudioStreamRecorder|Chunk.*uploaded"
```

**Ожидаемый результат:**
```
AudioStreamRecorder: Chunk 0 uploaded successfully (176400 bytes)
AudioStreamRecorder: Chunk 1 uploaded successfully (176400 bytes)
AudioStreamRecorder: Chunk 2 uploaded successfully (176400 bytes)
```

**Интервал между chunks должен быть ~2 секунды!**

Если интервал > 3 секунды → ParentWatch не успевает записывать!

### Шаг 2: Проверить размер очереди (ChildWatch)

Подключите ChildWatch через USB и запустите logcat:
```bash
adb logcat | grep -E "AudioStreamingActivity|queue size"
```

**Ожидаемый результат:**
```
AudioStreamingActivity: Added 1 chunks to queue (total: 5, queue size: 3)
AudioStreamingActivity: Played chunk: 176400 bytes (queue size: 2)
AudioStreamingActivity: Added 1 chunks to queue (total: 6, queue size: 3)
```

**Queue size должен быть >= 1 постоянно!**

Если queue size часто = 0 → Chunks приходят медленнее чем воспроизводятся!

### Шаг 3: Проверить сетевые задержки

В логах ChildWatch ищите:
```bash
adb logcat | grep -E "NetworkClient|getAudioChunks"
```

Смотрите время между запросом и получением chunks.

**Нормально:** < 500ms
**Плохо:** > 1000ms

## Быстрые тесты:

### Тест 1: Локальный сервер
1. Запустите сервер на ПК: `npm run dev`
2. Найдите IP адрес ПК в локальной сети: `ipconfig` (Windows) или `ifconfig` (Mac/Linux)
3. В ParentWatch и ChildWatch установите Server URL: `http://192.168.X.X:3000`
4. Запустите прослушку

**Если звук стал лучше** → проблема в Railway (сетевые задержки)
**Если без изменений** → проблема в записи/воспроизведении

### Тест 2: Увеличить буферизацию
Временно измените константы в коде:

**ChildWatch (AudioStreamingActivity.kt):**
```kotlin
private const val MIN_BUFFER_CHUNKS = 7  // было 3
```

**ParentWatch (AudioStreamRecorder.kt):**
```kotlin
private const val CHUNK_DURATION_MS = 3000L  // было 2000L
```

Пересоберите и протестируйте.

**Если звук стал лучше** → нужно больше буферизации!

## Альтернативные подходы:

### Подход 1: WebRTC (real-time streaming)
**Плюсы:**
- Специально разработан для real-time audio
- Автоматическая адаптация к сетевым условиям
- Встроенное шумоподавление

**Минусы:**
- Сложная реализация
- Требует WebRTC сервер (не Node.js REST API)

### Подход 2: WebSocket streaming
**Плюсы:**
- Постоянное соединение (нет overhead от HTTP requests)
- Push модель (сервер отправляет chunks сразу)
- Меньше задержки

**Минусы:**
- Нужно переделать сервер на WebSocket
- Более сложная логика

### Подход 3: Opus codec с компрессией
**Плюсы:**
- Меньший размер chunks (50-60% экономия)
- Быстрее передача по сети
- Лучше качество при том же bitrate

**Минусы:**
- Нужен encoder/decoder (библиотека Opus)
- Небольшая задержка на encoding/decoding

### Подход 4: Adaptive Bitrate
**Плюсы:**
- Автоматически снижает качество при плохой сети
- Динамически меняет CHUNK_DURATION_MS

**Минусы:**
- Сложная логика адаптации
- Переменное качество звука

## Рекомендуемое решение:

**Этап 1 (быстро):**
1. Увеличить MIN_BUFFER_CHUNKS до 7
2. Увеличить AudioTrack buffer до `bufferSize * 8`
3. Увеличить CHUNK_DURATION_MS до 3000ms

**Этап 2 (если не помогло):**
1. Переключиться на WebSocket streaming
2. Добавить adaptive buffering

**Этап 3 (для идеального качества):**
1. Использовать Opus codec
2. WebRTC для ultra-low latency

## Следующие шаги:

1. **СНАЧАЛА**: Запустите диагностику (logcat) чтобы понять где bottleneck
2. **ПОТОМ**: Примените соответствующее решение
3. **НАКОНЕЦ**: Если простые решения не помогают → WebSocket/WebRTC
