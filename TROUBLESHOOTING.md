# ChildWatch - Руководство по устранению неполадок

## 🐛 Известные проблемы и решения

### 1. ✅ Кракозябры в тексте (ИСПРАВЛЕНО)
**Проблема:** Русский текст отображался как "кракозябры" (�����)
**Решение:** Исправлено в коммите ab48195 - все hardcoded строки заменены на getString() из strings.xml

---

### 2. 🔍 Device Status показывает "status not available"

**Исправлено в коммите 95300e9**, но требует обновления сервера!

**Что было:** Сервер использовал `deviceInfoFromAuth` (пустой объект) вместо `reportedDeviceInfo` из тела запроса.

**Что делать:**
1. **Обновите сервер на Railway:**
   ```bash
   cd server
   git pull
   # Railway автоматически задеплоит новую версию
   ```

2. **Перезапустите ParentWatch (child device):**
   - Остановите службу мониторинга
   - Запустите снова
   - Подождите 30 секунд для отправки location update

3. **Проверьте в ChildWatch (parent app):**
   - Откройте главный экран
   - Карточка "Child Device Status" должна показать данные

**Логи для отладки:**
```bash
# На ParentWatch (child device):
adb logcat -s LocationService:D DeviceInfoCollector:D NetworkHelper:D | grep -i "device\|battery"

# На ChildWatch (parent device):
adb logcat -s MainActivity:D NetworkClient:D | grep -i "status"

# На сервере (Railway):
# Смотрите логи в Railway Dashboard
# Должно быть: "Device status saved for [deviceId]: Battery XX%"
```

---

### 3. 🎧 Прослушка не работает (аудио не поступает)

**Возможные причины:**

#### A. WebSocket не подключен
**Проверка:**
```bash
# ParentWatch (child device):
adb logcat -s AudioStreamRecorder:D | grep -i "websocket\|connected\|audio"

# ChildWatch (parent device):
adb logcat -s AudioPlaybackService:D WebSocketClient:D | grep -i "websocket\|connected"
```

**Что смотреть:**
- `WebSocket connected successfully` - должно появиться на ОБОИХ устройствах
- `register_child` / `register_parent` - устройства должны зарегистрироваться
- `registered` event - сервер должен подтвердить

**Решение если не подключается:**
1. Проверьте server URL в настройках (должен быть Railway URL)
2. Проверьте что сервер работает: `curl https://childwatch-production.up.railway.app/api/health`
3. Проверьте интернет-соединение на ОБОИХ устройствах

#### B. Устройства используют разные Device ID
**Проверка:**
```bash
# ParentWatch:
adb logcat -s LocationService:D | grep "Device registered"
# Запишите deviceId

# ChildWatch Settings:
# Проверьте что Child Device ID совпадает!
```

**Решение:**
1. На ParentWatch: Настройки → посмотрите Device ID
2. На ChildWatch: Настройки → введите тот же Child Device ID
3. Перезапустите прослушку

#### C. Микрофон не записывает
**Проверка:**
```bash
adb logcat -s AudioStreamRecorder:D | grep -i "recording\|permission\|audio"
```

**Что смотреть:**
- `Audio permission not granted` - нет разрешения на микрофон
- `AudioRecord initialized` - должно быть при старте
- `Recorded X bytes` - должно появляться каждые ~100ms

**Решение:**
1. Проверьте разрешения на ParentWatch: Настройки → Разрешения → Микрофон
2. Перезапустите LocationService

#### D. Сервер не пересылает chunks
**Проверка логов сервера (Railway):**
```
🎵 Audio chunk received from [deviceId] (#N, XXXX bytes)
📤 Audio chunk #N forwarded to parent
```

**Если видите `📭 No parent connected`:**
- ChildWatch не подключился к WebSocket
- Проверьте что прослушка АКТИВНА на ChildWatch

---

### 4. 💬 Чат не работает (сообщения не приходят)

**Та же проблема что и с аудио - WebSocket!**

**Проверка:**
```bash
# ParentWatch:
adb logcat -s ChatBackgroundService:D WebSocketManager:D | grep -i "chat\|message\|websocket"

# ChildWatch:
adb logcat -s ChatBackgroundService:D ChatActivity:D | grep -i "chat\|message"
```

