# Архитектура — AdventAI Desktop

Clean Architecture в одном Gradle-модуле, три пакета + composition root. Доменный слой не зависит ни
от чего (pure Kotlin), data реализует его порты, ui рисует Compose, `Main.kt` всё связывает вручную.

## Карта файлов

### `domain/` — без внешних зависимостей
- **Model.kt** — `Role`, `Message`(role, text, createdAtMs, `usage: TokenUsage?`), `Derived`(summary/facts + счётчики),
  `Conversation`(id, title, createdAtMs, messages, derived), `ConversationMeta`, `MemoryMode`(Layered/Window/Facts/Summary/Full),
  `WorkingMemory`(goal, constraints), `LongTermMemory`(profile, decisions).
- **Ports.kt** — интерфейсы: `LlmGateway.complete(messages): GatewayResponse`, `ConversationRepository`,
  `MemoryStore`; плюс `TokenUsage`, `GatewayResponse`.
- **Memory.kt** — `ContextAssembler` (suspend): по `MemoryMode` собирает сообщения для запроса. Чистые режимы
  (Full/Window/Layered) не ходят в сеть; Summary/Facts при росте «старого хвоста» дозапрашивают модель и
  обновляют `Derived`. Возвращает `Assembled(messages, derived, dropped)`.
- **VisaAgent.kt** — `VISA_SYSTEM_PROMPT` (визовый специалист + формат `[checklist]`), `VisaAgent.ask(mode, conversation,
  working, longTerm): Result<AgentTurn>` → собирает контекст ассемблером и зовёт `LlmGateway`.

### `data/` — реализация портов под JVM
- **LlmClient.kt** — `LlmGateway` на Ktor + движок **CIO**. На не-2xx бросает понятную ошибку (текст из
  `{"error":{"message":…}}`), на пустой `content` — тоже (чтобы не было «(пустой ответ)»). `LlmConfig(baseUrl, apiKey, model)`.
- **Models.kt** — `ModelOption`(id, title, provider, цены), список `Models.all` (DeepSeek + бесплатные OpenRouter),
  `resolveLlmConfig(model, config)` (baseUrl по провайдеру, ключ по провайдеру; null если ключа нет).
- **ConfigStore.kt** — `DesktopConfig`(openrouterKey, deepseekKey, modelId, memoryMode) + load/save в `config.json`;
  пустой ключ → fallback на переменные окружения `OPENROUTER_API_KEY`/`DEEPSEEK_API_KEY`.
- **FileConversationRepository.kt** — диалоги в `conversations/<id>.json` + `index.json` (персист между сессиями).
- **FileMemoryStore.kt** — `profile.md` + `decisions.json` (per-агент) и `working/<id>.json` (per-диалог).
- **Dto.kt** — `@Serializable` DTO + мапперы в domain (домен не знает про сериализацию), общий `appJson`, `nowMs()`.
- **Files.kt** — `FileStore` (тонкая обёртка над `java.io.File`), `appHomeDir()` = `~/.adventai`.

### `ui/` — Compose
- **Theme.kt** — `AdventTheme` (Material3 light), палитра в духе Claude Code (`AppColors.accent` = `#2F6BED`,
  тёмный primary), `StatusColors` для статусов документов.
- **ChatState.kt** — держатель состояния (Compose `mutableStateOf`) + оркестратор: `send()`, `newConversation()`,
  `open()`, `deleteConversation()`, `chooseModel()`, `chooseMode()`, `saveConfig()`, доступ к памяти; `sessionTokens`/`sessionCost`.
  Первое сообщение пользователя задаёт заголовок диалога.
- **App.kt** — окно целиком: `Sidebar` (логотип, «Новая сессия», список диалогов, «Память»/«Настройки»),
  `ChatPane` (шапка, лента, пустой экран), `Composer` (поле + панель: модель ▾, память ▾, токены, кнопка ↑),
  `MessageView` (пузырь юзера / текст агента + чип токенов), `ChecklistView` (карточка документов со статусами),
  парсер блока `[checklist]`.
- **Dialogs.kt** — `SettingsDialog` (оба ключа + модель по умолчанию) и `MemoryDialog` (просмотр/правка 3 слоёв).

### `Main.kt` — composition root
`application { Window(icon=…) { App(state) } }`; `rememberAppState()` создаёт `FileStore`, репозитории,
`ConfigStore`, `ChatState` вручную (KISS — без DI-фреймворка). Иконка окна — `src/main/resources/icon.png`.

## Поток данных (один ход)

```
Composer (ui) → ChatState.send()
   → добавляет user-сообщение, сохраняет диалог, refreshList
   → VisaAgent.ask(mode, conversation, working, longTerm)        // domain
        → ContextAssembler.assemble(mode, …)                     // при Summary/Facts может вызвать LLM
        → LlmGateway.complete(messages)                          // LlmClient (Ktor CIO) → DeepSeek/OpenRouter
   → AgentTurn(reply.text + usage, derived)
   → ChatState добавляет assistant-сообщение (с usage), сохраняет derived → UI обновляется
```

## Сборка, запуск, упаковка

- `gradlew run` — запуск из исходников (открывается окно).
- `gradlew createDistributable` — app-image без установщика (не требует WiX):
  `build\compose\binaries\main\app\AdventAI\AdventAI.exe` (≈160 МБ, вшит JRE).
- `gradlew packageMsi` / `packageExe` — установщик (Compose-плагин сам скачивает WiX).
- Иконка `.exe` — `icon.ico` (в `build.gradle.kts` → `windows { iconFile.set(...) }`).

## Грабли (уже наступали)

- **JVM-target**: Kotlin `jvmTarget` должен совпадать с компилятором Java → выставлен `JVM_21`.
- **Видимость**: публичный класс (`ConfigStore`) не может принимать `internal`-тип в конструкторе → `FileStore` сделан публичным.
- **Platform clash**: `var mode` + метод `setMode` дают одну JVM-сигнатуру → метод назван `chooseMode`.
- **Размытый шрифт** на HiDPI (масштаб Windows 125%): exe помечается «system-DPI-aware», Windows растягивает битмап.
  Лечение — флаг «Application» (per-app DPI override), либо PerMonitorV2-манифест.
- **Иконка Java** (кофейная чашка) убирается через `Window(icon = painterResource("icon.png"))` (окно/таскбар) и `iconFile` (сам .exe).
- **Кликабельный `Surface`**: использовать `Surface(onClick=…)`, иначе ripple рисуется прямоугольником поверх скругления.
- **Ключи**: для запуска двойным кликом ключи должны лежать в `config.json` (Проводник не видит переменные окружения до перелогина).
