# ⚡ ШПАРГАЛКА: 3 команды на каждый день

> **Архивная шпаргалка.** Серийники, имена APK и команды Gradle ниже относятся к старым устройствам и сборкам. Актуальные задачи и доказательства — в [TODO.md](TODO.md), состояние — в [TRACKING.md](TRACKING.md), правила сборки и установки — в [AGENTS.md](AGENTS.md). Для установки используйте `scripts/build-and-install.ps1`; его зависание после сборки описано в TODO вместе с проверенным обходом. Не применяйте команды ниже к действующим семейным телефонам без сверки серийника и установленной версии.

## 🌅 УТРО (1 раз):

```powershell
# 1. Запустить эмулятор (если не запущен)
Start-Process -FilePath "C:\Users\dr-ed\AppData\Local\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd Pixel_8_API_35"

# Подождать 30 секунд, затем:

# 2. Запустить scrcpy для Nokia (ChildDevice - устройство ребенка)
Start-Process scrcpy -ArgumentList "--serial PT19655KA1280800674 --max-size 1024 --video-bit-rate 2M --window-title 'ChildDevice (Nokia)' --window-x 0"

# 3. Запустить scrcpy для Pixel 8 (ParentMonitor - телефон родителя)
Start-Process scrcpy -ArgumentList "--serial emulator-5554 --max-size 1024 --video-bit-rate 2M --window-title 'ParentMonitor (Pixel 8)' --window-x 600"
```

**Готово!** Теперь видите 2 окна: 
- Nokia слева = **ChildDevice** (телефон ребенка)
- Pixel 8 справа = **ParentMonitor** (телефон родителя)

---

## 💻 РАБОТА (после каждого изменения кода):

```powershell
# Вариант 1: Автоматический (САМЫЙ ПРОСТОЙ)
.\scripts\dev-workflow.ps1 -Action deploy

# Вариант 2: Только для ParentMonitor (эмулятор Pixel 8 - РОДИТЕЛЬ)
.\gradlew.bat :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/ParentMonitor-v6.4.0-debug.apk
adb -s emulator-5554 shell am start -n ru.example.childwatch/ru.example.childwatch.MainActivity

# Вариант 3: Только для ChildDevice (Nokia - РЕБЕНОК)
.\gradlew.bat :parentwatch:assembleDebug
adb -s PT19655KA1280800674 install -r parentwatch/build/outputs/apk/debug/ChildDevice-v5.4.0-debug.apk
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