**Что должно быть:**
1. `ChatBackgroundService started` - на ОБОИХ устройствах
2. `WebSocket connected` - на ОБОИХ устройствах
3. `register_child` / `register_parent` - регистрация
4. `💬 Chat message forwarded` - в логах сервера

**Решение:**
1. **Убедитесь что ChatBackgroundService работает:**
   ```bash
   # ParentWatch:
   adb shell dumpsys activity services | grep ChatBackgroundService

   # ChildWatch:
   adb shell dumpsys activity services | grep ChatBackgroundService
   ```

2. **Если не работает - перезапустите чат:**
   - Закройте чат
   - Force Stop приложения
   - Откройте чат снова

3. **Проверьте что используется правильный server URL и device ID**

---

## 🔧 Общие рекомендации

### Для отладки WebSocket проблем:

1. **Проверьте connectivity:**
   ```bash
   # Тест подключения к серверу:
   curl https://childwatch-production.up.railway.app/api/health
   ```

2. **Проверьте что Socket.IO работает:**
   - Сервер должен отвечать на WebSocket connections
   - Порт должен быть открыт (Railway автоматически открывает)

3. **Синхронизация Device ID:**
   - ParentWatch device_id (в SharedPreferences)
   - ChildWatch child_device_id (в Settings)
   - Должны СОВПАДАТЬ!

### Логирование:

**Включить полное логирование:**
```bash
# Android:
adb logcat -s "*:D" | grep -i "childwatch\|parentwatch\|websocket\|audio\|chat"
```

**Сервер (Railway Dashboard):**
- Deploy → Logs
- Смотрите на:
  - `🔌 Client connected`
  - `📱 Child device registered`
  - `👨‍👩‍👧 Parent device registered`
  - `🎵 Audio chunk received`
  - `💬 Chat message forwarded`

---

## 📋 Checklist перед тестированием

- [ ] Сервер обновлен (коммит 95300e9 или новее)
- [ ] ParentWatch: Device ID настроен
- [ ] ChildWatch: Child Device ID = ParentWatch Device ID
- [ ] ParentWatch: Все разрешения предоставлены (Location, Microphone, Camera)
- [ ] ChildWatch: Все разрешения предоставлены (Location, Notifications)
- [ ] ParentWatch: LocationService запущен
- [ ] ChildWatch: ChatBackgroundService запущен (откройте чат 1 раз)
- [ ] Оба устройства имеют интернет (WiFi или Mobile Data)
- [ ] Server URL одинаковый на обоих устройствах (проверьте в Settings)

---

## 🆘 Если ничего не помогает

1. **Полная переустановка:**
   ```bash
   # ParentWatch:
   adb uninstall ru.example.parentwatch.debug
   adb install parentwatch/build/outputs/apk/debug/ChildDevice-v5.2.0-debug.apk

   # ChildWatch:
   adb uninstall ru.example.childwatch
   adb install app/build/outputs/apk/debug/ChildWatch-v4.4.0.apk
   ```

2. **Проверьте версии:**
   - ParentWatch: 5.2.0
   - ChildWatch: 4.4.0
   - Server: 1.3.1+

3. **Свяжитесь с разработчиком:**
   - Приложите логи с ОБОИХ устройств
   - Приложите логи сервера (Railway)
   - Опишите что именно не работает

---

## 🎯 Ожидаемое поведение (все работает)

### Device Status:
✅ ChildWatch главный экран показывает:
- Battery: XX% 🔋
- Charging: Yes/No
- Temperature: XX°C
- Device: [Model name]
- Android: vX.X SDK XX
- Updated: HH:MM

### Audio Streaming:
✅ ParentWatch: Зеленая полоса уровня микрофона
✅ ChildWatch: Таймер тикает, видна визуализация звука
✅ ChildWatch: Слышен звук из динамиков/наушников

### Chat:
✅ ParentWatch: Сообщения появляются мгновенно
✅ ChildWatch: Сообщения появляются мгновенно
✅ Уведомления приходят даже когда чат свернут
