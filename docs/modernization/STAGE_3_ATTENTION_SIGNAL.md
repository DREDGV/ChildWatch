# Этап 3: адресный «Сигнал внимания»

Дата автоматической проверки: 2026-07-17
Ветка: `feat/attention-signal`
Базовый SHA: `fd38252`
Серверный SHA: `3a3fbcd`
Android receiver SHA: `d1e7dd2`

## Результат

Реализован отдельный защищённый канал для краткого сигнала конкретному устройству семьи. Отправитель выбирает:

- длительность 5/10/15/30/60 секунд;
- громкость 0–100%;
- встроенный звук ATTENTION/RINGTONE/ALARM/SIREN;
- вибрацию и ритм PULSE/URGENT/SOS.

Интерфейс показывает реальные серверные статусы `QUEUED`, `DELIVERED`, `STARTED`, `COMPLETED`, `STOPPED`, `REJECTED`, `FAILED`, `EXPIRED` и позволяет удалённо остановить активный сигнал.

Доступ добавлен:

- на главный экран ParentMonitor;
- в строку конкретного ребёнка в списке участников;
- на карту выбранного участника;
- на главный экран ChildDevice как «Позвать родителя»;
- на карту родителя в ChildDevice.

## Сервер и безопасность

Сервер использует только отдельные Socket.IO-события:

```text
attention_signal_request
attention_signal_start
attention_signal_stop_request
attention_signal_stop
attention_signal_status
```

Generic `command` для функции не используется. Перед отправкой сервер проверяет:

- аутентифицированное фактическое устройство отправителя;
- совпадение заявленного `requesterDeviceId`;
- одну активную семью и разрешение `SEND_ATTENTION_SIGNAL`;
- точный `targetDeviceId`, family/member ID и владельца команды Stop;
- строгий список полей и границы значений;
- TTL по умолчанию 30 секунд и максимум 120 секунд;
- cooldown цели 5 секунд;
- лимит 10 принятых запросов в минуту от одного устройства;
- отсутствие повторного `requestId`.

Офлайн-сигнал не помещается в бессрочную очередь. События и терминальные статусы сохраняются в `attention_signals`; pending-состояние очищается по завершению или TTL.

## Android receiver

Оба приложения принимают сигнал через существующий устойчивый `ChatBackgroundService` и общий модуль `attention-android`.

- цель и TTL повторно проверяются на устройстве;
- `DELIVERED` отправляется до запуска Android playback;
- `STARTED` — только после фактического запуска контроллера;
- используется только системный ringtone/alarm/notification URI, без URL и произвольных файлов;
- устанавливается временная alarm-громкость и затем восстанавливается;
- DND не переключается;
- full-screen intent не используется;
- звук, вибрация и wake lock ограничены длительностью сигнала;
- ongoing-уведомление содержит действие «Остановить»;
- доступны local и remote stop;
- второй сигнал останавливает первый со статусом `STOPPED/REPLACED`;
- поздний callback старого MediaPlayer не может остановить уже заменивший его сигнал;
- недоставленные статусы кратковременно сохраняются до восстановления зарегистрированного WebSocket.

Проверка цели, TTL и порядок статусов вынесены в платформонезависимый `AttentionSignalReceiverCoordinator`, поэтому критическая часть проверяется JVM-тестами без телефона.

## Автоматические проверки

### Server

Команда: `npm test -- --runInBand`

- 10 test suites passed;
- 50 tests passed;
- 0 failed.

Attention-тесты проверяют exact target, чужой статус, offline, expiry, stop, duplicate request ID, cross-family, authentication, cooldown, строгие границы schema, лимит 10/мин и запрет Stop не владельцем.

### Android и shared-core

Команда:

```text
gradlew.bat :shared-core:test :app:testDebugUnitTest :parentwatch:testDebugUnitTest :app:assembleDebug :parentwatch:assembleDebug utf8Guard --no-daemon --no-parallel --max-workers=1
```

Результат: `BUILD SUCCESSFUL`.

- shared-core: 4 suites, 18 tests, 0 ошибок;
- `ParentMonitor-v7.2.26198.233514-debug.apk`
  SHA-256: `6B88E3480908B73350F9F41FA81256C44BD87F0595268027DDFB992A0C2BD0CD`;
- `ChildDevice-v7.2.26198.233514-debug.apk`
  SHA-256: `2BCDBD0E734E51B51E3757E23CD9D3898B7397B3C3388100116F7D660C9EF99D`.

`utf8Guard` завершился в штатном режиме. Он по-прежнему показывает старый повреждённый комментарий в `app/MainActivity.kt` и совпадения внутри локального `server/node_modules`; новые файлы этапа 3 в список не попали.

## Проверки, которые ещё нужны на реальных телефонах

До объединения этапа в `main` необходимо пройти матрицу:

- экран целевого телефона выключен, приложение свёрнуто;
- 5 секунд, 80%, vibration off, затем восстановление прежней alarm-громкости;
- 30 секунд и PULSE;
- local stop, remote stop и Stop из уведомления;
- два сигнала подряд с `REPLACED` для первого;
- reconnect после TTL — старый сигнал не воспроизводится;
- A → B при третьем устройстве C online — C не реагирует;
- DND не изменяется;
- после принудительного завершения процесса нет бесконечного сигнала.

На момент этого отчёта устройства к ADB не подключены, рабочий VDS не изменялся, серверный код этапа 3 не развёртывался.

## Следующий этап

Этап 4 — общая дизайн-система. Его нельзя начинать до успешной реальной проверки и фиксации этапа 3.
