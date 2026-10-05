# ChildDevice 7.4.283 — Home-пилот и recovery авторизации

Debug-only проверочная сборка, package `ru.example.parentwatch.debug`, versionCode 2000000283. Собрана 159с и установлена поверх 282 на запасной Moto ZY227XB3PV 05.10.2026, pm install -r Success 17с. Постоянный сертификат совпадает с 281/282. SHA256 APK в SHA256SUMS.

Сохраняет добровольный Home-пилот из 282. Дополнительно child onboarding после 401 пробует refresh/регистрацию того же устройства и один повтор запроса, проверяя неизменность сервера/own device. Живые NetworkClient читают обновлённый токен из TokenManager. 403 не обходится; ID не меняется; Room/schema без изменений.

На настоящем Moto ограниченная recovery вызвана, но реальное подключение **остаётся заблокировано сервером**: старый ID отозван, регистрация получает 403 DEVICE_REVOKED. Preview приглашения пока FAIL, звуковые кадры/снимок родителю не подтверждены. Сборка не объявляется исправившим все проблемы выпуском и не опубликована OTA.

Первый reboot 282 подтвердил системное открытие Home после обычного unlock и запуск LocationService/PhotoCaptureService/ChatBackgroundService с Android while-in-use доступом. Простое открытие WebSocket не доказывает серверную авторизацию; теперь проверено, что она отозвана. Полный повтор reboot/capture нужен после восстановления доступа и канонической семейной привязки.

[Отчёт](../../docs/boot-capture-home-pilot-2026-10-05.md), [пакет владельцу сервера](../home-pilot-server-restore-2026-10-05/README.md). Статус/продолжение: TODO, CW-01/FIELD-REPEAT.
