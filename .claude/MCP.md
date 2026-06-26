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
