# ChildWatch Server 2.2.3: семья, чат и удалённое фото

Пакет обновляет только серверный код. База данных, фотографии, чат и история
не входят в архив и не удаляются. Перед установкой создаётся отдельная резервная
копия кода и файлов SQLite.

## 1. Загрузка и проверка архива

Через WinSCP загрузите
`childwatch-server-family-onboarding-photo-2.2.3-20260724.zip`
в каталог `/home/adminuser/`.

Войдите в PuTTY под `root` и выполните:

```bash
ZIP=/home/adminuser/childwatch-server-family-onboarding-photo-2.2.3-20260724.zip
STAGE=/home/adminuser/childwatch-family-onboarding-photo-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q "$ZIP" -d "$STAGE"
find "$STAGE" -type f -printf '%P\n' | sort
find "$STAGE" -name '*.js' -type f -exec node --check {} \;
```

Если последняя команда ничего не напечатала, синтаксис JavaScript корректен.

## 2. Резервная копия и остановка приложения

```bash
BACKUP=/home/adminuser/childwatch-before-2.2.3-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP/data"
cd /var/www/childwatch
for FILE in package.json package-lock.json index.js database/DatabaseManager.js managers/WebSocketManager.js routes/family-onboarding.js services/ChatV2SocketService.js services/FamilyOnboardingService.js; do if [ -f "$FILE" ]; then DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then mkdir -p "$BACKUP/$DIR"; fi; cp -a "$FILE" "$BACKUP/$FILE"; fi; done
cp -a data/childwatch.db* "$BACKUP/data/"
sudo -iu adminuser pm2 stop childwatch
echo "$BACKUP"
```

Сохраните напечатанный путь `BACKUP`. Он понадобится только при откате.

## 3. Установка и запуск

```bash
cd /var/www/childwatch
find "$STAGE" -type f ! -name 'DEPLOY_FAMILY_ONBOARDING_PHOTO_2.2.3_RU.md' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$STAGE/$FILE" "/var/www/childwatch/$FILE"; done
find database managers routes services -name '*.js' -type f -exec node --check {} \;
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
sudo -iu adminuser pm2 logs childwatch --lines 80 --nostream
sudo -iu adminuser pm2 save
```

Ожидаемый результат проверки: `HTTP/1.1 200 OK` и `"version":"2.2.3"`.

## Откат при ошибке запуска

Используйте путь, напечатанный в переменной `BACKUP`:

```bash
sudo -iu adminuser pm2 stop childwatch
cd "$BACKUP"
find . -type f ! -path './data/*' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$FILE" "/var/www/childwatch/$FILE"; done
cp -a data/childwatch.db* /var/www/childwatch/data/
chown -R adminuser:adminuser /var/www/childwatch/data
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
```
