# CW-10: полевой отказ «Сигнал», диагностика 08.10.2026

Статус: причина сегодняшнего отказа не установлена. Исходники не исправлялись; звук/вибрация на телефонах не запускались. Установки, сеть, assistant, permissions и VPS не затрагивались.

Уточнение пользователя: родительское приложение показывало ожидание или ошибку; STARTED/«сигнал воспроизводится» не подтверждён. Поэтому сначала нужна цепочка доставки и конечного статуса конкретного запроса; гипотеза «проигрывание началось, но громкость не поднялась» имеющимися сведениями не поддержана.

## Доступная проверка

Read-only `adb devices -l`: список пуст. Зафиксировано `.runtime/signal-field-2026-10-08/devices.txt`. Поэтому детский/родительский журнал и requestId сегодняшнего отказа получить не удалось. Персональные данные/токены/содержимое сообщений не выгружались.

Изолированный командный цикл, cwd `server`:

```text
node node_modules/jest/bin/jest.js --runInBand __tests__/attention-signal.test.js --silent
PASS __tests__/attention-signal.test.js
Test Suites: 1 passed, 1 total
Tests: 12 passed, 12 total
Time: 1.425 s
```

Тесты используют SQLite `:memory:`. Проверяют exact-target START/status, отказ offline, TTL expiry, STOP владельца, дубликаты, чужую семью/отправителя, rate/cooldown и schema bounds. Они способны поймать ошибки этих протокольных веток, но не являются воспроизведением неизвестного полевого сценария и не доказывают слышимость/доставку на реальном телефоне. Общий Gradle не запускался.

## Реальная цепочка

- Parent `attention/ParentAttentionSignalLauncher.kt:204–248`: канонический FeatureTargetResult, собственные requester/device/member и явный адресат; AttentionSignalSheet связывается с WebSocketManager.
- Shared `attention-android/.../AttentionSignalSheet.kt:196–241`: проверка готовности транспорта, новый requestId/createdAt/expiresAt, validation; отправка WS. Успешное возвращение sendRequest означает локальную постановку emit, а не проигрывание ребёнком.
- Server `managers/WebSocketManager.js:637–651`: отдельные attention request/status/stop handlers; это не HTTP команда аудио.
- `managers/AttentionSignalManager.js:281–436`: проверка контракта, expiry, дубликата, SEND_ATTENTION_SIGNAL и family/member; сохранение QUEUED; exact-device emit. Нет соединения адресата — FAILED/TARGET_NOT_CONNECTED. Pending имеет серверный expiry.
- Тот же manager `:440–490`: статусы принимаются только от authenticated адресата данного request. STARTED и DELIVERED отдельны; terminal очищает pending. pending/timers в памяти — тесты не доказывают восстановление через restart сервера.
- Child `service/ChatBackgroundService.kt:220–232`: runtime и START/STOP listeners. `network/WebSocketManager.kt:568–596` очищает chat/photo/command listeners, но attention listener sets НЕ очищает; `:717–722` переподключает callbacks. Прежний установленный дефект photo handler нельзя переносить на сигнал без доказательства.
- Shared `AttentionSignalReceiverCoordinator.kt:19–48`: свой device/TTL/validation, затем DELIVERED до Android playback. Controller `:45–74`: подготовка volume/vibration/sound и callback STARTED либо FAILED. Runtime `:54–56`: notification и отправка STARTED.

## Отдельные исходные ограничения — не диагноз полевого отказа

1. Sheet `:226–241` отключает Send после emit, но своего request deadline/возврата ошибки при потере статусов нет. Полный поиск данного файла не нашёл Handler/postDelayed/timeout. Сервер истекает только принятую им команду; если запрос/ответ теряются или sender отключён, UI может остаться на «Отправка…». Малый будущий объём: конечное локальное ожидание по requestId + честное «результат не подтверждён». Это исправляет зависший интерфейс, не доставку/звук. Не запускать реальный повтор автоматически по такому таймауту.
2. Отправка/STOP идут только по готовому WebSocket; нет HTTP/poll fallback для attention. Перенос audio/photo fallback без срока/идемпотентности был бы опасным расширением. Сейчас не внедрялся.
3. Child main и найденные debug/release merged manifests не содержат MODIFY_AUDIO_SETTINGS, тогда как parent содержит. Однако отсутствие этого permission **не доказано дефектом данного вызова**: проверка Android 14 AOSP ниже не обнаружила безусловного запрета setStreamVolume(STREAM_ALARM) без него. Manifest не менялся. Фактическая громкость, OS-ограничения, маршрутизация и доступность системной мелодии не измерялись.

## Проверка конкретного Android API, а не общего имени permission

Controller `:58,125–129` вызывает `audioManager.setStreamVolume(AudioManager.STREAM_ALARM, value, 0)` до вибрации/звука и STARTED. `start :54–73` охватывает вызов `catch(Throwable)`: cleanup, active=null и callback.onFailed с PLAYBACK_FAILED. Восстановление предыдущей громкости `:132–138` выполняется через runCatching. Таким образом, исключение этого вызова должно попасть в FAILED, а не нормальный STARTED; фактическое прохождение ветки в поле неизвестно.

[Документация AudioManager.setStreamVolume](https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,int,int)) описывает fixed-volume no-op и ограничение изменения DND, но не безусловное требование MODIFY_AUDIO_SETTINGS для метода. В [AOSP Android 14.0.0_r1 AudioService.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/audio/AudioService.java#L3818-L3849) setStreamVolumeWithAttributionInt отдельно ограничивает ACCESSIBILITY/VOICE_CALL/ASSISTANT; для ALARM передаёт результат callingOrSelfHasAudioSettingsPermission как boolean, без отказа при false. Внутренний setStreamVolume `:4125–4235` проверяет AppOps/DND и передаёт boolean дальше; getValidIndex `:8222–8230` использует его для минимального допустимого индекса, а не бросает исключение при отсутствии permission. Значит добавление permission только на основании отсутствия в manifest сейчас не обосновано. Это проверка базового AOSP API 34, не доказательство поведения конкретной OEM-прошивки или причины сегодняшнего отказа.

## Точный шаг продолжения

Получить время неудачи, выбранного человека и последнее сообщение/статус экрана; подключить оба телефона без повторного запуска сигнала. Сохранить только релевантные журналы/стадии для одного requestId: parent emit→server authorize/exact emit→child DELIVERED→controller STARTED/FAILED→parent status. Если история уже вытеснена, после явного разрешения провести один контролируемый запрос на согласованном адресате с короткой длительностью/обычной громкостью и отдельным STOP; сохранить временную шкалу сразу. До доступного red-capable воспроизведения не объявлять конкретную source слабость причиной и не публиковать «исправленный сигнал».
