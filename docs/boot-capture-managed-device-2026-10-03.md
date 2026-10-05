# Восстановление всех функций после перезагрузки

03.10.2026. Владелец выбрал проверку на запасном телефоне; очистка/смена управления ещё не разрешены. Этот документ не означает исправление или готовность выпуска.

## Подтверждённая причина

Moto ZY227XB2QD, Android 11, ChildDevice 7.4.279-debug. После reboot LocationService работает в foreground, но Android выставляет `allowWhileInUsePermissionInFgs=false`. В журнале ActivityManager запрещает доступ к location/camera/microphone фоновой службе; захват звука заканчивается `AUDIO init failed`. Выданное RECORD_AUDIO и appops allow не снимают этот запрет.

После обычного открытия приложения, без переключения мониторинга, тот же ServiceRecord получает `allowWhileInUsePermissionInFgs=true`. В 21:53:37 происходят `retry_audio_after_foreground` и `AUDIO init OK`. Владелец подтвердил появление звука. Доказательства в `.runtime/audio-reboot-child-*` и `.runtime/audio-reboot-open-child-*`; они содержат частные журналы и не предназначены для публикации.

## Системный путь для проверки

Android официально предусматривает исключение для службы, которую запускает контроллер в режиме **device owner**. Обычный device administrator, рабочий профиль и отключение оптимизации батареи не равны этому исключению. FCM, WorkManager и повтор BOOT_COMPLETED сами по себе не дают микрофону доступа.

Предлагаемый отдельный режим ChildDevice — управление устройством с явной настройкой владельцем телефона. Службы должны получать право через настоящий DevicePolicyManager.isDeviceOwnerApp, а не через сохранённую галочку. Разрешения CAMERA/RECORD_AUDIO остаются необходимыми, работа камеры и микрофона отображается системными уведомлениями; права управления не означают непрерывную запись.

На текущем Moto уже есть Profile Owner Google Family Link. ChildWatch не является Device Owner. Нельзя автоматически снять Family Link, удалить аккаунты или назначить нового владельца. Штатное первоначальное provisioning происходит при настройке устройства; переход используемого телефона может требовать сброса и резервного копирования. Без отдельного решения владельца такие действия запрещены.

## Конкретный проверочный этап

1. Предпочтительно отдельный тестовый телефон или эмулятор с чистой настройкой. Существующий Moto сохраняет данные и Family Link.
2. Добавить минимальный DPC/provisioning ChildDevice: DeviceAdminReceiver, метаданные политик и обработчики актуального provisioning. Не добавлять wipe, изменение PIN или удаление аккаунтов как автоматические операции. Android 12+ требует отдельные handlers provisioning mode/compliance.
3. Связать запуск LocationService/AudioStreamingService/PhotoCaptureService с фактическим режимом устройства. Проверить запуск нужных типов FGS из boot/recovery на каждой поддерживаемой версии Android, включая ограничения Android 14/15. Не считать device-owner статус доказательством успешной съёмки.
4. Проверить reboot → первое разблокирование без открытия ChildDevice → свежая карта/чат → новый запрос звука → реальный поток → фото с работающим звуком → доставленный снимок. Проверить отдельно экран блокировки, потерю сети, рестарт процесса/сервера и явную остановку мониторинга.
5. Работа до первого разблокирования — отдельная граница: сейчас сессия/Room в credential-encrypted storage. Не переносить токены и личные данные в менее защищённое хранилище ради обещания «всегда». Проектировать Direct Boot отдельно, если этот сценарий требуется; device owner не расшифровывает пользовательское хранилище.

Приёмка — реальные результаты всех функций после reboot на выбранных версиях ОС, без открытия приложения и без ПК. Одна проверка AudioRecord, наличие службы или успешная компиляция не закрывают требование. Если системное ограничение конкретной ОС остаётся, указывать его прямо, не подменять результат сообщением или постоянно перезапускающейся службой.

