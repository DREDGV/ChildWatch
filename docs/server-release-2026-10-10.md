# Серверный комплект тестового выпуска · 10.10.2026

Комплект накопленных исходников карты и chat v2 подготовлен локально. Публикация сервера здесь не выполнялась. Он предназначен для действующей основы 7.4.296, обновлённой владельцем; фактические файлы, конфигурация, база и процесс VPS в этом запуске не прочитаны. Сначала выпускается этот серверный код, затем подписанная пара приложений с Room14 поверх существующих данных. Новый локальный радиосканер серверного обновления не требует; удалённое окружение ещё не включено.

## Комплект и точный состав

Готовый архив: [server-patch-7.4.308.zip](../releases/7.4.308/server-patch-7.4.308.zip); распакованная структура: `releases/7.4.308/server-patch/`. SHA-256 архива: `cbc27f9eef6029d9df654953e77d0a46af7529255534ed2411842a95c0595cbc`. Инструкция пары APK и фактических native проверок: [выпуск308](../releases/7.4.308/README.md). Исходная ревизия указана в `checksums.json`; основа сравнения — `a0d8e6d` (выпуск296). Полноразмерные изображения, `.env`, токены, ключи, модели/исполняемые файлы ASR, `node_modules`, базы, uploads и личные файлы не включены.

`checksums.json` и `SHA256SUMS` перечисляют **50 файлов payload, 2 331 404 байта**: 23 runtime, 17 каталога, два неизменённых npm manifest и восемь изолированных тестов. Размер служебных инструкций/manifest в эту цифру не входит. `deployment-files.json` разделяет роли. `required-baseline-files.json` содержит27 неизменённых зависимостей, найденных обходом literal `require`: они должны присутствовать в существующей одобренной основе. Их контрольные суммы описывают локальную ветку, не подтверждают VPS. Если основной сервер заметно отличается, сначала сверить эту основу, не копировать частичный пакет вслепую.

23 runtime-файла:

```
database/DatabaseManager.js
index.js
managers/WebSocketManager.js
managers/AttentionSignalManager.js
routes/chat-attachments.js
routes/chat-media-catalog.js
routes/chat-transcriptions.js
routes/chat-v2.js
routes/location.js
routes/family-places.js
scripts/benchmark-chat-transcription.js
scripts/transcription-exec-guard.py
services/ChatAttachmentStore.js
services/ChatConversationService.js
services/ChatMediaCatalog.js
services/ChatTranscriptionRunner.js
services/ChatTranscriptionService.js
services/LocationMotionStore.js
services/AppUsageDailyArchiveService.js
services/DeviceStatusReadService.js
services/FamilyPlacePresencePolicy.js
services/FamilyPlacesService.js
working-server.js
```

17 новых/изменённых runtime относительно296 и шесть связанных runtime из предыдущего комплекта296 включены вместе. `index.js` — штатная точка входа; `working-server.js` — сохранённый совместимый legacy endpoint, он не является вторым одновременно запускаемым сервером. Семнадцать файлов каталога находятся в `assets/chat-catalog/v1/`:12 PNG,2 GIF, `catalog.json`, `artwork-provenance.json`, `README.md`. Все ресурсы должны попасть в приватную структуру server/assets; не добавлять public static/nginx alias на эту папку.

`package.json` и `package-lock.json` не менялись относительно296: новых npm-зависимостей нет, существующие express/multer/socket.io/sqlite3 используются повторно. Не нужен безусловный `npm install` на рабочем сервере. Эти два файла включены для сверки; при штатной основе их не требуется заменять. Если зависимости не установлены, восстановление делается по согласованному lockfile отдельным шагом; тестам нужен Jest из devDependencies. Восемь тестов — дополнительные материалы для изолированной проверки, не запускают рабочую базу.

## SQLite: только вперёд, без локальной базы

Отдельного `.sql` файла миграции нет: аддитивные SQL выполняются штатным кодом через `ensure()` и зарегистрированную инициализацию. Не запускать `init-db`, не создавать пустую production базу и не переносить локальный `.db`.

