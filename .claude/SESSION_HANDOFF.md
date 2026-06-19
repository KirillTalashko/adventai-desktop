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
  `[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]`. → **реализовано и собрано, но ещё НЕ закоммичено** (нужен PR `day-12-personalization`).

## Архитектура (Desktop, Clean Architecture · DRY · KISS)

```
domain/  Model · Memory (ContextAssembler) · MemoryExtractor · UserProfile · Ports · VisaAgent  — без зависимостей
data/    LlmClient (Ktor/CIO) · FileConversationRepository · FileMemoryStore · AccountStore · ProfileStore ·
         ConfigStore · Models · Dto · Files
ui/      Theme · ChatState (state-holder) · App · Dialogs · Onboarding · ProfileForm   (Compose)
Main.kt  окно + composition root (ручной DI)
```
Границы: **UI знает только про `ChatState`; домен не знает про HTTP/файлы/Compose.**
Стек: Kotlin/JVM 21 · Compose MP 1.7.3 · Ktor CIO · kotlinx-serialization · jpackage. LLM: DeepSeek / OpenRouter.

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

- **Оформить День 12 как PR** (`day-12-personalization` → `main`) — ещё не сделано.
- Branching (ветки диалога), промоушен sticky-facts рабочая→долговременная, аватар аккаунта.
- Дни 13+: state-машина задачи + инварианты/валидация (конспект — в Android `.claude/WEEK_3_MEMORY_AND_STATE.md`,
  роадмап — `.claude/STATE_MACHINE.md`).

## Справочные документы

`.claude/INDEX.md` → ARCHITECTURE · CONTEXT_WINDOW · MEMORY_MODEL · MEMORY_AGENT · PERSONALIZATION ·
PROMPTING · STATE_MACHINE (роадмап) · ANTIPATTERNS. Плюс `README.md` и `DESIGN_BRIEF.md` в корне.
