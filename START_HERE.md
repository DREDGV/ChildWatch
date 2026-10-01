# ChildWatch: начало работы

1. [TODO.md](TODO.md): очередь, состояние выпуска, ограничения владельца.
2. [AGENTS.md](AGENTS.md): правила и безопасные команды.
3. Один ID задачи; исторические подробности читать только по необходимости.

| Модуль | Назначение |
|---|---|
| `app/` | ParentMonitor — взрослый; пакет `ru.example.childwatch` |
| `parentwatch/` | ChildDevice — ребёнок; пакет `ru.example.parentwatch` |
| `shared-core/` | Общие контракты и логика |
| `design-system/` | Общие визуальные компоненты и аватары |
| `server/` | Node/SQLite/WebSocket |

Названия модулей исторические: не определять роль телефона по имени папки.

## Когда разрешены сборка и установка

Из корня проекта, PowerShell:

```powershell
# Только сборка
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -BuildOnly -BuildTimeoutSeconds 900

# Установка готового APK на проверенный телефон
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -InstallOnly -Target parent -ConnectedParentSerial <serial>

# Компиляция через безопасную обёртку
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-gradle-safe.ps1 :app:compileDebugKotlin :parentwatch:compileDebugKotlin
```

Для выпуска: `.agents/skills/childwatch-release/SKILL.md`. Не подставлять серийники, APK или versionCode из истории. Публикация — отдельное действие.
Контекст семьи: [CONTEXT.md](CONTEXT.md). Навыки: [docs/agent-workflow.md](docs/agent-workflow.md).
Прежняя шпаргалка: [архив](docs/history/START_HERE-2026-10-01.md); её команды не являются действующей инструкцией.
