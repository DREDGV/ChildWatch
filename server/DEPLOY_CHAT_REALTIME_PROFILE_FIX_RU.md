# ChildWatch: обновление имени участника чата

Этот пакет заменяет только `database/DatabaseManager.js`. База данных и остальные файлы сервера не удаляются.

## Установка

Выполнять в PuTTY под `root` после загрузки ZIP в `/home/adminuser/`:

```bash
ZIP=/home/adminuser/childwatch-server-chat-realtime-profile-fix-20260719.zip
STAGE=/home/adminuser/childwatch-chat-profile-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q "$ZIP" -d "$STAGE"
find "$STAGE" -type f -printf '%P\n' | sort
node --check "$STAGE/database/DatabaseManager.js"
```

Ожидается один рабочий файл `database/DatabaseManager.js` и эта инструкция.

Создать резервные копии файла и базы:

```bash
BACKUP=/home/adminuser/childwatch-before-chat-profile-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP/database" "$BACKUP/data"
cp -a /var/www/childwatch/database/DatabaseManager.js "$BACKUP/database/"
cp -a /var/www/childwatch/data/childwatch.db* "$BACKUP/data/"
echo "$BACKUP"
```

Установить файл и перезапустить только процесс ChildWatch:

```bash
install -o adminuser -g adminuser -m 644 "$STAGE/database/DatabaseManager.js" /var/www/childwatch/database/DatabaseManager.js
node --check /var/www/childwatch/database/DatabaseManager.js
sudo -iu adminuser pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
sudo -iu adminuser pm2 save
```

Нормальный результат: PM2 показывает `online`, а health-check отвечает `HTTP/1.1 200 OK`.

## Откат

Если сервер не запустился, подставить путь, который напечатала команда `echo "$BACKUP"`:

```bash
install -o adminuser -g adminuser -m 644 /home/adminuser/childwatch-before-chat-profile-ДАТА/database/DatabaseManager.js /var/www/childwatch/database/DatabaseManager.js
sudo -iu adminuser pm2 restart childwatch --update-env
```
