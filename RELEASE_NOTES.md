# ChildWatch v3.0 - Release Notes

## 🎉 Новые возможности

### ChildWatch (приложение родителей)
- ✅ **QR-сканер** - быстрое добавление Device ID ребенка через QR-код
- ✅ **Google Maps интеграция** - отображение местоположения ребенка на карте
- ✅ **Настраиваемый URL сервера** - поддержка удаленных серверов для реальных устройств
- ✅ **ML Kit Barcode Scanning** - быстрое и надежное сканирование QR-кодов
- ✅ **Улучшенный UI** - кнопка QR в настройках рядом с полем Device ID

### ParentWatch (приложение ребенка)
- ✅ **Исправленный UI** - красивый и интуитивный интерфейс
- ✅ **Правильная обработка разрешений** - раздельный запрос foreground и background location
- ✅ **QR-код генерация** - легкая передача Device ID родителям
- ✅ **Убраны отладочные логи** - готово для production
- ✅ **Настраиваемый URL сервера** - поддержка удаленных серверов

## 📦 Готовые APK файлы

Собранные приложения находятся в:
- **ChildWatch**: `app/build/outputs/apk/debug/app-debug.apk`
- **ParentWatch**: `parentwatch/build/outputs/apk/debug/parentwatch-debug.apk`

## 🚀 Быстрый старт

### 1. Установка приложений

```bash
# Установить ChildWatch на телефон родителя
adb install app/build/outputs/apk/debug/app-debug.apk

# Установить ParentWatch на телефон ребенка
adb install parentwatch/build/outputs/apk/debug/parentwatch-debug.apk
```

### 2. Запуск сервера

#### Для тестирования (локальная сеть):
```bash
cd server
npm install
npm run dev
```

Сервер будет доступен на `http://YOUR_IP:3000`

#### Для тестирования через интернет (ngrok):
```bash
# В одном терминале
cd server
npm run dev

# В другом терминале
ngrok http 3000
```

Используйте ngrok URL в приложениях (например: `https://abc123.ngrok.io`)

### 3. Настройка ParentWatch (телефон ребенка)

1. Откройте ParentWatch
2. **Измените URL сервера** на ваш IP или ngrok URL
3. Скопируйте **Device ID** (4 символа) или нажмите **QR-код**
4. Нажмите **ЗАПУСТИТЬ МОНИТОРИНГ**
5. Разрешите доступ к геолокации:
   - **While using the app** (обязательно)
   - **Allow all the time** (рекомендуется для фонового режима)

### 4. Настройка ChildWatch (телефон родителя)

1. Пройдите согласие (Consent Screen)
2. В главном экране нажмите **Settings** (замок - вход без пароля временно отключен)
3. **Измените URL сервера** на тот же, что и в ParentWatch
4. В поле **Child Device ID**:
   - **Вариант А**: Нажмите кнопку **QR** и отсканируйте QR-код с телефона ребенка
   - **Вариант Б**: Введите 4 символа Device ID вручную
5. Сохраните настройки
6. Вернитесь в главное меню и нажмите **Geolocation** для просмотра местоположения на карте

## 🗺️ Google Maps API (опционально)

Карта будет работать после получения API ключа. См. [GOOGLE_MAPS_SETUP.md](GOOGLE_MAPS_SETUP.md)

**Без API ключа:**
- Координаты отображаются текстом
- Можно открыть в браузере/картах телефона

## 🌐 Настройка сервера для реальных устройств

См. подробную инструкцию в [SERVER_SETUP.md](SERVER_SETUP.md)

## 📋 Что работает

✅ **Регистрация устройств** через Device ID
✅ **Отправка геолокации** каждые 30 секунд
✅ **Получение последней геолокации** ребенка
✅ **История перемещений** (до 100 записей)
✅ **Foreground Service** с уведомлением
✅ **Автозапуск** после перезагрузки
✅ **QR-код** для передачи Device ID
✅ **Настраиваемый URL** сервера
✅ **Token-based аутентификация**

## 🔧 Технические детали

### Архитектура
- **Frontend**: Android (Kotlin) + Material Design 3
- **Backend**: Node.js + Express + SQLite
- **Networking**: Retrofit + OkHttp
- **Maps**: Google Maps SDK for Android
- **QR Scanning**: ML Kit Barcode Scanning
- **Location**: Google Play Services Location API

### Разрешения
- `ACCESS_FINE_LOCATION` - точная геолокация
- `ACCESS_BACKGROUND_LOCATION` - фоновая геолокация (Android 10+)
- `FOREGROUND_SERVICE_LOCATION` - foreground service
- `POST_NOTIFICATIONS` - уведомления (Android 13+)
- `CAMERA` - QR-сканер
- `INTERNET` - сетевые запросы

### Безопасность
- JWT tokens для аутентификации
- Rate limiting (60 requests/min)
- Input validation
- Encrypted SharedPreferences (готово для production)

## 🐛 Известные ограничения

- Google Maps требует API ключ (бесплатно до $200/месяц)
- Сервер на SQLite (для production рекомендуется PostgreSQL)
- Debug APK (для production нужен release с подписью)
- Временно отключена биометрическая аутентификация в Settings

## 📝 Следующие шаги

Для полноценного production:
1. Получить Google Maps API ключ
2. Создать release APK с подписью
3. Развернуть сервер на облачном хостинге
4. Настроить HTTPS
5. Включить биометрическую аутентификацию
6. Добавить push-уведомления (Firebase)

## 📄 Лицензия

MIT License - см. LICENSE файл

## 🤝 Поддержка

Вопросы и баг-репорты: создайте Issue в GitHub

---

🤖 Generated with Claude Code
Version: 3.0.0
Date: 2025-10-05
