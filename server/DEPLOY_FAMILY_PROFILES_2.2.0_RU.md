# ChildWatch Server 2.2.0: профили семьи и единая идентичность

Обновление добавляет серверную синхронизацию имён и встроенных аватаров, единый семейный каталог для функций приложения и защищённое редактирование профилей. База данных и пользовательские данные в архив не входят и не заменяются.

## Что загрузить

Через WinSCP загрузите файл `childwatch-server-family-profiles-2.2.0-20260719.zip` в каталог:

`/home/adminuser/`

## Установка

Войдите в PuTTY под `root`, затем последовательно выполняйте команды. После каждой команды убедитесь, что нет красных сообщений об ошибке.

### 1. Перейти к пользователю приложения

```bash
sudo -iu adminuser
```

### 2. Подготовить обновление и проверить JavaScript

```bash
ZIP=/home/adminuser/childwatch-server-family-profiles-2.2.0-20260719.zip
STAGE=/home/adminuser/childwatch-family-profiles-stage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$STAGE"
unzip -q "$ZIP" -d "$STAGE"
find "$STAGE" -maxdepth 4 -type f -printf '%P\n' | sort
find "$STAGE" -name '*.js' -type f -exec node --check {} \;
```

В списке должны быть `index.js`, файлы из `database`, `routes`, `services`, а команда `node --check` не должна вывести ошибок.

### 3. Создать резервную копию текущего кода

```bash
cd /var/www/childwatch
BACKUP=/home/adminuser/childwatch-before-family-profiles-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP"
FILES="index.js database/DatabaseManager.js routes/families.js routes/me.js services/FamilyIdentityService.js package.json package-lock.json"
for FILE in $FILES; do if [ -f "$FILE" ]; then mkdir -p "$BACKUP/$(dirname "$FILE")"; cp -a "$FILE" "$BACKUP/$FILE"; fi; done
echo "$BACKUP"
```

Сохраните показанный путь к резервной копии.

### 4. Коротко остановить ChildWatch и сохранить базу

```bash
pm2 stop childwatch
mkdir -p "$BACKUP/data"
cp -a /var/www/childwatch/data/childwatch.db* "$BACKUP/data/"
ls -lh "$BACKUP/data/"
```

### 5. Установить только файлы обновления

```bash
cd /var/www/childwatch
find "$STAGE" -type f ! -name 'DEPLOY_FAMILY_PROFILES_2.2.0_RU.md' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$STAGE/$FILE" "/var/www/childwatch/$FILE"; done
find index.js database routes services -name '*.js' -type f -exec node --check {} \;
```

Если проверка завершилась без ошибок, запускайте сервер.

### 6. Запустить и проверить

```bash
pm2 restart childwatch --update-env
sleep 3
curl -i --max-time 10 http://127.0.0.1:3000/api/health
pm2 logs childwatch --lines 60 --nostream
pm2 save
```

Ожидаемый результат: `HTTP/1.1 200 OK`, `"status":"OK"`, `"version":"2.2.0"`, процесс `childwatch` имеет статус `online`.

## Быстрая проверка в приложении

1. Откройте выбор членов семьи в ParentMonitor.
2. Измените имя или выберите встроенный аватар ребёнка.
3. Закройте и снова откройте приложение: имя и аватар должны сохраниться.
4. Проверьте карту, прослушивание, фото и чат — в них должен отображаться один и тот же человек.

## Откат, если сервер не запустился

Используйте сохранённое значение `BACKUP` из шага 3:

```bash
pm2 stop childwatch
cd "$BACKUP"
find . -type f ! -path './data/*' -printf '%P\n' | while read -r FILE; do DIR=$(dirname "$FILE"); if [ "$DIR" != "." ]; then install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"; fi; install -o adminuser -g adminuser -m 644 "$BACKUP/$FILE" "/var/www/childwatch/$FILE"; done
cp -a "$BACKUP"/data/childwatch.db* /var/www/childwatch/data/
chown -R adminuser:adminuser /var/www/childwatch/data
pm2 restart childwatch --update-env
curl -i --max-time 10 http://127.0.0.1:3000/api/health
```
