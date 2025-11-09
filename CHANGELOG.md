# Changelog

All notable changes to ChildWatch will be documented in this file.

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) and adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [7.1.0] - 2025-11-09

### Added

- **Реальная удалённая фотосъёмка** через Camera2 API
  - Замена placeholder (синий квадрат) на настоящую камеру
  - Фоновая съёмка через dummy SurfaceTexture (совместимость с Android 9+)
  - Автоматическая загрузка фото на сервер через `NetworkClient.uploadPhoto()`
  - Таймаут захвата 10 секунд с корректной очисткой ресурсов
  - JPEG качество 85%, разрешение 1920x1080
- **Разрешения для камеры**
  - `FOREGROUND_SERVICE_CAMERA` permission для Android 14+
  - MonitorService теперь поддерживает `foregroundServiceType="location|microphone|camera"`

### Changed

- `PhotoCapture.takePhoto()` — переведён на suspend функцию с `Dispatchers.IO`
- `PhotoCapture.captureRealPhoto()` — новая приватная функция с Camera2 API
- Удалены неиспользуемые методы `createPlaceholderPhoto()`, `processImage()`, `rotateBitmap()`

### Technical

- `app/build.gradle`: versionCode 44, versionName "7.1.0"
- `parentwatch/build.gradle`: versionCode 31, versionName "7.1.0" (синхронизация)
- Добавлены импорты: `ImageFormat`, `SurfaceTexture`, `ImageReader`, `Surface`, `CountDownLatch`, `TimeUnit`
- Серверный эндпоинт `/api/photo` уже был реализован (проверено)

### Known Issues

- ⚠️ **Privacy Indicators**: На Android 12+ появляется зелёный индикатор 🟢 при съёмке (системное ограничение)
- ⚠️ **Юридические риски**: Скрытая съёмка может быть незаконна в некоторых юрисдикциях

---

## [7.0.2] - 2025-11-08

### Fixed

- Room migration crash: `Migration didn't properly handle geofences` (удалены DEFAULT для булевых колонок в миграции 2→3)
- Неправильный бейдж чата на главном экране (теперь корректно скрывается после открытия чата)
- Потенциальная рассинхронизация прочитанных сообщений между legacy и Room хранилищами

### Changed

- При открытии чата все сообщения автоматически помечаются прочитанными (Room + SecurePreferences)
- Обновлена логика вычисления непрочитанных сообщений (корутина IO + обновление на Main потоке)

### Removed

- Кнопка "Очистить" из интерфейса чата (устранение риска случайной потери истории)

### Technical

- `ChildWatchDatabase.MIGRATION_2_3` — убраны DEFAULT значения для `is_active`, `notification_on_enter`, `notification_on_exit`
- `ChatActivity` — добавлена синхронизация `markAllAsRead()` c legacy `ChatManager`
- `MainActivity.updateChatBadge()` — переработано с использованием `Dispatchers.IO`

### Notes

- Версия повышена как PATCH (7.0.1 → 7.0.2), так как изменения не меняют публичный функционал, но устраняют сбои и улучшают UX.

### MAJOR: Application Renaming for Clarity

**BREAKING CHANGE:** Applications have been renamed to eliminate confusion:

- **ParentWatch** → **ChildDevice** (телефон ребенка)
- **ChildWatch** → **ParentMonitor** (телефон родителя)

This change makes it crystal clear which app goes on which device. Previous naming was counterintuitive.

### Added

- **Remote Camera Feature** - Parents can now remotely take photos from child's device
  - Front and back camera support
  - Silent photo capture via WebSocket commands
  - Photo gallery view (ready for implementation)
  - New `RemoteCameraActivity` with material design UI
  - Orange menu card on main screen for quick access
- **Unified Design System** - Complete UI consistency across all screens
  - Emerald green color scheme (`#0F766E` primary, `#115E59` variant)
  - Centered section headers in Settings
  - Consistent button styling (14sp text, green borders)
  - Semi-transparent FAB in chat blending with gradient
  - Navigation arrows in all screens (Map, Chat, Settings)
  - Chat title changed to "Чат с родителями"
- **Chat System Improvements**
  - Personalized chat titles showing real names from database
  - MessageQueue system for reliable message delivery
  - Persistent queue survives app restart
  - Automatic retry mechanism (up to 10 attempts, 5 second delay)
  - Thread-safe ConcurrentLinkedQueue implementation
  - Callback architecture for WebSocket integration
