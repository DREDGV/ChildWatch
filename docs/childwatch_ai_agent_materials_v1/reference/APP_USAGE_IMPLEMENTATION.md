# Полноценная активность приложений

## Недостаток текущего подхода

Снимок `UsageStats` внутри `device_status` не даёт надёжных сессий и зависит от общей телеметрии. Дневной `totalTimeInForeground` нельзя считать временем выбранного короткого окна без вычисления дельты.

## ChildDevice Room

```text
app_usage_cursor
- deviceId
- lastProcessedTimestamp

app_usage_sessions
- localId
- sessionId
- familyId
- memberId
- deviceId
- packageName
- appName
- startedAt
- endedAt
- durationMs
- uploadState
- createdAt
```

## Collector

1. Проверить Usage Access.
2. Взять cursor.
3. `UsageStatsManager.queryEvents(cursor, now)`.
4. Обработать foreground/resumed и background/paused.
5. Хранить текущую открытую сессию.
6. При переключении закрывать предыдущую.
7. Ограничить аномально длинную сессию.
8. Фильтровать launcher/System UI по конфигурации.
9. Сохранять транзакционно.
10. Сдвигать cursor только после commit.

## Upload

```http
POST /api/app-usage/sessions/batch
```

```json
{
  "familyId": "family",
  "memberId": "member",
  "deviceId": "device",
  "sessions": [
    {
      "sessionId": "uuid",
      "packageName": "com.example",
      "appName": "Example",
      "startedAt": 0,
      "endedAt": 0,
      "durationMs": 0
    }
  ]
}
```

Уникальность: `device_id + session_id`.

## Чтение

```http
GET /api/families/{familyId}/members/{memberId}/app-usage?from=&to=
```

Ответ:

```text
totalDurationMs
firstUseAt
lastUseAt
launchCount
apps[]
hourly[]
```

## UI

- today/yesterday/7/30;
- total и сравнение;
- список приложений;
- число запусков;
- почасовая диаграмма;
- иконки;
- permission state;
- stale warning;
- hide system apps.

## Тесты

- foreground/background pair;
- missing background;
- app switch;
- collector restart;
- duplicate batch;
- midnight/timezone;
- permission revoked;
- system filter;
- несколько устройств одного участника.
