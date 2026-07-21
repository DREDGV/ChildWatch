# ChildWatch Server 2.2.1: стабильность профилей, чата и фото

Этот пакет обновляет только код сервера. База данных, фотографии, чат и настройки не входят в архив и не удаляются.

## 1. Загрузите архив

Через WinSCP загрузите файл `childwatch-server-stability-2.2.1-20260722.zip` в каталог:

`/home/adminuser/`

## 2. В PuTTY войдите под `root` и проверьте архив

```bash
unzip -l /home/adminuser/childwatch-server-stability-2.2.1-20260722.zip
```

В списке должны быть: `package.json`, `package-lock.json`, `database/DatabaseManager.js`, файлы из `managers` и `services`.

## 3. Распакуйте и проверьте JavaScript

```bash
STAGE=/home/adminuser/childwatch-stability-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q /home/adminuser/childwatch-server-stability-2.2.1-20260722.zip -d "$STAGE"
find "$STAGE" -type f -printf '%P\n' | sort
find "$STAGE" -name '*.js' -type f -exec node --check {} \;
```

Если команда `node --check` ничего не вывела, проверка успешна.

## 4. Создайте резервную копию текущего кода и базы

```bash
BACKUP=/home/adminuser/childwatch-before-stability-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP"
cd /var/www/childwatch
FILES="package.json package-lock.json database/DatabaseManager.js managers/WebSocketManager.js services/ChatConversationService.js services/ChatV2SocketService.js"
for FILE in $FILES; do if [ -f "$FILE" ]; then mkdir -p "$BACKUP/$(dirname "$FILE")"; cp -a "$FILE" "$BACKUP/$FILE"; fi; done
sudo -iu adminuser pm2 stop childwatch
mkdir -p "$BACKUP/data"
cp -a /var/www/childwatch/data/childwatch.db* "$BACKUP/data/"
ls -lh "$BACKUP" "$BACKUP/data"
```

Сохраните показанный путь `BACKUP`: он нужен только для отката.

## 5. Установите обновление и перезапустите сервер

```bash
cd /var/www/childwatch
find "$STAGE" -type f ! -name 'DEPLOY_STABILITY_2.2.1_RU.md' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$STAGE/$FILE" "/var/www/childwatch/$FILE"; done
find database managers services -name '*.js' -type f -exec node --check {} \;
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
sudo -iu adminuser pm2 save
```

Ожидается `HTTP/1.1 200 OK`, `"status":"OK"`, `"version":"2.2.1"`.

## Откат, если сервер не запускается

```bash
sudo -iu adminuser pm2 stop childwatch
cd "$BACKUP"
find . -type f ! -path './data/*' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$BACKUP/$FILE" "/var/www/childwatch/$FILE"; done
cp -a "$BACKUP"/data/childwatch.db* /var/www/childwatch/data/
chown -R adminuser:adminuser /var/www/childwatch/data
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
```
