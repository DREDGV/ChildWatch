# ⚡ ШПАРГАЛКА: 3 команды на каждый день

## 🌅 УТРО (1 раз):

```powershell
# 1. Запустить эмулятор (если не запущен)
Start-Process -FilePath "C:\Users\dr-ed\AppData\Local\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd Pixel_8_API_35"

# Подождать 30 секунд, затем:

# 2. Запустить scrcpy для Nokia
Start-Process scrcpy -ArgumentList "--serial PT19655KA1280800674 --max-size 1024 --video-bit-rate 2M --window-title 'ParentWatch' --window-x 0"

# 3. Запустить scrcpy для Pixel 8
Start-Process scrcpy -ArgumentList "--serial emulator-5554 --max-size 1024 --video-bit-rate 2M --window-title 'ChildWatch' --window-x 600"
```

**Готово!** Теперь видите 2 окна: Nokia слева, Pixel 8 справа.

---

## 💻 РАБОТА (после каждого изменения кода):

```powershell
# Вариант 1: Автоматический (САМЫЙ ПРОСТОЙ)
.\scripts\dev-workflow.ps1 -Action deploy

# Вариант 2: Только для ChildWatch (эмулятор)
.\gradlew.bat :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/ChildWatch-v5.5.0-debug.apk
adb -s emulator-5554 shell am start -n ru.example.childwatch/ru.example.childwatch.MainActivity

# Вариант 3: Только для ParentWatch (Nokia)
.\gradlew.bat :parentwatch:assembleDebug
adb -s PT19655KA1280800674 install -r parentwatch/build/outputs/apk/debug/ChildDevice-v6.3.0-debug.apk
adb -s PT19655KA1280800674 shell am start -n ru.example.parentwatch.debug/ru.example.parentwatch.MainActivity

# Вариант 4: Быстрый перезапуск (если уже установлено)
.\scripts\quick-launch.ps1
```

**Ждите 20-30 секунд и смотрите в окна scrcpy!**

---

## 🌙 ВЕЧЕР:

```powershell
# Закрыть все scrcpy
Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process

# Остановить эмулятор (опционально)
adb -s emulator-5554 emu kill
```

---

## 🎯 ВСЁ!

Вот и всё, что нужно знать!

**3 этапа:**

1. Утром → запустить окна
2. Работа → deploy после изменений
3. Вечер → закрыть

**Live preview = вы видите экран устройств в реальном времени через scrcpy!**
