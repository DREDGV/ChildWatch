# ChildWatch v4.10.0 & ParentWatch v5.7.0 - Релиз

**Дата**: 2025-10-21

---

## 📱 ChildWatch v4.10.0 (Родительское приложение)

### ✨ Исправления и улучшения

#### 🎧 **Унификация системы аудио фильтров**
- ✅ Удалена старая система AudioQualityMode
- ✅ Все компоненты переведены на AudioEnhancer.FilterMode
- ✅ AudioStreamingActivity полностью переработан
- ✅ Упрощена архитектура фильтров

**Изменения в AudioStreamingActivity**:
- Удалена зависимость от AudioQualityManager
- Переход на прямое использование FilterMode
- Убраны "расширенные настройки" (custom mode)
- Упрощена синхронизация с сервисом

**Изменения в AudioPlaybackService**:
- Удалены устаревшие методы `updateAudioEnhancerConfig()` и `updateAudioEnhancer()`
- Оставлен только `setFilterMode(mode)` для изменения фильтра
- Более чистая архитектура

### 🔧 Технические детали
- **Версия**: 4.9.1 → 4.10.0
- **Version Code**: 24 → 25
- **Удаленные файлы**:
  - `audio/AudioQualityModes.kt` - старая система фильтров
- **Обновленные файлы**:
  - `AudioStreamingActivity.kt` - переход на FilterMode
  - `service/AudioPlaybackService.kt` - удаление устаревших методов

---

## 📱 ParentWatch v5.7.0 (Детское приложение)

### 📄 Технические детали
- **Версия**: 5.6.1 → 5.7.0
- **Version Code**: 20 → 21
- Синхронизация версии для совместимости с ChildWatch v4.10.0

---

## 🎯 Система фильтров (AudioEnhancer.FilterMode)

### Доступные режимы:

1. **ORIGINAL** 📡
   - Оригинальный звук без обработки
   - Проход аудио без изменений
   - Лучшее качество по умолчанию

2. **VOICE** 🎤
   - Усиление речи
   - Подавление фонового шума
   - Оптимизация для голосовой связи

3. **QUIET_SOUNDS** 🔇
   - Максимальное усиление тихих звуков
   - Увеличенный gain
   - Для прослушивания шепота

4. **MUSIC** 🎵
   - Естественное звучание музыки
   - Сбалансированная обработка
   - Без агрессивного шумоподавления

5. **OUTDOOR** 🌳
   - Подавление ветра и уличного шума
   - Агрессивное шумоподавление
   - Для улицы и помещений с шумом

---

## 🔄 Архитектура фильтров

### Поток обработки:
```
UI (AudioActivity/AudioStreamingActivity)
    │
    ├─ setFilterMode(mode: FilterMode)
    │   └─ SharedPreferences.save("filter_mode")
    │   └─ BroadcastIntent("UPDATE_FILTER_MODE")
    │
AudioPlaybackService
    │
    ├─ BroadcastReceiver.onReceive()
    │   └─ setFilterMode(mode)
    │       └─ AudioEnhancer.updateConfig(mode)
    │
    └─ Audio Processing Loop
        └─ AudioEnhancer.process(audioData)
            └─ when (mode) {
                ORIGINAL -> passthrough
                VOICE -> processVoiceMode()
                QUIET_SOUNDS -> processQuietSoundsMode()
                MUSIC -> processMusicMode()
                OUTDOOR -> processOutdoorMode()
              }
```

---

## 🚀 Установка

### ChildWatch v4.10.0 (Родитель)
```bash
adb install app/build/outputs/apk/debug/ChildWatch-v4.10.0-debug.apk
```

### ParentWatch v5.7.0 (Ребенок)
```bash
adb install parentwatch/build/outputs/apk/debug/ChildDevice-v5.7.0-debug.apk
```

---

## 🔄 Миграция

Обновление с v4.9.1/v5.6.1 безопасно:
- Настройки фильтров сохраняются (ключ изменен с "quality_mode" на "filter_mode")
- Все данные остаются
- Совместимость с сервером не нарушена

**Важно**: После обновления фильтр по умолчанию - ORIGINAL (чистый звук без обработки)

---

## 🎯 Что исправлено

### Проблема: Смешение двух систем фильтров
**До**:
- AudioActivity использовал FilterMode
- AudioStreamingActivity использовал AudioQualityMode
- AudioQualityMode создавал Config без поля `mode`
- Параметры (gainBoostDb, noiseSuppressionEnabled) игнорировались в AudioEnhancer.process()

**После**:
- Все используют только FilterMode
- Единая система фильтрации
- Четкая семантика каждого режима
- Код проще и понятнее

### Проблема: Качество звука по умолчанию
**Решение**:
- По умолчанию FilterMode.ORIGINAL
- В режиме ORIGINAL аудио проходит без обработки
- Сохраняется исходное качество PCM 16-bit 44100 Hz

---

## 📊 Следующие шаги

1. **Тестирование аудио качества**
   - Проверить ORIGINAL mode - должен быть чистый звук
   - Протестировать каждый фильтр на реальных устройствах
   - Убедиться в стабильности WebSocket соединения

2. **Исправление других проблем** (из предыдущих тестов):
   - Чат работает только с ребенка к родителю (нужен bidirectional)
   - Удаленная камера не подключается (WebSocket)
   - Карта геолокации не отображается

3. **Оптимизация**
   - Улучшить алгоритмы фильтров
   - Добавить визуальную индикацию активного фильтра
   - Настройки компрессии/экспандера для VOICE режима

---

**Сгенерировано с помощью Claude Code** 🤖
