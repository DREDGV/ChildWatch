# Этап 1: единый ActiveContext и фундамент профилей

Дата проверки: 2026-07-17  
Базовый SHA: `6e9ede0`  
Проверенный SHA реализации до добавления этого отчёта: `3c6cd0f`

## Результат

Оба Android-приложения теперь используют одну семантику активного контекста:

- `selfDeviceId` — текущее устройство;
- `focusedMemberId` — выбранный участник семьи;
- `targetDeviceId` — конкретное целевое устройство;
- `serverUrl` — сервер этого контекста;
- `familyId` и `selfMemberId` — стабильные локальные идентификаторы до появления серверной family-модели;
- `source` и `updatedAt` — происхождение и время обновления.

Один и тот же контекст передаётся в чат и карту. Локальные ключи чата и карты разделены по семье, текущему устройству, выбранной цели и функции. Старые настройки пока сохраняются как compatibility mirror.

## Основные файлы

Общий модуль `shared-core`:

- `FamilyModels.kt`, `FeatureContext.kt`;
- `ActiveContextResolver.kt`, `ActiveContextStore.kt`, `EffectiveContextProvider.kt`;
- `ActiveContextCodec.kt`, `ContextNamespace.kt`, `ContextDiagnostics.kt`;
- `ActiveContextResolverTest.kt`, `ContextNamespaceTest.kt`.

ParentMonitor (`app/`):

- `ParentContextStore`, `ParentEffectiveContextProvider`;
- `ParentLegacyContextMigration`, `ParentContextDiagnostics`;
- `ParentActiveSessionStore`, `ParentEffectiveContextResolver`, `ParentLegacyProfileMigration`;
- `MainActivity`, `ChatActivity`, `ChatManager`, `DualLocationMapActivity`;
- диагностический блок в `SettingsActivity`.

ChildDevice (`parentwatch/`):

- `ChildContextStore`, `ChildEffectiveContextProvider`;
- `ChildLegacyContextMigration`, `ChildContextDiagnostics`;
- `ChildActiveSessionStore`, `ChildEffectiveContextResolver`;
- `MainActivity`, `ChatActivity`, `DualLocationMapActivity`;
- диагностический блок в `SettingsActivity`.

## Приоритет миграции

Поля собираются по порядку:

1. canonical ActiveContext;
2. active session;
3. secure/current settings;
4. выбранная запись старого профиля;
5. остальные legacy preferences.

Правила:

- пустое значение не стирает непустое значение более низкого источника;
- `selfDeviceId` не может стать `targetDeviceId`;
- цель и focused member меняются одной операцией;
- повторная миграция не переписывает уже сохранённый контекст;
- производные member/family ID стабильны и не создаются заново при каждом чтении;
- `active_parent_profile_id` и `saved_parent_profiles_json` обрабатываются миграционным адаптером;
- старые ключи временно зеркалируются для совместимости существующих сервисов.

## Автоматические проверки

- `:shared-core:test`: 11 тестов, 0 ошибок;
- `:app:compileDebugKotlin`: успешно;
- `:parentwatch:compileDebugKotlin`: успешно;
- `server: npm test -- --runInBand`: 6 suites, 26 tests, все успешно;
- `utf8Guard`: задача успешна в штатном режиме.

`utf8Guard` по-прежнему сообщает об одном старом повреждённом комментарии в `app/MainActivity.kt` и о совпадениях внутри `server/node_modules`. Это известный долг исходной версии, а не новое повреждение этапа 1.

## Границы и риски

- Внутренняя реализация аудио и удалённого фото в этом этапе не изменена.
- Полная изоляция Room-истории чата и медиахранилищ относится к соответствующим последующим этапам; текущие SharedPreferences-кэши чата и карты уже разделены.
- До этапа серверной family-модели family/member ID выводятся детерминированно из существующей пары устройств.
- На реальных телефонах после этого изменения ещё нужно вручную проверить переключение двух сохранённых профилей: карта и чат должны одновременно показывать одну выбранную цель.
- Ошибка phased Gradle action в панели VS Code относится к фоновому Java-расширению VS Code; проектный Gradle Wrapper независимо проходит компиляцию.

## Что оставлено этапу 2

- серверные таблицы и API family/member/device/membership/permission;
- проверка принадлежности устройств семье на сервере;
- обратная совместимость существующих pair-based маршрутов;
- серверные миграции и интеграционные тесты без удаления текущих данных.
