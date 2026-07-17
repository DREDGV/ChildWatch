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
