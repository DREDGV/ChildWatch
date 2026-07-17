---

## FILE: `00_README.md`

# ChildWatch — пакет материалов для ИИ-агента

## Исходная точка

- Репозиторий: `DREDGV/ChildWatch`
- Ветка: `main`
- Проверенный HEAD: `6c67319f45811196da862ab02845da5696d81016`
- Версионная база: `7.2`
- `app/` — ParentMonitor, приложение родителя.
- `parentwatch/` — ChildDevice, приложение ребёнка.
- `server/` — Node.js, Socket.IO, SQLite.

Перед реализацией агент обязан сверить свежий `main`. Если HEAD изменился, он сначала анализирует разницу.

## Цели

1. Единый современный дизайн обоих приложений.
2. Технические данные убрать в «Настройки → Диагностика».
3. Современный и надёжный чат.
4. Полноценная активность приложений ребёнка.
5. Завершённые профили, семья, участники и устройства.
6. Полезная семейная карта.
7. Функция «Сигнал внимания» конкретному члену семьи:
   длительность, громкость, звук, вибрация, подтверждения и остановка.

## Порядок

```text
baseline → ActiveContext → семейная серверная модель →
Сигнал внимания → дизайн-система → главные экраны →
чат → активность → семейная карта → удаление legacy
```

Нельзя начинать с полного редизайна: сначала все функции должны получать одну и ту же цель из единого контекста.

## Использование

1. Передать агенту весь каталог.
2. Начать с `prompts/00_BASELINE_PROMPT.md`.
3. Затем дать `prompts/01_CONTEXT_PROMPT.md`.
4. Один этап — одна ветка и один ограниченный PR.
5. Следующий этап агент не начинает без отдельного задания.
6. После каждого этапа: сборка двух APK, server tests, реальные тесты, обновление `TRACKING.md`.

Референсный код требуется адаптировать к свежему репозиторию и обязательно проверять сборкой.


---

## FILE: `01_MASTER_SPEC.md`

# ChildWatch — мастер-спецификация модернизации

## 1. Роль исполнителя

Работай как ведущий Android/Node.js инженер над существующим продуктом.

Не создавай приложение заново. Не заменяй рабочую архитектуру ради моды. Не смешивай несколько крупных подсистем в одном изменении.

Задачи:

- сохранить почти стабильные прослушивание, чат и удалённое фото;
- устранить рассинхронизацию профилей и ID;
- внедрить семейную доменную модель;
- унифицировать интерфейсы;
- завершить чат, активность и карту;
- реализовать адресный «Сигнал внимания»;
- оставлять проект собираемым после каждого этапа.

## 2. Защищаемое ядро

Нельзя допустить регрессию:

- запуск/остановка прослушивания;
- повторное подключение аудиопотока;
- восстановление после сна и перезапуска;
- чат, offline-очередь и статусы;
- удалённое фото;
- восстановление аудио после фото;
- Socket.IO-регистрация;
- авторизация и refresh token;
- совместимость текущих установок.

## 3. Главный технический долг

Контекст сейчас может собираться из:

```text
active session
SecureSettings
childwatch_prefs
app_prefs
device_id
parent_device_id
child_device_id
selected_device_id
linked_parent_device_id
```

Из-за этого разные функции могут выбрать разные устройства.

После миграции функция получает контекст только через:

```kotlin
interface EffectiveContextProvider {
    fun current(): ActiveContext
    fun observe(): kotlinx.coroutines.flow.StateFlow<ActiveContext>
}
```

Legacy Preferences разрешены только внутри migration/compatibility/diagnostics.

## 4. Целевая модель

```text
Family
FamilyMember
FamilyDevice
FamilyPermission
ActiveContext
```

Человек и устройство — разные сущности.

```kotlin
data class ActiveContext(
    val version: Int,
    val familyId: String?,
    val selfMemberId: String?,
    val selfDeviceId: String,
    val focusedMemberId: String?,
    val targetDeviceId: String?,
    val serverUrl: String,
    val source: ContextSource,
    val updatedAt: Long
)
```

Правила:

- UI выбирает человека;
- сетевой вызов адресуется конкретному устройству;
- история хранится с family/member/device;
- смена профиля атомарно меняет цель всех функций;
- self ID не может быть target ID.

## 5. Серверная семейная модель

Совместимо с `device_links` добавить:

