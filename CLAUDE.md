# CLAUDE.md — AdventAI Desktop (Визовый специалист)

Точка входа для Claude Code по этому **desktop-проекту**. Здесь короткий обзор; детали — в `.claude/*.md`.

## Что это

Настольное приложение для Windows на **Compose for Desktop** — AI-агент **«Визовый специалист»**:
чат с агентом, сохранение диалогов между сессиями, явная модель памяти и переключаемые стратегии
управления контекстом, счётчик токенов, выбор модели снизу (стиль Claude Code).

Это **отдельный проект**, не путать с Android-приложением AdventAI (`C:\Users\Huawei\AndroidStudioProjects\AdventAI`).
Доменный слой портирован оттуда (pure Kotlin), но data/UI переписаны под JVM + Compose Desktop.

## Стек

Kotlin/JVM (target 21) · **Compose Multiplatform Desktop 1.7.3** · Ktor client (CIO) ·
kotlinx-serialization · Okio нет (java.io) · jpackage (упаковка в `.exe`/`.msi`). LLM: DeepSeek / OpenRouter.

## Архитектура (Clean Architecture · DRY · KISS)

```
domain/   Model · Memory (ContextAssembler + стратегии) · Ports (интерфейсы) · VisaAgent  — без зависимостей
data/     LlmClient (Ktor) · FileConversationRepository · FileMemoryStore · ConfigStore · Models · Dto · Files
ui/       Theme · ChatState (state-holder) · App · Dialogs  — Compose
Main.kt   окно + composition root (ручной DI)
```
Правило границ: **UI знает только про `ChatState`; домен не знает про HTTP/файлы/Compose.** Подробности — `.claude/ARCHITECTURE.md`.

## Память и контекст

Три слоя памяти (краткосрочная / рабочая / долговременная) + 5 стратегий контекста
(3 слоя · окно · факты · резюме · полная). Подробности и где в коде — `.claude/MEMORY_AND_CONTEXT.md`.

## Данные на диске (`~/.adventai/`)

`conversations/<id>.json` + `index.json` (диалоги) · `working/<id>.json` (задача диалога) ·
`profile.md` + `decisions.json` (долговременная память) · `config.json` (ключи, модель, режим памяти).

## Сборка / запуск

```powershell
.\gradlew.bat run                          # запустить (откроется окно)
.\gradlew.bat createDistributable          # app-image: build\compose\binaries\main\app\AdventAI\AdventAI.exe
.\gradlew.bat packageMsi                   # установщик .msi (WiX скачивается плагином)
```

## Навигация по коду (ast-index)

Для поиска по проекту **сначала `ast-index`** (структурный, по токенам дешевле чтения файлов/grep),
потом `Read` по найденному `file:line`. Примеры: `ast-index symbol ChatState`,
`ast-index usages connectMcp`, `ast-index outline <file>`, `ast-index map`. Индекс держится свежим
автоматически (хуки `PostToolUse`/`SessionStart` → `ast-index update`). Шпаргалка — `.claude/AST_INDEX.md`.

## Правила

- Не тащить HTTP/Ktor/файлы в `ui` — только через доменные порты (`LlmGateway`, репозитории).
- Кликабельные элементы — `Surface(onClick=…)` (ripple обрезается по скруглению), не `Modifier.clickable` на Surface.
- Дизайн: сине-белый, один акцент `#2F6BED`, тёмный primary, скругления; стиль Claude Code (см. `DESIGN_BRIEF.md`).
- Ключи и секреты — в `~/.adventai/config.json` или переменных окружения, **не** в репозитории.
- JVM-target Kotlin = Java (21); публичный класс не должен светить `internal`-тип в конструкторе.

## Справочные документы (`.claude/`)

База знаний дробится по темам — индекс в `.claude/INDEX.md`:

- `ARCHITECTURE.md` — слои, карта файлов, поток данных, сборка, грабли.
- `CONTEXT_WINDOW.md` — контекстное окно, стратегии контекста, токены.
- `MEMORY_MODEL.md` — модель памяти (3 слоя), хранение, маршрутизация.
- `PROMPTING.md` — промпт-инжиниринг, системный промпт, формат `[checklist]`.
- `STATE_MACHINE.md` — состояние задачи и инварианты (роадмап).
- `ANTIPATTERNS.md` — чего не делать.
- `DESIGN_BRIEF.md` (в корне) — мета-промпт по дизайну в стиле Claude Code.
