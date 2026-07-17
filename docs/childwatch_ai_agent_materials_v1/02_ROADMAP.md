# ChildWatch — план реализации

## Этап 0. Защита 7.2
Ветка: `chore/baseline-7-2-protection`

- зафиксировать HEAD;
- проверить server tests;
- собрать оба Android-модуля;
- запустить `utf8Guard`;
- зафиксировать smoke-сценарии аудио, чата, фото и reconnect;
- не менять поведение.

## Этап 1. Единый ActiveContext
Ветка: `feat/family-context-foundation`

- canonical context;
- централизованная legacy migration;
- общий target для chat/map;
- namespace данных по профилю;
- unit tests;
- без редизайна.

## Этап 2. Семья на сервере
Ветка: `feat/server-family-model`

- families, members, devices, permissions;
- bootstrap из `device_links`;
- нейтральный `deviceSockets`;
- точная адресация;
- authorization tests.

## Этап 3. Сигнал внимания
Ветка: `feat/attention-signal`

- отдельный Socket.IO протокол;
- TTL и rate limit;
- Android playback;
- уведомление и local stop;
- remote stop;
- ACK/status;
- аудит;
- тесты на телефонах.

## Этап 4. Общая дизайн-система
Ветка: `feat/shared-design-system`

- общий resource/library module;
- цвета, типографика, карточки, кнопки, статусы;
- светлая/тёмная темы;
- отдельная диагностика;
- без полной миграции на Compose.

## Этап 5. Главные экраны
Ветка: `feat/home-screen-redesign`

- ParentMonitor dashboard;
- минимальный ChildDevice home;
- быстрые действия;
- скрыть raw IDs и серверные данные.

## Этап 6. Чат
Ветка: `feat/chat-conversations`

- conversations;
- shared chat core;
- pagination;
- unread;
- современный UI;
- offline/reconnect без дублей.

## Этап 7. Активность приложений
Ветка: `feat/app-usage-analytics`

- UsageEvents;
- локальные сессии;
- batch upload;
- серверная агрегация;
- экран today/yesterday/7/30.

## Этап 8. Семейная карта
Ветка: `feat/family-map`

- family location endpoint;
- участники и устройства;
- свежесть, точность, online, заряд;
- маршрут и геозоны;
- сигнал из карточки.

## Этап 9. Удаление legacy
Ветка: `refactor/remove-legacy-context`

Только после миграции существующих установок.
