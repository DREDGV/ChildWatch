ChildWatch — установка обновления сервера
=======================================

Архив содержит ТОЛЬКО изменённые файлы серверного кода.
Внутри нет базы данных, фотографий, аудио, сессий, логов и node_modules,
поэтому установка не может удалить данные пользователей.

Порядок (всё выполняется на VPS под root через PuTTY, файл загружается WinSCP
в /home/adminuser/):

1. Загрузить архив и проверить контрольную сумму

   ZIP=/home/adminuser/<имя-архива>.zip
   sha256sum "$ZIP"
   # сверить с SHA-256, который напечатал скрипт подготовки

2. Распаковать в отдельный каталог и проверить синтаксис

   STAGE=/home/adminuser/childwatch-stage-$(date +%Y%m%d-%H%M%S)
   mkdir -p "$STAGE"
   unzip -q "$ZIP" -d "$STAGE"
   cat "$STAGE/MANIFEST.txt"
   find "$STAGE" -name '*.js' -type f -exec node --check {} \;

   Если последняя команда ничего не напечатала — синтаксис корректен.

3. Резервная копия и остановка приложения

   BACKUP=/home/adminuser/childwatch-before-$(date +%Y%m%d-%H%M%S)
   mkdir -p "$BACKUP/data"
   cd /var/www/childwatch
   while read -r FILE; do
     [ -f "$FILE" ] || continue
     DIR=$(dirname "$FILE")
     [ "$DIR" != "." ] && mkdir -p "$BACKUP/$DIR"
     cp -a "$FILE" "$BACKUP/$FILE"
   done < <(grep '^  ' "$STAGE/MANIFEST.txt" | sed 's/^  //')
   cp -a data/childwatch.db* "$BACKUP/data/" 2>/dev/null || true
   sudo -iu adminuser pm2 stop childwatch
   echo "BACKUP=$BACKUP"

   Сохраните напечатанный путь — он нужен только для отката.

4. Установка и запуск

   cd /var/www/childwatch
   cd "$STAGE"
   find . -type f ! -name 'MANIFEST.txt' ! -name 'README.txt' -printf '%P\n' | while read -r FILE; do
     DIR=$(dirname "$FILE")
     [ "$DIR" != "." ] && install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"
     install -o adminuser -g adminuser -m 644 "$FILE" "/var/www/childwatch/$FILE"
   done
   cd /var/www/childwatch
   find . -name '*.js' -not -path './node_modules/*' -type f -exec node --check {} \;
   sudo -iu adminuser pm2 restart childwatch --update-env
   sleep 3
   curl -i --max-time 10 http://127.0.0.1:3000/api/health

5. Проверка результата

   Ожидается HTTP/1.1 200 OK и "version":"<версия из MANIFEST.txt>".
   Затем посмотреть логи:

   sudo -iu adminuser pm2 logs childwatch --lines 80 --nostream
   sudo -iu adminuser pm2 save

Откат при ошибке запуска
------------------------

   sudo -iu adminuser pm2 stop childwatch
   cd "$BACKUP"
   find . -type f ! -path './data/*' -printf '%P\n' | while read -r FILE; do
     DIR=$(dirname "$FILE")
     [ "$DIR" != "." ] && install -d -o adminuser -g adminuser -m 755 "/var/www/childwatch/$DIR"
     install -o adminuser -g adminuser -m 644 "$FILE" "/var/www/childwatch/$FILE"
   done
   cp -a data/childwatch.db* /var/www/childwatch/data/ 2>/dev/null || true
   chown -R adminuser:adminuser /var/www/childwatch/data
   sudo -iu adminuser pm2 restart childwatch --update-env
   curl -i --max-time 10 http://127.0.0.1:3000/api/health

Важно: после обновления оба приложения нужно переустановить новыми APK,
потому что часть изменений затрагивает протокол доступа (проверка владельца
устройства в запросах карты, медиа и прослушки).

Что меняется в этом обновлении
------------------------------

Полный список изменённых файлов — в MANIFEST.txt. Кратко по существу:

- критические алерты снова доходят до родителя (раньше POST /api/alerts
  отвечал 500, потому что вызывался несуществующий метод);
- доступ к алертам, медиа, прослушке, отладочным логам и геолокации проверяется
  по аутентифицированному устройству, а не по идентификатору из запроса;
- у маршрутов локации убрано перекрытие: защищённые обработчики больше не
  заслоняются неаутентифицированным роутером;
- лимит частоты запросов теперь применяет заявленное значение, а не жёсткие
  60/мин (из-за этого страдал канал локации ребёнка);
- согласован срок ожидания удалённого фото: сервер больше не отменяет запрос
  раньше, чем перестаёт ждать родитель, и продлевает срок, когда ребёнок
  подтвердил получение команды.
