# MCP — Model Context Protocol (День 16)

Подключение агента к инструментам по стандарту **MCP** (Anthropic). День 16 — каркас: установить
соединение и получить список инструментов; в окне приложения это видно вживую, плюс вызов `ping` (запрос→ответ).

## Что это даёт

- Доменный порт **`ToolGateway`** — агент знает про «инструменты», но не про SDK/процессы/JSON-RPC.
- Локальный **MCP-сервер** (`visa-mcp`) с инструментами; пока демонстрационные (`ping`, заглушка `get_visa_news`).
- Клиент поднимает сервер **подпроцессом** и общается с ним по **stdio** (JSON-RPC).

## Карта файлов

### `domain/` (чистый, без SDK)
- **Tools.kt** — `Tool(name, description, inputSchema)` — доменное описание инструмента.
- **Ports.kt** — порт `ToolGateway`: `connect()`, `listTools(): List<Tool>`, `callTool(name, args): String`, `close()`.

### `data/`
- **McpClient.kt** — реализация `ToolGateway` на **MCP Kotlin SDK** (`io.modelcontextprotocol:kotlin-sdk`).
  `connect()` запускает сервер как процесс `java -cp <classpath> …VisaMcpServerKt`, поднимает `StdioClientTransport`
  и делает MCP-хэндшейк. `listTools()` → `tools/list`, маппинг в доменный `Tool`. `callTool()` → `tools/call`,
  результат склеивается из `TextContent`. Свежий клиент создаётся на каждое подключение (повторный демо-показ).

### `mcp/`
- **VisaMcpServer.kt** — минимальный MCP-**сервер** (`Server` + `StdioServerTransport`). Тулзы: `ping` (→ «pong»)
  и `get_visa_news(country)` (заглушка; реальный парсинг официальных сайтов — День 17). ⚠️ Протокол идёт по
  **stdout**, поэтому печатать в stdout нельзя; логи SLF4J заглушены (`slf4j-nop`).
- **McpDemoMain.kt** — консольная проверка (`runMcpDemo`): подключиться и вывести список тулзов.

### `ui/`
- **ChatState** — `connectMcp()` (поднять сервер + список тулзов), `pingMcp()` (вызвать `ping`), `closeMcpDialog()`
  (закрыть окно и погасить подпроцесс). Состояние: `mcpDialogOpen`, `mcpConnecting`, `mcpTools`, `mcpError`,
  `mcpPinging`, `mcpPingResult`. Гейтвей создаётся фабрикой `toolGatewayFactory` (внедряется в `Main.kt`).
- **App.kt** — кнопка-пазл **`McpButton`** в композере рядом с «+», и окно **`McpToolsDialog`** (статус соединения,
  список инструментов, кнопка «Проверить связь (ping)» → `→ pong`).

## Поток (один показ)

```
McpButton (ui) → ChatState.connectMcp()
   → toolGatewayFactory() = McpClient()                    // data
        → ProcessBuilder(java -cp … VisaMcpServerKt)        // сервер как подпроцесс
        → StdioClientTransport → client.connect()           // MCP initialize по stdio
   → gateway.listTools()  → tools/list → List<Tool>         // показываем в окне
ping: ChatState.pingMcp() → gateway.callTool("ping") → tools/call → "pong"
```

## Запуск / проверка

- Консоль: `.\gradlew.bat runMcpDemo` (UTF-8: `chcp 65001` для читаемой кириллицы).
- В приложении: `.\gradlew.bat run` → иконка-пазл рядом с «+» → окно «Инструменты MCP» → «Проверить связь (ping)».
- Что сервер реально живой: при открытом окне
  `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -like '*VisaMcpServerKt*' }`
  покажет процесс сервера; после «Закрыть» — исчезнет.

## День 17 — `get_visa_requirements`: агент-исследователь внутри MCP

Самодостаточный умный сервис — агентный RAG с самокритикой внутри MCP-сервера.

