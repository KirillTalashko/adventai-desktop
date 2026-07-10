# Local LLM — локальная генеративная модель через Ollama (День 26)

Старт недели 6. На неделе 5 (RAG) локально работал только **эмбеддер** (`nomic-embed-text`); теперь локально
крутится и **сама отвечающая LLM** — через **Ollama** на `localhost:11434`, бесплатно и приватно, без облака.
День 26 по брифу: модель запущена и отвечает на **3 запроса разной сложности** (CLI/HTTP).

> Обзор недели и теория (квантизация, tooling, зачем локально) — `.claude/WEEK_6_LOCAL_LLM.md`.

## Что делает

Локальная LLM подставляется вместо облачной (DeepSeek/OpenRouter) через тот же доменный порт — домен не
замечает разницы (Clean Architecture). Два способа проверить:

1. **CLI** — `.\gradlew.bat runLocalLlm`: прогоняет 3 запроса и печатает ответ + задержку + токены.
2. **Dev-панель «Локальная LLM»** — иконка-чип в композере (режим разработчика): свой запрос или кнопка
   «Прогнать 3 запроса».

## Как устроено (код)

```
domain/LlmGateway  ─ порт (complete: messages → GatewayResponse), домен без HTTP
        ▲
        │ реализует
data/LocalLlmClient ── HTTP POST → http://localhost:11434/api/chat  (Ollama, stream=false)
                       без прокси (localhost), Ktor CIO; ответ → text + TokenUsage(prompt/eval)
```

- **`data/LocalLlmClient.kt`** — Ktor-клиент к нативному Ollama `/api/chat`. Реализует `domain.LlmGateway`
  (`complete(messages, tools, params, executeTool)`), маппит `Message`(`Role.wire`)→ollama-сообщения,
  берёт `message.content`, токены — из `prompt_eval_count`/`eval_count`. Модель по умолчанию — `qwen2.5:7b`.
- **`cli/LocalLlmMain.kt`** + задача `runLocalLlm` в `build.gradle.kts` (по образцу `runMcpDemo`).
- **UI:** `ChatState.localLlmRunSamples()/localLlmAsk()` + панель `LocalLlmDialog` в `App.kt` (mirror RAG-панели,
  gated `developerMode`). Прогресс показывается по мере ответов.
- **3 запроса** — общий список `LOCAL_LLM_SAMPLES` (в `LocalLlmClient.kt`), один и тот же для CLI и панели (DRY).

### Почему отдельный клиент, а не `LlmClient` с другим baseUrl

Облачный `data/LlmClient` всегда ходит через `HttpProxy` (туннель на сетях с закрытым прямым выходом). К
`localhost` прокси нужно **обходить** — ровно как это делает `OllamaEmbedder`. Плюс нативный `/api/chat` не
требует API-ключа и tool-схем. Поэтому `LocalLlmClient` — отдельная реализация того же порта, без прокси.

## 3 запроса разной сложности

| # | Сложность | Запрос |
|---|---|---|
| 1 | простой факт | «В каком городе штаб-квартира Евросоюза? Одним предложением.» |
| 2 | доменное рассуждение | «Гражданин РФ, Испания, 10 дней, июль, двое детей → 5 ключевых документов на шенген.» |
| 3 | структурный вывод | «Верни только JSON: country, visa_type, documents[] для поездки в Японию.» |

## Как запустить

```bash
# 1. Ollama (уже установлена) + скачать генеративную модель (~4.7 GB):
ollama serve                 # если не запущена как сервис
ollama pull qwen2.5:7b

# 2. CLI-демо (3 запроса):
.\gradlew.bat runLocalLlm
.\gradlew.bat runLocalLlm -Dmodel=llama3.2:3b   # другая модель

# 3. В приложении: .\gradlew.bat run → Настройки → режим разработчика →
#    иконка-чип «Локальная LLM» в композере → «Прогнать 3 запроса».
```

Без запущенной Ollama или без скачанной модели клиент отдаёт внятную ошибку с подсказкой `ollama pull …`.

## Проверка

`.\gradlew.bat compileKotlin --offline` — зелёный. `.\gradlew.bat runLocalLlm` на `qwen2.5:7b` отвечает на все
3 запроса: штаб-квартира ЕС → «Брюссель» (~14 c / 90 ток.), список документов на шенген (~57 c / 524 ток.),
валидный JSON по Японии (~10 c / 126 ток.). На отсутствие модели — понятная ошибка. Локальный Ollama на
`localhost` доступен и из headless-gradle (в отличие от облачной LLM — DNS), поэтому демо проверяемо не только в GUI.

## Что дальше (следующие дни недели 6)

Тумблер **cloud ↔ local** в нижнем селекторе моделей (+ провайдер «ollama» в `Models.kt`); режим **сравнения**
двух ответов (облако vs локаль) с маркировкой; **MCP-tool-loop** для локальной модели (Ollama умеет `tools` в
`/api/chat`); конфиг-поле `localModel`; при желании — стриминг токенов.