- **Enhanced Child Profile Management** (ChildWatch)
  - Avatar picker from gallery with circular preview (120dp)
  - Device ID field (required, read-only when editing)
  - Name field (required)
  - Age field (optional, numeric input)
  - Phone number field (optional)
  - Complete ScrollView dialog with Material Design
  - Avatar display in device list
  - Full profile editing capabilities

### Changed

- Settings screen: all buttons now use unified outlined style with green borders
- All section titles in Settings are now centered
- "О приложении" and "Статистика" buttons are now equal size (64dp height)
- Font sizes increased from 12sp to 14sp for better readability
- Toolbar titles centered with emerald green background

### Fixed

- Chat message delivery - messages no longer lost when connection drops
- Chat titles now show actual names instead of generic labels
- Message queue ensures delivery even after app closes

### Technical

- Added `RemoteCameraActivity.kt` with WebSocket command sending
- Created `activity_remote_camera.xml` layout with camera controls
- Added `bg_menu_card_orange.xml` drawable for camera menu card
- Updated `AndroidManifest.xml` with new activity registration
- PhotoCaptureService already supports `take_photo` commands
- CameraService handles front/back camera selection
- New `MessageQueue.kt` (248 lines) with persistence via SecurePreferences
- New `dialog_edit_child.xml` ScrollView with Material Design
- Modified `ChatActivity.kt` for both apps with personalization
- Modified `ChildSelectionActivity.kt` and `ChildrenAdapter.kt`

### Documentation

- Updated `.github/copilot-instructions.md` with clear warnings about app naming
- Added prominent section explaining the counterintuitive naming

## [6.2.0 / 5.4.0] - 2025-10-25

### Added

- **Emoji System** in chat
  - Emoji button in chat interface
  - Popup with 40 popular emojis in 5x5 grid
  - Direct insertion at cursor position
  - Categories: emotions, celebrations, family, food, sports, tech
- **Message Status System**
  - Advanced status indicators: SENDING, SENT, DELIVERED, READ, FAILED
  - Color differentiation: green checkmarks for read, gray for others
  - Single checkmark (sent), double checkmark (delivered/read)

### Changed

