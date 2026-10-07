# Проверка обрыва прослушки7.4.291 — 07.10.2026

Рабочие Samsung(parent) и Moto(child), обе приложения code2000000291.
Выбран Лёва; семейная привязка/assistant/PIN не менялись. Владелец сообщил
об обновлении сервера; состав выкладки и OTA этим тестом не проверялись.
Детское приложение не открывали и мониторинг вручную не перезапускали.

## Наблюдаемые результаты

| Сценарий | Что действительно произошло | Результат |
|---|---|---|
| Активный звук → полная потеря сети → возврат | WiFi115 → нет default network → WiFi117; WiFi и mobile выключены примерно20с | Приём прекратился; после возврата пришли новые пакеты и AudioTrack снова начал playback автоматически. В последнем образце total1297920B; экран «Поток стабилен», последний пакет11мс. При обрыве underrun+1: перерыв действительно был. |
| Прослушка OFF → обрыв → мобильная сеть → новый START | Нет default network → CELLULAR118; START отдельно после возврата | Parent playback стартовал; total591360B за15с образца, underruns0. Далее STOP. USB-запись child прервалась, но parent capture продолжался и системный журнал child подтверждает mic start/stop. |
| Прослушка OFF → обрыв → WiFi → новый START | Нет default network → WIFI121; START отдельно после возврата | Parent playback стартовал; total593280B за15с образца, underruns0. Далее STOP. Child logcat также неполон после transient USB, системный mic-журнал сохранён отдельно. |

Финальный parent UI показывает «Начать передачу звука». Child AUDIO stop OK
11:52:27.310; системный recording monitor rec stop11:52:27.305. RECORD_AUDIO
appops сообщает завершённую длительность15с, без running. Системный журнал
показывает одну mic-сессию через активный обрыв и две отдельные сессии только
после явных START; нет новых mic-сессий в промежутках OFF/возврата сети.
WiFi и mobile data в конце оба1, исходное состояние восстановлено.

## Доказательства и границы

.runtime/audio-recovery291: active-events.json, off-cellular-events.json,
off-wifi-events.json; parent/child-*-logcat.txt, snapshots connectivity,
baseline/restored-parent.png, wifi-playing-parent.png,
after-*-off.xml, final-child-stop.txt, final-microphone-appops.txt,
final-audio-state.txt. Screenshots baseline/restored inspected; OFF verified
by UI XML. No global logcat buffer was cleared.

В cellular trial USB Moto исчез в момент snapshot; scripted finally restore
FAILED, не считать его успешным. Следующий отдельный ADB вызов увидел Moto,
явно вернул svc wifi/data enable и проверил оба1. WiFi trial использовал
ограниченный retry для transient USB; восстановление и финальная проверка
успешны. Микрофонный системный журнал компенсирует часть пропуска child logcat,
но не реконструирует каждый socket/capture callback.

Показатели и playback-журнал подтверждают восстановление передачи и запуск
воспроизведения, не человеческое подтверждение слышимого голоса. Телефонные
часы не калибровались относительно ПК: точную задержку first packet не заявляем.
Проверена20-секундная потеря сети; более длинный обрыв, зависший сервер при
сохранённой VALIDATED-сети, capture-only stall и параллельное фото остаются
открыты в CW-01. Новый deadline watchdog этим outage не вызван: сработало
штатное/route recovery. Не считать полевой дефект окончательно закрытым.

Следующий отдельный сценарий: обрыв дольше60с, чтобы проверить поведение
родительского таймаута и новый START после возврата сети. Это рекомендация,
в текущем тесте длительный обрыв не выполнялся.
