# ChildWatch Server 2.2.2: каталог семьи и статусы фото

Пакет обновляет только серверный код. База данных, фотографии, чат и история
не входят в архив и не удаляются.

## 1. Загрузите архив

Через WinSCP загрузите `childwatch-server-family-map-photo-2.2.2-20260722.zip`
в `/home/adminuser/`.

## 2. В PuTTY войдите под `root` и подготовьте проверку

```bash
ZIP=/home/adminuser/childwatch-server-family-map-photo-2.2.2-20260722.zip
STAGE=/home/adminuser/childwatch-family-map-photo-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q "$ZIP" -d "$STAGE"
find "$STAGE" -type f -printf '%P\n' | sort
find "$STAGE" -name '*.js' -type f -exec node --check {} \;
```

Если последняя команда ничего не вывела, JavaScript проверен успешно.

## 3. Создайте резервную копию и остановите только приложение

```bash
BACKUP=/home/adminuser/childwatch-before-family-map-photo-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP/data"
cd /var/www/childwatch
for FILE in package.json package-lock.json database/DatabaseManager.js managers/WebSocketManager.js; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then mkdir -p "$BACKUP/$DIR"; fi; cp -a "$FILE" "$BACKUP/$FILE"; done
cp -a data/childwatch.db* "$BACKUP/data/"
sudo -iu adminuser pm2 stop childwatch
echo "$BACKUP"
```

Сохраните напечатанный путь `BACKUP`: он нужен только для отката.

## 4. Установите код и запустите сервер

```bash
cd /var/www/childwatch
find "$STAGE" -type f ! -name 'DEPLOY_FAMILY_MAP_PHOTO_2.2.2_RU.md' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$STAGE/$FILE" "/var/www/childwatch/$FILE"; done
find database managers -name '*.js' -type f -exec node --check {} \;
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
sudo -iu adminuser pm2 save
```

Ожидается `HTTP/1.1 200 OK` и `"version":"2.2.2"`.

## Откат, если сервер не запустился

```bash
sudo -iu adminuser pm2 stop childwatch
cd "$BACKUP"
install -o adminuser -g adminuser -m 644 package.json /var/www/childwatch/package.json
install -o adminuser -g adminuser -m 644 package-lock.json /var/www/childwatch/package-lock.json
install -d -o adminuser -g adminuser -m 755 /var/www/childwatch/database /var/www/childwatch/managers
install -o adminuser -g adminuser -m 644 database/DatabaseManager.js /var/www/childwatch/database/DatabaseManager.js
install -o adminuser -g adminuser -m 644 managers/WebSocketManager.js /var/www/childwatch/managers/WebSocketManager.js
cp -a data/childwatch.db* /var/www/childwatch/data/
chown -R adminuser:adminuser /var/www/childwatch/data
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
```
