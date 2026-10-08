# Wi-Fi как дополнительное свидетельство места — проверка 08.10.2026

Статус: исследование исходников и официальной документации. Реализация, новые разрешения, сборки, подключение телефонов и сервер не затрагивались.

## Что действительно существует

- ChildDevice `parentwatch/build.gradle:9,14`: compile/target SDK34.
- `parentwatch/src/main/java/ru/example/parentwatch/diagnostics/MetricsManager.kt:257–315`: определяет транспорт активного default network. На Android10+ `getWifiNetworkName()` безусловно возвращает строку «Wi-Fi», не SSID. На старых OS пытается connectionInfo.ssid. Ветка Wi-Fi задаёт GOOD константой, не измерением RSSI. BSSID не собирается. `exportDiagnosticsJson` имеет только объявления в обоих клиентах, найденного пути его автоматической отправки нет.
- Child manifest имеет FINE/COARSE/BACKGROUND_LOCATION и ACCESS_NETWORK_STATE, но не ACCESS_WIFI_STATE/NEARBY_WIFI_DEVICES. Parent manifest имеет ACCESS_WIFI_STATE; разрешение другого applicationId не распространяется на ребёнка.
- `DeviceInfoCollector.kt:98–110`: батарея, устройство, камера, locationReadiness, usage. SSID/BSSID/RSSI в payload нет. `LocationService.kt:442–459` отправляет статус раз в60с; `:542–545` присоединяет его к координатам.
- `network/DefaultNetworkRecoveryObserver.kt:28–61` наблюдает валидированный default route и восстанавливает транспорт с задержкой500мс. Он не измеряет Wi-Fi и не извлекает WifiInfo; VPN/default route и connected Wi-Fi — разные факты. Его критерий INTERNET+VALIDATED нельзя использовать как признак отсутствия подключённой сети без интернета.
- Сервер `index.js:533–608,900–972` принимает deviceInfo, а `DatabaseManager.js:5008–5063` сохраняет raw JSON в device_status.status_json. Отдельной Wi-Fi модели/валидации/retention/геозонного происхождения нет. Нельзя просто добавить сырые идентификаторы в общий raw: это создаст лишнюю историю чувствительных данных.

## Проверенные ограничения Android

[WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo) описывает информацию активного соединения. Чувствительные поля требуют разрешений, соответствующих getScanResults; недоступные SSID/BSSID заменяются UNKNOWN_SSID / 02:00:00:00:00:00. Такая маска означает неизвестность, не отключение и не отсутствие дома.

[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#FLAG_INCLUDE_LOCATION_INFO) на Android12+ по умолчанию редактирует чувствительные поля даже при имеющихся разрешениях. Для callback нужен FLAG_INCLUDE_LOCATION_INFO; система проверяет permission и location toggle и учитывает обращение к местоположению.

[Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan) описывает FINE_LOCATION, ACCESS_WIFI_STATE и включённые службы location для чтения результатов; активные сканы ограничиваются Android. Предлагаемый первый этап вообще не вызывает startScan/getScanResults: только сведения уже подключённой сети. Условия доступа к чувствительным полям WifiInfo всё равно сохраняются.

[Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) отделяет NEARBY_WIFI_DEVICES от location и перечисляет API, которым оно нужно. Нельзя обещать, что новое nearby-разрешение заменит precise location для геопривязки. Здесь сеть используется для вывода о месте, поэтому neverForLocation не соответствует назначению. Точный набор разрешений проверить на реально выбранных API/OS; не добавлять nearby автоматически ради названия Wi-Fi.

## Минимальный будущий полезный этап

Добровольная настройка «Подтверждать подключение к сети этого места». Сеть обучается по реальному текущему подключению детского телефона; взрослый выбирает место и явно подтверждает связь. Ручной SSID сам по себе недостаточен: одинаковое название бывает у разных сетей. Проверяем известный BSSID/набор AP, допускаем обновление для mesh/роуминга, не просим пароль сети.

Коллектор ребёнка наблюдает подключённую Wi-Fi через пассивный callback (на31+ флаг location; версия-зависимый fallback), хранит measuredAt, elapsed time/boot session, device/member binding, availability reason. Результаты: MATCHED / DIFFERENT_NETWORK / DISCONNECTED / REDACTED / PERMISSION_MISSING / LOCATION_DISABLED / STALE. Потеря подключения, отказ разрешений и истечение времени немедленно снимают подтверждение. Старое событие после offline/reboot не получает новый measuredAt.

Передавать только family-scoped opaque network token/matched configured network id, время и источник CONNECTED_WIFI; не SSID, BSSID, соседние сети, пароли и не RSSI как метры. Для токена нужна согласованная семейная секретная соль/HMAC, а не обычный глобальный hash BSSID. Ключ/привязка меняются при выходе из семьи/перепривязке; raw identifiers остаются локальными и не попадают в логи. Это проект решения, не реализованная криптографическая гарантия.

На сервере — собственный authenticated device, активное family/member binding, права LOCATION+LOCATION_HISTORY для чтения результата взрослым, explicit opt-in. Сохранять отдельный последний evidence snapshot с коротким согласованным TTL (предложение5мин для статуса; не хранить raw-историю), сбрасывать при отзыве/смене устройства. Конфиг действует до удаления/отзыва; срок его хранения описать явно. Для будущих аудируемых событий сохранять только необходимый source/matched-place/time согласно выбранной политике, без радиосреды.

UI в существующей карточке места: «Телефон подключён к сети дома · замер …». Это отдельное дополнительное свидетельство, не координата/комната и не повод безусловно объявить геометрический ENTER/EXIT. Отсутствие Wi-Fi не доказывает выход: телефон мог уйти на мобильную связь или роутер выключился. SSID/BSSID можно воспроизвести; метод не является удостоверением физического присутствия.

## Файлы минимальной реализации после выбора

- Новый child `location/ConnectedWifiEvidenceCollector.kt`; parentwatch manifest только действительно нужное разрешение; lifecycle LocationService, DeviceInfoCollector/NetworkHelper только для компактного scoped результата.
- Новая чистая shared policy с тестами масок/устаревания/отзыва/сменыdevice/boot/порядка.
- Сервер отдельный evidence service + authenticated route/валидация; FamilyPlacesService добавляет отдельное evidence, не подменяет GPS-state. Не разносить BSSID в общий device_status raw.
- Родитель FamilyPlacesController: привязка реальной наблюдаемой сети и честное дополнительное состояние; никаких незаполненных кнопок/панели на карте.

Следующий конкретный шаг: отдельный согласованный native эксперимент на рабочем Android11 и Android14 — подключённая известная сеть, foreground/background, reboot/unlock, precise/approximate, location off, Wi-Fi→cellular, VPN, mesh; фиксировать только availability/redaction/match/time без логирования идентификаторов. До этого нельзя обещать автономность и доступность WifiInfo на конкретных телефонах. После подтверждения API — законченный opt-in сбор/доставка одного evidence, затем решение о включении в политику мест.
