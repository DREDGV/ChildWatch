# Changelog

All notable changes to ChildWatch will be documented in this file.

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) and adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.3.0 / 6.1.0] - 2025-10-22

### Added
- Three-level volume modes (Quiet, Normal, Loud) with a dedicated toggle button and persistent preferences.
- Enhanced heads-up display that now shows connection status with session timer, network type, data rate, ping, battery level, audio state, queue health, total data transferred and sample rate.
- Battery indicator on the listening screen so parents can monitor their own device while streaming.

### Improved
- Audio capture and playback run at 22.05 kHz with 20 ms frames, increasing voice clarity without sacrificing latency.
- Jitter buffer management drops excess frames aggressively when the queue grows beyond the optimal window, keeping latency under control.
- Audio focus handling pauses or ducks playback automatically during calls or system notifications and restores volume afterwards.

### Fixed
- Streaming service now holds a partial WakeLock to prevent the child device from suspending the CPU during long sessions.
- System audio filter updates broadcast from ChildWatch are applied immediately on ParentWatch with additional diagnostics.

### Technical
- Added `AudioEnhancer.VolumeMode` on ChildWatch with efficient PCM amplification and clipping protection.
- ParentWatch logs availability/status of Android audio effects and responds to `UPDATE_FILTER_MODE` broadcasts.
- HUD layout updated to two rows with improved typography for readability in daylight.

## [5.2.0 / 4.4.0] - 2025-10-13

### Added
- ParentWatch now collects battery, charging, temperature, voltage and device details from the child device and sends them with every location update.
- Server persists the latest child status in a new device_status table and exposes it via GET /api/device/status/{deviceId}.
- ChildWatch shows a dedicated "Child Device Status" card with live battery/charging stats and caches the last snapshot locally.

### Changed
- ParentWatch location uploads use `uploadLocationWithDeviceInfo` and the foreground notification shows the current battery state.
- Both Android apps send their `BuildConfig` version in the User-Agent header and bumped to ParentWatch v5.2.0 / ChildWatch v4.4.0.
- Added a Gson dependency in ChildWatch for parsing the status payload.

## [2.0.0] - 2025-01-05

### Added
- **ParentWatch модуль** - приложение для ребёнка с отслеживанием местоположения
- **QR-код генерация** для передачи ID устройства между приложениями
- **Короткий формат ID** (4 символа: A1B2) для удобства
- **Настоящий QR-код** с использованием ZXing библиотеки
- **Улучшенная обработка ошибок** в ErrorHandler
- **Graceful degradation** при сетевых ошибках

### Changed
- **Стабильность приложений** - исправлены краши и зависания
- **Обработка разрешений** - улучшен ConsentActivity без зависаний
- **Сетевые запросы** - добавлена обработка ошибок без крашей
- **LocationService** - добавлены try-catch блоки и Toast сообщения
- **NetworkClient** - возвращает ошибки вместо крашей

### Fixed
- **Зависание на разрешениях** - исправлен ConsentActivity
- **Краши при сетевых ошибках** - добавлена обработка исключений
- **Toast в фоновом потоке** - исправлен ErrorHandler
- **Регистрация устройства** - добавлена обработка ошибок в ParentWatch
- **Аутентификация настроек** - временно отключена для тестирования

### Security
- **Улучшенная обработка ошибок** без утечки информации
- **Graceful degradation** при сбоях сервера
- **Безопасные сетевые запросы** с обработкой исключений

## [1.0.0] - 2025-01-04

### Added
- **ChildWatch приложение** - родительское приложение для мониторинга
- **Основные функции мониторинга**:
  - Геолокация с картой
  - Аудиозапись и прослушивание
  - Фото и видео с камеры
  - Чат между родителем и ребёнком
- **Серверная часть** с SQLite базой данных
- **REST API** для всех функций мониторинга
- **Система безопасности**:
  - HTTPS с certificate pinning
  - Token-based аутентификация
  - Шифрование данных
  - Защита от взлома
- **Настройки приложения** с аутентификацией
- **Обработка разрешений** Android
- **Foreground сервисы** для фоновой работы

### Technical
- **Android SDK 26+** поддержка
- **Kotlin** язык программирования
- **Material Design 3** интерфейс
- **Retrofit** для сетевых запросов
- **OkHttp** с interceptors
- **Coroutines** для асинхронности
- **View Binding** для UI
- **SQLite** база данных на сервере
- **Express.js** серверная часть

---

## Типы изменений

- **Added** - новые функции
- **Changed** - изменения в существующей функциональности
- **Deprecated** - функции, которые будут удалены в будущих версиях
- **Removed** - удалённые функции
- **Fixed** - исправления ошибок
- **Security** - изменения, касающиеся безопасности
