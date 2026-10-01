# CW-03 — семейное подключение

01.10.2026. Локальная разработка и проверка; публикация и реальные телефоны отдельно.

## Результат

- Два режима: новый человек и новый телефон существующего участника. Имя, роль, аватар и старые телефоны существующего участника сохраняются.
- Взрослый создаёт QR и передаёт его изображением со ссылкой. Получатель может сканировать камерой, выбрать QR из галереи или вставить ссылку. ML Kit bundled работает без скачивания модели.
- Оба приложения показывают семью, человека, роль и результат **до** подтверждения. ChildDevice принимает только ребёнка, ParentMonitor — взрослого; ошибочный клиент не расходует приглашение.
- Одноразовость, истечение, отзыв, отсутствие сети и повторное нажатие обработаны; редактирование введённого кода отменяет прежнюю проверку. Созданный QR отслеживает принятие и отключает дальнейшую передачу.
- Переработаны формы, выбор роли/существующего человека, QR-карточка, приём взрослого и отказ в камере. Новый вид выбора аватара: внешнее кольцо с зазором, подложка и галочка, одинаковый размер и яркость вариантов, checked semantics.
- Исправлены Android 14 ошибки геолокации без разрешения: проверка до запуска adult service; child service и все входы запуска, включая обработчик обновления, настройки и смену профиля. Room не менялся.

Основные файлы: `FamilyInviteActivity`, оба `FamilyJoinActivity`/`QrScannerActivity`, `ParentInvitationCompletion`, `FamilyInvitationQr`, `FamilyInvitationTokenParser`, `ParentLocationService`, детские `LocationService`/`BootReceiver`/`ChildProfileRuntimeCoordinator`.

## Проверки и уровень доказательства

| Сценарий | Доказательство |
|---|---|
| Сервер: новые CHILD/PARENT/GUARDIAN; preview/accept/replay; новый телефон; ошибки роли/семьи/срока/отзыва | 30 тестов, 2 suites PASS. Реальные семейные сервисы и SQLite, HTTP; actor header только в тестовом middleware |
| Kotlin parser / entry / role policies | Shared-core и целевые FamilyInvitation проверки PASS; оба клиента скомпилированы |
| QR реально читается | Production generator → ZXing decode в 240/640px PASS; production QR PNG → ML Kit gallery decode в обоих приложениях; детский сценарий использует actual FileProvider export |
| Новый взрослый | Deep link → preview Мама/PARENT → accept → реальная fixture family выросла до 2 участников; [preview](evidence/family-onboarding-2026-10-01/parent-preview.png), [done](evidence/family-onboarding-2026-10-01/parent-done.png) |
| Новый ребёнок | Создан QR в UI взрослого → Android share chooser → actual PNG → галерея ChildDevice → Lev/CHILD preview → accept; family 3 участника. [preview](evidence/family-onboarding-2026-10-01/child-qr-confirm.png), [done](evidence/family-onboarding-2026-10-01/child-joined.png) |
| Приглашающий видит успех | Возврат к создателю + polling → использовано, передача отключена. [экран](evidence/family-onboarding-2026-10-01/creator-consumed.png) |
| Новый телефон существующей Мамы | QR в UI → ParentMonitor ML Kit gallery → accept; участников осталось 3, телефонов Мамы стало 2. [preview](evidence/family-onboarding-2026-10-01/adult-qr-confirm.png), [done](evidence/family-onboarding-2026-10-01/adult-qr-joined.png) |
| Отказ в камере | Сканер остаётся открыт, галерея доступна; [экран](evidence/family-onboarding-2026-10-01/child-scanner-without-camera.png) |
| Камера сканера | CameraX preview действительно открылся после выдачи разрешения на эмуляторе. Оптическое считывание QR с другого телефона **не проверено** |
| Неправильное приложение | CHILD клиент отклонил PARENT invite; canonical server isConsumed=false. Противоположное направление дополнительно покрыто HTTP тестами |
| Новое выделение аватара | На 130% шрифте переключено корги → робот: единственный checked элемент, кольцо/галочка не обрезаны, размеры остальных не меняются. [экран](evidence/family-onboarding-2026-10-01/avatar-final-robot.png) |

Среда: отдельный Android 14 API34 эмулятор; временная SQLite `:memory:`, настоящий Bearer middleware и семейные routes на локальном fixture сервере. Семья/имена/устройства синтетические; боевой сервер и телефоны не менялись. Скриншоты содержат только тестовые приглашения.

Команды: сервер `node node_modules/jest/bin/jest.js --runInBand __tests__/family-onboarding.test.js __tests__/family-join-existing-member.test.js`; Kotlin через `scripts/run-gradle-safe.ps1 :shared-core:test :app:testDebugUnitTest --tests '*FamilyInvitation*' :app:compileDebugKotlin :parentwatch:compileDebugKotlin`; debug сборка/установка через `scripts/build-and-install.ps1`, VersionCode 2000000268.

APK: `app/build/outputs/apk/debug/ParentMonitor-v7.4.268-debug.apk`, `parentwatch/build/outputs/apk/debug/ChildDevice-v7.4.268-debug.apk`. Это локальные debug артефакты, не опубликованный выпуск. Build both 03:38:27 PASS, 132с; child после BootReceiver 03:44:12 PASS, 90с, установлен поверх 03:44:56. После обновления и отказа в location/audio/notifications crash buffer пуст; Logcat подтверждает пропущенный запуск без разрешения.

## Что осталось

- Настоящие два телефона: оптическая камера, отказ/возврат разрешений, подключение через текущий сервер, повторный вход после обновления. Локальная среда не доказывает VPS публикацию.
- Восстановление семьи без другого взрослого — CW-11; не включено в обычное приглашение.
- Детский главный экран содержит статические обещания защиты/связи даже без разрешений — находка записана в CW-05. Следующий рекомендуемый результат: честное состояние телефона и понятное действие для восстановления работы.
- Дизайн-подготовка и ограничения инструментов — [design-bootstrap](design-bootstrap.md), [DESIGN](../DESIGN.md), [референсы](../DESIGN_REFERENCES.md).