```text
families
family_members
family_devices
family_permissions
```

Сделать идемпотентный bootstrap старых пар. До rollout `device_links` не удалять.

## 6. Нейтральный реестр устройств

Существующие `childSockets`/`parentSockets` оставить для аудио.

Для семейных адресных функций:

```js
this.deviceSockets = new Map(); // deviceId -> Set<socketId>
```

Методы:

```js
registerDeviceSocket(socket, deviceId)
unregisterDeviceSocket(socket)
getConnectedSocketIdsForDevice(deviceId)
emitToExactDevice(deviceId, event, payload)
```

Адресную команду нельзя переназначать на «единственного подключённого ребёнка».

## 7. Дизайн-система

Не выполнять полную миграцию на Compose.

Создать общий ресурсный модуль `:shared-ui`:

- палитра;
- типографика;
- spacing;
- радиусы;
- карточки;
- кнопки;
- chips;
- статусы;
- аватары;
- loading/empty/error;
- light/dark.

Принципы:

- спокойный семейный интерфейс;
- минимум неона;
- одинаковая семантика в двух приложениях;
- крупные действия;
- технические данные скрыты;
- ошибки объясняют действие.

## 8. ParentMonitor

Главный экран:

```text
[Аватар] Лёва
Онлайн • 74% • обновлено 2 мин назад

[Прослушать] [Фото]
[Чат]        [Сигнал]
[Карта]      [Активность]

Последние события
```

В диагностику убрать:

- raw Device ID;
- URL сервера;
- SDK/модель;
- токены;
- внутренние службы;
- сырые ошибки;
- технические timestamps.

## 9. ChildDevice

```text
ChildWatch
Защита активна
Связь с семьёй установлена

[Чат]
[Семья на карте]
[Позвать родителя]
[Настройки]
```

Изменение серверных параметров и остановка мониторинга — с PIN/подтверждением.

## 10. Чат

Целевая модель:

```text
Conversation
ConversationMember
Message
```

Требования:

- idempotent clientMessageId;
- единая очередь;
- sending/sent/delivered/read/failed;
- offline/reconnect;
- pagination;
- unread per conversation;
- отсутствие дублей;
- namespace по семье/диалогу;
- shared chat core;
- одинаковое поведение двух приложений.

UI:

- имена/аватары;
- даты;
- «Новые сообщения»;
- галочки;
- retry;
- копирование;
- reply;
- typing;
- современная строка ввода.

## 11. Активность приложений

Текущий `device_status` — только временная совместимость.

ChildDevice:

```text
UsageStatsManager.queryEvents
Room pending sessions
last processed cursor
foreground service collection
WorkManager fallback
batch upload
```

Сессия:

```text
AppUsageSession
- id
- familyId
- memberId
- deviceId
- packageName
- appName
- startedAt
- endedAt
- durationMs
```

ParentMonitor:

- сегодня/вчера/7/30;
- total;
- приложения;
- launches;
- hourly chart;
- first/last use;
- categories;
- icons;
- permission state;
- hide system apps.

Не считать дневной `totalTimeInForeground` временем короткого окна без дельты.

## 12. Семейная карта

Сохранить osmdroid.

Endpoint:

```http
GET /api/families/{familyId}/locations/latest
```

По каждому устройству:

```text
memberId, deviceId, displayName, role,
latitude, longitude, accuracy, speed,
batteryLevel, isCharging, isOnline,
timestamp, source
```

UI:

- chips участников;
- именные маркеры;
- свежесть;
- точность;
- заряд;
- online/offline;
- stale appearance;
- accuracy circle;
- bottom sheet;
- маршрут;
- обновить;
- сигнал;
- профиль.

Не рисовать бессмысленную линию между всеми участниками.

История/геозоны namespaced по family/member/device.

## 13. Сигнал внимания

Цель: разрешённый член семьи отправляет сигнал на конкретное устройство.

Настройки:

- 5/10/15/30/60 сек;
- 0–100%;
- ATTENTION/RINGTONE/ALARM/SIREN;
- vibration on/off;
- PULSE/URGENT/SOS.

Dedicated events:

```text
attention_signal_request
attention_signal_start
attention_signal_stop_request
attention_signal_stop
attention_signal_status
```

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

Ограничения:

