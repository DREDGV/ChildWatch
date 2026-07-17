# Промт агенту: «Сигнал внимания»

Работай только над этой функцией.

Прочитай:

```text
01_MASTER_SPEC.md
contracts/attention-signal.schema.json
reference/AttentionSignalModels.kt
reference/AttentionSignalController.kt
reference/AttentionSignalCommandHandler.kt
reference/attention_signal_server_reference.js
reference/attention_signal_migration.sql
acceptance/REAL_DEVICE_TEST_MATRIX.md
```

## Результат

Разрешённый член семьи выбирает конкретного участника/устройство и задаёт:

- 5/10/15/30/60 секунд;
- 0–100%;
- ATTENTION/RINGTONE/ALARM/SIREN;
- vibration;
- PULSE/URGENT/SOS.

Статусы:

```text
QUEUED
DELIVERED
STARTED
COMPLETED
STOPPED
REJECTED
FAILED
EXPIRED
```

## Протокол

```text
attention_signal_request
attention_signal_start
attention_signal_stop_request
attention_signal_stop
attention_signal_status
```

Не использовать generic `command` как основной транспорт.

## Сервер

- exact `targetDeviceId`;
- authenticated requester;
- same-family permission;
- schema validation;
- TTL default 30, max 120 сек;
- не класть в бессрочную очередь;
- cooldown цели 5 сек;
- max 10 запросов/мин;
- pending map по requestId;
- status обратно инициатору;
- audit;
- stop request;
- cleanup по terminal status/TTL;
- тесты wrong target/offline/expiry/stop/duplicate/cross-family.

## Android target

- интеграция в существующий устойчивый foreground service;
- exact target;
- повторная TTL-проверка;
- DELIVERED перед playback;
- STARTED после фактического старта;
- ongoing notification с Stop;
- local/remote stop;
- restore alarm volume;
- DND не менять;
- без full-screen intent;
- второй сигнал заменяет первый;
- без произвольного URL/аудиофайла.

## UI sender

Bottom sheet из `04_UI_WIREFRAMES.md`.

Доступ:

- ParentMonitor home;
- профиль участника;
- карточка на карте;
- ChildDevice «Позвать родителя».

Показывать реальный статус и remote stop.

## Проверка

- server/unit tests;
- сборка обоих APK;
- два реальных телефона;
- экран выключен;
- приложение свёрнуто;
- volume restore;
- vibration;
- local/remote stop;
- expired offline request;
- два сигнала подряд;
- точная адресация при трёх устройствах.

Не начинай другие этапы.
