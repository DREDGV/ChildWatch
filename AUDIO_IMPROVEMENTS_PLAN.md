# 🎙️ Audio Streaming Improvement Plan

## ✅ Текущий статус (v3.2.3)
- ✅ Device ID синхронизация работает
- ✅ Audio streaming передает звук с детского на родительский телефон
- ✅ Счетчик фрагментов работает корректно
- ✅ Таймер отсчитывает время прослушки

## ❌ Критичные проблемы (требуют немедленного исправления)

### 1. 🔴 ВЫСОКИЙ ПРИОРИТЕТ: Прерывания звука
**Проблема:** Звук прерывается каждые несколько секунд, потом возобновляется
**Причина:** Проблема с буферизацией audio chunks в ChildWatch
**Решение:**
- Увеличить буфер AudioTrack
- Реализовать очередь chunks для плавного воспроизведения
- Pre-buffering (накопить 2-3 chunks перед началом воспроизведения)
- Interpolation при пропуске chunks

**Файлы:**
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`
- Нужно переделать логику воспроизведения с очередью

---

### 2. 🔴 ВЫСОКИЙ ПРИОРИТЕТ: Прослушка останавливается при повороте экрана
**Проблема:** При повороте телефона Activity пересоздается и streaming прекращается
**Причина:** Android пересоздает Activity при изменении конфигурации
**Решение:**
- Добавить `android:configChanges="orientation|screenSize"` в AndroidManifest
- Или вынести audio streaming в Service (более надежно)
- Сохранить состояние streaming в `onSaveInstanceState()`

**Файлы:**
- `app/src/main/AndroidManifest.xml` - добавить configChanges
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt` - обработать rotation

---

### 3. 🟡 СРЕДНИЙ ПРИОРИТЕТ: Шум и шипение на фоне
**Проблема:** Много фонового шума и шипения во время прослушки
**Причины:**
- Нет шумоподавления
- PCM формат без обработки
- Возможно низкое качество микрофона на детском телефоне

**Решение:**
- Noise Gate (отсекать тихие звуки ниже threshold)
- Low-pass filter (убрать высокочастотный шум)
- AGC (Automatic Gain Control) для нормализации громкости
- Возможно использовать `AcousticEchoCanceler` и `NoiseSuppressor` из Android API

**Файлы:**
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt`
- Создать новый класс `AudioProcessor.kt` для фильтрации

---

## 🟢 Функциональные улучшения (средний/низкий приоритет)

### 4. Режимы прослушки и настройки качества
**Функции:**
- **Громкость:** Регулировка усиления звука (1x, 2x, 3x)
- **Качество:**
  - Низкое (22050 Hz, меньше трафика)
  - Среднее (44100 Hz, текущее)
  - Высокое (48000 Hz, лучше качество)
- **Режимы:**
  - Обычный режим (без обработки)
  - Шумоподавление (noise reduction)
  - Голосовой режим (optimize для речи)

**Файлы:**
- `app/src/main/res/layout/activity_audio_streaming.xml` - добавить controls
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt` - UI controls
- `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt` - quality modes

---

### 5. Визуальный эквалайзер / индикатор уровня звука
**Функции:**
- Real-time waveform (волна звука)
- Volume meter (полоска уровня громкости)
- Frequency bars (эквалайзер как в музыкальных плеерах)
- Показывать "Тишина" когда нет звука

**Технология:**
- Анализировать amplitude audio chunks
- Canvas/Custom View для отрисовки
- Обновлять каждые 100-200ms

**Файлы:**
- Создать `app/src/main/java/ru/example/childwatch/ui/AudioVisualizer.kt`
- Обновить layout `activity_audio_streaming.xml`

---

### 6. Функция записи прослушки
**Текущее состояние:** Только переключатель "Записывать аудио" (не работает)

**Что нужно реализовать:**
- ✅ Toast уведомление "🔴 Запись начата"
- ✅ Индикатор REC на экране прослушки
- ✅ Сохранение audio chunks в файл на сервере
- ✅ Endpoint для скачивания записи
- ✅ Список записей в ChildWatch
- ✅ Воспроизведение записи
- ✅ Удаление старых записей

**Файлы:**
- `server/routes/streaming.js` - endpoint для сохранения recording
- `server/routes/recordings.js` - NEW: управление записями
- `app/src/main/java/ru/example/childwatch/RecordingsActivity.kt` - NEW: список записей