- TTL default 30 сек, max 120;
- duration 5–60;
- target cooldown 5 сек;
- max 10 запросов/мин;
- только встроенные типы;
- same-family permission;
- локальная остановка всегда;
- истёкший запрос не воспроизводить;
- не использовать бессрочную generic queue.

Android:

- `USAGE_ALARM`;
- временно менять только `STREAM_ALARM`;
- обязательно восстановить громкость;
- DND не менять автоматически;
- VibratorManager/VibrationEffect;
- wake lock с timeout;
- второй сигнал заменяет первый;
- ongoing notification с Stop;
- без full-screen intent по умолчанию.

## 14. Диагностика

Отдельный экран:

- version;
- server hostname;
- masked IDs;
- family/member/target;
- source context;
- WebSocket state;
- sync timestamps;
- permissions;
- battery optimization;
- service state;
- безопасный журнал;
- копирование отчёта без секретов.

## 15. Definition of Done

1. Профиль одинаков во всех функциях.
2. Данные семей не смешиваются.
3. Технические данные убраны с главных экранов.
4. Оба приложения в одном стиле.
5. Чат без потерь и дублей.
6. Активность показывает реальные сессии.
7. Карта показывает семью со свежестью/точностью.
8. Сигнал идёт только выбранному устройству.
9. Сигнал подтверждается и останавливается.
10. Аудио/фото не регрессировали.
11. `TRACKING.md` соответствует коду.
12. Сборки и тесты проходят.


---

## FILE: `02_ROADMAP.md`

# ChildWatch — план реализации

## Этап 0. Защита 7.2
Ветка: `chore/baseline-7-2-protection`

- зафиксировать HEAD;
- проверить server tests;
- собрать оба Android-модуля;
- запустить `utf8Guard`;
- зафиксировать smoke-сценарии аудио, чата, фото и reconnect;
- не менять поведение.

## Этап 1. Единый ActiveContext
Ветка: `feat/family-context-foundation`

- canonical context;
- централизованная legacy migration;
- общий target для chat/map;
- namespace данных по профилю;
- unit tests;
- без редизайна.

## Этап 2. Семья на сервере
Ветка: `feat/server-family-model`

- families, members, devices, permissions;
- bootstrap из `device_links`;
- нейтральный `deviceSockets`;
- точная адресация;
- authorization tests.

## Этап 3. Сигнал внимания
Ветка: `feat/attention-signal`

- отдельный Socket.IO протокол;
- TTL и rate limit;
- Android playback;
- уведомление и local stop;
- remote stop;
- ACK/status;
- аудит;
- тесты на телефонах.

## Этап 4. Общая дизайн-система
Ветка: `feat/shared-design-system`

- общий resource/library module;
- цвета, типографика, карточки, кнопки, статусы;
- светлая/тёмная темы;
- отдельная диагностика;
- без полной миграции на Compose.

## Этап 5. Главные экраны
Ветка: `feat/home-screen-redesign`

- ParentMonitor dashboard;
- минимальный ChildDevice home;
- быстрые действия;
- скрыть raw IDs и серверные данные.

## Этап 6. Чат
Ветка: `feat/chat-conversations`

- conversations;
- shared chat core;
- pagination;
- unread;
- современный UI;
- offline/reconnect без дублей.

## Этап 7. Активность приложений
Ветка: `feat/app-usage-analytics`

- UsageEvents;
- локальные сессии;
- batch upload;
- серверная агрегация;
- экран today/yesterday/7/30.

## Этап 8. Семейная карта
Ветка: `feat/family-map`

- family location endpoint;
- участники и устройства;
- свежесть, точность, online, заряд;
- маршрут и геозоны;
- сигнал из карточки.

## Этап 9. Удаление legacy
Ветка: `refactor/remove-legacy-context`

Только после миграции существующих установок.


---

## FILE: `03_AGENT_RULES.md`

# Правила работы ИИ-агента

## До изменения

1. `git status`.
2. Текущий HEAD.
3. Прочитать `README.md`, `TRACKING.md` и changelog.
4. Найти реальные call sites.
5. Указать scope текущего этапа.

## Во время работы

- маленькие логичные коммиты;
- не создавать параллельную третью архитектуру;
- legacy изолировать в migration/adapter;
- валидировать сетевые payload;
- не логировать секреты, аудио, фото и точные координаты без необходимости;
- не использовать fake IDs в production;
- не заявлять о прохождении не запущенных тестов.

