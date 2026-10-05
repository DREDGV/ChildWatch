# Проверка помощника на Test Moto

CW-01/FIELD-REPEAT, 05.10.2026. Владелец разрешил продолжить к реальным телефонам. Проверочный режим debug-only, рабочий стол не меняется, системного помощника выбирает владелец через Android.

Подключены и подтверждены Test Moto ZY227XB3PV и родитель Samsung R5CX71CKKJW. Перед и после обновления собственный /api/me Moto=200/memberships=1, canonical family devices=200/spareBindingCount=1, parentSelectedSpare=true. Доказательства `.runtime/ordinary-bootstrap/assistant-before.json` и `assistant-installed.json`; токены не публикуются.

Изменения: AssistantRecoveryAccess.kt проверяет debug opt-in и фактически выбранную Android VoiceInteractionService; camera eligibility в LocationService/PhotoCaptureService учитывает этот системный допуск. FamilyAssistantService.onReady восстанавливает обычные ChatBackgroundService/LocationService только при monitoring desired и собственной сессии. На физическом телефоне автоматическая capture-проверка исключена. AudioStreamRecorder и серверные семейные разрешения остаются штатными. FamilyRecognitionService допускает выбранный opt-in режим. AssistantPilotControlActivity предоставляет объяснение, системный выбор, возврат/отключение/закрытие; кандидат включается только после кнопки владельца.

Сборка через scripts/build-and-install.ps1, 74с BUILD SUCCESSFUL, установка поверх данных Test Moto 17с PASS. Только ChildDevice 7.4.285-debug, versionCode=2000000285. APK `parentwatch/build/outputs/apk/debug/ChildDevice-v7.4.285-debug.apk`, SHA256=B47CEDF04217930598B0C2BACBD4821F9E2F72F176D733518B8F27D6444E6A1E. XML/diff-check PASS. Реальная отрисовка control screen проверена, `.runtime/assistant-pilot-control.png`.

До системного выбора зафиксированы прежние Google GsaVoiceInteractionService и Motorola CustomizationPanelLauncher. Agent открыл только control Activity; роль не назначал. Отправлен запрос владельцу нажать «Включить и выбрать помощника» и подтвердить системный выбор. Пока ожидается этот шаг, reboot/capture на телефоне не выполнялись. Сервер/OTA/родительский APK/настоящий детский аппарат не изменены.

Продолжить после выбора: подтвердить own opt-in, выбранную службу и прежний Home; сохранить исходные состояния; reboot только Test Moto, обычный unlock без ручного открытия ChildWatch; затем no Main/роль/службы/причины Android, штатные запросы Samsung на выбранный Test Moto и реальная доставка. Положительный эмуляторный gate не закрывает эту проверку.

## Исправление системного выбора

Владелец сообщил, что кнопка выбора не дала видимого результата, а закрытие работает. Чтение own prefs подтвердило enabled=true, PackageManager показывает FamilyAssistantService; значит локальная кнопка выполнилась, но запрос RoleManager не показал chooser. Google остаётся выбранным. Проверен рабочий путь на настоящем Moto: ACTION_VOICE_INPUT_SETTINGS → «Цифровой ассистент» → «Цифровой ассистент по умолчанию». В списке наша кандидатура отображается **ChildDevice** (package label), а не service label «Семейный помощник ChildWatch». Список открыт владельцу без назначения роли. Кнопка исправлена на Settings intent; повторно запросили фактический выбор ChildDevice. Capture/reboot до выбора исключены. Подготовлена сборка 286 только для Test Moto; точный результат установки записывается в TODO.

## Реальный выбор и начало reboot-проверки

286 установлена поверх 285: build75с PASS, install16с PASS, targeted diff-check PASS. APK SHA256=83003C790ADD7829F82787CA4558872743C9AA2E87BD798147F333B63AF6561C. Владелец подтвердил «выбрал», Android secure voice_interaction_service показывает FamilyAssistantService, own enabled=true; Motorola Home сохранён. До reboot собственная авторизация/каноническая привязка повторно подтверждены 200/membership1/binding1/parentSelectedSpare=true, `.runtime/ordinary-bootstrap/assistant-selected.json` (первый запрос без эскалации получил сетевой отказ; разрешённый сетевой повтор успешен). Состояния сохранены `.runtime/assistant-phone-reboot/services-before.txt`, voice-before.txt. Выполнен reboot только запасного Moto. Ожидается обычный unlock владельцем без запуска ChildWatch; пока USB Moto не вернулся, post-boot/runtime capture доказательств нет. Это не отрицательный результат микрофона.

## Результат реального прогона

05.10 владелец после этого reboot подтвердил «протестировал. прослушка работает», затем «фото тоже работает». Обе доставки засчитываются как реальное пользовательское подтверждение на Test Moto 286 и родительском Samsung, отдельно от эмуляторного gate.

После возврата USB сохранены `.runtime/assistant-phone-reboot/{services-after.txt,activities-after.txt,voice-after.txt,ready-after.json,home-after.txt,child-process-log.txt,parent-process-log.txt,confirmation-proof.json}`. onReady uptimeMs=232233, systemSelected=true, appVisible=false; Home resolver Motorola. PhotoCaptureService, ChatBackgroundService, LocationService и FamilyAssistantService имеют allowWhileInUsePermissionInFgs=true. MainActivity отсутствует в снятом activity snapshot; это снимок состояния, не доказательство отсутствия любого краткого открытия за всё время после reboot. Первый достижимый USB уже после unlock; pre-unlock не доказан.

Родительский own-process журнал содержит 2065 упоминаний audio chunk 960 bytes; детский журнал содержит один Photo saved и один PhotoUploadWorker SUCCESS. Нулевой результат узкого поиска child audio-chunk шаблона не означает отсутствия записи: слышимый звук подтверждён владельцем и приём чанков — родителем. На настоящем UI Samsung выбран Test Moto, галерея показывает новый снимок 05 окт. 03:56. Отдельный агентский запрос после подтверждения владельца не выполнялся.

Итог: **реальный assistant-пилот восстановил звук и фото после reboot, сохранив Motorola Home**. Остаются открытыми совместная/повторная/длительная работа audio-photo, граница первого unlock, другие OEM/версии и перенос из debug opt-in в штатную настройку рабочего выпуска. Другие аппараты не обновлялись, сервер/OTA не публиковались. Следующий предлагаемый этап — оформить подтверждённый режим как понятную добровольную настройку с возвратом прежнего помощника и подготовить установку на рабочий детский телефон; не назначать ему assistant автоматически.