- **`mcp/VisaSearch.kt`** — поиск и контент: `searchVisa` = **Tavily** (осн., `TAVILY_API_KEY`) → **DuckDuckGo**
  (парсинг HTML-выдачи, без ключа); официальные домены (gov/gouv/mfa/vfsglobal/посольства) — вперёд.
  Плюс `fetchReadable` (полный текст доступной страницы; Cloudflare/CAPTCHA пропускаются) и `wikipediaPolicy`
  (аварийный фоллбэк, когда поиск пуст).
- **`mcp/VisaResearchAgent.kt`** — цикл (≤3 раунда): стартовые запросы → раунд (поиск + дотягивание страниц,
  dedup по URL) → **критик** (DeepSeek, строгий JSON `{done, missing, next_queries}`) → **синтез** (DeepSeek:
  структура + цитаты на офиц. URL + «Пробелы» + дата + дисклеймер).
- **`VisaMcpServer`** — тул `get_visa_requirements(destination, citizenship, purpose)`; ключи из env
  (`DEEPSEEK_API_KEY` — агенты, `TAVILY_API_KEY` — поиск); при старте пишет в stderr диагностику
  `visa-mcp ready: deepseek=…, tavily=…`. Без LLM — «сырой» вывод источников; без поиска — Wikipedia.
- **UI** — в окне «Инструменты MCP» поля «Страна»/«Гражданство» + «Узнать» (`ChatState.callVisaRequirements`).

Ключи серверу-подпроцессу: DeepSeek прокидывает `McpClient` через env процесса; Tavily наследуется окружением
(`setx TAVILY_API_KEY …` → новое окно → `gradlew --stop`). Порталы подачи (France-Visas, кабинет VFS) под
Cloudflare — не скрейпятся, поэтому ищем через поисковый индекс и цитируем найденные официальные URL.

## Грабли / решения

- **Версия SDK = 0.10.0.** Новее (0.11+) собраны на Kotlin 2.3 (метаданные 2.3.0), а компилятор проекта —
  **2.1.21** (читает только до 2.2.0). 0.10.0 — последняя на Kotlin 2.2.x. Свежий SDK потребует апгрейда
  Kotlin/Compose — отдельной задачей (см. [WEEK_4_MCP_AND_SKILLS.md](WEEK_4_MCP_AND_SKILLS.md)).
- **stdio-транспорт сервера** в 0.10.0: вход через Ktor `System.\`in\`.asInput()`, выход — `asSink().buffered()`.
- **Кодировка консоли**: вывод UTF-8 + `chcp 65001`; в окне Compose кириллица и так корректна.
- **Подпроцесс**: при закрытии stdin (выход клиента) сервер ловит EOF и завершается сам; `closeMcpDialog` гасит явно.
- **Сеть**: зависимости тянутся с Maven Central — нужен рабочий DNS/интернет на машине сборки.

## День 18 — Планировщик, фоновые задачи и remote-деплой

Периодический **визовый дайджест** + транспорт-агностичный сервер + 24/7 на VPS.

### Планировщик (SQLite + фон)
- **`mcp/scheduler/DigestStore.kt`** — SQLite (sqlite-jdbc, чистый JDBC). Таблицы `digest_country`
  (подписки) и `digest_snapshot` (накапливаемая история сводок). Одно соединение, `@Synchronized`,
  схема `CREATE TABLE IF NOT EXISTS`. Путь — env `DIGEST_DB`.
- **`mcp/scheduler/DigestScheduler.kt`** — корутинный цикл: раз в `DIGEST_INTERVAL_MINUTES` обходит
  подписки и собирает свежую сводку через `VisaResearchAgent` (тот же, что в `get_visa_requirements`),
  пишет снимок. Изоляция отказов по странам; первый обход — сразу при старте.
- **Тулзы:** `add_digest_country` / `list_digest_countries` / `remove_digest_country` / `get_visa_digest`
  (агрегат: по каждой стране последняя сводка + дата сбора + число снимков). Регистрируются в
  `buildServer()` рядом с `ping`/`get_visa_requirements`.