## Запрещено

- тотальный rewrite;
- полная миграция на Compose;
- гигантский коммит;
- удаление legacy до миграции;
- переназначение адресного сигнала другому устройству;
- бессрочная очередь сигнала;
- произвольный загружаемый звук;
- сигнал без локальной кнопки остановки.

## Итоговый отчёт

- ветка;
- base SHA;
- итоговый SHA;
- изменённые файлы;
- выполненные требования;
- реальные результаты тестов;
- непроверенные пункты;
- миграции;
- риски;
- ручные шаги;
- что оставлено следующему этапу.


---

## FILE: `04_UI_WIREFRAMES.md`

# Текстовые wireframes

## ParentMonitor

```text
┌──────────────────────────────────┐
│ ChildWatch                 ⚙     │
│ (Л) Лёва                         │
│     ● онлайн • заряд 74%         │
│     обновлено 2 мин назад        │
│                       Сменить ›  │
├──────────────────────────────────┤
│ [Прослушать]   [Сделать фото]    │
│ [Чат]          [Сигнал]          │
│ [Карта]        [Активность]      │
├──────────────────────────────────┤
│ Последние события                │
└──────────────────────────────────┘
```

## «Сигнал внимания»

```text
┌──────────────────────────────────┐
│ Сигнал для Лёвы             ×    │
│ [Внимание] [Звонок] [Будильник] │
│ [Сирена]                         │
│ [5с] [10с] [15с] [30с] [60с]    │
│ Громкость ───────●──── 80%       │
│ Вибрация                  [ON]    │
│ [Импульсы] [Срочно] [SOS]        │
│       [Отправить сигнал]         │
└──────────────────────────────────┘
```

Статусы:

```text
Отправка…
Доставлено
Сигнал воспроизводится • 12 сек
[Остановить]
```

## ChildDevice

```text
┌──────────────────────────────────┐
│ ChildWatch                 ⚙     │
│       Защита активна             │
│       ● связь с семьёй           │
│ [Чат]                            │
│ [Семья на карте]                 │
│ [Позвать родителя]               │
│ [Настройки]                      │
└──────────────────────────────────┘
```

## Активность

```text
Активность Лёвы
[Сегодня] [Вчера] [7 дней] [30]
3 ч 42 мин • +28 мин к вчера
▁▁▂▄▆█▃▂▁
YouTube 1 ч 18 мин
Roblox 54 мин
Telegram 37 мин
```

## Карта

```text
Семья
[Все] [Лёва] [Григорий] [Марина]

КАРТА с именными маркерами

Лёва
2 минуты назад • точность 18 м
заряд 74% • онлайн
[Маршрут] [Обновить] [Сигнал]
```


---

## FILE: `prompts/00_BASELINE_PROMPT.md`

# Промт агенту: защита baseline 7.2

Работай в `DREDGV/ChildWatch`.

Выполни только подготовительный этап. Не меняй продуктовые функции и UI.

## Задачи

1. Проверь свежий `main`, HEAD и `git status`.
2. Прочитай `README.md`, `TRACKING.md`, `CHANGELOG-v7.2.0.md`.
3. Зафиксируй:
   - `app/` = ParentMonitor;
   - `parentwatch/` = ChildDevice;
   - `server/` = backend.
4. Запусти доступные server tests.
5. Собери debug обоих Android-модулей.
6. Запусти `utf8Guard`.
7. Создай `docs/modernization/BASELINE_7_2.md`.
8. Запиши base SHA, команды, реальные результаты, предупреждения и smoke-сценарии:
   - чат online/offline/reconnect;
   - прослушивание;
   - фото;
   - восстановление аудио после фото;
   - перезапуск сервера;
   - перезагрузка телефона.
9. Не исправляй найденные дефекты в этой ветке.
10. Не начинай следующий этап.

Не называй тест пройденным, если команда не выполнялась.


---

## FILE: `prompts/01_CONTEXT_PROMPT.md`

# Промт агенту: единый ActiveContext

Выполни только этап «Единый ActiveContext и фундамент профилей».

Не начинай редизайн, серверную family-модель, новый чат, активность, карту или сигнал.

## Прочитать

