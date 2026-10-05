# Проверка восстановления через домашний экран — 04.10.2026

Текущая задача: TODO.md, CW-01/FIELD-REPEAT. Платформенный эксперимент завершён; полное восстановление всех функций на семейном телефоне остаётся открытым.

## Результат

На отдельном ChildWatchHomeTest / emulator-5582 / Android 14, targetSdk 34:

| Сценарий после реальной перезагрузки | Допуск LocationService | Фактический захват |
|---|---|---|
| Обычный Nexus Launcher, ChildWatch после boot не открывался | `DENIED`, types `0x08` | Мониторинг активен, AudioRecord вернул 960 bytes, `isClientSilenced=true` |
| Система сама показала выбранный Home ChildWatch; затем Settings и погашенный экран | `PROC_STATE_TOP`, types `0x48` до захвата | Мониторинг активен, 960 bytes, `isClientSilenced=false`, JPEG 24984 bytes; приложение невидимо до/после, `pass=true` |

Поздний проверочный BOOT receiver повторил захват уже в фоне: снова PASS, JPEG 25230 bytes. Извлечённый JPEG соответствует `result-repeat-capture.json`, декодирован Pillow: JPEG 1280×720. Скрипт поправлен: в Home-сценарии этот отдельный baseline receiver выключается, захват запускает только уход Home в фон. Команда выключения проверена на этом эмуляторе. Дисплей после захвата: `mWakefulness=Asleep`.

Доступ даёт действительно показанная Activity, а не специальная привилегия ROLE_HOME. Нет device owner, ChildWatch не выбран ассистентом. Захват выполняет обычный процесс приложения с его UID, без instrumentation целевого APK и без запуска службы от shell. ADB используется для первоначальной настройки изолированного стенда, реальной перезагрузки, обычного перехода в Settings/погашения дисплея и чтения свидетельств.

## Изменено

- Только debug source set: `HomeRecoveryProbeActivity.kt`, `HomeBackgroundCaptureProbe.kt`, `res/values/home_probe.xml`, debug manifest. В release этих компонентов нет; Home и оба проверочных receiver выключены по умолчанию. Дополнительная защита — emulator hardware, отсутствие device owner и точная фиктивная сессия `isolated-home-boot-probe` / `http://127.0.0.1:9`.
- Home — обычный видимый экран со списком запускаемых приложений, входом в ChildWatch и выбором прежнего домашнего экрана. Не прозрачный экран и не автозакрывающаяся Activity. Использованы цвета DESIGN.md, системная типографика, вертикальный прокручиваемый список с крупными действиями. Проверочная UI, не готовый продуктовый launcher.
- `scripts/test-home-boot-probe.ps1`: только AVD ChildWatchHomeTest; проверка отсутствия owner/assistant-привилегии; начальная настройка до reboot; ожидание реально отрисованного Home и `TOP`, а не только topResumed; аппаратный захват и сохранение результата.

## Проверки и свидетельства

- `build-and-install.ps1 -Target child -BuildOnly -VersionCode 2000000281 -BuildTimeoutSeconds 900`: PASS 225с после повторного запуска с разрешённым доступом Windows; первая попытка остановилась на запуске AAPT2, не на Kotlin.
- Актуальная сборка/установка `-Target child -ConnectedChildSerial emulator-5582 -VersionCode 2000000281 -BuildTimeoutSeconds 900`: PASS 202с, install Success, установлен 7.4.281-debug. `.runtime/home-probe-final-build-install.log`.
- Baseline: `.runtime/home-boot-baseline-run.log`, `.runtime/home-boot-baseline/result.json`, `services-after-capture.txt`, `home-after.txt` (Nexus), `device-policy-before.txt`, `assistant-before.txt`.
- Home: `.runtime/home-boot-home-run.log`, `.runtime/home-boot-home/result-first-capture.json`, `result-repeat-capture.json`, `location-home-visible-before-capture.txt`, `activities-home-visible.txt`, `services-after-capture.txt`, `power-after-capture.txt`, `camera.jpg`. Native screenshot `home-ui.png` просмотрен: действительно показан «Мой телефон» и список приложений.
- Первый baseline не дал результата: свежая установка была stopped/notLaunched; начальную настройку перенесли до reboot. Это не принято за отказ сенсора.
- Первый Home-прогон был преждевременным: topResumed уже указывал Home, но `reportedDrawn=false`, снимок показывал Pixel is starting; микрофон был silenced. Это не принято за завершённую проверку Home. Исправлена готовность стенда и выполнен новый реальный reboot, без открытия ChildWatch после него.
- PowerShell parser и diff-check затронутых файлов PASS. Проверочный APK и SHA256 в `releases/home-recovery-test-7.4.281/`. Физические телефоны, их роли/аккаунты/приложения не изменялись; OTA и сервер не публиковались.

## Границы результата и продолжение

Эмулятор запущен без host audio: проверены кадры и отсутствие системного silencing, не слышимость голоса. Фиктивный сервер localhost:9 не отвечает, поэтому настоящий AudioStreamRecorder WebSocket/доставка родителю, карта/чат, одновременный звук/фото и восстановление после гибели процесса не доказаны. Сессионные данные остаются в credential encrypted storage: до первого разблокирования полного доступа нет. Эмулятор был RUNNING_UNLOCKED; PIN/реальный lock screen телефона не проверен, подтверждён именно погашенный дисплей и невидимая Activity.

Это рабочий кандидат после обычной разблокировки/появления Home, не универсальная гарантия всех функций после любого reboot. Для физического телефона нужен добровольный выбор нового домашнего экрана; действующее ограничение владельца не разрешает менять его сейчас.

Рекомендуемый следующий этап: превратить прототип в добровольный детский Home с привычными приложениями, готовностью восстановления и возвратом прежнего экрана, затем отдельно согласовать назначение на запасном Moto. До этого обычный выпуск не объявлять исправляющим reboot.

Официальная основа: [Android — видимая Activity и ограничения чувствительных FGS](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [ROLE_HOME](https://developer.android.com/reference/android/app/role/RoleManager), [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot). Исследование конкурентов: [проверенные первичные сведения и неизвестное](boot-capture-alternatives-2026-10-03.md).