---

### 7. Таймер авто-остановки
**Функция:** Автоматически останавливать прослушку через N минут

**Настройки:**
- По умолчанию: 30 минут
- Настраиваемое время: 10, 20, 30, 60 минут
- Уведомление за 1 минуту до остановки: "⚠️ Прослушка остановится через 1 минуту"

**Файлы:**
- `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`
- Settings для настройки времени

---

## 📊 Приоритеты реализации

### Этап 1: Критичные исправления (немедленно)
1. **Фикс прерываний звука** (buffering queue) - СЕЙЧАС
2. **Фикс поворота экрана** (configChanges) - СЕЙЧАС
3. **Базовое шумоподавление** (noise gate) - СЛЕДУЮЩИЙ

### Этап 2: Улучшение качества (ближайшие версии)
4. **Режимы качества и громкость** (v3.3.0)
5. **Визуальный индикатор звука** (v3.3.0)
6. **Таймер авто-остановки** (v3.3.0)

### Этап 3: Дополнительные функции (будущие версии)
7. **Функция записи** (v3.4.0)
8. **Продвинутые аудио фильтры** (v3.5.0)

---

## 🔧 Технические детали решений

### Решение прерываний звука (buffering queue)

**Проблема:** ChildWatch запрашивает chunks каждые 2 секунды, но AudioTrack воспроизводит быстрее

**Текущий код (упрощенно):**
```kotlin
// Запрос chunks каждые 2 секунды
while (streaming) {
    val chunks = getChunks()
    for (chunk in chunks) {
        audioTrack.write(chunk) // Играет сразу
    }
    delay(2000) // Ждет 2 секунды
    // Во время ожидания AudioTrack закончил воспроизведение -> тишина
}
```

**Решение - очередь с pre-buffering:**
```kotlin
val chunkQueue = ConcurrentLinkedQueue<ByteArray>()
var isBuffering = true

// Поток 1: Загрузка chunks
launch {
    while (streaming) {
        val chunks = getChunks()
        chunkQueue.addAll(chunks)

        // Начать воспроизведение после накопления 3 chunks
        if (isBuffering && chunkQueue.size >= 3) {
            isBuffering = false
        }

        delay(2000)
    }
}

// Поток 2: Воспроизведение chunks
launch {
    while (streaming) {
        if (!isBuffering && chunkQueue.isNotEmpty()) {
            val chunk = chunkQueue.poll()
            audioTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        } else {
            delay(100) // Проверять очередь каждые 100ms
        }
    }
}
```

---

### Решение поворота экрана

**Вариант 1: Блокировка пересоздания Activity**
```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".AudioStreamingActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:screenOrientation="portrait">
```

**Вариант 2: Вынести streaming в Service (более надежно)**
```kotlin
class AudioStreamingService : Service() {
    private val audioPlayer = AudioPlayer()

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val deviceId = intent.getStringExtra("deviceId")
        audioPlayer.startStreaming(deviceId)
        return START_STICKY // Restart if killed
    }
}
```

---

### Шумоподавление - Noise Gate

**Простая реализация:**
```kotlin
fun applyNoiseGate(audioData: ByteArray, threshold: Short = 500): ByteArray {
    val samples = audioData.toShortArray()

    for (i in samples.indices) {
        // Если амплитуда ниже threshold - заменить на тишину
        if (abs(samples[i]) < threshold) {
            samples[i] = 0
        }
    }

    return samples.toByteArray()
}

// Использование в AudioStreamRecorder
private fun recordChunk(): ByteArray? {
    val rawAudio = audioRecord.read(buffer, ...)
    val filtered = applyNoiseGate(rawAudio, threshold = 500)
    return filtered
}
```

---

## 📝 Следующие шаги

1. **СЕЙЧАС**: Фикс прерываний звука (buffering queue)
2. **СЕЙЧАС**: Фикс поворота экрана (configChanges)
3. **СЛЕДУЮЩИЙ**: Базовое шумоподавление
4. **ПОТОМ**: Остальные функции по плану

---

## 📦 Версии

- **v3.2.3** (текущая) - Audio streaming работает, но с прерываниями
- **v3.3.0** (planned) - Исправления качества звука + базовые улучшения
- **v3.4.0** (planned) - Функция записи
- **v3.5.0** (planned) - Продвинутые аудио фильтры

---

Этот план будет обновляться по мере реализации функций.
