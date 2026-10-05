# Восстановление звука: проверка исходников 06.10.2026

Симптом владельца: около 20–30 секунд «Ожидание звука», затем перезапуск мониторинга ребёнка сразу помог. Журнал этого отказа не получен. Следующие исправления устраняют доказуемые пути в коде, но не устанавливают причину того полевого случая. Текущий статус и продолжение — CW-01/FIRST-AUDIO-RECOVERY в TODO.md.

## Детский клиент

`parentwatch/.../audio/AudioStreamRecorder.kt` прежде мог закончить recording coroutine при исключении из передачи и оставить `isRecording=true`. Повторный START тогда возвращался из `ensureCaptureRunning`, хотя работающего loop не было.

Теперь завершение loop сбрасывает активность, освобождает recorder и использует существующее восстановление. Cleanup/read failure проверяют owner и generation: старый loop после паузы не освобождает новый recorder и не отправляет старый кадр. Восстановление допустимо только при `streamingDesired && !capturePaused`.

Независимая ревизия дополнительно нашла два расписания: мгновенная ошибка capture до освобождения recovery job теряла повтор, а параллельные START/recovery могли повторно инициализировать уже работающий recorder. Recovery публикуется до запуска, очищает только свой job и повторно назначается после передачи владения при необходимости. Инициализация перепроверяет `isRecording` под монитором. Прежняя поправка duplicate resume сохранена.

## Родительский клиент

`app/.../service/AudioPlaybackService.kt` терял resume после повторного transient focus loss. При focus gain запускал playback, но не возвращал watchdog/первый START repeater. Пауза явно отменяет recovery jobs; возврат восстанавливает их, сохраняя ограничения автоматических попыток. Отдельное окно восстановления исключает время звонка из timeout; времена реально полученных пакетов не изменяются.

START chain проверяет cancellation, адресата и поколение сессии до/после HTTP и перед WebSocket. Запоздалый busy/result не останавливает новую сессию. STOP и focus pause инвалидируют поколение. Watchdog имеет локальный timeout флаг, сверяет поколение и фактическую тишину перед отложенным STOP; новый пакет отменяет эту остановку, цикл продолжает наблюдение. После истечения timeout новых START нет.

## Доказательство

Безопасная проверка выполнена без установки, звука, фото и серверных команд:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-gradle-safe.ps1 :shared-core:test --tests ru.childwatch.shared.audio.AudioCaptureLoopPolicyTest --tests ru.childwatch.shared.audio.AudioPlaybackFocusStateTest --tests ru.childwatch.shared.family.FamilyDeviceHeartbeatPolicyTest :app:compileDebugKotlin :parentwatch:compileDebugKotlin
```

BUILD SUCCESSFUL, 1м39с, 68 задач. XML в `shared-core/build/test-results/test`: capture6 + focus3 + presence3, failures0/errors0/skipped0. Это проверки решений политики; реальное межпоточное расписание Android не воспроизводилось этими тестами.

После завершающих правок watchdog и переноса строки главного экрана повторена только `:app:compileDebugKotlin`: BUILD SUCCESSFUL, 58с, 48 задач. Ревизия финального watchdog и обеих частей восстановления — PASS по исходникам. Diff-check PASS. APK не собирались/не устанавливались, сервер и публикация в этом этапе не менялись.

## Граница результата

Native read, который навсегда не возвращает управление, здесь не считается исправленным. Не проверены реальные no-audio→recovery, STOP→новое подключение, звук→фото→звук, звонок/потеря focus и смена сети. Для полевого отказа по-прежнему нужны согласованные журналы обоих телефонов. Системные разрешения и выбор помощника не менялись.
