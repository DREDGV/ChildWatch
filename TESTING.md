# ChildWatch Testing Guide

## Quick Test Commands

### Build and Install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ru.example.childwatch/.MainActivity
```

### Grant Permissions
```bash
adb shell pm grant ru.example.childwatch android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ru.example.childwatch android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ru.example.childwatch android.permission.RECORD_AUDIO
adb shell pm grant ru.example.childwatch android.permission.ACCESS_BACKGROUND_LOCATION
```

### Start Test Server
```bash
cd server
npm install
npm start
```

### Test with ngrok
```bash
ngrok http 3000
# Copy HTTPS URL to app settings
```

### Test Commands
```bash
# Request immediate location
adb shell am start-foreground-service -n ru.example.childwatch/.service.MonitorService -a request_location

# Start audio recording (15 seconds)
adb shell am start-foreground-service -n ru.example.childwatch/.service.MonitorService -a start_audio_capture --ei audio_duration 15
```

### Check Logs
```bash
adb logcat | grep ChildWatch
tail -f server/server.log
```
