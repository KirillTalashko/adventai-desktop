# Мета-промпт для новой сессии (handoff)

> Вставь это в начало новой сессии, чтобы Claude восстановил контекст. Отвечай по-русски.

## Кто я и что за проекты

Ты — Senior Android/Kotlin разработчик. Я (пользователь) прохожу **AI Advent Challenge #8** — агент строится
по одному заданию в день. У нас три связанных проекта:

- 🟢 **АКТИВНЫЙ — Desktop:** `C:\Users\Huawei\IdeaProjects\AdventAiDesktop` — десктопное приложение
  **«Визовый специалист»** на **Compose for Desktop**. Здесь идёт работа по дням 11+.
  GitHub: **https://github.com/KirillTalashko/adventai-desktop** (public, аккаунт `KirillTalashko`).
- CLI (прототип, не активен): `C:\Users\Huawei\IdeaProjects\AdventAiCli` — Kotlin/Native, тот же агент в терминале.
- Android (дни 1–10): `C:\Users\Huawei\AndroidStudioProjects\AdventAI` — исходное приложение челленджа
  (Jetpack Compose/Android). Обычно это рабочая папка сессии, но **код мы пишем в Desktop-проекте**.

## Что уже сделано (Desktop)

- **День 11 — модель памяти:** 3 слоя (краткосрочная = реплики; рабочая = цель/ограничения задачи;
  долговременная = профиль/решения). Авто-наполнение фоновым **агентом памяти** (`MemoryExtractor`, всегда
  на `deepseek-chat`). Контекст управляется **под капотом без режимов**: sliding window N=12 + авто-резюме
  старого хвоста при заполнении окна ≥30%. Долговременная память — в Markdown. → **смержено в `main` (PR #1)**.
- **День 12 — персонализация:** профиль предпочтений (`UserProfile`: длина/тон/формат/ограничения/язык),
  **онбординг**, **локальные аккаунты с изоляцией данных**, профиль инжектируется в каждый запрос блоком
  `[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]`. → **закоммичено и влито в `main`**.
- **Дни 13–15 — состояние задачи:** конечный автомат задачи (этап/шаг/ожидаемое действие), инварианты +
  страж-валидатор, pause/resume. → влито в `main`.
- **Неделя 4 (Дни 16–20) — MCP + Skills:** MCP-клиент (stdio-подпроцесс), свой remote MCP-сервер на VPS
  (HTTPS + токен-авторизация, digest-планировщик), Skill + CLI как лёгкая альтернатива MCP, замеры токенов. → влито в `main`.
- **Неделя 5 (Дни 21–25) — RAG:** индексация базы знаний (эмбеддер Ollama `nomic-embed-text`, чанкинг+overlap,
  SQLite-индекс), векторный поиск, reranking, цитаты/источники/анти-галлюцинации, RAG в основном оркестраторе +
  память задачи. База знаний ~50 документов. → влито в `main`.
- **Неделя 6 (День 26) — Local LLM:** локальная генеративная LLM через **Ollama** (`data/LocalLlmClient`,
  порт `LlmGateway`, `POST localhost:11434/api/chat`, дефолт `qwen2.5:7b`), CLI-задача `runLocalLlm`,
  dev-панель «Локальная LLM». → ветка `local-llm-runner` (коммит `b2ea474`).

## Архитектура (Desktop, Clean Architecture · DRY · KISS)

```
domain/  Model · Memory (ContextAssembler) · MemoryExtractor · UserProfile · Ports · VisaAgent  — без зависимостей
data/    LlmClient (Ktor/CIO) · LocalLlmClient (Ollama localhost) · FileConversationRepository · FileMemoryStore ·
         AccountStore · ProfileStore · ConfigStore · Models · Dto · Files · RAG (OllamaEmbedder · KnowledgeIndex ·
         Reranker · RagKnowledgeRetriever) · MCP (McpClient · McpRouter)
ui/      Theme · ChatState (state-holder) · App · Dialogs · Onboarding · ProfileForm   (Compose)
Main.kt  окно + composition root (ручной DI)
```
Границы: **UI знает только про `ChatState`; домен не знает про HTTP/файлы/Compose.**
Стек: Kotlin/JVM 21 · Compose MP 1.7.3 · Ktor CIO · kotlinx-serialization · jpackage. LLM: DeepSeek / OpenRouter + локальная Ollama (`qwen2.5:7b`, `localhost:11434`).

## Данные на диске (`~/.adventai/`)

```
config.json                 ключи API + модель по умолчанию (ОБЩЕЕ для аккаунтов)
accounts.json               список аккаунтов + активный
accounts/<id>/
  profile.json              профиль предпочтений (Day 12)
  conversations/ + index.json
  working/<convId>.json     рабочая память
  memory/profile.md + decisions.md   долговременная память (Markdown)
```

## Ключи / модели

- API-ключи — в Windows user env: `OPENROUTER_API_KEY`, `DEEPSEEK_API_KEY` (заданы), и/или в `config.json`.
- Модель по умолчанию в чате: **`deepseek-chat`** (бесплатные OpenRouter лимитятся 429). Извлечение фактов — всегда `deepseek-chat`.

## Как собрать/запустить/перезапустить (важный рабочий процесс)

1. Проверка: `gradlew compileKotlin` (на Day-ошибки).
2. Сборка app-image: `gradlew createDistributable` → `build\compose\binaries\main\app\AdventAI\AdventAI.exe`.
3. **Перед пересборкой закрыть приложение** (оно держит файлы):
   PowerShell `Get-Process -Name AdventAI | Stop-Process -Force`.
4. **Запуск с ключами из реестра** (фоновая сессия не видит env напрямую):
   ```powershell
   $env:OPENROUTER_API_KEY = [Environment]::GetEnvironmentVariable('OPENROUTER_API_KEY','User')
   $env:DEEPSEEK_API_KEY   = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
   Start-Process "C:\Users\Huawei\IdeaProjects\AdventAiDesktop\build\compose\binaries\main\app\AdventAI\AdventAI.exe"
   ```
   GUI-окно я сам не запускаю без спроса. Открывается также в **IntelliJ IDEA 2025.3** (задача Gradle `run`).

## Конвенции

- Отвечать **по-русски**. Документацию в `.claude/` **дробить по темам** (см. `.claude/INDEX.md`).
- Перед перезапуском — зелёная компиляция. Не коммитить секреты (`.gitignore` закрывает ключи/данные памяти).
- Дизайн в стиле Claude Code: сине-белый, тёмная primary-кнопка, скругления; см. `DESIGN_BRIEF.md`.

## Грабли (уже наступали)

- HiDPI 125% → размытость: ставим per-app DPI override `~ HIGHDPIAWARE` в реестре
  `HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers` для exe.
- Kotlin `jvmTarget` = Java 21 (иначе «Inconsistent JVM-target»).
- Публичный класс не должен светить `internal`-тип в конструкторе.
- `var x` + `fun setX(...)` → platform declaration clash (переименовать метод).
- Внутри `buildString { }` `length` затеняется `StringBuilder.length` — выноси в локальную переменную.
- `FlowRow` — экспериментальный → `@OptIn(ExperimentalLayoutApi::class)`.
- `LlmClient` не глотает ошибки API (показывает причину, напр. 429).
- Иконка окна — `Window(icon = painterResource("icon.png"))`; иконка .exe — `windows { iconFile.set("icon.ico") }`.

## Что дальше (TODO / роадмап)

- **Неделя 6 (Local LLM), после Дня 26:** тумблер **cloud ↔ local** в нижнем селекторе моделей (+ провайдер
  «ollama» в `Models.kt`), режим **сравнения** двух ответов (облако vs локаль) с маркировкой, MCP-tool-loop для
  локальной модели, конфиг-поле `localModel`, стриминг токенов. Подробности — `.claude/WEEK_6_LOCAL_LLM.md`.
- Branching (ветки диалога), промоушен sticky-facts рабочая→долговременная, аватар аккаунта.

## Справочные документы

`.claude/INDEX.md` → ARCHITECTURE · CONTEXT_WINDOW · MEMORY_MODEL · MEMORY_AGENT · PERSONALIZATION ·
PROMPTING · STATE_MACHINE (роадмап) · ANTIPATTERNS. Плюс `README.md` и `DESIGN_BRIEF.md` в корне.