- `LocationMotionStore`: таблица `location_motion`, индекс времени; недостающие `elapsed_nanos TEXT` и `boot_session TEXT` добавляются через проверку `PRAGMA table_info`/`ALTER TABLE`. Данные скорости связаны с точным device/time/latitude/longitude; монотонное время хранится строкой.
- `ChatAttachmentStore`: `chat_attachments`, `chat_message_media`, уникальная загрузка conversation/device/clientUploadId и индекс GC. Таблицы создаются при capabilities/работе attachments/GC; привязка сообщения и файла транзакционная.
- `ChatTranscriptionService`: `chat_transcription_jobs`, `chat_transcription_worker_lease` и индекс очереди; недостающие поля lease owner_pid/owner_start/owner_host добавляются идемпотентно. При выключенном worker таблицы могут появиться при GC или запросе сохранённого состояния, это не включает распознавание.
- Восемь исходников прежнего комплекта296 сохраняют существующие family places/day usage/schema bootstrap. Откат новой записи БД назад не входит в комплект. Room12→13→14 относится к телефонам, не к SQLite сервера.

Личные вложения: `CHAT_ATTACHMENT_ROOT`, по умолчанию `<server>/private-data/chat-attachments`. Временное аудио ASR: `CHAT_TRANSCRIPTION_ROOT`, по умолчанию `<server>/private-data/chat-transcriptions`. Обе папки вне public/static root, владелец — пользователь процесса, права0700; не заменять/удалять уже существующие папки. Удаление «для меня» не удаляет общий файл. Сохранить SQLite backup и приватные каталоги вместе.

## Порядок ручной выкладки

1. Уточнить фактические рабочие каталог/пользователь/точку входа PM2 (`pm2 describe <точный id>`), путь базы `CW_DB_PATH` или фактический `<server>/childwatch.db`, nginx/static root и оба private root. Старый пример `/var/www/childwatch` не подтверждён текущим запуском. Не выводить секретное окружение/токены в общий журнал.
2. Распаковать пакет в отдельную staging-папку. Из её корня выполнить `sha256sum -c SHA256SUMS`; все50 файлов должны совпасть. Сверить текущие27 baseline dependencies и package manifests. При неизвестном расхождении остановить выкладку и согласовать основу. Временные highres арт-оригиналы не нужны.
3. Записать число семей/участников/бесед/сообщений через SELECT из фактической базы. Сохранить заменяемые code/assets и перечень отсутствующих новых путей в отдельный rollback-каталог. Сохранить конфигурацию у оператора; не заменять `.env` локальной конфигурацией. В первом тестовом выпуске выставить `CHAT_TRANSCRIPTION_ENABLED=0` в действующем источнике окружения процесса, сохранив прежнюю конфигурацию в резерве. Изменение shell export само по себе не меняет PM2 env.
4. Остановить **только подтверждённый процесс ChildWatch**, не `pm2 stop all`. После остановки сделать консистентный SQLite backup (SQLite `.backup` или Python `sqlite3.Connection.backup`), проверить `PRAGMA integrity_check` и сохранить private roots. Не копировать только открытый `.db`, игнорируя WAL. Окно недоступности согласуется при фактической выкладке; этот документ не выполняет остановку.
5. Перенести23 runtime-файла с сохранением относительных путей и владельца. Перенести PNG/GIF/README/provenance целиком в `assets/chat-catalog/v1/`, **catalog.json последним**. До первого опубликованного v1 пакет заменяет отклонённый прототип; после публикации менять байты v1 нельзя. Пакет не удаляет сторонние файлы и не выполняет rsync `--delete`.
6. `node --check` всех JavaScript-файлов комплекта; проверить читаемость Python helper. Тесты копировать в отдельную тестовую копию server вместе с одобренными baseline dependencies, а не запускать на production DB. Выполнить команды ниже. Каталог и обе приватные папки не должны быть публично доступны через nginx.
7. Запустить/перезапустить только подтверждённый процесс от прежнего пользователя с прежней конфигурацией и явно выключенным ASR. Проверить `pm2 status`, последние логи без secret dump и `/api/health`200. После нового обращения повторить SQLite integrity/counts; отсутствие таблицы, которая создаётся лениво, не означает сбой bootstrap.
8. Неавторизованный chat capabilities/list/content должен требовать авторизацию;401 подтверждает лишь защиту маршрута. С разрешённым семейным клиентом проверить capabilities: attachments=true, типы IMAGE/FILE/GIF/VOICE/STICKER, mediaCatalog=true, **transcription=false**. Получить14 item каталога и приватный content с совпадающим SHA256; чужая/отозванная беседа должна быть403/404 без контента. Проверить фото/файл/voice upload→send→download и повтор с тем же ID без второго сообщения. Метаданные speedMps/speedAccuracyMps/mono/boot должны возвращаться на точном GPS fix.
9. После успешных серверных gates устанавливать подписанные APK поверх текущих приложений, сохраняя family links/Room данные. OTA — отдельный комплект выпуска: APK доступны первыми, manifest атомарно последним, публичный SHA/сертификат/versionCode проверены. Этот серверный ZIP не содержит APK/OTA manifest и не публикует их.

