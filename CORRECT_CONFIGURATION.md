# ✅ ПРАВИЛЬНАЯ КОНФИГУРАЦИЯ ПРОЕКТА

## 📱 Приложения и их назначение:

### ChildWatch (app/)

- **Описание:** Родительское приложение
- **Назначение:** Устанавливается на телефон РОДИТЕЛЯ
- **Функции:** Просмотр местоположения детей, прослушивание, чат
- **Package:** `ru.example.childwatch`
- **APK:** `app/build/outputs/apk/debug/ChildWatch-v5.5.0-debug.apk`

### ParentWatch (parentwatch/) = ChildDevice

- **Описание:** Детское приложение
- **Назначение:** Устанавливается на телефон РЕБЕНКА
- **Функции:** Отправка геолокации, запись аудио, мониторинг
- **Package:** `ru.example.parentwatch.debug` (debug) или `ru.example.parentwatch` (release)
- **APK:** `parentwatch/build/outputs/apk/debug/ChildDevice-v6.3.0-debug.apk`

---

## 🎯 ПРАВИЛЬНОЕ РАСПРЕДЕЛЕНИЕ:

```
┌────────────────────────────────────────────┐
│          РОДИТЕЛЬ (Nokia G21)              │
│                                            │
│     📱 ChildWatch (app/)                   │
│     Смотрит за детьми                      │
│     Package: ru.example.childwatch         │
│                                            │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│       РЕБЕНОК (Pixel 8 Emulator)           │
│                                            │
│   👶 ParentWatch (parentwatch/)            │
│      = ChildDevice                         │
│      Отправляет данные родителю            │
│      Package: ru.example.parentwatch.debug │
│                                            │
└────────────────────────────────────────────┘
```

---

## ✅ Текущая установка (ПРАВИЛЬНО):

- **Nokia G21:** ChildWatch v5.5.0 (родительское) ✅
- **Pixel 8 Emulator:** ParentWatch/ChildDevice v6.3.0 (детское) ✅

---

## 📋 Команды для правильной установки:

### Собрать оба приложения:

```powershell
.\gradlew.bat assembleDebug
```

### Установить ChildWatch (родительское) на Nokia:

```powershell
adb -s PT19655KA1280800674 install -r app/build/outputs/apk/debug/ChildWatch-v5.5.0-debug.apk
adb -s PT19655KA1280800674 shell monkey -p ru.example.childwatch -c android.intent.category.LAUNCHER 1
```

### Установить ParentWatch (детское) на эмулятор:

```powershell
adb -s emulator-5554 install -r parentwatch/build/outputs/apk/debug/ChildDevice-v6.3.0-debug.apk
adb -s emulator-5554 shell am start -n ru.example.parentwatch.debug/ru.example.parentwatch.MainActivity
```

---

## 🔄 Обновление скриптов:

Нужно обновить `dev-workflow.ps1` чтобы:

1. **app/** (ChildWatch) → Nokia G21 (родитель)
2. **parentwatch/** (ChildDevice) → Pixel 8 (ребенок)

---

## 💡 Запутанные названия (для понимания):

| Папка          | Реальное название приложения | Кто использует | Package name                   |
| -------------- | ---------------------------- | -------------- | ------------------------------ |
| `app/`         | **ChildWatch**               | РОДИТЕЛЬ       | `ru.example.childwatch`        |
| `parentwatch/` | **ParentWatch/ChildDevice**  | РЕБЕНОК        | `ru.example.parentwatch.debug` |

**Почему путаница?**

- Папка называется `parentwatch`, но это приложение для ребенка
- Приложение `ChildWatch` используется родителем, а не ребенком

**Логика названий:**

- `ChildWatch` = родитель "смотрит" (watch) за ребенком (child)
- `ParentWatch` = ребенок "под наблюдением" родителя (parent)

---

## 🎯 ИТОГО - что и куда:

**Родитель (Nokia):**

- Ставит **ChildWatch** (app/)
- Видит местоположение детей
- Может слушать аудио
- Может писать в чат

**Ребенок (Emulator):**

- Ставит **ParentWatch** (parentwatch/) = ChildDevice
- Отправляет свою геолокацию
- Записывает аудио по команде
- Получает сообщения от родителя

---

**Теперь всё правильно!** ✅