### Транспорт-агностичный сервер
- `VisaMcpServer.main()` выбирает транспорт по env **`MCP_TRANSPORT`**: `stdio` (как Дни 16–17, подпроцесс)
  или `sse`. Тулзы и планировщик — общие (`buildServer()` вызывается для stdio один раз, для SSE — на
  каждую сессию; `store`/`scheduler` в замыкании общие).
- **SSE:** `embeddedServer(CIO){ install(SSE); install(Authentication){ bearer } ; routing { get("/health"); authenticate { mcp { buildServer() } } } }`.
  Хелперы из SDK 0.10.0: `io.ktor.server.sse.SSE`, `io.modelcontextprotocol.kotlin.sdk.server.mcp`.
- **Клиент (`data/McpClient.kt`):** два режима — stdio (подпроцесс) или remote (`sseUrl`+`authToken` через
  `HttpClient.mcpSse`). Токен вешаем `defaultRequest`-ом (SDK шлёт служебный POST без кастомных заголовков).
  ⚠️ У CIO-клиента `engine { requestTimeout = 0 }` — иначе дефолтные 15 c рвут долгий research по сети.

### Деплой (remote, 24/7) — обобщённый рецепт
> Конкретные домен/пути/порт/токены — НЕ в репозитории (инфра/секреты). Здесь только техника.
- Fat-jar: задача **`mcpServerJar`** (Shadow) → `build/libs/visa-mcp-server-all.jar`
  (`mergeServiceFiles()` обязателен — JDBC-драйвер и Ktor по `META-INF/services`).
- systemd-сервис: non-root пользователь, `EnvironmentFile=/etc/<service>.env`, `Restart=always`,
  `ProtectSystem=strict` + `ReadWritePaths=<каталог данных>`. Jar и БД — в отдельных каталогах сервиса.
- **reverse-proxy** (напр. Caddy): `<домен> { reverse_proxy 127.0.0.1:<порт> }` — авто-HTTPS
  (Let's Encrypt), SSE проксируется без доп. настройки.
- **Секреты — только на сервере** (env-файл, права 600): `DEEPSEEK_API_KEY`, `TAVILY_API_KEY`,
  `MCP_AUTH_TOKEN`, `MCP_TRANSPORT=sse`, `MCP_HOST`, `MCP_PORT`, `DIGEST_DB`, `DIGEST_INTERVAL_MINUTES`.
  В репозиторий не коммитятся. Десктоп хранит `mcpRemoteUrl`/`mcpRemoteToken` в `~/.adventai/config.json`.
- **Defense-in-depth:** SSE-сервер биндится на **`127.0.0.1`** по умолчанию (env `MCP_HOST`) — снаружи
  недоступен даже при сбое firewall, ходит только reverse-proxy с localhost. `MCP_HOST=0.0.0.0` — лишь для
  прямого внешнего доступа без прокси. Write-тул `add_digest_country` запускает платный research → на
  публичном коннекторе нужен rate-limit / отдельный admin-токен (заложено на День 19).

### Грабли Дня 18
- **Два `main()` в пакете `mcp`** (демо + сервер) уживаются, только пока ОБА — валидные entry-points
  `fun main(): Unit`. SSE-ветка `embeddedServer(...).start()` сделала вывод типа `Any` → конфликт; лечится
  явной аннотацией `fun main(): Unit = …`.
- **`umask 077` при `mkdir`** на сервере → каталог 700, и сервис-пользователь не читает jar
  («Unable to access jarfile»). Нужны `chmod 755` на каталог jar и `644` на сам jar.
- **SDK-версия:** SSE/StreamableHTTP-серверные транспорты есть уже в **0.10.0** (`SSEServerTransport`,
  `KtorServer.mcp`, `mcpStreamableHttp`) — апгрейд SDK не понадобился.
