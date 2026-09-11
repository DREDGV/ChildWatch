# Контроль работы ИИ-агента через Android Studio

ChildWatch состоит из двух обычных Android-приложений. Физические телефоны для
визуального контроля не нужны. В Android Studio используются два режима:

1. `Design` — мгновенный статический просмотр XML-экрана без запуска Android.
2. `Running Devices` — настоящее приложение работает во встроенном эмуляторе
   Android Studio, а не на телефоне.

Полного мгновенного hot reload, как у веб-страницы, в обычном Android нет.
XML-разметка видна сразу, а Kotlin-логика появляется в эмуляторе после
инкрементальной сборки.

## Что открыть в Android Studio

Открывайте именно корень проекта:

```text
C:\Users\dr-ed\ChildWatch
```

Модули проекта:

- `app` — родительское приложение ParentMonitor;
- `parentwatch` — детское приложение ChildDevice.

Откройте окно `View → Tool Windows → Commit` или `Git → Local Changes`.
В нём видны все изменённые файлы. Касание файла открывает сравнение старой и
новой версии.

Для XML-экранов используйте вкладку `Design` или `Split`. Для запущенного
приложения используйте `Tools → Layout Inspector`.

## Статический просмотр экранов без запуска приложения

В окне проекта откройте `app → src → main → res → layout`, затем нужный XML.
Справа сверху выберите `Design` или `Split`.

Основные экраны ParentMonitor:

- `activity_main_menu.xml` — главная страница;
- `activity_remote_camera.xml` — удалённое фото;
- `activity_dual_location_map.xml` — карта;
- `activity_chat.xml` — чат.

Основные экраны ChildDevice:

- `parentwatch/.../layout/activity_main.xml` — главная страница;
- `parentwatch/.../layout/activity_dual_location_map.xml` — карта;
- `parentwatch/.../layout/activity_chat.xml` — чат.

В разметках уже используются данные `tools:text` и `tools:visibility`: они
показывают пример имени, заряда, статуса и карточек только в Android Studio и
не влияют на настоящее приложение.

## Настоящее приложение внутри Android Studio без телефона

На компьютере уже имеются эмуляторы:

- `Medium_Phone_API_35`;
- `Pixel_8_API_35`;
- `Pixel_Fold_API_35`;
- `Nokia_G21`.

Откройте `Tools → Device Manager`, нажмите кнопку запуска напротив
`Medium_Phone_API_35`, затем откройте `View → Tool Windows → Running Devices`.
Эмулятор появится внутри Android Studio.

В верхнем списке конфигураций:

- выберите модуль `app`, чтобы запустить ParentMonitor;
- выберите `parentwatch`, чтобы запустить ChildDevice.

Оба приложения можно запустить на одном эмуляторе: имена пакетов у них разные.
Физический телефон и ручная установка APK не требуются.

## Экран физического телефона — только дополнительный вариант

Для родительского телефона включите USB-отладку, подключите кабель и подтвердите
разрешение на телефоне. Затем:

1. Откройте `File → Settings → Tools → Device Mirroring`.
2. Включите зеркалирование подключённых физических устройств.
3. Откройте `View → Tool Windows → Running Devices`.

После этого экран телефона будет виден внутри Android Studio, и приложением
можно будет управлять мышью. Если на детском телефоне родительский контроль
запрещает USB-отладку, используйте для визуальной проверки детского приложения
Android Emulator либо устанавливайте готовый APK вручную.

Для просмотра ошибок откройте `View → Tool Windows → Logcat` и выберите
подключённый телефон. Удобные фильтры:

```text
package:ru.example.childwatch
```

для ParentMonitor и:

```text
package:ru.example.parentwatch.debug
```

для тестовой сборки ChildDevice.

## Безопасное наблюдение без автоматической установки

Откройте вкладку `Terminal` в Android Studio и выполните:

```powershell
.\scripts\dev-workflow.ps1 -Action observe -Target both
```

Команда только показывает изменяемые файлы. Она не запускает Gradle и не
трогает телефоны. Остановка — `Ctrl+C`.

## Проверка готовой контрольной точки в эмуляторе

Сначала запустите `Medium_Phone_API_35` через Device Manager и проверьте его:

```powershell
.\scripts\dev-workflow.ps1 -Action devices
```

Родительское приложение:

```powershell
.\scripts\dev-workflow.ps1 -Action deploy -Target app
```

Детское приложение:

```powershell
.\scripts\dev-workflow.ps1 -Action deploy -Target parentwatch
```

Оба приложения:

```powershell
.\scripts\dev-workflow.ps1 -Action deploy -Target both
```

Сборка всегда проходит через `scripts/run-gradle-safe.ps1`. Скрипт устанавливает
тестовую сборку только во внутренний эмулятор, если физические телефоны не
подключены.

## Автоматический предпросмотр

Этот режим включайте на время визуальной доработки одного экрана. Эмулятор
должен быть запущен в `Running Devices`:

```powershell
.\scripts\dev-workflow.ps1 -Action watch -Target app
```

или:

```powershell
.\scripts\dev-workflow.ps1 -Action watch -Target parentwatch
```

После последнего изменения скрипт ждёт 15 секунд, собирает только нужный
модуль, устанавливает APK поверх текущего приложения и запускает его.
Остановка — `Ctrl+C`.

Не рекомендуется постоянно запускать `watch -Target both`: серверные и
архитектурные изменения не требуют переустановки APK после каждого файла.

## Настройки Android Studio

Проверьте один раз:

1. `File → Settings → Advanced Settings`.
2. Включите синхронизацию внешних изменений файлов при возвращении в окно IDE,
   если такая настройка показана вашей версией Android Studio.
3. `File → Settings → Version Control → Confirmation` — не включайте
   автоматический commit.
4. В списке запуска выбирайте `app` для ParentMonitor и `parentwatch` для
   ChildDevice.

Перед каждым крупным этапом агент должен сообщать, какие файлы и функции он
меняет. После устойчивой контрольной точки можно запускать `deploy` и
проверять результат на телефоне.
