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
