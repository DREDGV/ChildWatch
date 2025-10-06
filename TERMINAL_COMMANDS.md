# Шпаргалка по командам терминала для ChildWatch

## 📱 Управление эмуляторами

### Просмотр доступных эмуляторов
```bash
emulator -list-avds
```

### Запуск эмулятора
```bash
# Запуск конкретного эмулятора
emulator -avd Pixel_8_API_35 &

# Запуск без сохранения состояния (чистый старт)
emulator -avd Pixel_8_API_35 -no-snapshot-load &

# Запуск двух эмуляторов одновременно
emulator -avd Pixel_8_API_35 -no-snapshot-load & sleep 3 && emulator -avd Medium_Phone_API_35 -no-snapshot-load &
```

### Остановка эмулятора
```bash
# Остановка всех эмуляторов
adb -s emulator-5554 emu kill
adb -s emulator-5556 emu kill
```

## 📲 Работа с ADB (Android Debug Bridge)

### Просмотр подключенных устройств
```bash
adb devices
```

### Установка приложений
```bash
# Установка ParentWatch на emulator-5554
adb -s emulator-5554 install -r parentwatch/build/outputs/apk/debug/ParentWatch-v3.1.0-debug.apk

# Установка ChildWatch на emulator-5556
adb -s emulator-5556 install -r app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk

# Установка на реальное устройство (замените DEVICE_ID на ваш ID)
adb -s DEVICE_ID install -r app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk
```

### Удаление приложений
```bash
# Удаление ParentWatch
adb -s emulator-5554 uninstall ru.example.parentwatch.debug

# Удаление ChildWatch
adb -s emulator-5556 uninstall ru.example.childwatch.debug
```

### Предоставление разрешений
```bash
# Разрешения для ParentWatch
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.RECORD_AUDIO
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.ACCESS_COARSE_LOCATION
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.POST_NOTIFICATIONS

# Разрешения для ChildWatch
adb -s emulator-5556 shell pm grant ru.example.childwatch.debug android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5556 shell pm grant ru.example.childwatch.debug android.permission.ACCESS_COARSE_LOCATION
adb -s emulator-5556 shell pm grant ru.example.childwatch.debug android.permission.POST_NOTIFICATIONS
```

### Запуск приложений
```bash
# Запуск ParentWatch
adb -s emulator-5554 shell monkey -p ru.example.parentwatch.debug 1

# Запуск ChildWatch
adb -s emulator-5556 shell monkey -p ru.example.childwatch.debug 1
```

### Очистка данных приложения
```bash
# Очистка данных ParentWatch (сброс к начальному состоянию)
adb -s emulator-5554 shell pm clear ru.example.parentwatch.debug

# Очистка данных ChildWatch
adb -s emulator-5556 shell pm clear ru.example.childwatch.debug
```

## 📝 Просмотр логов (Logcat)

### Просмотр всех логов
```bash
# Все логи от ParentWatch
adb -s emulator-5554 logcat

# Все логи от ChildWatch
adb -s emulator-5556 logcat
```

### Фильтрация логов по тегам
```bash
# Логи ParentWatch (только важные компоненты)
adb -s emulator-5554 logcat AudioStreamRecorder:D LocationService:D NetworkHelper:D MainActivity:D *:S

# Логи ChildWatch (только важные компоненты)
adb -s emulator-5556 logcat AudioStreamingActivity:D NetworkClient:D MainActivity:D *:S
```

### Очистка логов
```bash
# Очистка логов перед тестом
adb -s emulator-5554 logcat -c
adb -s emulator-5556 logcat -c
```

### Сохранение логов в файл
```bash
# Сохранить логи ParentWatch в файл
adb -s emulator-5554 logcat > parentwatch_logs.txt

# Сохранить логи с временной меткой
adb -s emulator-5554 logcat -v time > parentwatch_logs_$(date +%Y%m%d_%H%M%S).txt
```

## 🔨 Сборка проекта

### Сборка debug APK
```bash
# Сборка обоих приложений
gradlew.bat assembleDebug

# Только ParentWatch
gradlew.bat :parentwatch:assembleDebug

# Только ChildWatch
gradlew.bat :app:assembleDebug
```

### Очистка проекта
```bash
# Полная очистка
gradlew.bat clean

# Очистка и пересборка
gradlew.bat clean assembleDebug
```

## 🌐 Работа с сервером

### Запуск локального сервера
```bash
# Запуск в режиме разработки
cd server
npm run dev

# Запуск в фоновом режиме (Windows)
start /B npm run dev

# Остановка сервера (найти процесс и убить)
netstat -ano | findstr :3000
taskkill /PID [номер_процесса] /F
```

### Проверка работы сервера
```bash
# Проверка здоровья сервера
curl http://localhost:3000/api/health

# Проверка Railway сервера
curl https://childwatch-production.up.railway.app/api/health
```

