# Release Notes - 5.2.0 / 4.4.0

## ParentWatch (ChildDevice) v5.2.0

### Added
- Added DeviceInfoCollector to capture battery level, charging type, temperature, voltage and device metadata on the child device.
- LocationService now sends the collected device info along with every location update and shows the current battery status in the foreground notification.

### Changed
- All network requests include the current BuildConfig version in the User-Agent header.

### Fixed
- Ensured device registration reports the correct app version.

### Build Artifacts
- **versionName**: 5.2.0
- **APK**: ChildDevice-v5.2.0-debug.apk

## ChildWatch (Parent) v4.4.0

### Added
- Introduced a "Child Device Status" card on the home screen that displays battery percentage, charging method, temperature, voltage, health, model and Android version.
- Cached the latest device status locally so it survives activity restarts and shows immediately while fresh data loads from the server.

### Changed
- Fetches device status from the new /api/device/status/{deviceId} endpoint and stores the snapshot together with the fetched timestamp.
- Both Retrofit requests and WebSocket handshake now send the correct app version in the User-Agent header.

### Dependencies
- Added com.google.code.gson:gson to parse the status payload directly on device.

## Server 1.3.0

### Added
- Created a device_status table to persist the latest snapshot reported by the child device.
- Location uploads save the supplied device info and expose it through GET /api/device/status/{deviceId}.
- AuthManager keeps an in-memory copy of the latest status for quicker responses.

