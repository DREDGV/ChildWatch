# Обновление сервера ChildWatch через WinSCP и PuTTY

Архив обновления содержит только серверный код. Он не содержит и не должен
заменять `childwatch.db`, `uploads`, `data`, логи или `node_modules`.

По данным панели Hoster, рабочий процесс запущен из
`/var/www/childwatch/index.js`, а PM2 работает от пользователя `adminuser`.
Команды PM2 нужно выполнять именно от этого пользователя.

## 1. Безопасная диагностика в PuTTY

Сначала выполнить только эти команды и проверить результат:

```bash
whoami
sudo -iu adminuser
pm2 list
pm2 describe childwatch
cd /var/www/childwatch
pwd
curl -i --max-time 5 http://127.0.0.1:3000/api/health
exit
```

Если имя процесса в `pm2 list` отличается от `childwatch`, использовать его в
последующих командах перезапуска. На этом этапе сервер не изменяется.

## 2. Загрузка через WinSCP

Загрузить архив `childwatch-server-reconnect-update-20260714.zip` в:

```text
/home/adminuser/childwatch-server-reconnect-update-20260714.zip
```

Не распаковывать архив прямо поверх `/var/www/childwatch` через WinSCP.

## 3. Резервная копия и проверка архива

Войти в PuTTY и выполнить:

```bash
sudo -iu adminuser
set -e
cd /var/www/childwatch
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="/var/www/childwatch/backups/$STAMP"
STAGE="/home/adminuser/childwatch-update-$STAMP"
mkdir -p "$BACKUP" "$STAGE"

FILES="index.js auth/AuthManager.js database/DatabaseManager.js managers/CommandManager.js managers/WebSocketManager.js middleware/AuthMiddleware.js middleware/SocketAuthMiddleware.js routes/media.js ecosystem.config.cjs"

for f in $FILES; do
  if [ -f "$f" ]; then
    mkdir -p "$BACKUP/$(dirname "$f")"
    cp -a "$f" "$BACKUP/$f"
  fi
done

if [ -f childwatch.db ]; then
  cp -a childwatch.db "$BACKUP/childwatch.db"
fi

if [ -f data/auth-sessions.json ]; then
  mkdir -p "$BACKUP/data"
  cp -a data/auth-sessions.json "$BACKUP/data/auth-sessions.json"
fi

unzip -q /home/adminuser/childwatch-server-reconnect-update-20260714.zip -d "$STAGE"
cd "$STAGE"
for f in $FILES; do
  node --check "$f"
done
echo "BACKUP=$BACKUP"
echo "STAGE=$STAGE"
```

Если любая команда завершилась ошибкой, файлы рабочего сервера ещё не заменены:
нужно остановиться и проверить сообщение.

## 4. Установка проверенных файлов

Продолжить только после успешного этапа 3:

```bash
sudo -iu adminuser
set -e
cd /var/www/childwatch
STAGE=$(ls -dt /home/adminuser/childwatch-update-* | head -n 1)
FILES="index.js auth/AuthManager.js database/DatabaseManager.js managers/CommandManager.js managers/WebSocketManager.js middleware/AuthMiddleware.js middleware/SocketAuthMiddleware.js routes/media.js ecosystem.config.cjs"

for f in $FILES; do
  mkdir -p "$(dirname "$f")"
  cp -a "$STAGE/$f" "$f"
  node --check "$f"
done

pm2 restart childwatch --update-env
pm2 save
sleep 3
pm2 list
pm2 logs childwatch --lines 80 --nostream
curl -i --max-time 5 http://127.0.0.1:3000/api/health
```

Оставить `CW_REQUIRE_WS_AUTH=0` на первом запуске. Строгую обязательную
авторизацию Socket.IO можно включать только после установки новых APK и
успешной повторной регистрации обоих телефонов.

## 5. Проверка устойчивости перезапуска

После того как оба телефона хотя бы один раз подключились к обновлённому
серверу:

```bash
sudo -iu adminuser
cd /var/www/childwatch
test -s data/auth-sessions.json && echo "auth sessions: OK"
pm2 restart childwatch --update-env
sleep 3
pm2 list
curl -i --max-time 5 http://127.0.0.1:3000/api/health
```

После этого проверить связь в приложениях без ручного перезапуска мониторинга.