## 🔧 Управление файлами на устройстве

### Просмотр файлов на устройстве
```bash
# Список файлов приложения
adb -s emulator-5554 shell ls /data/data/ru.example.parentwatch.debug/

# Просмотр SharedPreferences
adb -s emulator-5554 shell cat /data/data/ru.example.parentwatch.debug/shared_prefs/app_prefs.xml
```

### Копирование файлов
```bash
# Скачать файл с устройства
adb -s emulator-5554 pull /sdcard/Download/file.txt ./

# Загрузить файл на устройство
adb -s emulator-5554 push ./file.txt /sdcard/Download/
```

## 📸 Скриншоты и запись экрана

### Создание скриншота
```bash
# Скриншот ParentWatch
adb -s emulator-5554 shell screencap /sdcard/screenshot.png
adb -s emulator-5554 pull /sdcard/screenshot.png ./parentwatch_screenshot.png

# Быстрый скриншот
adb -s emulator-5554 exec-out screencap -p > screenshot.png
```

### Запись экрана
```bash
# Начать запись (максимум 3 минуты)
adb -s emulator-5554 shell screenrecord /sdcard/demo.mp4

# Остановить запись: Ctrl+C
# Скачать видео
adb -s emulator-5554 pull /sdcard/demo.mp4 ./
```

## 🐛 Отладка

### Проверка состояния устройства
```bash
# Информация об устройстве
adb -s emulator-5554 shell getprop

# Версия Android
adb -s emulator-5554 shell getprop ro.build.version.release

# Модель устройства
adb -s emulator-5554 shell getprop ro.product.model
```

### Перезагрузка устройства
```bash
# Перезагрузка эмулятора
adb -s emulator-5554 reboot
```

## ⚡ Полезные комбинации команд

### Полный цикл тестирования с нуля
```bash
# 1. Запуск эмуляторов
emulator -avd Pixel_8_API_35 -no-snapshot-load & sleep 3 && emulator -avd Medium_Phone_API_35 -no-snapshot-load &

# 2. Ждем загрузки (30 сек)
sleep 30

# 3. Установка приложений
adb -s emulator-5554 install -r parentwatch/build/outputs/apk/debug/ParentWatch-v3.1.0-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk

# 4. Предоставление разрешений
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.RECORD_AUDIO
adb -s emulator-5554 shell pm grant ru.example.parentwatch.debug android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5556 shell pm grant ru.example.childwatch.debug android.permission.ACCESS_FINE_LOCATION

# 5. Очистка и запуск логов
adb -s emulator-5554 logcat -c
adb -s emulator-5556 logcat -c

# 6. Запуск приложений
adb -s emulator-5554 shell monkey -p ru.example.parentwatch.debug 1
adb -s emulator-5556 shell monkey -p ru.example.childwatch.debug 1
```

### Быстрая пересборка и переустановка
```bash
# Пересборка
gradlew.bat assembleDebug

# Переустановка на устройства
adb -s emulator-5554 install -r parentwatch/build/outputs/apk/debug/ParentWatch-v3.1.0-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk
```

## 📋 Git команды для проекта

### Основные операции
```bash
# Проверка статуса
git status

# Добавить все изменения
git add .

# Коммит
git commit -m "Описание изменений"

# Отправка на GitHub
git push origin main

# Получение изменений
git pull origin main

# Просмотр истории
git log --oneline -10
```

## 🔍 Поиск в проекте

### Поиск текста в файлах
```bash
# Поиск в Kotlin файлах
grep -r "AudioStreamRecorder" --include="*.kt" .

# Поиск с номерами строк
grep -rn "Device ID" --include="*.kt" parentwatch/

# Поиск в XML файлах
grep -r "proslushka" --include="*.xml" app/
```

---

## 💡 Советы

1. **Замените `emulator-5554` и `emulator-5556`** на ID ваших устройств из `adb devices`
2. **Для реальных устройств** используйте их серийный номер вместо `emulator-XXXX`
3. **Всегда проверяйте `adb devices`** перед выполнением команд
4. **Используйте `-r` флаг** при установке для переустановки без удаления данных
5. **Логи в реальном времени:** добавьте `| grep "ERROR"` для фильтрации ошибок

## 🆘 Решение проблем

### Эмулятор не запускается
```bash
# Убить все процессы эмулятора
taskkill /F /IM qemu-system-x86_64.exe

# Перезапустить ADB
adb kill-server
adb start-server
```

### Устройство не отвечает
```bash
# Перезапуск ADB сервера
adb kill-server
adb start-server
adb devices
```

### Приложение не устанавливается
```bash
# Удалить старую версию
adb uninstall ru.example.childwatch.debug

# Очистить кеш Gradle
gradlew.bat clean

# Пересборка и установка
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/ChildWatch-v3.1.0-debug.apk
```
