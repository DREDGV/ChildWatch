@echo off
echo 🔄 Быстрая сборка и установка ChildWatch...
echo.

echo 📦 Сборка проекта...
call gradlew assembleDebug
if %errorlevel% neq 0 (
    echo ❌ Ошибка сборки!
    pause
    exit /b 1
)

echo 📱 Установка на устройство...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo ❌ Ошибка установки! Проверьте подключение устройства.
    pause
    exit /b 1
)

echo 🚀 Запуск приложения...
adb shell monkey -p ru.example.childwatch -c android.intent.category.LAUNCHER 1

echo ✅ Готово! Приложение установлено и запущено.
pause
