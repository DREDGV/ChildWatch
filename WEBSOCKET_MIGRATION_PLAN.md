# WebSocket Migration Plan - Непрерывная Прослушка

## 🎯 Цель
Заменить HTTP polling на WebSocket для **непрерывной** передачи аудио без прерываний.

## 📋 Текущая Проблема
- HTTP polling создает задержки 1.5-2 секунды между запросами
- Запись и опрос не синхронизированы → периодические "дыры" в данных
- Невозможно получить real-time поток с HTTP
- Результат: звук прерывается волнами, даже с буферизацией

## ✅ Решение: WebSocket Architecture

```
ParentWatch (child phone)
    ↓ запись PCM каждые 2 сек
    ↓ WebSocket.send(chunk) ← мгновенная отправка
    ↓
Server (Node.js + Socket.IO)
    ↓ получает chunk
    ↓ WebSocket.emit(chunk) → мгновенная отправка
    ↓
ChildWatch (parent phone)
    ↓ WebSocket.on('chunk') ← мгновенное получение
    ↓ добавить в очередь
    ↓ AudioTrack.write() ← непрерывное воспроизведение
```

---

## 📝 План Действий (Пошаговый)

### ✅ Фаза 1: Подготовка Сервера (30 минут)

#### Шаг 1.1: Установить Socket.IO на сервер
```bash
cd server
npm install socket.io@4
```

#### Шаг 1.2: Создать WebSocket Manager
**Файл:** `server/managers/WebSocketManager.js`

**Функции:**
- Подключение/отключение клиентов
- Роутинг чанков: `child-XXXX` → `parent`
- Управление комнатами (rooms) для изоляции сессий
- Heartbeat для проверки соединения

#### Шаг 1.3: Интегрировать Socket.IO в сервер
**Файл:** `server/index.js`

**Изменения:**
- Добавить Socket.IO к Express
- Настроить CORS для WebSocket
- Подключить WebSocketManager

#### Шаг 1.4: Обновить API эндпоинты
**Файл:** `server/routes/streaming.js`

**Изменения:**
- `/api/streaming/start` → создает WebSocket комнату
- `/api/streaming/stop` → закрывает WebSocket соединение
- Убрать `/api/streaming/chunks` (больше не нужен HTTP polling)

---

### ✅ Фаза 2: ParentWatch (Child Device) - Отправка через WebSocket (45 минут)

#### Шаг 2.1: Добавить Socket.IO клиент в Android
**Файл:** `parentwatch/build.gradle`

```gradle
dependencies {
    implementation 'io.socket:socket.io-client:2.1.0'
}
```

#### Шаг 2.2: Создать WebSocketClient для ParentWatch
**Новый файл:** `parentwatch/src/main/java/ru/example/parentwatch/network/WebSocketClient.kt`

**Функции:**
- Подключение к серверу через Socket.IO
- Отправка аудио чанков: `socket.emit("audio_chunk", data)`
- Обработка ошибок и переподключение
- Heartbeat для поддержания соединения

#### Шаг 2.3: Изменить AudioStreamRecorder
**Файл:** `parentwatch/src/main/java/ru/example/parentwatch/audio/AudioStreamRecorder.kt`

**Изменения:**
- Убрать HTTP POST для загрузки чанков
- Использовать `WebSocketClient.sendAudioChunk()`
- Мгновенная отправка сразу после записи (без delay)

#### Шаг 2.4: Обновить LocationService
**Файл:** `parentwatch/src/main/java/ru/example/parentwatch/service/LocationService.kt`

**Изменения:**
- Инициализация WebSocketClient при старте стриминга
- Закрытие WebSocket при остановке

---

### ✅ Фаза 3: ChildWatch (Parent Device) - Получение через WebSocket (45 минут)

#### Шаг 3.1: Добавить Socket.IO клиент в Android
**Файл:** `app/build.gradle`

```gradle
dependencies {
    implementation 'io.socket:socket.io-client:2.1.0'
}
```

#### Шаг 3.2: Создать WebSocketClient для ChildWatch
**Новый файл:** `app/src/main/java/ru/example/childwatch/network/WebSocketClient.kt`

