# Подготовленный кандидат 7.4.319

Signed debug variants сохраняют действующие package и сертификат опубликованной308. SHA/size/cert/public version guard и архивы проверены; детали verification.json. Source commit 996c8521c67a09c0d94f6f13efeb5d4289b66ef0. Основные APK установлены10.10 на Samsung/Moto поверх308; native6/6 на каждом и запуск проверены. Сервер/OTA НЕ опубликованы; pair/field gates остаются открытыми в TODO. Автонумерация учитывает зарезервированные номера тестовых пакетов;308 не пересобран и updates/manifest не заменён.

Состав: исправления GIF/меню, участники/presence, существующая карта/скорость/follow. Сначала server-patch-7.4.319.zip (проверить основу308), затем signed OTA-пара. Серверный пакет3 runtime+3 isolated tests; приватные данные не содержит. ASR OFF, каталог v1 прежний. OTA zip включает APK и manifest только для отдельно согласованной публикации.

Проверки: локальные Android policies/test source PASS1м53с/140tasks, server131PASS/3LinuxSKIP. Исправлен нестабильный тест точного состава группы (tie joined_at); production SQL не менялся. Свежая сборка/логи в .runtime/candidate-*. Реальная визуальная и полевая приёмка: docs/candidate-acceptance-2026-10-10.md; не подменять её компиляцией. Установка без сброса/выхода/downgrade. Следующий шаг — обновление сервера presence, затем парная/полевая приёмка; до этого новые функции не расширять.