- **Modern Chat UI**
  - Beautiful gradient message bubbles (#5E72E4 → #4361EE)
  - Improved incoming message design (light gray background #F5F7FA with soft border)
  - Updated chat background (#ECF0F1) for better readability
  - Material Design 3 components
  - Increased corner radius (18dp)
  - Maximum width limit (280dp)
  - Shadow effects using layer-list
- **Audio Streaming Tab Improvements** (ChildWatch)
  - Fixed encoding issues (кракозябры) under audio filter icons
  - Fixed white text on light background for labels
  - All texts now use string resources instead of hardcoded values
  - Proper UTF-8 encoding for all Russian texts
  - Fixed battery display in HUD (was showing constant 95%)
  - Battery now updates in real-time from AudioStreamMetrics
  - Increased text size in HUD (13sp → 15sp)
  - Increased amplification mode button size (textSize: 18sp, iconSize: 36dp, minHeight: 64dp)
  - Increased visualization button size (textSize: 16sp, iconSize: 32dp, minHeight: 56dp)
  - Changed HUD toggle from OutlinedButton to TonalButton for better contrast
  - Added 35+ string resources for AudioStreamingActivity

### Fixed

- Removed test button from chat interface
- Fixed extra closing bracket in ParentWatch ChatActivity
- All texts properly use UTF-8 encoding

### Technical

- ChatMessage already supports extended statuses (SENDING, SENT, DELIVERED, READ, FAILED)
- Support for `client_message_id` for message tracking
- Foundation laid for improved MessagingStyle notifications
- Preparation for sound and vibration settings
- Ready for delivery reliability improvements

## [6.1.0 / 5.3.0] - 2025-10-22

### Added

- Three-level volume modes (Quiet, Normal, Loud) with dedicated toggle button and persistent preferences
- Enhanced heads-up display showing connection status with session timer, network type, data rate, ping, battery level, audio state, queue health, total data transferred and sample rate
- Battery indicator on listening screen for parent device monitoring during streaming

### Improved

- Audio capture and playback run at 22.05 kHz with 20 ms frames, increasing voice clarity without sacrificing latency
- Jitter buffer management drops excess frames aggressively when queue grows beyond optimal window, keeping latency under control
- Audio focus handling pauses or ducks playback automatically during calls or system notifications and restores volume afterwards

### Fixed

- Streaming service now holds partial WakeLock to prevent child device from suspending CPU during long sessions
- System audio filter updates broadcast from ChildWatch are applied immediately on ParentWatch with additional diagnostics

### Technical

- Added `AudioEnhancer.VolumeMode` on ChildWatch with efficient PCM amplification and clipping protection
- ParentWatch logs availability/status of Android audio effects and responds to `UPDATE_FILTER_MODE` broadcasts
- HUD layout updated to two rows with improved typography for readability in daylight

## [6.0.0] - 2025-10-30

### Added

- **"Где родители?" Feature** - Parent location sharing with real-time tracking
  - Parents can share their location in real-time
  - Children see parents on map with distance and ETA
  - **ParentLocation** entity with latitude, longitude, accuracy, timestamp, battery, speed, bearing
  - **ParentLocationDao** with CRUD operations
  - **ParentLocationRepository** with ETA calculation (Haversine formula)
  - Database migration v1 → v2 with indices for performance
  - Schema exported to `app/schemas/2.json`
- **ParentLocationTracker** class (277 lines)
  - FusedLocationProviderClient with PRIORITY_BALANCED_POWER_ACCURACY
  - Updates every 60 seconds
  - Automatic upload to server
  - Tracks battery level, speed, bearing
- **Settings UI** with toggle "📍 Делиться моей локацией"
- **MonitorService** integration (auto start/stop/cleanup)
- **Server API Endpoints**
  - POST `/api/location/parent/:parentId` - save parent location
  - GET `/api/location/parent/latest/:parentId` - get latest location
  - GET `/api/location/parent/history/:parentId` - history with pagination
  - Auto-create `parent_locations` table with indices
  - Cleanup old data (keeps last 1000)
- **ParentLocationMapActivity** (398 lines)
  - OSMdroid map with two markers (🟢 parent, 🔵 child)
  - Line between markers
  - Distance calculation (Haversine formula)
  - ETA calculation based on speed
  - Auto-refresh every 30 seconds
  - Smooth zoom based on distance
  - Graceful error handling
- **7 icon drawables** for UI
- **Background location permission** dialog for Android 10+
  - Clear explanation before request
  - Auto-request when enabling location sharing
  - Proper handling of permission denial

### Technical

- Client integration: `getLatestParentLocation()` with fallback to local DB
- `NetworkClient` method `uploadParentLocation()`
- Layout `activity_parent_location_map.xml` with stats card and floating refresh button
- MainActivity integration with new card "📍 Где родители?"

## [5.7.0] - 2025-10-28

### Added

- **OpenStreetMap Integration** - Complete migration from Google Maps
  - Works without VPN in Russia
  - Uses open OpenStreetMap (Mapnik tiles)
  - All features preserved: markers, movement history, auto-update
  - Dependency: `org.osmdroid:osmdroid-android:6.1.18`
  - Dependency: `androidx.preference:preference-ktx:1.2.1`

### Changed

- **Audio Quality Improvements for Unstable Internet**
  - Minimum jitter buffer increased from 160ms to 240ms
  - Maximum buffer increased from 1000ms to 1200ms
  - Aggressive drop threshold increased from 300ms to 400ms
  - Result: Better audio quality with poor internet connection
- **WebSocket Reconnect Optimization**
  - Initial connection timeout reduced from 20s to 15s
  - First reconnection attempt accelerated (from 1s to 0.5s)
  - Maximum retry interval increased (from 5s to 10s)
  - Result: Faster connection recovery during interruptions
- LocationMapActivity completely rewritten for OSMdroid
  - Replaced imports: `com.google.android.gms.maps.*` → `org.osmdroid.*`
  - `GoogleMap` → `MapView`
  - `LatLng` → `GeoPoint`
  - `MarkerOptions` → `Marker`
  - Reverse geocoding (Geocoder) preserved

### Removed

- Google Maps dependency: `com.google.android.gms:play-services-maps:18.2.0`
- Kept: `play-services-location:21.0.1` (for location determination)

### Technical

- AudioPlaybackService.kt: Updated jitter buffer constants
- WebSocketClient.kt: Updated connection timing constants
- AndroidManifest.xml: Added `ACCESS_WIFI_STATE` permission
- Layout: `activity_location_map_new.xml` updated with `<org.osmdroid.views.MapView>`

## [5.5.0] - 2025-10-25

### Added

- Device editing capability with edit button on each device card
- Long press on card as alternative way to open editing
- Convenient editing dialog with three buttons: Save, Cancel, Delete
- Delete device functionality with confirmation dialog
- Visual edit button (pencil icon) on each device card

### Changed

- Device ID now read-only during edit (protection from errors)
- Improved element layout on device card

### Fixed

- **CRITICAL**: Crash when opening ChildSelectionActivity due to ActionBar conflict
  - Created special theme `Theme.ChildWatch.NoActionBar`
  - Activity now opens without errors
- **CRITICAL**: "Выберите устройство" button not working
  - Removed conflicting `foreground` attribute from MaterialCardView
  - Simplified click handler logic
  - Added `background` with `selectableItemBackground` to container

### Technical

- `item_child.xml` - added edit button
- `ChildrenAdapter.kt` - added callback for editing and long press
- `ChildSelectionActivity.kt` - added editing and deletion functions
- `activity_main_menu.xml` - fixed device selection card layout
- `MainActivity.kt` - simplified click handlers
- `themes.xml` - added NoActionBar theme
- `AndroidManifest.xml` - applied NoActionBar theme to ChildSelectionActivity
- New methods: `showEditChildDialog()`, `updateChild()`, `showDeleteConfirmDialog()`, `deleteChild()`

## [5.2.0 / 4.4.0] - 2025-10-13

### Added

- ParentWatch now collects battery, charging, temperature, voltage and device details from child device and sends with every location update
- Server persists latest child status in new device_status table and exposes via GET /api/device/status/{deviceId}
- ChildWatch shows dedicated "Child Device Status" card with live battery/charging stats and caches last snapshot locally
- DeviceInfoCollector to capture battery level, charging type, temperature, voltage and device metadata on child device
- LocationService now sends collected device info along with every location update and shows current battery status in foreground notification
- "Child Device Status" card on home screen displaying battery percentage, charging method, temperature, voltage, health, model and Android version
- Cached latest device status locally so it survives activity restarts and shows immediately while fresh data loads

### Changed

- ParentWatch location uploads use `uploadLocationWithDeviceInfo` and foreground notification shows current battery state
- Both Android apps send their `BuildConfig` version in User-Agent header and bumped to ParentWatch v5.2.0 / ChildWatch v4.4.0
- Fetches device status from new /api/device/status/{deviceId} endpoint and stores snapshot together with fetched timestamp
- Both Retrofit requests and WebSocket handshake now send correct app version in User-Agent header
- All network requests include current BuildConfig version in User-Agent header

### Fixed

- Ensured device registration reports correct app version

### Technical

- Added Gson dependency in ChildWatch for parsing status payload
- Created device_status table to persist latest snapshot reported by child device
- Location uploads save supplied device info and expose through GET /api/device/status/{deviceId}
- AuthManager keeps in-memory copy of latest status for quicker responses

## [5.1.0 / 4.3.0] - 2025-10-12

### Added

- **Fully Updated Main Menu Interface** (ParentWatch)
  - Modern design with cards using Material Design 3
  - Convenient navigation with four main sections:
    - 💬 Chat with parents - Send messages to parents
    - ⚙️ Settings - App configuration
    - ℹ️ About - Version and device information
    - 📊 Statistics - View monitoring data
  - Status indicator - visible icon with dot showing service state
  - Quick control - start/stop monitoring buttons on main screen
  - App version display in header
- **Functional Chat Implementation**
  - Full messaging system between child and parents
  - History saving - all messages saved locally and available after app restart
  - Visual distinction - child and parent messages have different colors:
    - 🔵 Blue background for child messages
    - 🟢 Green background for parent messages
  - Timestamps - each message shows send time (HH:MM)
  - Test messages - "Test" button for quick random message sending
  - Chat clearing - ability to delete all message history
  - Auto-scroll - automatic scroll to latest messages
  - Response simulation - for demonstration, parent responses simulated after 3-6 seconds
- **Enhanced Settings Activity**
  - Modern design - settings moved to separate Activity with improved interface
  - Server configuration:
    - Server URL input with hints
    - Quick buttons: 🏠 Local (emulator) and ☁️ Cloud (Railway)
  - Device management:
    - Device ID display in convenient format
    - 📋 One-button copy ID to clipboard
    - 📱 QR code display for quick pairing (in development)
  - Settings saving - validation and saving with confirmation

### Technical

- New classes and components:
  - `ChatMessage.kt` - Chat message data model
  - `ChatManager.kt` - Manager for saving and loading messages (JSON + SharedPreferences)
  - `ChatAdapter.kt` - RecyclerView adapter for displaying messages
  - `SecurePreferences.kt` - Wrapper for secure data storage
- New resources:
  - `message_bubble_child.xml` - Child message background
  - `message_bubble_parent.xml` - Parent message background
  - `item_chat_message.xml` - Message item layout
  - `activity_chat.xml` - Fully updated chat layout with RecyclerView
  - `activity_main.xml` - New main screen with cards
  - `status_badge.xml` - Status indicator drawable
- Material Design 3 - Using MaterialCardView and MaterialButton
- ViewBinding - Safe UI element handling
- LinearLayoutManager - Optimized message list display
- Foreground Service - Geolocation service continues working in background

## [5.0.0 / 6.0.0] - 2025-10-22

### Added

- **Comprehensive Diagnostics System** - "See the problem before complaint"
  - Complete monitoring and diagnostics of audio streaming in real-time
  - All key metrics visible directly in interface
  - Instant problem cause identification
- **Compact HUD Diagnostics** (ChildWatch)
  - Location: Above visualizer in listening screen
  - Format: Compact line with 5 key metrics
  - Real-time updates via StateFlow (every 2 sec)
  - Metrics displayed: WebSocket Status (🟢/🟡/🟠/🔴), Network Type (📡/📱/🌐), Data Rate, Queue Depth, Ping
- **Metrics Architecture**
  - `diagnostics/AudioStreamMetrics.kt` - data class with all metrics
  - `diagnostics/MetricsManager.kt` - metrics collection and management manager
  - Automatic battery and network monitoring
  - StateFlow for reactive UI updates
  - Error history (up to 20 latest)
  - Log history (up to 100 latest)
  - Export to JSON for sending to developer
  - Calculated metrics (healthStatus, dataRatePercent, bufferDurationMs)
- **Diagnostics Infrastructure** (ParentWatch)
  - Same data classes and MetricsManager
  - Ready for integration into AudioStreamRecorder
  - Version synchronization for compatibility with ChildWatch v5.0.0

### Changed

- AudioPlaybackService integration with MetricsManager
  - WebSocket status updates on connect/disconnect
  - Audio status updates (BUFFERING → PLAYING)
  - Data rate tracking every 2 seconds
  - Queue depth and underrun tracking
  - Automatic cleanup on destroy

### Fixed

- All conflicts with `FilterMode` enum resolved
- Created single enum in separate file for each module
- All `AudioEnhancer.FilterMode` references replaced with `FilterMode`
- Successful compilation without errors

### Technical

- Semi-transparent HUD (#1A000000) for minimal visual noise
- Emoji icons for quick status recognition
- Ping color indication for instant quality assessment
- Compact format - all information in one line
- Automatic time formatting (12s, 2m 34s, 1h 15m)
- Color indication: Green (< 50ms), Light green (50-100ms), Yellow (100-200ms), Red (> 200ms)

## [4.10.0 / 5.7.0] - 2025-10-21

### Added

- Unified audio filter system using AudioEnhancer.FilterMode

### Changed

- All components migrated to AudioEnhancer.FilterMode
- AudioStreamingActivity completely reworked
- Simplified filter architecture
- Removed dependency on AudioQualityManager
- Direct FilterMode usage
- Removed "advanced settings" (custom mode)
- Simplified service synchronization

### Removed

- Old AudioQualityMode system
- `audio/AudioQualityModes.kt` - old filter system
- Deprecated methods `updateAudioEnhancerConfig()` and `updateAudioEnhancer()` from AudioPlaybackService
- Only `setFilterMode(mode)` kept for filter changes

### Fixed

- **CRITICAL**: Filters displayed as old text strings issue
  - In layout file `activity_audio_streaming.xml` old Chip elements were hardcoded instead of RecyclerView with filter cards
  - Replaced "Audio Quality Modes" section with "Audio Filter Modes"
  - Method `setupQualityModeChips()` now creates RecyclerView with filter cards
  - Filters now display as Material Design 3 cards with icons, titles and descriptions

### Technical

- Updated files: `AudioStreamingActivity.kt`, `service/AudioPlaybackService.kt`
- Cleaner architecture
- 5 filter cards display: 📡 Original, 🎤 Voice, 🔇 Quiet Sounds, 🎵 Music, 🌳 Outdoor

## [4.9.0 / 5.6.0] - 2025-10-21

### Fixed

- **Audio Streaming** (ChildWatch)
  - Fixed type errors `AudioQualityMode` → `AudioEnhancer.FilterMode`
  - Restored `getModeName()` method for filter name display
  - Fixed `AudioFilterItem` and `AudioFilterAdapter`
  - Audio streaming now works correctly with all filters
- **Chat** (ChildWatch)
  - Fixed syntax errors in `WebSocketClient`
  - Removed duplicate `onChatMessageCallback`
  - Added correct `ChatMessage` import from `ru.example.childwatch.chat`
  - Replaced `khttp` with `OkHttpClient` for HTTP requests
  - Implemented methods `getServerUrl()` and `getChildDeviceId()`
  - Added imports `Dispatchers`, `withContext`, `Context`
  - Fixed `getDeviceId()` method conflict → renamed to `getChildDeviceId()`
- **WebSocketManager**
  - Added `onMissedMessages` parameter for handling unread messages
  - Fixed `ChatMessage` import

### Technical

- Files: AudioActivity.kt, AudioFilterItem.kt, AudioFilterAdapter.kt, ChatActivity.kt, WebSocketClient.kt, WebSocketManager.kt

## [4.8.0 / 5.5.0] - 2025-10-19

### Added

- **Remote Camera Control** (Task #9 - completed)
  - Send remote photo command via WebSocket
  - Camera selection (front/back)
  - Updated PhotoActivity with WebSocket support
  - Command sending status indication
  - Camera selection dialog
  - `sendCommand()` method in WebSocketClient
- **Remote Photo Capture System** (ParentWatch)
  - `PhotoCaptureService` - foreground service for background operation
  - `CameraService` - photo capture from front/back camera
  - WebSocket integration for receiving commands
  - Automatic start when monitoring enabled
  - Background capture without preview
  - Photo saving to local storage
  - Automatic server upload
- **WebSocket Improvements**
  - Added `take_photo` command processing
  - WebSocketManager now supports command callbacks
  - Command distribution between services

### Technical

- New files: `service/PhotoCaptureService.kt`, `service/CameraService.kt`
- Updated files: `PhotoActivity.kt`, `WebSocketClient.kt`, `WebSocketManager.kt`, `NetworkHelper.kt`, `MainActivity.kt`
- Command flow: ChildWatch → WebSocket Server → ParentWatch → PhotoCaptureService → CameraService → Capture → Upload

## [4.7.0 / 5.4.0] - 2025-10-19

### Added

- **Geolocation with Map** (Task #8)
  - Movement history on Google Maps
  - Routes (Polyline) showing child's path
  - Color markers:
    - 🟢 Green - start point
    - 🔴 Red - end point
    - 🟠 Orange - intermediate points
  - Time filter (1 hour / 3 hours / 12 hours / day / 3 days / week)
  - Reverse Geocoding - shows addresses instead of coordinates
  - Automatic map scaling
- **Remote Photo** (Task #9 - basic structure)
  - `CameraService.kt` - photo capture service
  - `PhotoCaptureService.kt` - background service
  - Support for front and back camera
  - Background capture without preview
  - Photo storage saving
  - API for uploading photos to server

### Changed

- **Audio Filter Improvements**
  - Added "📡 Original" mode - no filters (default)
  - Reduced gain in all filter modes
  - Eliminated crackling and audio distortion
  - Smoother soft limiting

### Fixed

- Chat message sending from child to parent (sender: "child")
- All кракозябры fixed in dialog boxes
- Correct Russian text display

### Technical

- Files: `LocationMapActivity.kt`, `NetworkClient.kt`, `AudioEnhancer.kt`, `ChatActivity.kt`
- New files: `service/CameraService.kt`, `service/PhotoCaptureService.kt`, `network/NetworkHelper.kt`

## [4.6.0] - 2025-10-19

### Added

- **Audio Filter System** (Task #7)
  - 4 specialized modes with distinct parameters
  - 🎤 Voice - Optimized for conversations
    - Moderate noise reduction (threshold: 100)
    - Speech enhancement (+6dB)
    - Compression for even sound (3:1)
  - 🔇 Quiet Sounds - Maximum sensitivity
    - Minimal noise reduction (threshold: 30)
    - Strong amplification (+12dB)
    - Light compression (2.5:1)
  - 🎵 Music - Natural sound
    - Minimal processing (threshold: 20)
    - Light amplification (+1.6dB)
    - Delicate compression (1.5:1)
  - 🌳 Outdoor - Aggressive noise reduction
    - Strong noise suppression (threshold: 200)
    - Wind and traffic suppression
    - Medium amplification (+8dB)
    - Strong compression (4:1)

### Changed

- New filter selection interface with RadioGroup
- Each mode has description and icon (emoji)
- Material Design 3 cards
- Auto-save selected mode
- Improved AudioActivity with "Filter Mode" card

### Technical

- `AudioEnhancer.kt` - New code: 196 lines (was: 151)
  - `enum class FilterMode` with 4 modes
  - `processVoiceMode()`, `processQuietSoundsMode()`, `processMusicMode()`, `processOutdoorMode()`
  - Parameterized filters and compressor
- `AudioPlaybackService.kt` - Added `setFilterMode(mode)`
- `AudioActivity.kt` - Added `setupFilterModeUI()`, `loadFilterMode()`, `updateFilterMode()`, `getModeName()`
- `activity_audio.xml` - Added 102 lines with MaterialCardView for filters

## [4.5.0 / 5.3.0] - 2025-10-19

### Added

- **Current App Tracking** (Task #2)
  - Display of currently open app on child's device
  - UsageStatsManager API for accurate tracking
  - Automatic data transmission to server with geolocation
  - System app filtering
  - AppUsageTracker.kt - tracking through UsageStatsManager
  - PACKAGE_USAGE_STATS permission request
  - Automatic sending of current app with geolocation
  - Settings UI for permission granting
- **Unread Message Indicator** (Task #6)
  - Red badge on chat card in main menu
  - Display of unread count (or "99+")
  - Automatic hiding when no unread messages
  - Updates on app resume (onResume)
  - Badge automatically appears on launcher icon (Android 8+)
  - Shows unread count using `.setShowBadge(true)` and `.setNumber(count)`
  - Messages from child default to `isRead = false`
  - All marked as read when opening chat

### Changed

- **Simplified Device Status** (Task #1)
  - Removed technical fields: Voltage, Battery Health, Android Version/SDK
  - Kept only useful data: Battery level (%), Charging status, Temperature, Device model, Current app (new!), Update time
- **Chat Stability Improvements** (Task #3)
  - Server-side: All messages now saved to database
  - Messages delivered even if recipient offline
  - Delivery confirmation with `delivered` flag
  - Automatic synchronization on connection
  - Client-side: Chat history sync with server on open
  - GET `/api/chat/history/:deviceId` - get history (up to 500 messages)
  - POST `/api/chat/mark-read/:deviceId` - mark as read
  - Smart merging of local and server messages without duplicates
- **Modernized Chat UI** (Task #4)
  - Beautiful shadows (layer-list)
  - Green gradient for child messages (#4CAF50)
  - White background with gray border for parent messages
  - Increased corner radius (18dp)
  - Maximum width limit (280dp)
  - Light gray background (#F5F5F5) like modern messengers
  - Separator line between messages and input area
  - Increased shadow for input area
  - Improved typography (Material 3)
  - Status indicators: ✓ sent, ✓✓ delivered/read with color coding
- **Extended Notification Settings** (Task #5)
  - Notification size: Compact / Expanded (BigTextStyle)
  - Priority: Low / Medium / High
  - Duration: 5-30 seconds
  - Sound: On/Off
  - Vibration: On/Off
  - Settings saved in `notification_prefs`
  - Applied dynamically when showing notifications
  - Material Design UI with sliders and radio buttons

### Technical

- Files added/modified: 15+ (ChildWatch), 8+ (ParentWatch)
- Database: Added `chat_messages` table, added columns `current_app_name`, `current_app_package` in `device_status`
- API Endpoints: GET `/api/chat/history/:deviceId`, POST `/api/chat/mark-read/:deviceId`
- WebSocket: Save all messages to DB, `delivered` flag in send confirmation, `chat_message_error` event
- New resources: `message_bubble_*.xml`, `item_message_*.xml`, `activity_chat.xml`, `badge_background.xml`
- New classes: `AppUsageTracker.kt`, `ChatManager.kt`

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
- **Technical** - технические детали и инфраструктура
