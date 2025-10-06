# 🎙️ Audio Streaming Test Instructions

## Шаг 1: Установка и подготовка

### ParentWatch (детский телефон)
1. Установите **ParentWatch v3.2.2** ([ParentWatch-v3.2.2-debug.apk](parentwatch/build/outputs/apk/debug/ParentWatch-v3.2.2-debug.apk))
2. Откройте приложение
3. При первом запуске должен появиться Toast: **"Новый ID устройства: child-XXXXXXXX"**
4. Проверьте Device ID на главном экране - должен быть формат: `child-XXXXXXXX` (14 символов)
5. **Нажмите кнопку "Копировать"** чтобы скопировать Device ID

### ChildWatch (родительский телефон)
1. Установите **ChildWatch v3.2.1** ([ChildWatch-v3.2.1-debug.apk](app/build/outputs/apk/debug/ChildWatch-v3.2.1-debug.apk))
2. Откройте приложение
3. Зайдите в **Settings (Настройки)**
4. **Вставьте скопированный Device ID** из ParentWatch в поле "ID устройства"
5. Выберите сервер:
   - **Railway Cloud**: `https://childwatch-production.up.railway.app`
   - **Локальный**: `http://10.0.2.2:3000` (только для эмуляторов)
6. **Сохраните настройки**

## Шаг 2: Проверка разрешений

### ParentWatch (детский телефон)
1. Откройте **Настройки Android** → **Приложения** → **ParentWatch** → **Разрешения**
2. Проверьте, что выданы разрешения:
   - ✅ **Микрофон** (обязательно!)
   - ✅ **Местоположение** → "Всегда разрешать"
   - ✅ **Уведомления**
3. Если разрешение на микрофон **НЕ ВЫДАНО** - включите его вручную!

## Шаг 3: Запуск мониторинга

### ParentWatch (детский телефон)
1. Вернитесь в приложение ParentWatch
2. Проверьте Server URL - должен совпадать с ChildWatch
3. Нажмите кнопку **"Запустить мониторинг"** (зеленая кнопка)
4. Проверьте статус - должно быть: **"Статус: Работает"** (зеленый текст)

## Шаг 4: Тест аудио стриминга

### ChildWatch (родительский телефон)
1. На главном экране найдите устройство child-XXXXXXXX
2. Нажмите на кнопку **"Прослушка"** (микрофон)
3. Откроется окно "Аудио прослушка"
4. Нажмите кнопку **"Начать прослушку"** (фиолетовая кнопка)

### Ожидаемый результат:
- ✅ Таймер начинает отсчет: 00:00:01, 00:00:02, ...
- ✅ **Фрагменты** увеличиваются: 1, 2, 3, ... (НЕ остаются на 0!)
- ✅ На ParentWatch в статус-баре появляется **значок микрофона** 🎙️
- ✅ В динамиках слышно окружение детского телефона

### Если не работает - проверьте:

**Проблема: Фрагменты = 0, нет звука**

Причины:
1. **Device ID не совпадают**
   - Проверьте: ID в ParentWatch и ID в ChildWatch Settings должны быть **одинаковыми**

2. **Разные Server URL**
   - Проверьте: URL в ParentWatch и URL в ChildWatch Settings должны быть **одинаковыми**
   - Railway: `https://childwatch-production.up.railway.app`

3. **Разрешение RECORD_AUDIO не выдано**
   - Проверьте: Settings → Apps → ParentWatch → Permissions → Microphone = ✅

4. **Мониторинг не запущен на ParentWatch**
   - Проверьте: Статус должен быть "Работает" (зеленый)

## Шаг 5: Проверка логов (для отладки)

### Railway Server Logs:
1. Откройте https://railway.app/project/childwatch-production
2. Перейдите в "Deployments" → "Logs"
3. Ищите строки:
   ```
   Device registered: child-XXXXXXXX
   🎙️ Audio streaming started for child-XXXXXXXX
   POST /api/streaming/chunk - 200
   ```

### Android Logcat (если подключен через USB):
```bash
# ParentWatch logs
adb logcat | grep -E "AudioStreamRecorder|LocationService|NetworkHelper"

# ChildWatch logs
adb logcat | grep -E "AudioStreamingActivity|NetworkClient"
```

Ищите строки:
```
AudioStreamRecorder: Starting audio streaming
AudioStreamRecorder: Chunk 0 uploaded successfully (176400 bytes)
AudioStreamRecorder: Chunk 1 uploaded successfully (176400 bytes)
```

Если видите:
```
AudioStreamRecorder: AudioRecord not initialized
AudioStreamRecorder: No audio data recorded
```
→ Проблема с разрешением RECORD_AUDIO!

## Шаг 6: Решение проблем

### Если ID не изменился после установки v3.2.2:
1. **Удалите ParentWatch полностью** (включая данные)
2. Перезагрузите телефон
3. Установите ParentWatch v3.2.2 заново
4. При первом запуске должен появиться новый ID

### Если фрагменты всё еще = 0:
1. Проверьте, что Device ID **точно совпадают** (скопируйте-вставьте, не вводите вручную)
2. Проверьте, что Server URL **точно совпадают** (включая https://)
3. Проверьте разрешение Microphone на ParentWatch
4. Перезапустите мониторинг на ParentWatch
5. Попробуйте снова "Начать прослушку" на ChildWatch

### Если слышно только шипение 2 секунды:
- Это означает, что ChildWatch получает **пустые audio chunks** (тишина)
- Скорее всего ParentWatch не отправляет настоящие audio chunks
- Проверьте разрешение RECORD_AUDIO!

## Версии приложений:
- **ParentWatch**: v3.2.2 (versionCode 7)
- **ChildWatch**: v3.2.1 (versionCode 6)
- **Server**: v1.1.0

## Формат Device ID:
- **Новый формат**: `child-XXXXXXXX` (14 символов, например `child-A1B2C3D4`)
- **Старый формат**: `child-c6d2c18b3632b5ac` (21 символ) - **НЕ РАБОТАЕТ!**

Если у вас старый формат - переустановите ParentWatch v3.2.2!
