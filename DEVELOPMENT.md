# ChildWatch Development Configuration

## Environment Setup

### Required Tools
- Android Studio Arctic Fox or later
- Android SDK API 26-34
- Node.js 16+ (for test server)
- Git for version control
- ADB for device testing

### IDE Configuration
```json
{
  "editor.formatOnSave": true,
  "kotlin.codeStyle": "official",
  "java.format.settings.url": "https://raw.githubusercontent.com/google/styleguide/gh-pages/eclipse-java-google-style.xml"
}
```

## Build Configuration

### Debug Build
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build (Production)
```bash
./gradlew assembleRelease
# Requires signing configuration
```

### Test Build
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Development Workflow

### 1. Feature Development
```bash
git checkout -b feature/new-feature
# Make changes
git add .
git commit -m "feat: Add new feature"
git push origin feature/new-feature
```

### 2. Testing Workflow
```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Start server
cd server && npm start

# Test with ngrok
ngrok http 3000

# Check logs
adb logcat | grep ChildWatch
```

### 3. Code Quality
```bash
# Lint check
./gradlew lint

# Static analysis
./gradlew check

# Dependency check
./gradlew dependencyCheckAnalyze
```

## Server Development

### Local Development
```bash
cd server
npm install
npm run dev  # Auto-restart on changes
```

### Production Deployment
```bash
# Using PM2
npm install -g pm2
pm2 start index.js --name childwatch-server

# Using Docker
docker build -t childwatch-server .
docker run -p 3000:3000 childwatch-server
```

## Testing Strategy

### Unit Tests
- LocationManager tests
- AudioRecorder tests
- NetworkClient tests
- PermissionHelper tests

### Integration Tests
- Service lifecycle tests
- Network communication tests
- Permission flow tests

### UI Tests
- Onboarding flow tests
- Settings configuration tests
- Monitoring control tests

### Manual Testing Checklist
- [ ] App installs without errors
- [ ] Permissions are requested correctly
- [ ] Consent screen displays properly
- [ ] Monitoring starts/stops correctly
- [ ] Location updates are sent to server
- [ ] Audio recording works on command
- [ ] Notifications display correctly
- [ ] App survives device reboot

## Debugging

### Common Issues
1. **Permission Denied**: Check Android settings
2. **Location Not Updating**: Verify GPS is enabled
3. **Audio Recording Failed**: Check Android 14+ restrictions
4. **Server Connection Failed**: Verify ngrok URL and server status

### Debug Commands
```bash
# Check app permissions
adb shell dumpsys package ru.example.childwatch | grep permission

# Check service status
adb shell dumpsys activity services ru.example.childwatch

# Monitor network traffic
adb shell tcpdump -i any -s 0 -w /sdcard/capture.pcap

# Check battery optimization
adb shell dumpsys deviceidle whitelist
```

## Performance Monitoring

### Key Metrics
- Location update frequency
- Battery consumption
- Network data usage
- Memory usage
- CPU usage

### Monitoring Tools
- Android Studio Profiler
- Firebase Performance Monitoring
- Custom logging and analytics

## Security Checklist

### Client Security
- [ ] HTTPS-only communication
- [ ] Certificate pinning (production)
- [ ] Data encryption (sensitive data)
- [ ] Secure storage (credentials)
- [ ] Input validation
- [ ] Permission validation

### Server Security
- [ ] Authentication and authorization
- [ ] Rate limiting
- [ ] Input sanitization
- [ ] File upload validation
- [ ] HTTPS enforcement
- [ ] Security headers
- [ ] Regular security audits

## Deployment Checklist

### Pre-Release
- [ ] All tests passing
- [ ] Code review completed
- [ ] Security scan passed
- [ ] Performance benchmarks met
- [ ] Documentation updated
- [ ] Legal compliance verified

### Release
- [ ] Version number updated
- [ ] Release notes prepared
- [ ] Build signed and verified
- [ ] Server deployed and tested
- [ ] Monitoring configured
- [ ] Rollback plan prepared

## Maintenance

### Regular Tasks
- Security updates
- Dependency updates
- Performance monitoring
- User feedback analysis
- Bug fixes and improvements

### Monitoring
- Server uptime
- Error rates
- Performance metrics
- User engagement
- Security incidents
