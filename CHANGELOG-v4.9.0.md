# ChildWatch v4.9.0 & ParentWatch v5.6.0 - Исправления

**Дата**: 2025-10-21

---

## 🐛 Исправления

### ChildWatch v4.9.0:
1. **Прослушка** ✅
   - Исправлена ошибка типов `AudioQualityMode` → `AudioEnhancer.FilterMode`
   - Восстановлен метод `getModeName()` для отображения названий фильтров
   - Исправлен `AudioFilterItem` и `AudioFilterAdapter`
   - Прослушка теперь работает корректно со всеми фильтрами

2. **Чат** ✅
   - Исправлены синтаксические ошибки в `WebSocketClient`
   - Убран дублирующийся `onChatMessageCallback`
   - Добавлен правильный импорт `ChatMessage` из `ru.example.childwatch.chat`
   - Заменен `khttp` на `OkHttpClient` для HTTP запросов
   - Реализованы методы `getServerUrl()` и `getChildDeviceId()`
   - Добавлены импорты `Dispatchers`, `withContext`, `Context`
   - Исправлен конфликт метода `getDeviceId()` → переименован в `getChildDeviceId()`

3. **WebSocketManager** ✅
   - Добавлен параметр `onMissedMessages` для обработки непрочитанных сообщений
   - Исправлен импорт `ChatMessage`

### ParentWatch v5.6.0:
- Нет изменений (версия обновлена для синхронизации)

---

## 📋 Известные проблемы (требуют дальнейшей проверки):

1. **Геолокация - карта не отображается** ⚠️
   - Требуется проверка `LocationMapActivity`
   - Возможно, проблема с Google Maps API key или permissions

2. **Фильтры аудио отображаются по-старому** ⚠️
   - Layout файлы возможно не обновились
   - Требуется проверка `item_audio_filter.xml`

3. **Дистанционное фото не работает** ⚠️
   - Требуется проверка интеграции PhotoActivity и PhotoCaptureService
   - Проверка серверной части (WebSocket команды)

---

## 🔧 Технические изменения:

### Файлы:
- [AudioActivity.kt](app/src/main/java/ru/example/childwatch/AudioActivity.kt) - исправлены типы фильтров
- [AudioFilterItem.kt](app/src/main/java/ru/example/childwatch/audio/AudioFilterItem.kt) - изменен тип mode
- [AudioFilterAdapter.kt](app/src/main/java/ru/example/childwatch/audio/AudioFilterAdapter.kt) - изменены типы
- [ChatActivity.kt](app/src/main/java/ru/example/childwatch/ChatActivity.kt) - множественные исправления
- [WebSocketClient.kt](app/src/main/java/ru/example/childwatch/network/WebSocketClient.kt) - исправления синтаксиса и HTTP
- [WebSocketManager.kt](app/src/main/java/ru/example/childwatch/network/WebSocketManager.kt) - добавлен callback

### Версии:
- **ChildWatch**: 4.8.0 → 4.9.0 (versionCode 23)
- **ParentWatch**: 5.5.0 → 5.6.0 (versionCode 19)

---

## 🚀 Установка

### ChildWatch v4.9.0 (Родитель)
```bash
adb install app/build/outputs/apk/debug/ChildWatch-v4.9.0-debug.apk
```

### ParentWatch v5.6.0 (Ребенок)
```bash
adb install parentwatch/build/outputs/apk/debug/ChildDevice-v5.6.0-debug.apk
```

---

**Сгенерировано с помощью Claude Code** 🤖