**Функции:**
- Подключение к серверу
- Прием аудио чанков: `socket.on("audio_chunk") { data -> ... }`
- Автоматическое переподключение при обрыве
- Heartbeat

#### Шаг 3.3: Изменить AudioStreamingActivity
**Файл:** `app/src/main/java/ru/example/childwatch/AudioStreamingActivity.kt`

**Изменения:**
- Убрать HTTP polling loop (updateJob)
- Подключиться к WebSocket при старте
- Получать чанки через WebSocket callback → добавлять в очередь
- Playback job остается без изменений (воспроизводит из очереди)

#### Шаг 3.4: Улучшить буферизацию
**Параметры:**
- `MIN_BUFFER_CHUNKS = 3` (меньше задержка, т.к. WebSocket быстрее)
- `MAX_BUFFER_CHUNKS = 10` (ограничение для предотвращения накопления)
- Автоматическая очистка старых чанков

---

### ✅ Фаза 4: Тестирование (30 минут)

#### Шаг 4.1: Локальное тестирование
1. Запустить сервер локально: `cd server && npm run dev`
2. Настроить оба приложения на `http://10.0.2.2:3000`
3. Запустить на эмуляторах
4. Проверить:
   - ✅ Подключение WebSocket (логи сервера)
   - ✅ Передача чанков в реальном времени
   - ✅ Непрерывное воспроизведение без пауз

#### Шаг 4.2: Тестирование на реальных устройствах
1. Задеплоить на Railway (автоматически при push)
2. Настроить оба приложения на Railway URL
3. Тест в реальных условиях WiFi
4. Проверить поведение при слабом сигнале

#### Шаг 4.3: Проверка переподключения
1. Отключить WiFi на child device → подождать 5 сек → включить
2. Проверить автоматическое восстановление стриминга
3. Логи должны показать: `WebSocket reconnected`

---

### ✅ Фаза 5: Оптимизация (опционально, 1-2 часа)

#### Шаг 5.1: Уменьшить размер чанков (Opus codec)
**Зачем:** 264 KB → ~9 KB (30x меньше) → быстрее передача

**Библиотека:** `com.github.theeasiestway:opus:1.0.2`

**Изменения:**
- ParentWatch: PCM → Opus перед отправкой
- ChildWatch: Opus → PCM перед воспроизведением

**Ожидаемый результат:** Еще более стабильный стриминг, меньше нагрузка на сеть

#### Шаг 5.2: Добавить сжатие WebSocket
**Server config:**
```javascript
io.use(compression())
```

#### Шаг 5.3: Мониторинг качества соединения
- Отображать ping/latency в UI
- Показывать количество потерянных пакетов
- Индикатор качества соединения (зеленый/желтый/красный)

---

## 🔧 Технические Детали

### WebSocket Events (Server ↔ Clients)

#### ParentWatch → Server:
- `connect` - подключение child device
- `audio_chunk` - отправка PCM данных
- `heartbeat` - проверка соединения

#### Server → ChildWatch:
- `audio_chunk` - доставка PCM данных
- `streaming_started` - уведомление о начале
- `streaming_stopped` - уведомление об остановке

#### Bi-directional:
- `ping/pong` - поддержание соединения
- `error` - обработка ошибок

### Socket.IO Configuration

```javascript
// Server
const io = require('socket.io')(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"]
    },
    pingTimeout: 60000,
    pingInterval: 25000,
    transports: ['websocket', 'polling'] // fallback to polling if needed
});

// Android Client
val opts = IO.Options().apply {
    transports = arrayOf("websocket")
    reconnection = true
    reconnectionAttempts = Int.MAX_VALUE
    reconnectionDelay = 1000
    timeout = 20000
}
```

---

## 📦 Структура Файлов (После Миграции)

### Server (Node.js)
```
server/
├── managers/
│   ├── CommandManager.js (existing - keep for commands)
│   └── WebSocketManager.js (NEW - WebSocket logic)
├── routes/
│   └── streaming.js (modified - WebSocket setup endpoints)
├── index.js (modified - integrate Socket.IO)
└── package.json (add socket.io dependency)
```

