# ChildWatch - Technical Architecture

## System Overview

ChildWatch is a comprehensive Android monitoring application designed for legitimate safety purposes. The application consists of an Android client and a Node.js test server.

## Architecture Diagram

```
┌─────────────────────────┐    HTTPS     ┌─────────────────────────┐
│   Android App           │ ────────────► │  Node.js Server         │
│                         │              │                         │
│ ┌─────────────────────┐ │              │ ┌─────────────────────┐ │
│ │MainActivity         │ │              │ │Express API           │ │
│ └─────────────────────┘ │              │ └─────────────────────┘ │
│ ┌─────────────────────┐ │              │ ┌─────────────────────┐ │
│ │MonitorService       │ │              │ │File Storage          │ │
│ │(Foreground)         │ │              │ └─────────────────────┘ │
│ └─────────────────────┘ │              └─────────────────────────┘
│ ┌─────────────────────┐ │
│ │LocationMgr          │ │
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │AudioRecorder        │ │
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │NetworkClient        │ │
│ └─────────────────────┘ │
└─────────────────────────┘
```

## Component Details

### Android Client Components

#### 1. MainActivity
- **Purpose**: User interface and onboarding
- **Features**:
  - Consent screen with clear explanation
  - Permission management
  - Start/stop monitoring controls
  - Settings configuration
- **Key Methods**:
  - `giveConsent()`: Records user consent
  - `startMonitoring()`: Initiates monitoring service
  - `requestPermissions()`: Handles runtime permissions

#### 2. MonitorService (Foreground Service)
- **Purpose**: Continuous background monitoring
- **Features**:
  - Persistent notification
  - Location updates every 30s (configurable)
  - Audio recording on command
  - Network data upload
- **Key Methods**:
  - `startMonitoring()`: Begins continuous monitoring
  - `startLocationUpdates()`: Periodic GPS tracking
  - `startAudioCapture()`: On-demand audio recording

#### 3. LocationManager
- **Purpose**: GPS location handling
- **Features**:
  - High accuracy location via FusedLocationProvider
  - Background location support
  - Battery optimization
  - Error handling
- **Key Methods**:
  - `getCurrentLocation()`: Immediate location request
  - `startLocationUpdates()`: Continuous tracking
  - `checkLocationSettings()`: GPS availability

#### 4. AudioRecorder
- **Purpose**: Audio capture and management
- **Features**:
  - Configurable duration recording
  - Temporary file management
  - Android 14+ compatibility
  - Background recording restrictions handling
- **Key Methods**:
  - `startRecording()`: Begin audio capture
  - `stopRecording()`: End and return file
  - `isAudioRecordingAvailable()`: Check permissions

#### 5. NetworkClient
- **Purpose**: Server communication
- **Features**:
  - HTTPS-only requests
  - Multipart file uploads
  - JSON data transmission
  - Error handling and retries
- **Key Methods**:
  - `uploadLocation()`: Send GPS coordinates
  - `uploadAudio()`: Send audio files
  - `testConnection()`: Server connectivity

#### 6. PermissionHelper
- **Purpose**: Runtime permission management
- **Features**:
  - Permission status checking
  - Android version compatibility
  - Background location handling
- **Key Methods**:
  - `hasAllRequiredPermissions()`: Complete check
  - `getMissingPermissions()`: Identify gaps

### Server Components

#### 1. Express API Server
- **Purpose**: Data reception and storage
- **Endpoints**:
  - `POST /api/loc`: Receive location data
  - `POST /api/audio`: Receive audio files
  - `POST /api/photo`: Receive photos (future)
  - `GET /api/health`: Health check
  - `GET /api/data`: Recent data retrieval

#### 2. File Storage
- **Purpose**: Temporary file management
- **Features**:
  - Automatic file naming with timestamps
  - Device ID tracking
  - Size limits (50MB)
  - Cleanup procedures

## Data Flow

### Location Monitoring Flow
```
1. MonitorService starts location updates
2. LocationManager requests GPS coordinates
3. Coordinates sent to NetworkClient
4. NetworkClient uploads to /api/loc endpoint
5. Server logs and stores location data
```

### Audio Recording Flow
```
1. FCM command received: startAudioCapture
2. MonitorService initiates audio recording
3. AudioRecorder captures audio to temp file
4. NetworkClient uploads file to /api/audio
5. Local temp file deleted after upload
```

## Security Considerations

### Client-Side Security
- ✅ HTTPS-only communication
- ✅ Local preference encryption
- ✅ Temporary file cleanup
- ✅ Explicit user consent
- ✅ Visible monitoring notifications

### Server-Side Security (Production Recommendations)
- 🔐 Implement authentication
- 🔐 Encrypt stored data
- 🔐 Rate limiting
- 🔐 Input validation
- 🔐 Certificate pinning
- 🔐 Regular security audits

## Performance Optimizations

### Battery Optimization
- Efficient location request intervals
- Foreground service with low priority
- Minimal background processing
- Proper resource cleanup

### Network Optimization
- Compressed audio files
- Efficient JSON payloads
- Connection pooling
- Retry mechanisms

## Android Version Compatibility

### Android 8.0+ (API 26+)
- Background service restrictions
- Foreground service requirements
- Runtime permissions

### Android 10+ (API 29+)
- Background location permission
- Scoped storage limitations
- Enhanced privacy controls

### Android 14+ (API 34+)
- Background microphone restrictions
- Enhanced notification requirements
- Stricter permission enforcement

## Error Handling

### Client Error Handling
- Network connectivity issues
- Permission denials
- GPS unavailability
- Audio recording failures
- Server communication errors

### Server Error Handling
- File upload failures
- Invalid data formats
- Storage space issues
- Rate limiting
- Authentication failures

## Monitoring and Logging

### Client Logging
- Location update frequency
- Audio recording attempts
- Network request success/failure
- Permission status changes
- Service lifecycle events

### Server Logging
- Incoming requests
- File uploads
- Error conditions
- Performance metrics
- Security events

## Future Enhancements

### Planned Features
- [ ] Firebase integration (FCM, Storage)
- [ ] Photo capture capability
- [ ] Geofencing alerts
- [ ] Web dashboard for parents
- [ ] Push notifications
- [ ] Multi-device support
- [ ] Data encryption
- [ ] Offline data caching

### Technical Improvements
- [ ] Dependency injection (Hilt)
- [ ] Reactive programming (RxJava)
- [ ] Unit testing coverage
- [ ] UI testing automation
- [ ] Performance monitoring
- [ ] Crash reporting