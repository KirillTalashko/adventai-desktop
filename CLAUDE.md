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
kotlinx-serialization · Okio нет (java.io) · jpackage (упаковка в `.exe`/`.msi`). LLM: DeepSeek / OpenRouter (облако) + локальная **Ollama** (`qwen2.5:7b`, `localhost:11434`).

## Архитектура (Clean Architecture · DRY · KISS)

Проект разбит на **Gradle-модули** (День 32, шаг 2). Стрелки зависимостей идут строго вверх —
нарушение теперь не проходит код-ревью, а не компилируется:

```
:core:domain    Model · Memory (ContextAssembler + режимы) · Ports · VisaAgent · rag/
                зависимости: ТОЛЬКО kotlinx-coroutines-core
:core:data      LlmClient (Ktor) · FileConversationRepository · FileMemoryStore · ConfigStore ·
                Models · Dto · Files · KnowledgeIndex · McpClient  + resources/{knowledge,skills,rag_eval}
                -> :core:domain
:app            Theme · ChatState (state-holder) · App · Dialogs · di/AppModule · Main.kt
                -> :core:data   (Compose + Koin; composition root)
:tools:cli      консольные харнессы (runAgentProbe, runTaskFlowCheck, …) + fat-jar visa-cli
:tools:mcp      MCP-серверы (визовый + dev) + fat-jar для VPS
:tools:service  приватный HTTP LLM-сервис + fat-jar
```
Правило границ: **UI знает только про `ChatState`; домен не знает про HTTP/файлы/Compose.**

Одна связь не видна компилятору: `McpClient` поднимает MCP-сервер подпроцессом
(`java -cp <java.class.path> …mcp.VisaMcpServerKt`), поэтому `:app` и `:tools:cli` держат
`runtimeOnly(project(":tools:mcp"))` — на компиляционном classpath его нет и быть не должно.

DI — **Koin 4.1.1**, граф в `app/…/di/AppModule.kt` (`dataModule` / `gatewayModule` / `stateModule`).
Версия не последняя намеренно: 4.2.x собран на Kotlin 2.3.20, компилятор проекта 2.1.21 таких
метаданных не читает. `koin-compose` не подключён (тянет Compose 1.8.2 против 1.7.3).
Полнота графа проверяется тестом `app/src/test/…/AppModuleTest.kt` на каждой сборке.

Подробности — `.claude/ARCHITECTURE.md`.

## Память и контекст

Три слоя памяти (краткосрочная / рабочая / долговременная) + 5 режимов контекста, переключаемых
чипом «Память» в композере (`domain/Memory.kt → MemoryMode`): авто · только последние · ключевые факты ·
краткий пересказ · вся история. Режим меняет объём истории и блоки памяти диалога, но НЕ трогает
инварианты и профиль. Подробности — `.claude/MEMORY_MODEL.md` и `.claude/CONTEXT_WINDOW.md`.

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
- Дизайн: «бумага + терракота» — тёплый кремовый холст `#FAF9F5`, один акцент терракота `#C96442`
  (в тёмной `#D97757`), тёмный primary, засечки в заголовках, границы вместо теней (см. `REDESIGN_CLAUDE_DESIGN.md`).
  Цвета — только через `MaterialTheme.colorScheme` / `AppColors` / `StatusColors`; hex в композаблах не хардкодить.
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
- `REDESIGN_CLAUDE_DESIGN.md` (в корне) — **действующий** бриф визуального слоя: «бумага + терракота» (claude.ai).
- `DESIGN_BRIEF.md` (в корне) — прежний бриф в стиле Claude Code; по визуальному слою заменён на редизайн выше,
  раскладка/принципы взаимодействия оттуда остаются в силе.