### ParentWatch (Child Device)
```
parentwatch/src/main/java/ru/example/parentwatch/
├── network/
│   ├── NetworkHelper.kt (existing - keep for HTTP)
│   └── WebSocketClient.kt (NEW - WebSocket audio upload)
├── audio/
│   └── AudioStreamRecorder.kt (modified - use WebSocket)
└── service/
    └── LocationService.kt (modified - manage WebSocket lifecycle)
```

### ChildWatch (Parent Device)
```
app/src/main/java/ru/example/childwatch/
├── network/
│   ├── NetworkClient.kt (existing - keep for HTTP commands)
│   └── WebSocketClient.kt (NEW - WebSocket audio download)
└── AudioStreamingActivity.kt (modified - receive from WebSocket)
```

---

## ⚠️ Важные Моменты

### 1. Обратная Совместимость
- HTTP API для команд (start/stop) **остается**
- Только аудио чанки переходят на WebSocket
- Позволяет постепенную миграцию

### 2. Обработка Ошибок
- Автоматическое переподключение при обрыве связи
- Очистка буфера при долгой потере соединения (>30 сек)
- Уведомления пользователя о проблемах соединения

### 3. Безопасность
- Socket.IO поддерживает те же токены авторизации
- Проверка Device ID при подключении
- Изоляция сессий через комнаты (rooms)

### 4. Производительность
- WebSocket использует одно TCP соединение (vs HTTP множественные)
- Меньше overhead (нет HTTP headers каждый раз)
- Binary mode для передачи аудио (не JSON)

---

## 📊 Ожидаемые Результаты

### До (HTTP Polling):
- ❌ Задержка: 1.5-2 секунды между чанками
- ❌ Прерывания: звук волнами (2-5 сек звук, 2-3 сек тишина)
- ❌ Не синхронизировано: запись и опрос независимы
- ❌ Высокая нагрузка: много HTTP запросов

### После (WebSocket):
- ✅ Задержка: < 100 мс (почти мгновенно)
- ✅ Непрерывный звук: без пауз и прерываний
- ✅ Синхронизировано: push при записи → instant delivery
- ✅ Низкая нагрузка: одно постоянное соединение

---

## 🚀 Порядок Выполнения

1. **Сначала:** Фаза 1 (Server) - подготовить инфраструктуру
2. **Потом:** Фаза 2 (ParentWatch) - отправка через WebSocket
3. **Потом:** Фаза 3 (ChildWatch) - получение через WebSocket
4. **Потом:** Фаза 4 (Testing) - проверить на эмуляторах и реальных устройствах
5. **Опционально:** Фаза 5 (Opus) - если нужна дополнительная оптимизация

---

## 🔍 Как Проверить Успех?

### Логи сервера должны показывать:
```
🔌 Child device child-XXXX connected via WebSocket
🎙️ Receiving audio chunks from child-XXXX (2 chunks/sec)
📤 Broadcasting to parent device
```

### Логи ChildWatch должны показывать:
```
🔌 WebSocket connected to server
🎧 Receiving audio chunk (sequence: 0, size: 264600)
🎧 Receiving audio chunk (sequence: 1, size: 264600)
▶️ Playing chunk from queue (queue size: 5)
```

### Критерии успеха:
- ✅ Фрагменты приходят непрерывно (без пропусков)
- ✅ Очередь стабильна (3-7 чанков)
- ✅ Воспроизведение без пауз >500ms
- ✅ Работает при повороте экрана
- ✅ Автоматически восстанавливается при потере WiFi

---

## 📚 Полезные Ссылки

- Socket.IO Android Client: https://socket.io/docs/v4/client-installation/
- Socket.IO Server (Node.js): https://socket.io/docs/v4/server-api/
- Binary data with Socket.IO: https://socket.io/docs/v4/emitting-events/#binary
- Opus Codec for Android: https://github.com/theeasiestway/android-opus-codec

---

## ⏱️ Общее Время: ~2.5 часа

- Фаза 1: 30 минут (Server)
- Фаза 2: 45 минут (ParentWatch)
- Фаза 3: 45 минут (ChildWatch)
- Фаза 4: 30 минут (Testing)
- **Итого:** 2.5 часа до полностью рабочего решения

Фаза 5 (Opus) - опционально, +1-2 часа если нужно.

---

**Готово! Этот план решит проблему прерываний и даст качественную непрерывную прослушку.**
