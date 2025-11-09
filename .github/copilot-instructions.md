<!-- Short, actionable rules for AI agents working in ChildWatch repo -->

# Copilot / AI instructions — ChildWatch (ESSENTIALS)

## 1) ДВА ПРИЛОЖЕНИЯ (важно не перепутать)

- 📱 `app/` = ChildWatch (ParentMonitor) — УСТАНАВЛИВАЕТСЯ НА ТЕЛЕФОН РОДИТЕЛЯ — pkg `ru.example.childwatch`
  - APK имя: `ParentMonitor-v<version>-debug.apk`
  - VS Code задачи деплоя: «� Quick Deploy: ChildWatch to Real Device (Nokia)», «🚀 Quick Deploy: ChildWatch to Emulator»
  - Роль: получает локации/медиа от ребёнка, отправляет команды (аудио, фото, геозоны)
- �👶 `parentwatch/` = ParentWatch (ChildDevice) — УСТАНАВЛИВАЕТСЯ НА ТЕЛЕФОН РЕБЕНКА — pkg `ru.example.parentwatch`
  - APK имя: `ChildDevice-v<version>-debug.apk`
  - Установка выполняется вручную: `adb install -r parentwatch/build/outputs/apk/debug/ChildDevice-v<version>-debug.apk`
  - Роль: захватывает локацию, фото, аудио; синхронизирует чат; отправляет события на сервер

⚠ Памятка для ИИ: НЕ ПУТАТЬ — название каталога `parentwatch/` содержит код ДЕТСКОГО устройства, а каталог `app/` — приложения РОДИТЕЛЯ.
Если меняешь логику обмена, повышай версии ОБОИХ и фиксируй в `CHANGELOG.md`.

## 2) Архитектура и потоки

- Клиенты (оба модуля) + Node.js сервер (`server/`, Express + Socket.IO + sqlite3).
- Foreground‑сервисы: родитель — `MonitorService`, `ParentLocationService`; ребенок — `LocationService`, `PhotoCaptureService`.
- WebSocket: регистрируй callbacks ПЕРЕД `connect()`; командами управляет `server/managers/*`.
- База (Room): `ChildWatchDatabase`; миграции v1→2 (parent_locations), v2→3 (geofences). Избегай DEFAULT для булевых в миграции 2→3.

## 3) Ключевые файлы/потоки

- Чат (родитель): `ChatActivity.kt`, `service/ChatBackgroundService.kt`, `chat/ChatManagerV2.kt`.
  - При открытии чата все сообщения помечаются прочитанными (Room + legacy `ChatManager`).
  - Бейдж на главной считает непрочитанные через `MainActivity.updateChatBadge()` (корутина IO → Main).
- Локации родителей: `ParentLocationService.kt` (WebSocket + REST fallback в `NetworkClient.uploadParentLocation`).
- WebSocket клиент: `network/WebSocketManager.kt` — не инициализируй без валидного `child_device_id`.

## 4) Конвенции/источники данных

- Единый источник `child_device_id`: `SecurePreferences("childwatch_prefs")` → `SharedPreferences("childwatch_prefs")` → legacy `app_prefs` (fallback).
- В AndroidManifest избегай дубликатов сервисов (была проблема с `ParentLocationService`).
- Уведомления: используй `utils/NotificationManager.showChatNotification(...)` (нет `showNotification`).

## 5) Сборка, запуск, зеркалирование

- Сборка: `./gradlew :app:assembleDebug` (аналогично для `:parentwatch`).
- Имена APK: родитель `ParentMonitor-v<ver>-debug.apk`, ребенок `ChildDevice-v<ver>-debug.apk`.
- VS Code Tasks (Windows/PowerShell):
  - «🚀 Quick Deploy: ChildWatch to Real Device (Nokia)» — установка на PT19655KA1280800674
  - «🚀 Quick Deploy: ChildWatch to Emulator» — установка на `emulator-5554`
  - «🎯 Dual Mirror: Real + Emulator» — scrcpy для обоих устройств

## 6) Сервер

- Точки входа: `server/index.js`, маршруты в `server/routes/`, менеджеры команд — `server/managers/`, база — `server/database/`.
- Важные эндпоинты: `POST /api/loc`, `POST /api/audio`, `POST /api/photo`, `GET /api/location/latest`, `POST /api/auth/register`.
- Загрузка медиа: Multer → `server/uploads/`; прод хранение заменить на безопасное.

## 7) Версионирование (обязательно)

- Любое изменение логики/UX/миграций/сервисов ⇒ bump version в ОБОИХ модулях (versionCode+versionName) + запись в `CHANGELOG.md`.
- Классификация: PATCH — фиксы/миграции/UX‑мелочи; MINOR — новая совместимая функциональность; MAJOR — несовместимые изменения.
- См. `VERSIONING.md` и скрипт `version.sh` (коммит + тег).

## 8) Частые ловушки

- Порядок WebSocket: сначала `set*Callback()`, затем `connect()`.
- Room миграции: не добавляй DEFAULT к булевым полям (см. `MIGRATION_2_3`).
- Бейдж чата: обновлять через `updateChatBadge()`; сообщения читать автоматически при открытии чата.

Ссылки для старта: `ARCHITECTURE.md`, `DEVELOPMENT.md`, `server/index.js`, `app/.../service/MonitorService.kt`, `app/.../database/ChildWatchDatabase.kt`.
