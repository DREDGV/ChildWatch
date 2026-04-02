# Emulator Preview Workflow

This project is a native Android multi-module app, not React Native or Flutter.
That means there is no true hot reload in VS Code.

The closest practical setup is:

1. Start one Android Emulator in Android Studio.
2. Install `app`, `parentwatch`, or both onto that emulator.
3. Keep the emulator window open next to the editor.
4. Use Android Studio `Apply Changes` for small edits, or use the PowerShell watcher for rebuild + reinstall on save.

## Best option: Android Studio

Android Studio is the best tool for "almost live" preview in this repo because it gives you:

- Android Emulator / Device Manager
- XML layout preview
- Layout Inspector
- `Apply Changes` after the app is already running

Recommended flow:

1. Open the project in Android Studio.
2. Open `Tools -> Device Manager`.
3. Create or start an emulator.
4. Run the `app` module or the `parentwatch` module.
5. After UI edits, use `Apply Changes and Restart Activity` when Android Studio offers it.

Notes:

- `app/` is the parent app (`ru.example.childwatch`)
- `parentwatch/` is the child app (`ru.example.parentwatch.debug` in debug builds)
- Both apps can be installed on the same emulator because the package names are different.

## VS Code workflow

VS Code can still be used if the emulator is already running.

Use the new helper script:

```powershell
.\scripts\dev-workflow.ps1 -Action status
.\scripts\dev-workflow.ps1 -Action deploy -Target both
.\scripts\dev-workflow.ps1 -Action watch -Target both
```

What each command does:

- `status` checks Android Studio, adb, emulator support, and connected devices
- `deploy` builds the selected module and installs it to the running emulator
- `watch` listens for source changes and redeploys the changed module automatically

You can also run the same workflow from VS Code tasks:

- `Preview: status`
- `Preview: deploy app to emulator`
- `Preview: deploy parentwatch to emulator`
- `Preview: deploy both to emulator`
- `Preview: watch both`

## What is currently missing on this machine

`adb` and Android Studio are present.
The Android SDK currently does not include the `emulator/` binary, so the emulator should be installed from Android Studio:

1. Open Android Studio
2. `More Actions -> SDK Manager`
3. Install:
   - Android Emulator
   - at least one system image
4. Open `Device Manager` and create an AVD

After that, the workflow above will work without plugging a phone into the PC.
