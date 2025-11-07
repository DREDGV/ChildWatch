# ChildWatch v7.0.0 - Major Chat & Geofence Release

## 📅 Release Date: 7 ноября 2025

## 🎯 Major Features

### 💬 **Enhanced Chat System**
1. **Connection Status Indicator**
   - Real-time WebSocket connection status
   - Visual indicators: 🟢 Connected, 🟡 Connecting, 🔴 Offline
   - Auto-hide when connected, shows when connecting/offline
   - Improves user confidence in messaging reliability

2. **Message Send Status & Retry**
   - Message states: 🕐 SENDING → ✓ SENT / ❌ FAILED
   - Visual feedback for each message state
   - Retry button for failed messages
   - Automatic status updates on success/error
   - Queue-based reliable delivery

3. **Typing Indicator**
   - Real-time "...печатает" indicator
   - 5-second debounce to reduce network traffic
   - Bidirectional (parent ↔ child)
   - Auto-hide on message send or timeout
   - WebSocket events: typing_start/typing_stop

### 📍 **Geofencing Infrastructure (Part 1)**
1. **Database & Backend**
   - New Geofence entity with radius, notifications settings
   - GeofenceDao for CRUD operations
   - Database migration v2 → v3
   - Indexed queries for performance

2. **Geofence Management**
   - Integration with Android Geofencing API
   - Add/update/delete geofences
   - Activate/deactivate individual zones
   - Re-register after device reboot
   - Support for multiple zones per device

3. **Event Handling & Notifications**
   - GeofenceBroadcastReceiver for zone transitions
   - ENTER/EXIT event detection
   - High-priority notifications for zone exits
   - Configurable notifications per geofence
   - Custom vibration patterns for exits

## 🔧 Technical Improvements

### Backend
- Enhanced WebSocket event system
- New typing events (typing_start, typing_stop)
- Improved message queue reliability
- Geofence event processing pipeline

### UI/UX
- Consistent status indicators across chat
- Material Design 3 components
- Improved error handling with retry
- Better network state visibility

### Database
- Schema version 3 with geofences table
- Proper migration path from v2
- Indexed geofence queries
- LiveData for reactive updates

## 📱 App Versions

- **ChildWatch (Parent):** v7.0.0 (versionCode 41)
- **ParentWatch (Child):** v7.0.0 (versionCode 28)

## 🔄 Breaking Changes

None - backward compatible with existing data

## 📝 Database Migrations

- **v2 → v3:** Added geofences table
  - Columns: id, name, latitude, longitude, radius, device_id, is_active, created_at, notification_on_enter, notification_on_exit
  - Indices: device_id, is_active

## 🐛 Bug Fixes

- Fixed message status not updating on network errors
- Improved WebSocket reconnection logic
- Better handling of typing events during poor connection

## 📦 Dependencies

- Google Play Services Location (for Geofencing API)
- Room Database 2.6.0
- Socket.IO Client 2.1.0
- Material Components 1.11.0

## 🚀 Coming in v7.1.0

- Geofence UI for creating/managing zones
- Map interface for zone selection
- Geofence statistics and history
- Multiple zone monitoring
- Advanced notification settings

## 📸 Screenshots

_(Screenshots will be added during testing phase)_

## 🔗 Related Issues

- Chat improvements roadmap tasks 1-3
- Geofencing foundation (Task 5 - Part 1)

## ⚠️ Known Limitations

- Geofence UI not yet implemented (manual DB entry required)
- Background location permission required for geofencing
- Minimum radius: 100m, Maximum: 5km

## 🙏 Testing Checklist

- [  ] Chat connection status indicator displays correctly
- [  ] Message retry works after network error
- [  ] Typing indicator shows/hides properly
- [  ] Geofence notifications trigger on zone exit
- [ ] Database migration runs successfully
- [ ] App performance remains stable

---

**Full Changelog:** https://github.com/DREDGV/ChildWatch/compare/v6.4.1...v7.0.0
