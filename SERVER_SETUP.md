# Server Setup для реальных устройств

## Текущая настройка

Сейчас приложения используют:
- **ChildWatch**: `http://10.0.2.2:3000` (эмулятор → localhost)
- **ParentWatch**: `http://10.0.2.2:3000` (эмулятор → localhost)

## Для тестирования на реальных устройствах

### Вариант 1: Устройства в одной WiFi сети

1. **Узнайте IP адрес вашего компьютера:**

Windows:
```cmd
ipconfig
```
Найдите IPv4 Address вашего WiFi адаптера (например: `192.168.1.100`)

Linux/Mac:
```bash
ifconfig | grep inet
```

2. **Убедитесь что сервер слушает все интерфейсы:**

Откройте `server/index.js` и проверьте:
```javascript
const PORT = process.env.PORT || 3000;
const HOST = '0.0.0.0'; // Слушаем все интерфейсы

app.listen(PORT, HOST, () => {
    console.log(`Server running on http://${HOST}:${PORT}`);
});
```

3. **Запустите сервер:**
```bash
cd server
npm run dev
```

4. **Откройте порт в файерволе (Windows):**
```powershell
netsh advfirewall firewall add rule name="ChildWatch Server" dir=in action=allow protocol=TCP localport=3000
```

5. **В приложениях измените URL сервера:**
   - **ChildWatch**: Settings → Server URL → `http://192.168.1.100:3000`
   - **ParentWatch**: Main screen → Server URL → `http://192.168.1.100:3000`

### Вариант 2: ngrok (тестирование через интернет)

1. **Установите ngrok:**
   - Скачайте с [ngrok.com](https://ngrok.com/download)
   - Распакуйте в любую папку

2. **Запустите сервер:**
```bash
cd server
npm run dev
```

3. **Запустите ngrok:**
```bash
ngrok http 3000
```

4. **Скопируйте URL** (например: `https://abc123.ngrok.io`)

5. **В приложениях используйте ngrok URL:**
   - **ChildWatch**: Settings → Server URL → `https://abc123.ngrok.io`
   - **ParentWatch**: Main screen → Server URL → `https://abc123.ngrok.io`

### Вариант 3: Облачный сервер (production)

Для production рекомендуется развернуть сервер на:
- **Heroku** (бесплатный tier)
- **Railway.app** (бесплатный tier)
- **Render.com** (бесплатный tier)
- **DigitalOcean** ($5/month)
- **AWS EC2** (бесплатный tier 12 месяцев)

## Проверка подключения

После настройки сервера проверьте доступность:

### На компьютере:
```bash
curl http://YOUR_SERVER_URL/api/health
```

### На телефоне (в браузере):
Откройте `http://YOUR_SERVER_URL/api/health`

Должен вернуться JSON:
```json
{
  "status": "healthy",
  "message": "ChildWatch Server is running",
  "version": "1.0.0"
}
```

## Важно для production

1. **Используйте HTTPS** (сертификат Let's Encrypt бесплатный)
2. **Настройте переменные окружения**:
```bash
PORT=3000
NODE_ENV=production
JWT_SECRET=your-secret-key-here
```
3. **Используйте PostgreSQL вместо SQLite** для больших объемов данных
4. **Настройте rate limiting** (уже есть в коде)
5. **Настройте мониторинг** (PM2, Winston logs)

## Текущий URL по умолчанию

В обоих приложениях URL сервера можно изменить в настройках:
- Это сохраняется в SharedPreferences
- Не требует пересборки приложения
