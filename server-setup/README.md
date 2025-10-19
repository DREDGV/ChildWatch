# 🚀 Автоматическая настройка сервера ChildWatch

## 📋 Что у вас есть:

В папке `C:\Users\dr-ed\ChildWatch\server-setup\` находятся:

1. ✅ **setup-childwatch.sh** - автоматический скрипт установки
2. ✅ **ИНСТРУКЦИЯ.txt** - подробная инструкция
3. ✅ **README.md** - этот файл

---

## 🎯 Быстрый старт (3 простых шага):

### Шаг 1️⃣: Переустановите VDS на Ubuntu 22.04

Зайдите на https://cp.hoster.ru → Ваш VDS → Изменить шаблон → Ubuntu 22.04

### Шаг 2️⃣: Загрузите скрипт на сервер

**Вариант A - Через WinSCP (рекомендую):**
1. Скачайте WinSCP: https://winscp.net/eng/download.php
2. Подключитесь: 31.28.27.96, логин: root
3. Перетащите файл `setup-childwatch.sh` в папку `/root/`

**Вариант B - Через PuTTY (сложнее):**
```bash
nano /root/setup-childwatch.sh
# Вставьте содержимое файла
# Ctrl+X, Y, Enter
```

### Шаг 3️⃣: Запустите скрипт

В PuTTY введите:
```bash
chmod +x /root/setup-childwatch.sh
/root/setup-childwatch.sh
```

Подождите 5-10 минут. Готово! ✅

---

## 📦 Что установит скрипт:

- ✅ Node.js 20.x
- ✅ NPM (менеджер пакетов)
- ✅ PM2 (менеджер процессов)
- ✅ Nginx (веб-сервер)
- ✅ Certbot (SSL сертификаты)
- ✅ UFW Firewall (защита)
- ✅ Папки проекта

---

## 🔥 Следующие шаги после установки:

### 1. Загрузить код backend на сервер

```bash
cd /var/www/childwatch
git clone https://github.com/YOUR_USERNAME/childwatch-backend.git .
```

### 2. Установить зависимости

```bash
cd /var/www/childwatch
npm install
```

### 3. Создать .env файл

```bash
nano .env
```

Пример содержимого:
```
PORT=3000
NODE_ENV=production
DATABASE_URL=your_database_url
JWT_SECRET=your_secret_key
```

### 4. Запустить приложение

```bash
pm2 start server.js --name childwatch
pm2 save
pm2 startup
```

### 5. Настроить Nginx (опционально)

```bash
nano /etc/nginx/sites-available/childwatch
```

### 6. Установить SSL сертификат (опционально)

```bash
certbot --nginx -d yourdomain.com
```

---

## 🛠️ Полезные команды PM2:

```bash
pm2 list                  # Список процессов
pm2 logs childwatch       # Просмотр логов
pm2 restart childwatch    # Перезапуск
pm2 stop childwatch       # Остановка
pm2 delete childwatch     # Удаление
pm2 monit                 # Мониторинг в реальном времени
```

---

## 🔒 Firewall правила:

Скрипт автоматически откроет порты:
- ✅ 22 (SSH)
- ✅ 80 (HTTP)
- ✅ 443 (HTTPS)
- ✅ 3000 (Node.js)

Проверка: `ufw status`

---

## ❓ Если что-то не работает:

### Проблема: Скрипт не запускается

**Решение:**
```bash
chmod +x /root/setup-childwatch.sh
bash /root/setup-childwatch.sh
```

### Проблема: Node.js не установился

**Решение:**
```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs
```

### Проблема: Firewall заблокировал SSH

**Решение:**
Через панель Hoster.ru → Консоль → Введите:
```bash
ufw allow 22/tcp
```

---

## 📞 Поддержка

Если возникли проблемы - пришлите скриншот ошибки в чат.

**Логи для диагностики:**
```bash
journalctl -xe          # Системные логи
pm2 logs childwatch     # Логи приложения
nginx -t                # Проверка Nginx
```

---

## 📊 Структура проекта после установки:

```
/var/www/childwatch/    # Код приложения
/var/log/childwatch/    # Логи
/etc/nginx/             # Конфигурация Nginx
/root/.pm2/             # PM2 процессы
```

---

## ⚡ Производительность

**Характеристики VDS:**
- CPU: 2 vCore
- RAM: 2048 MB
- Диск: 20 GB

**Рекомендации:**
- Используйте PM2 для автозапуска
- Настройте мониторинг через `pm2 monit`
- Регулярно проверяйте `df -h` (место на диске)
- Регулярно проверяйте `free -h` (память)

---

## 🎉 Готово!

После выполнения всех шагов у вас будет:
- ✅ Рабочий сервер на Ubuntu 22.04
- ✅ Node.js приложение под PM2
- ✅ Nginx reverse proxy
- ✅ SSL сертификат (опционально)
- ✅ Защищенный firewall

**Следующий шаг:** Обновите URL в Android приложениях с Railway на ваш новый сервер!

---

*Создано: Claude AI Assistant*
*Версия скрипта: 1.0*
*Дата: 2025*