```text
README.md
TRACKING.md
CHANGELOG-v7.2.0.md
app/src/main/java/ru/example/childwatch/profile/
app/src/main/java/ru/example/childwatch/utils/ParentMonitorProfileManager.kt
parentwatch/src/main/java/ru/example/parentwatch/session/
parentwatch/src/main/java/ru/example/parentwatch/utils/ChildDeviceProfileManager.kt
обе ChatActivity
обе DualLocationMapActivity
обе MainActivity
01_MASTER_SPEC.md
reference/FamilyModels.kt
```

## Цель

Все мигрированные функции одинаково получают:

- self device;
- focused member;
- target device;
- server URL;
- источник;
- timestamp;
- будущие family/member IDs.

## Модель

```kotlin
data class ActiveContext(
    val version: Int,
    val familyId: String?,
    val selfMemberId: String?,
    val selfDeviceId: String,
    val focusedMemberId: String?,
    val targetDeviceId: String?,
    val serverUrl: String,
    val source: ContextSource,
    val updatedAt: Long
)
```

## Компоненты

ParentMonitor:

```text
ParentContextStore
ParentEffectiveContextProvider
ParentLegacyContextMigration
ParentContextDiagnostics
```

ChildDevice:

```text
ChildContextStore
ChildEffectiveContextProvider
ChildLegacyContextMigration
ChildContextDiagnostics
```

Семантика полей должна совпадать.

## Legacy

Централизованно обработай:

```text
device_id
parent_device_id
child_device_id
selected_device_id
linked_parent_device_id
server_url
active_parent_profile_id
saved_parent_profiles_json
```

Приоритет:

```text
canonical → active session → secure settings → legacy prefs
```

Правила:

- blank не стирает non-blank;
- self не становится target;
- migration идемпотентна;
- ID не создаётся при каждом чтении;
- compatibility mirror временно сохраняется.

## В этом PR мигрировать

1. chat target resolution в обоих приложениях;
2. map target resolution в обоих приложениях;
3. переходы MainActivity к chat/map;
4. namespace локальных ключей.

Не менять внутреннюю реализацию аудио и фото.

## Тесты

- canonical выше legacy;
- blank не стирает target;
- self исключён;
- смена selected child атомарна;
- повтор migration без изменений;
- разные профили имеют разные namespace;
- chat/map получают один target;
- single-pair setup совместим.

## Проверка

- compile `app`;
- compile `parentwatch`;
- unit tests;
- server tests без изменений;
- `utf8Guard`.

Верни файлы, migration priority, тесты, риски и итоговый SHA. Не начинай этап 2.


---

## FILE: `prompts/02_SERVER_FAMILY_PROMPT.md`

# Промт агенту: серверная семейная модель

Выполни только server family foundation.

## Требуется

1. Добавить:
   - `families`;
   - `family_members`;
   - `family_devices`;
   - `family_permissions`.
2. Идемпотентный bootstrap из `device_links`.
3. Не удалять `device_links`.
4. Добавить `deviceSockets: Map<deviceId, Set<socketId>>`.
5. Регистрировать реальный authenticated deviceId обеих ролей.
6. Добавить exact-device routing.
7. Не менять аудиомаршрутизацию без необходимости.
8. Добавить endpoints чтения семьи/участников/устройств.
9. Добавить permission service.
10. Тесты:
    - bootstrap;
    - повтор migration;
    - member/device separation;
    - cross-family denial;
    - exact routing;
    - reconnect cleanup.

## Ограничения

- не добавлять UI;
- не реализовывать сигнал;
- не удалять legacy;
- не делать fallback exact target на другое устройство.

Верни migrations, API contract, тесты и SHA.


---

## FILE: `prompts/03_ATTENTION_SIGNAL_PROMPT.md`

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


---

## FILE: `reference/APP_USAGE_IMPLEMENTATION.md`

# Полноценная активность приложений

## Недостаток текущего подхода

Снимок `UsageStats` внутри `device_status` не даёт надёжных сессий и зависит от общей телеметрии. Дневной `totalTimeInForeground` нельзя считать временем выбранного короткого окна без вычисления дельты.

## ChildDevice Room

```text
app_usage_cursor
- deviceId
- lastProcessedTimestamp

app_usage_sessions
- localId
- sessionId
- familyId
- memberId
- deviceId
- packageName
- appName
- startedAt
- endedAt
- durationMs
- uploadState
- createdAt
```

## Collector

