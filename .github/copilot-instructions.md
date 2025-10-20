<!--
Инструкции для AI-агентов: кратко и конкретно о проекте ChildWatch.
Созданы для быстрого понимания архитектуры, рабочих процессов и точек интеграции.
-->
# Copilot / AI instructions — ChildWatch

Коротко: ChildWatch — Android-клиент (foreground service) + Node.js тестовый сервер.  Основные точки входа и полезные команды перечислены ниже.

## Быстрая карта по проекту
- Android app: `app/src/main/java/ru/example/childwatch/` — ключевые компоненты: `MainActivity`, `service/MonitorService.kt`, `location/LocationManager.kt`, `audio/AudioRecorder.kt`, `network/NetworkClient.kt`.
- Server: `server/` — `index.js` (Express + Socket.IO), маршруты в `server/routes/`, менеджеры в `server/managers/`, база — `server/database/` (sqlite3).

## Что важно знать (архитектура и потоки)
- Клиент держит foreground сервис (`MonitorService`) — периодически отправляет локации и по команде загружает аудио.
- Endpoints сервера: `POST /api/loc`, `POST /api/audio`, `POST /api/photo`, `POST /api/auth/register`, `POST /api/auth/refresh`, `GET /api/location/latest` и др. (см. `server/index.js`).
- Аудио/фото загружаются как multipart (Multer) в `server/uploads/` и метаданные сохраняются в sqlite.
- WebSocket (Socket.IO) используется для команд/стриминга; менеджеры команд и WS находятся в `server/managers/`.

## Быстрые команды (локальная разработка)
- Сборка Android (корень проекта):
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- Сервер (в `server/`):
```bash
cd server
npm install
npm start        # или npm run dev для nodemon
```
- Тестовый туннель: `ngrok http 3000` — подставить HTTPS URL в настройки приложения.

## Полезные примеры из кода
- Отправка локации: клиент вызывает `/api/loc` с { latitude, longitude, accuracy, timestamp, deviceInfo } — сервер валидирует и сохраняет через `DatabaseManager`.
- Загрузка аудио: `POST /api/audio` с полем `audio` (multipart). Ограничение ~10MB (см. multer в `server/index.js`).
- Регистрация устройства: `POST /api/auth/register` — валидирует deviceId, возвращает authToken/refreshToken.

## Проектные конвенции и примечания
- minSdk=26, targetSdk=34 (Android) — фоновые ограничения и требования к уведомлениям критичны.
- Файлы временно сохраняются в `server/uploads/` и удаляются/архивируются отдельно; для production — заменить на безопасное хранилище.
- HTTPS-only для сетевых запросов (README/ARCHITECTURE.md указывают на обязательность).
- Логика аутентификации реализована в `server/auth/` и применяются middleware (`AuthMiddleware`).

## Что проверять при изменениях (практические подсказки)
- При изменении API обновите `server/index.js` и маршруты в `server/routes/` и добавьте/обновите тесты (jest в `server/package.json`).
- Для изменений background/foreground logic на Android внимательно тестировать на Android 10+ и Android 14 (ограничения микрофона и background location).
- Если добавляете большие файлы — проверьте `multer` limits и `maxHttpBufferSize` в Socket.IO (см. `server/index.js`).

## Где смотреть для быстрого понимания
- Архитектура: `ARCHITECTURE.md`
- Руководство разработчика: `DEVELOPMENT.md`
- Главный сервер: `server/index.js`
- Android структура и сервис: `app/src/main/java/ru/example/childwatch/service/MonitorService.kt` и `location/` `audio/` папки.

---
Если нужно, внесу дополнительные короткие инструкции (например, «как запустить unit tests», «полезные grep-запросы» или готовый список HTTP-запросов для тестирования API). Напиши, что добавить.
