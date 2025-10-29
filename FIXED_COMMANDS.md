# ✅ ИСПРАВЛЕНО: Правильные команды для работы

## 🔧 Что было исправлено:

1. ✅ ParentWatch теперь правильно устанавливается и запускается
2. ✅ ChildWatch обновлен до последней версии на эмуляторе
3. ✅ Создан скрипт `quick-launch.ps1` для быстрого перезапуска
4. ✅ Обновлен `dev-workflow.ps1` для корректной работы с debug версиями

---

## ⚡ Самые нужные команды:

### 1. Запустить scrcpy (2 окна):

```powershell
# Nokia слева
Start-Process scrcpy -ArgumentList "--serial PT19655KA1280800674 --max-size 1024 --video-bit-rate 2M --window-title 'ParentWatch' --window-x 0"

# Pixel 8 справа
Start-Process scrcpy -ArgumentList "--serial emulator-5554 --max-size 1024 --video-bit-rate 2M --window-title 'ChildWatch' --window-x 600"
```

### 2. Собрать и установить оба приложения:

```powershell
.\scripts\dev-workflow.ps1 -Action deploy
```

### 3. Только перезапустить приложения (без сборки):

```powershell
.\scripts\quick-launch.ps1
```

---

## 📋 Детальные команды:

### ChildWatch (эмулятор):

```powershell
# Сборка
.\gradlew.bat :app:assembleDebug

# Установка
adb -s emulator-5554 install -r app/build/outputs/apk/debug/ChildWatch-v5.5.0-debug.apk

# Запуск
adb -s emulator-5554 shell am start -n ru.example.childwatch/ru.example.childwatch.MainActivity
```

### ParentWatch (Nokia):

```powershell
# Сборка
.\gradlew.bat :parentwatch:assembleDebug

# Установка (ВНИМАНИЕ: файл называется ChildDevice!)
adb -s PT19655KA1280800674 install -r parentwatch/build/outputs/apk/debug/ChildDevice-v6.3.0-debug.apk

# Запуск (ВНИМАНИЕ: debug версия!)
adb -s PT19655KA1280800674 shell am start -n ru.example.parentwatch.debug/ru.example.parentwatch.MainActivity
```

---

## 🎯 Ваш ежедневный workflow:

### Утро:

```powershell
# 1. Запустить эмулятор (если не запущен)
Start-Process -FilePath "C:\Users\dr-ed\AppData\Local\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd Pixel_8_API_35"

# Подождать 30 секунд, затем:

# 2. Запустить scrcpy для обоих устройств (команды выше)
```

### Работа:

```powershell
# После каждого изменения кода:
.\scripts\dev-workflow.ps1 -Action deploy

# Или только перезапуск (если ничего не меняли в коде):
.\scripts\quick-launch.ps1
```

### Вечер:

```powershell
# Закрыть scrcpy
Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process

# Остановить эмулятор
adb -s emulator-5554 emu kill
```

---

## 💡 Важные заметки:

### Package Names:

- **ChildWatch:** `ru.example.childwatch`
- **ParentWatch (debug):** `ru.example.parentwatch.debug` ⚠️ Не забывайте `.debug`!

### APK Files:

- **ChildWatch:** `app/build/outputs/apk/debug/ChildWatch-v5.5.0-debug.apk`
- **ParentWatch:** `parentwatch/build/outputs/apk/debug/ChildDevice-v6.3.0-debug.apk` ⚠️ Странное название!

### Activity Names:

- **ChildWatch:** `ru.example.childwatch.MainActivity`
- **ParentWatch:** `ru.example.parentwatch.MainActivity`

---

## 🚀 Проверка установки:

```powershell
# Проверить устройства
adb devices

# Проверить установленные приложения на эмуляторе
adb -s emulator-5554 shell pm list packages | Select-String "child"

# Проверить установленные приложения на Nokia
adb -s PT19655KA1280800674 shell pm list packages | Select-String "parent"
```

---

## ✅ Сейчас у вас:

- ✅ Оба устройства подключены
- ✅ ChildWatch v5.5.0 установлен на эмуляторе
- ✅ ParentWatch v6.3.0 установлен на Nokia
- ✅ Оба приложения запущены
- ✅ Два окна scrcpy открыты

**Теперь всё работает правильно!** 🎉