1. Проверить Usage Access.
2. Взять cursor.
3. `UsageStatsManager.queryEvents(cursor, now)`.
4. Обработать foreground/resumed и background/paused.
5. Хранить текущую открытую сессию.
6. При переключении закрывать предыдущую.
7. Ограничить аномально длинную сессию.
8. Фильтровать launcher/System UI по конфигурации.
9. Сохранять транзакционно.
10. Сдвигать cursor только после commit.

## Upload

```http
POST /api/app-usage/sessions/batch
```

```json
{
  "familyId": "family",
  "memberId": "member",
  "deviceId": "device",
  "sessions": [
    {
      "sessionId": "uuid",
      "packageName": "com.example",
      "appName": "Example",
      "startedAt": 0,
      "endedAt": 0,
      "durationMs": 0
    }
  ]
}
```

Уникальность: `device_id + session_id`.

## Чтение

```http
GET /api/families/{familyId}/members/{memberId}/app-usage?from=&to=
```

Ответ:

```text
totalDurationMs
firstUseAt
lastUseAt
launchCount
apps[]
hourly[]
```

## UI

- today/yesterday/7/30;
- total и сравнение;
- список приложений;
- число запусков;
- почасовая диаграмма;
- иконки;
- permission state;
- stale warning;
- hide system apps.

## Тесты

- foreground/background pair;
- missing background;
- app switch;
- collector restart;
- duplicate batch;
- midnight/timezone;
- permission revoked;
- system filter;
- несколько устройств одного участника.


---

## FILE: `acceptance/REAL_DEVICE_TEST_MATRIX.md`

# Матрица тестов на реальных устройствах

## Подготовка

Минимум:

- телефон родителя A;
- телефон ребёнка B;
- желательно второй телефон родителя C;
- сервер с чистым логом;
- реальные device IDs;
- fake IDs отключены.

## Базовое ядро

| Сценарий | Ожидаемый результат |
|---|---|
| Чат A→B | одно сообщение, корректные статусы |
| B offline | доставка после reconnect без дубля |
| Перезапуск сервера | оба клиента восстанавливаются |
| Прослушивание | устойчивый звук |
| Экран B выключен | поведение соответствует Android-ограничениям |
| Фото B | правильная камера и один ответ |
| Фото после аудио | аудио корректно восстанавливается |
| Перезагрузка B | нужные службы восстанавливаются |

## Профили

| Сценарий | Ожидаемый результат |
|---|---|
| Выбрать B | chat/photo/audio/map/signal используют B |
| Перезапуск A | выбор сохраняется |
| Переключить на C | данные B не смешиваются |
| Legacy установка | мигрируется |
| Повтор migration | IDs не меняются |

## Сигнал внимания

| Сценарий | Ожидаемый результат |
|---|---|
| 5 сек, 80%, vibration off | играет около 5 сек, volume restored |
| 30 сек + PULSE | звук и вибрация, затем завершение |
| Local stop B | A получает STOPPED/LOCAL_USER |
| Remote stop A | B останавливается |
| Экран B выключен | сигнал запускается |
| B offline | FAILED/TARGET_OFFLINE |
| Reconnect после TTL | старый сигнал не играет |
| Два сигнала подряд | первый REPLACED, второй играет |
| A→B при C online | C не воспроизводит |
| Cross-family | REJECTED/FORBIDDEN |
| DND | приложение не меняет DND |
| Volume restore | прежняя alarm volume возвращена |
| Process killed | нет бесконечного сигнала |
| Notification Stop | немедленная остановка |

## Активность

| Сценарий | Ожидаемый результат |
|---|---|
| YouTube 10 минут | близкая длительность |
| YouTube→Telegram | две сессии |
| Перезагрузка B | незавершённая сессия не ломает итог |
| Offline | upload позже без дублей |
| Usage Access off | понятное предупреждение |
| Смена суток | корректная агрегация |

## Карта

| Сценарий | Ожидаемый результат |
|---|---|
| A/B/C online | три правильных маркера |
| B stale | явно показана давность |
| Нет permission | объяснение и действие |
| Плохая точность | accuracy отображается |
| История B | только маршрут B |
| Смена профиля | карта перестраивается |
| Signal из карточки | точная цель |

## Фиксация

Для каждого теста:

```text
дата
версии APK
server SHA
модели телефонов
версии Android
шаги
результат
лог/скриншот
дефект
```
