# Системный помощник: изолированная проверка после перезагрузки

05.10.2026. CW-01/FIELD-REPEAT. Проверяется допуск Android к capture, а не доставка родителю.

## Результат

На отдельном Android 14 AVD `ChildWatchHomeTest` (`emulator-5582`) выбран debug FamilyAssistantService. После настоящего reboot Android сам связал службу; обычный NexusLauncher сохранился. MainActivity/HomeRecoveryProbeActivity после reboot отсутствуют в снятых activity states, окно помощника после reboot не вызывалось. Нет Device/Profile Owner, используется фиктивный isolated-home-boot-probe с localhost:9.

`assistant-ready.json`: uptimeMs=27488, systemSelected=true, appVisible=false. `voice-after-boot.txt`: mBound=true, mService непустой. Системный клиент binding — system/1000; FamilyAssistantService имеет mIsAllowedBgActivityStartsByBinding=true. У тестовой capture-службы mAllowWhileInUsePermissionInFgsReason=ACTIVITY_STARTER; это точное название причины Android в данном прогоне, а не утверждение о запущенной Activity. Для неё tempAllowListReason=null, types=000000C0. Состояния Activity и power сняты отдельно: только штатный Home, mWakefulness=Asleep.

Конечная проверка в обычном процессе приложения: uptimeMs=52776, assistantActive=true, deviceOwner=false, visibleBefore/After=false, audioBytes=960, audioSilenced=false, jpegBytes=24143, pass=true. Микрофон освобождён перед фото. JPEG извлечён через exec-out/run-as, размер и сигнатура проверены, изображение визуально просмотрено: настоящая тестовая сцена эмулируемой камеры. Это не утверждение о слышимом человеческом голосе в эмуляторе.

Артефакты: `.runtime/assistant-boot-lab/` — result.json, ready-after-boot.json, voice-after-boot.txt, services-during-capture.txt, power-during-capture.txt, activities-after-boot.txt, activities-after-capture.txt, services-after-capture.txt, camera.jpg, assistant-ui.png и logcat.txt. Capture-служба после проверки отсутствует; assistant_lab.captureEnabled=false подтверждён чтением prefs. Другие телефоны/их роли, сервер, OTA не изменялись.

## Исходники и сборка

Только debug source set: FamilyAssistantService/Session, FamilyRecognitionService, AssistantCaptureProbeService, AndroidManifest.xml, family_assistant.xml; isolated setup-флаг в HomeBackgroundCaptureProbe.kt. Семейная панель открывается по явному вызову, содержит переходы к существующим чату/главному экрану и закрытие; её фактический рендер просмотрен. Распознавание делегируется установленному системному speech engine только по запросу; речевой ввод и распознавание команды отдельно не испытаны. Компоненты выключены по умолчанию, capture дополнительно требует isolated emulator/фиктивную сессию/отсутствие DPC.

Контроллер: scripts/test-assistant-boot-probe.py, строгий serial/AVD guard. Python AST, XML parse, targeted git diff --check PASS. Сборка и установка через build-and-install.ps1: 72с BUILD SUCCESSFUL, установка только emulator-5582. APK `parentwatch/build/outputs/apk/debug/ChildDevice-v7.4.284-debug.apk`, versionCode=2000000284, SHA256=B2A0F0A8E452C02C94169EF2CBF253CFF395DB149117BA96C000D2B4F9EB747B. Это лабораторный APK, не опубликованный выпуск.

Препятствия проверки: роль первоначально отклонена из-за отсутствующего recognitionService; требование подтверждено локальным SDK VoiceInteractionServiceInfo и выполнено настоящим адаптером, а не пустой службой. Первая сборка адаптера выявила недоступную SDK-константу разрешения; исправлено строковым Android permission name. Первый reboot-контроллер прочёл маркер до onReady; исправлено ограниченным ожиданием, затем проведён новый reboot. Во втором прогоне gate приложения PASS, но контроллер завершился с ошибкой копирования JPEG в /data/local/tmp (доступ own UID). Извлечение отдельно успешно выполнено через exec-out/run-as; контроллер исправлен тем же способом. Полный контроллер после этой последней правки повторно не запускался; дополнительный reboot ради выгрузки файла не требовался.

## Границы и следующий шаг

Открыты: физический Moto/OEM и версии Android, экран с PIN/первый unlock, реальные родительские команды и их доставка, штатные AudioStreamRecorder/PhotoCaptureService, длительная работа/повторные запросы, конкурентные audio/photo. Основная проблема CW-01 остаётся открытой. Эксперимент не меняет production capture-guard.

Следующий предлагаемый этап: подключить подтверждённый допуск к штатным обработчикам audio/photo в лабораторном контуре с конечными запросами и остановкой. Затем отдельное согласованное испытание на запасном Moto с явным выбором помощника владельцем и обратимым возвращением прежнего помощника, сохраняя Motorola Home. До этого не менять assistant на физических телефонах.