## Уже доступная проверка пригодности

`scripts/inspect-boot-capture-readiness.ps1 -Serial ZY227XB2QD` читает режим управления, версию ОС, разрешения и состояние служб. Скрипт не меняет настройки телефона, не запускает микрофон/камеру и не выполняет provisioning. JSON сохраняется только в `.runtime`. Это диагностика, а не реализованное восстановление.

## Первичные источники

- [Ограничения FGS и исключения while-in-use](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- [Камера и микрофон Android 11](https://developer.android.com/about/versions/11/privacy/foreground-services).
- [Provisioning управляемого устройства](https://source.android.com/docs/devices/admin/provision).
- [Direct Boot и защищённое хранилище](https://developer.android.com/privacy-and-security/direct-boot).

Очередь, актуальный статус и следующий шаг находятся только в TODO.md, CW-01/FIELD-REPEAT.

## Подготовленная реализация

В ChildDevice добавлены ManagedDeviceAccess (проверка настоящего device owner через Android), ChildDeviceAdminReceiver и системный ManagedProvisioningActivity (Android 12+). Receiver и activity защищены BIND_DEVICE_ADMIN. Политики wipe/password/lock не запрошены, enrollment не включает мониторинг и не меняет семейную сессию. LocationService/PhotoCaptureService допускают подготовку камеры в фоне при проверенном device owner; обычный режим по-прежнему требует видимого приложения. Для микрофона используется существующий запуск FGS: исключение должен предоставить Android, а не локальная настройка.

Запасной Moto ZY227XB3PV: Android 11, user_setup_complete=1, девять аккаунтов, ChildDevice 7.1.26086.142252-debug. Владелец управления в dumpsys не найден. Это используемый, а не чистый телефон. Сброс/удаление аккаунтов/назначение управления не выполнялись. Проверка готовности на рабочем Moto выполнена read-only: оба разрешения выданы, ChildWatch не device owner, Family Link profile owner; JSON в .runtime.

Компиляция :parentwatch:compileDebugKotlin PASS (1м08с), .runtime/managed-device-compile-20261003.log; provisioning и фоновый захват на управляемом устройстве пока НЕ проверены.

## Проверка на отдельном эмуляторе

Чистый AVD в .runtime/managed-avd, Android 14; userdata реальных AVD не менялись. Установка ChildDevice 7.4.280 через build-and-install -InstallOnly с явным serial emulator-5580 PASS, Android dpm подтвердил настоящего device owner. Отдельно выданы тестовые разрешения только на эмуляторе.

ManagedBootCaptureTest.prepareFixture PASS. После настоящей перезагрузки без запуска Activity и **до запуска проверочной instrumentation** LocationService восстановилась из boot: createdFromFg=false, mAllowWhileInUsePermissionInFgsReason=DEVICE_OWNER, FGS types 0x48 (location/camera). Snapshot: .runtime/managed-boot-services-later.txt. Это доказательство системного исключения независимо от привилегий instrumentation.

ManagedBootCaptureTest.captureAfterBoot PASS, 5.892с: существующий мониторинг активен, после команды аудио AudioRecord запущен, read возвращает кадры и isClientSilenced не true; CameraService выдаёт непустой JPEG; Activity остаётся закрытой. Проверочная instrumentation может влиять на привилегии процесса: отдельно сохранённый boot snapshot обязателен при трактовке результата. Тест намеренно блокирует запуск на физических телефонах.

Граница доказательства: эмулятор без host audio, тестовый server localhost:9 не обслуживается. Не проверены слышимость, родительская доставка, живые команды сервера, одновременность фото/звука, Android 11 Moto и другие ОС. Device-owner путь подтверждён на уровне платформенного восстановления и capture probe, исходный пользовательский критерий всех функций на реальном телефоне остаётся открытым. Нужна согласованная подготовка запасного Moto; ни один аккаунт/данные телефона не удалены.
