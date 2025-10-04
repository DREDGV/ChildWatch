@echo off
echo Предоставляем все разрешения для ChildWatch...

adb shell pm grant ru.example.childwatch.debug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ru.example.childwatch.debug android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ru.example.childwatch.debug android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant ru.example.childwatch.debug android.permission.RECORD_AUDIO
adb shell pm grant ru.example.childwatch.debug android.permission.CAMERA

echo Все разрешения предоставлены!
echo Запускаем приложение...

adb shell monkey -p ru.example.childwatch.debug -c android.intent.category.LAUNCHER 1

echo Готово!
