# Промт агенту: серверная семейная модель

Выполни только server family foundation.

## Требуется

1. Добавить:
   - `families`;
   - `family_members`;
   - `family_devices`;
   - `family_permissions`.
2. Идемпотентный bootstrap из `device_links`.
3. Не удалять `device_links`.
4. Добавить `deviceSockets: Map<deviceId, Set<socketId>>`.
5. Регистрировать реальный authenticated deviceId обеих ролей.
6. Добавить exact-device routing.
7. Не менять аудиомаршрутизацию без необходимости.
8. Добавить endpoints чтения семьи/участников/устройств.
9. Добавить permission service.
10. Тесты:
    - bootstrap;
    - повтор migration;
    - member/device separation;
    - cross-family denial;
    - exact routing;
    - reconnect cleanup.

## Ограничения

- не добавлять UI;
- не реализовывать сигнал;
- не удалять legacy;
- не делать fallback exact target на другое устройство.

Верни migrations, API contract, тесты и SHA.
