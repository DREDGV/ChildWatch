@echo off
echo 🔄 Быстрая сборка и установка ChildDevice...
echo.

echo 📦 Сборка проекта...
call gradlew assembleDebug
if %errorlevel% neq 0 (
    echo ❌ Ошибка сборки!
    pause
    exit /b 1
)

echo 📱 Установка на устройство...
adb install -r parentwatch\build\outputs\apk\debug\ChildDevice-v5.0.0-debug.apk
if %errorlevel% neq 0 (
    echo ❌ Ошибка установки! Проверьте подключение устройства.
    pause
    exit /b 1
)

echo 🚀 Запуск приложения...
adb shell monkey -p ru.example.parentwatch -c android.intent.category.LAUNCHER 1

echo ✅ Готово! ChildDevice установлен и запущен.
pause
