# Поручение серверному агенту: семейные игры ChildWatch 7.4.348

Исходники: `dd2e0e57b9bb83395037add5b1aa9a4476a58a80`. Пакет подготовлен основным агентом локально. Владелец поручает серверную выкладку другому агенту; VPN не менять. Клиентская пара подписана прежним сертификатом. Порядок: **сервер → установка клиентов → публикация OTA**.

## Состав

Скопировать из `server/` относительно корня действующего сервера ровно девять runtime-файлов, перечисленных в `verification.json`: FamilyGameService, BattleshipRules, SnakeRules/SessionService/SocketService, ArcanoidRules/SessionService/SocketService и routes/family-games.

`reference/` — материалы для сверки существующих зависимостей и интеграции. **Не заменять действующий index.js целиком и не копировать reference как runtime**: в нём могут быть другие актуальные серверные исправления. Сверить DeviceAccessService, SocketAuthMiddleware, DataValidator, AuthManager, DatabaseManager, package.json/package-lock.json по приложенным исходникам и SHA. При различиях проверить совместимость методов, сохранить более свежие изменения. Новых npm-зависимостей нет: express, socket.io и sqlite3 уже используются. Не устанавливать зависимости заново без выявленной необходимости; Node должен поддерживать используемые API (рекомендуется действующая версия >=18).

Контракт существующих зависимостей: dbManager имеет withTransaction/get/all/run/getFamilyMembers/getFamilyIdentityMembershipsForDevice; DeviceAccessService — isDeviceRevoked/idForms; AuthManager — validateToken с authenticated deviceId; SocketAuthMiddleware поддерживает `{required:true}` и выставляет authMode/authenticatedDeviceId. Игровой namespace требует токен **всегда**, независимо от совместимого режима основного мониторинга.

## Точечная интеграция index.js

Импорты рядом с существующими модулями, каждый ровно один раз:

```js
const createFamilyGameRoutes = require('./routes/family-games');
const FamilyGameService = require('./services/FamilyGameService');
const SnakeSessionService = require('./services/SnakeSessionService');
const SnakeSocketService = require('./services/SnakeSocketService');
const ArcanoidSessionService = require('./services/ArcanoidSessionService');
const ArcanoidSocketService = require('./services/ArcanoidSocketService');
```

После создания существующих io/dbManager/authManager:

```js
const familyGameService = new FamilyGameService(dbManager);
const gameNamespace = io.of('/family-games');
const authenticateGameSocket = new SocketAuthMiddleware(authManager, {required:true}).authenticate();
gameNamespace.use(authenticateGameSocket);
const snakeSessions = new SnakeSessionService(familyGameService);
const snakeSockets = new SnakeSocketService(gameNamespace, snakeSessions, {authenticate:authenticateGameSocket});
const arcanoidSessions = new ArcanoidSessionService(familyGameService);
const arcanoidSockets = new ArcanoidSocketService(gameNamespace, arcanoidSessions, {authenticate:authenticateGameSocket});
```

Существующий mount обновить, а не дублировать:

```js
app.use('/api/family-games', authMiddleware.authenticate(),
  authMiddleware.rateLimit(60_000,90), createFamilyGameRoutes(dbManager,familyGameService));
```

В асинхронном старте **после** успешного initializeDatabase и **до** server.listen:

```js
await snakeSessions.ensure();
await arcanoidSessions.ensure();
snakeSockets.start();
arcanoidSockets.start();
```

Добавить оба игровых close в существующий graceful shutdown, если он есть, до закрытия SQLite. Не запускать игровые таймеры в нескольких процессах: текущая симуляция рассчитана на **один Node worker**. При cluster/нескольких PM2 instances сначала согласовать однопроцессную конфигурацию или отдельную архитектуру; не выдавать такой запуск за проверенный.

## Выкладка и проверка

1. Установить действующий путь/процесс/SQLite; сохранить резервные копии изменяемого кода и согласованный snapshot базы при остановленных записях. Базу, uploads, auth sessions и семейные данные из пакета не заменять: их в пакете нет.
2. Проверить архив и все SHA из verification.json. Скопировать девять runtime-файлов, интегрировать index точечно. Выполнить node --check каждого файла и index. Сверить требуемые зависимости и авторизацию namespace до включения capabilities.
3. Перезапустить штатный процесс. Убедиться, что health и прежние API живы, логи чистые, SQLite integrity_check возвращает ok и состав сообщений/семей сохранён. Создаются только дополнительные игровые таблицы/поле type; миграции Android Room не требуются.
4. Без авторизации `/api/family-games/capabilities` должен вернуть401; Socket.IO namespace без токена отклоняет handshake. С разрешённым семейным клиентом capabilities содержит BATTLESHIP/SNAKE_COOP/ARKANOID_COOP, realtime protocolVersion1 и `/family-games`. Один401 не доказывает рабочую игру.
5. Передать владельцу результат выкладки и текущую опубликованную OTA-версию. Основной агент затем установит новую пару поверх333. Для проверки: явное приглашение/принятие, готовность обоих, свои поля Морского боя, общий мир Змейки/Арканоида, пауза/возврат. Не отправлять тестовые приглашения в рабочую семью без поручения владельца, не отключать связь дольше минуты. Звук/фото/карта должны сохраниться.
6. По поручению владельца опубликовать OTA из `ota.zip`: сначала оба APK, сверить размер/SHA/подписи, затем атомарно заменить manifest. Скачать опубликованные manifest/APK и проверить SHA; проверить обновление с обоих типов приложений, включая детский `ru.example.parentwatch.debug`. Не публиковать manifest раньше файлов.

Транскрибация остаётся выключенной. Каталог GIF/чаты/ранее выложенные исправления не менять. Откат старого игрового кода после появления новых типов партий несовместим с их чтением: сначала определить совместимый rollback, не возвращать старую базу поверх новых пользовательских данных. Android не понижать — существующий разрушительный downgrade Room остаётся отдельной открытой задачей.

## Подтверждённые границы

Локально пройдены102 Node game tests,110 Android unit tests, сборка обоих клиентов, UI/две настоящие Socket.IO связи в изолированном пакете на Samsung и Moto. Это не приёмка рабочих профилей через VPS и мобильную сеть. Зафиксировать конкретные результаты выкладки и полевой партии отдельно.