## Откат

При startup/auth/SQLite/privacy отказе остановить только тот же ChildWatch процесс, восстановить прежние заменённые исходники/assets и его прежнюю конфигурацию из rollback-каталога, затем запустить и проверить health/auth/counts. Новые неиспользуемые файлы старый код может игнорировать; не выполнять массовое удаление server/private-data. Аддитивная новая схема обычно остаётся читаемой прежним кодом, но это требуется проверить на копии с фактической базой. **Не заменять рабочую БД бэкапом автоматически после новых сообщений:** это потеряет новые данные. Если понадобится восстановление данных, оно требует отдельного решения владельца и совместного восстановления соответствующих private blobs. APK понижение версии не является штатным откатом Room14.

## Проверки и границы доказательств

Локально10.10: 30 JS/CJS payload разобраны `node --check`;50 копий совпадают по SHA256; static literal require closure57modules/27baseline files/4npm packages разрешается. Динамические вычисляемые require и фактическая конфигурация VPS этим не проверяются.

Jest: **77pass,3skip,0fail,7suites**,36,355с — attachments/catalog/transcriptions/chat-v2-routes/chat-v2-socket/edit-delete/location-access. Node motion: **7pass/0fail**,338мс. Логи: `.runtime/release-server-preparation-2026-10-10/jest-tests.log` и `location-motion-tests.log`. Первый sandbox запуск localhost получил EACCES; повтор с разрешённым локальным доступом прошёл. Linux-only3 tests честно пропущены на Windows. Настоящее ffmpeg/whisper/audio распознавание, VPS benchmark, deployment, native chat/map/Room upgrade здесь не подтверждены.

На изолированной копии server, при наличии node_modules/Jest и `.runtime` в родительской папке:

```bash
node node_modules/jest/bin/jest.js --runInBand --cacheDirectory ../.runtime/server-release-jest __tests__/chat-attachments.test.js __tests__/chat-media-catalog.test.js __tests__/chat-transcriptions.test.js __tests__/chat-v2-routes.test.js __tests__/chat-v2-socket.test.js __tests__/chat-message-edit-delete.test.js __tests__/location-access-control.test.js
node --test __tests__/location-motion.node.cjs
```

## Распознавание пока выключено

В приложениях запись/голосовые сообщения доступны через attachments. «Запись в текст» скрыта, пока capabilityfalse. Пакет содержит собственный worker source и Linux guard, но не модель, ffmpeg/whisper binaries или чужой benchmarkproof. Внешний ASR не используется.

Последующее включение требует отдельного измерения на том же Linux VPS: Python3 `/usr/bin/python3`, ffmpeg, CPU whisper.cpp, multilingual ggml `base` (не base.en), абсолютные `CHAT_TRANSCRIPTION_FFMPEG`, `CHAT_TRANSCRIPTION_WHISPER`, `CHAT_TRANSCRIPTION_MODEL`, `CHAT_TRANSCRIPTION_BENCHMARK`; измеренный `CHAT_TRANSCRIPTION_MEMORY_BUDGET_BYTES`. `CHAT_TRANSCRIPTION_PYTHON` и `CHAT_TRANSCRIPTION_GUARD` по умолчанию системный Python и shippedhelper. Один worker, один job≤3мин audio/≤10МиБ, общий deadline10мин.

Сначала на Linux все18 transcription tests, включая3 guard/controller-kill fixtures, должны пройти. Затем `node scripts/benchmark-chat-transcription.js /absolute/path/neutral-175s.m4a`: исходное нейтральное аудио170–180сек, guardprobe, actualhost/binary/model hashes, память, обработка≤10мин и фон/нагрузка healthlatency. Нужны≥5baseline/≥3during probes, P95≤1000мс по умолчанию и≤max(100мс,3×baseline). Proof неполный/с другого хоста запрещает capability. Только после успешного benchmark и отдельного решения владельца `CHAT_TRANSCRIPTION_ENABLED=1` в действующей конфигурации/перезапуск; без измерения оставить0.

Следующий runtime шаг: оператор сверяет фактическую основу VPS и выполняет описанный server-first выпуск; после подтверждённых gates родитель/ребёнок проверяют новую подписанную пару и локальный radio probe. Текущий статус находится только в TODO.md.
