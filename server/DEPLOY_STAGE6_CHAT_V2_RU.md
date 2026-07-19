# ChildWatch: установка тестового обновления чата v2

Пакет рассчитан на действующий сервер `/var/www/childwatch` под управлением PM2 (`childwatch`).
Обновление не удаляет старые таблицы и сообщения: новые таблицы создаются дополнительно при первом запуске.

Команды ниже выполняются по одной. Перед установкой убедитесь, что архив уже загружен в `/home/adminuser/`.

## 1. Распаковка и проверка

```bash
ZIP=/home/adminuser/childwatch-server-chat-v2-startup-fix-2.1.0-rc1-20260719.zip
STAGE=/home/adminuser/childwatch-chat-v2-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q "$ZIP" -d "$STAGE"
find "$STAGE" -maxdepth 4 -type f -printf '%P\n' | sort
```

Проверка JavaScript до остановки сервера:

```bash
find "$STAGE" -name '*.js' -type f -exec node --check {} \;
```

Если сообщений об ошибках нет, можно продолжать.

При запуске на давно работающей базе сервер проверяет, что существующая
семейная проекция уже покрывает legacy-связи, и не перестраивает тысячи
готовых записей повторно. В журнале ожидается строка вида
`[family] Existing projection covers ...; full bootstrap skipped`.

## 2. Резервная копия

```bash
BACKUP=/home/adminuser/childwatch-before-chat-v2-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP"
cd /var/www/childwatch
for FILE in index.js database/DatabaseManager.js managers/WebSocketManager.js routes/chat.js routes/chat-v2.js services/ChatConversationService.js services/ChatV2SocketService.js package.json package-lock.json; do if [ -f "$FILE" ]; then mkdir -p "$BACKUP/$(dirname "$FILE")"; cp -a "$FILE" "$BACKUP/$FILE"; fi; done
sudo -iu adminuser pm2 stop childwatch
if [ -f data/childwatch.db ]; then mkdir -p "$BACKUP/data"; cp -a data/childwatch.db* "$BACKUP/data/"; fi
echo "$BACKUP"
```

## 3. Установка файлов

```bash
cd /var/www/childwatch
find "$STAGE" -type f ! -name 'DEPLOY_STAGE6_CHAT_V2_RU.md' -printf '%P\n' | while read -r FILE; do install -d -o adminuser -g adminuser -m 755 "$(dirname "$FILE")"; install -o adminuser -g adminuser -m 644 "$STAGE/$FILE" "$FILE"; done
find index.js database managers routes services -name '*.js' -type f -exec node --check {} \;
```

## 4. Запуск и проверка

```bash
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 5 http://127.0.0.1:3000/api/health
sudo -iu adminuser pm2 logs childwatch --lines 80 --nostream
sudo -iu adminuser pm2 save
```

Ожидается `HTTP/1.1 200 OK`, `"status":"OK"` и `"version":"2.1.0"`.

## Откат при ошибке

Используйте путь `BACKUP`, показанный на этапе резервного копирования:

```bash
sudo -iu adminuser pm2 stop childwatch
cd "$BACKUP"
find . -type f -printf '%P\n' | while read -r FILE; do install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$(dirname "$FILE")"; install -o adminuser -g adminuser -m 644 "$FILE" "/var/www/childwatch/$FILE"; done
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 5 http://127.0.0.1:3000/api/health
```
