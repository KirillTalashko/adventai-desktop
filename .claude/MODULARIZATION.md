# Многомодульная архитектура и порядок рефакторинга

> **Статус:** снимок на `ee44bc7`. Продукт роя из 8 агентов (6 исследователей → архитектор → критик).
> Критик отбраковал переусложнение архитектора (13 модулей → 6–7) и две ложные оптимизации — здесь
> уже **исправленная** версия. Метод — прикладной к этому репозиторию; переносимый скилл — в
> [REFACTOR_SKILL.md](REFACTOR_SKILL.md). Диагноз god-object — в [ARCHITECTURE_AUDIT.md](ARCHITECTURE_AUDIT.md).

---

## 0. Главный ответ: с чего начинать

**Не с модулей. И не со «свободного исследования». А в строгом порядке:**

```
тесты-швы  →  логические границы на бумаге (Mikado + git-история)  →  СЖАТЬ код  →
расшить god-object на держатели состояния  →  и ТОЛЬКО ПОТОМ физически резать модули
```

Почему «модули-сначала» — ошибка именно здесь:

1. **Сквозь god-object границу не провести.** В `ChatState` — 94 сцепленных `mutableStateOf` и живые межпанельные записи (`optimizeCompare()` пишет в `localLlmNote`, `openRag()` — в `localLlmModels`, `ragNote` делят 5 фич). Физический шов Gradle через это множество провести не по чему.
2. **Ноль тестов.** `src/test` отсутствует. Двигать god-object вслепую = гарантированная тихая регрессия — ровно того класса, что уже случился: `init`-order NPE на `ChatState.kt:1922` (свойство объявлено на 700 строк ниже его чтения в `init`), который `compileKotlin` не ловит.
3. **Ранний раскол замораживает беспорядок в build-файлах** и превращает 23 копии метода в **межмодульное** дублирование, которое схлопывается труднее, чем внутри одного файла.
4. **Это прямо против вашей цели «меньше кода».** Модули добавляют build-конфиг, DI-проводку, barrel-файлы. Если резать ДО сжатия — суммарный LOC растёт.

**Вывод:** сначала чиним god-object и сжимаем код **внутри одного модуля** (это снимает ~90% боли), и лишь затем — минимальный физический раскол. Причём границы модулей мы уже **обнаружили** по швам (change-coupling, скрытый composition root, 12 зон `ChatState`), а не назначили схемой слоёв — домен-то уже чист.

---

## 1. Целевой граф: 7 модулей, а не 13

Критик отбраковал раскладку архитектора «слой × фича» на 13 модулей как **прямое переусложнение** для соло-проекта на 13k строк: она копирует Now-in-Android (большая команда, независимые релизы фич), которых здесь нет. Все три боли (god-object, скрытый composition root, HTTP в UI) снимаются **внутри одного модуля** — физический раскол на 5 фичевых модулей за них не отвечает.

```
                          ┌─────────────────┐
                          │   :app:desktop  │  окно Compose + ЕДИНСТВЕННЫЙ composition root (ручной DI)
                          └────────┬────────┘
                 ┌─────────────────┼─────────────────┐
                 ▼                 ▼                 ▼
          ┌───────────┐     ┌───────────┐     ┌─────────────┐
          │  :ui:kit  │     │ :core:data│     │ :core:domain│
          │ токены +  │     │ репозитории│    │ ЧИСТ. ядро: │
          │ атомы,    │     │ конфиг,   │────▶│ порты,модель│
          │ domain-free│    │ http, LLM-│     │ FSM, агенты │
          └───────────┘     │ клиенты   │     │ (0 внеш.имп)│
                            └─────┬─────┘     └──────▲──────┘
                                  │                  │
     ┌────────────────────────────┼──────────────────┤
     ▼                            ▼                  │
┌──────────────┐         ┌──────────────┐            │
│:service:mcp- │         │ :service:llm │            │  (сервисы и cli тоже
│ visa (деплой)│         │  (деплой)    │────────────┘   зависят только вниз)
└──────────────┘         └──────────────┘
     ▲
┌──────────────┐
│  :app:cli    │  headless-точки входа (проверка, что core работает без Compose)
└──────────────┘
```

| Модуль | Тип | Что внутри | Зависит от |
|---|---|---|---|
| `:core:domain` | core, **pure kotlin** | модель, `VisaAgent`, память, FSM, инварианты, Case File, **все порты** (`Ports.kt`) + поднятые из `domain/rag`: порт `KnowledgeRetriever` + `KnowledgeHit`/`CountryScope`/`KnowledgeScope` | — |
| `:core:data` | core | персистентность (репозитории/сторы/SQLite), конфиг (`ConfigStore`), http-инфра (`HttpProxy`, фабрика `cioClient`), DTO, **3 LLM-клиента** (облако/Ollama/сервис) | `:core:domain` |
| `:ui:kit` | ui | **domain-free** дизайн-система: токены (`Space`/`Radii`/палитра), атомы `IconActionButton`/`PanelDialog`/`ResultCard`/`MarkdownText` | — |
| `:app:desktop` | app | окно Compose + **единственный composition root** + `ChatState` (расшитый на держатели) + весь фичевый UI **как ПАКЕТЫ** | всё нижнее |
| `:service:mcp-visa` | service | серверный MCP + планировщик дайджеста (деплой на VPS, fat-jar) | `:core:*` |
| `:service:llm` | service | приватный HTTP-LLM-сервис (День 30, fat-jar) | `:core:domain`, `:core:data` |
| `:app:cli` | app | `VisaCliMain`/`DevHelpMain`/… — headless-входы | `:core:*` |

**Фичи (RAG / MCP / локальная LLM / dev-ассистент) — это ПАКЕТЫ, а не модули.** Их изоляцию друг от друга держит **одно правило Konsist в CI**, а не 5 build-файлов. `:feature:*` как отдельные Gradle-модули — это задокументированный **потолок на будущее** (§6), к которому идут strangler-fig'ом, только если появится измеримая боль сборки или второй потребитель. `:feature:local-llm` архитектор сам пометил «кандидат слиться в labs» — классический признак, что выделять его модулем рано.

---

## 2. Логическая связка

- **Всё межграничное общение — через доменные порты** в `:core:domain` (`LlmGateway`, `ToolGateway`, `SkillRunner`, `ConversationRepository`, `MemoryStore` + поднятый `KnowledgeRetriever`). Потребитель зависит от абстракции; конкретную реализацию (Ktor/файлы/SQLite/Ollama) не импортирует. Так правило `CLAUDE.md` «не тащить HTTP/Ktor в ui» поднимается **с уровня ревью на уровень компилятора**: `:ui`/фичи физически не могут импортировать транспорт.
- **Composition root ровно один — `:app:desktop` (`Main.kt`).** Сейчас `ChatState` — скрытый второй root: сам создаёт `LlmClient`/`LocalLlmClient`/`LlmServiceClient`/`OllamaEmbedder` (строки `1096/1153/1200/1218/1233/1333–1334` + `rebuildAgent` `1901/1902/1908`; эмбеддер ещё и `546/964/974`). Пока корней два — настоящего шва нет; ликвидация скрытого root первоочередна.
- **`feature НЕ зависит от feature`** соблюдается строго: общий тип едет **вниз** в `:core:domain`, а не вбок. Поэтому текущее ребро `domain→domain.rag` (его тянут `VisaAgent`/`TaskOrchestrator`/`DevAssistant`) лечится **подъёмом в `:core:domain` порта `KnowledgeRetriever` и типов `KnowledgeHit`/`CountryScope`/`KnowledgeScope`**. Проверено grep'ом: домен импортирует ровно `KnowledgeRetriever`/`KnowledgeHit`/`CountryScope`; `KnowledgeScope` едет вместе с `CountryScope.kt` (он в сигнатуре порта `retrieve(query, scope)` и в возврате `CountryScope.scopeFor`), а `RagOptions` домену не нужен вовсе. Dev-ассистент берёт RAG через порт, а не через фичу.
- **Открытая панель = живое дочернее состояние** (`sealed ActivePanel`), а не булев `*Open`-флаг: закрыл панель — её состояние умерло целиком, нечего забывать сбрасывать. Чат открывает чужие панели не импортом фичи, а через этот sealed-тип, скомпонованный в `:app`.
- **`internal` по умолчанию; `api` — только для типов, реально протекающих в публичных сигнатурах** (`Message`/`Model` из `:core:domain`). Всё прочее — `implementation`: меньше ABI-поверхность → быстрее инкрементальная сборка.
- **Границы держит машина, а не ревью.** Konsist / module-graph-assert как **обязательный CI-гейт** (нет циклов; feature не видит feature; `:ui` не видит `:core:data`). Правило, которое не проверяет CI, деградирует за недели.

### Два уточнения критика (учтены)

- **`:core:data` — следить за grab-bag.** Персистентность + конфиг + http + DTO меняются по разным причинам (CCP-запах). Пока держим одним модулем с разделением по пакетам (`data.persist`/`data.config`/`data.net`/`data.llm`); как только конфиг/прокси начнут меняться своим темпом — выделить узкий `:core:config`, чтобы `:core:data`/`:core` сетевой слой зависел от него, а не от файлового репозитория.
- **`:ui:kit` — domain-free.** `MessageView`/`ChecklistView` знают про `Message` → они едут в `:app:desktop` (фичевый пакет чата), а в `:ui:kit` остаются только предметно-нейтральные атомы. Тогда `:ui:kit` не зависит от `:core:domain` вовсе — модуль реально атомарный.

---

## 3. Порядок работ (проверенная цепочка)

Три фазы. **Фаза A целиком идёт внутри текущего одного модуля** и снимает основную боль. Только после неё — раскол.

### Фаза A — починить god-object (без единого нового Gradle-модуля)

| Шаг | Действие | Метод | ↓LOC | Риск |
|---|---|---|---|---|
| A1 | **Тест-опора.** Расширить `runRagCountryCheck` на 2–3 сценария god-object (`send`/`advanceTask`/одна панель), зафиксировав **текущее** поведение | Characterization tests (Feathers) | — | нет |
| A2 | **Логические границы на бумаге.** Mikado-граф «вынести каждый холдер»: пробы-откаты показывают пред-условия (развести общий `ragNote`, убрать межпанельные записи); листья графа = первые правки. Сверить с git-историей (RAG=Дни 21–25, локаль=Дни 26–30) | Mikado + CCP | — | нет |
| A3 | **Подключить детекцию + baseline.** detekt (`LargeClass`/`LongMethod`/`TooManyFunctions`) + PMD CPD (порог ~75 токенов) как Gradle-задачи; зафиксировать `baseline.json` (cloc/dup%/смелы) | Fitness function | +~15 стр конфига | нет |
| A4 | **СЖАТЬ каркас.** 23 копии «панельного метода» → один `PanelOp`/`serviceCall` (обобщение уже написано, применено к 2 из 23). Тройки `running+note+result` → `Async<T>`. Развести поля-мосты | Preparatory refactoring (Beck) | **−~300; 94→~35 полей** | низкий |
| A5 | **СЖАТЬ инфру и UI-атомы.** Фабрика `cioClient(json,timeouts,proxy)`: 8 мест `HttpClient` → однострочники (сходятся таймауты 120/300/60/8 с); 3 именованных `Json` вместо 9. `IconActionButton` (−5 клонов кнопок), `PanelDialog` (−6 каркасов, −4 копии «Закрыть»), `ResultCard` (−~5 карточек) | DRY→KISS, Rule of Three | **да** | низкий |
| A6 | **Числа → токены, повторяющиеся строки → ресурсы.** `object Space{xs=4…xl=24}` рядом с `Radii`; заменить 163 инлайновых `spacedBy`/`padding` **по семантической роли** (не слепым sed). Строки — **только повторяющиеся** фразы и длинные промпты (см. оговорку ниже) | Design tokens / DTCG | **да (net-negative)** | низкий |
| A6+ | **Убить мёртвый код ДО раскола.** detekt `UnusedPrivateMember` + IDE «Unused declaration» по всему модулю; проверить 49 маркеров «День NN» на устаревшие демо-ветки. **Исключить** headless-входы (`VisaCliMain`/`McpDemoMain`/`LocalLlmMain`) — статически «мёртвы», но запускаются извне | Dead-code elimination | **да** | низкий |
| A7 | **Единый composition root.** Вынести `rebuildAgent` и конструирование клиентов (строки 1096/1153/1200/1218/1233/1333–1334 + `rebuildAgent` 1901/1902/1908) в `Main.kt`/`AppComposition`; `ChatState` принимает готовые порты через конструктор. Попутно исчезает класс бага «порядок init» | Branch by Abstraction | слегка | средний |
| A8 | **Расшить `ChatState` на держатели по зонам.** Листья-первыми: `ServicePanelState`/`LocalLlmPanelState` → `RagPanelState` → `McpPanelState`; ядро чата последним. После каждого — прогон харнесса A1 | Branch by Abstraction + листья-сначала | нейтр. (SRP) | средний |
| A9 | **`App.kt` → файл-на-панель + `sealed ActivePanel`.** Панель принимает своё под-состояние и лямбды (UDF). Чинит лишние рекомпозиции от чтения чужого `mutableStateOf` | Strangler Fig | нейтр. | средний |
| A10 | **Починить ISP/LSP-разрывы.** Разрезать жирный `LlmGateway` на `ChatCompletion` + `ToolCallingLlm`, tool-loop → доменный `ToolLoopRunner`; `Embedder.close()` в порт (−9 даункастов). Делать ДО раскола, чтобы не заморозить тихий отказ `LocalLlmClient` за стеной модуля | Role interfaces (ISP) | −9 даункастов | средний |

### Фаза B — минимальный физический раскол

| Шаг | Действие | ↓LOC | Риск |
|---|---|---|---|
| B1 | **Version catalog + convention plugins ДО раскола.** `gradle/libs.versions.toml` (Ktor ×8, Kotlin ×3 в одно место) и `build-logic` с плагинами `advent.kotlin.jvm`/`advent.compose.desktop`, **пока модуль ещё один** | **да** | низкий |
| B2 | **Резать листьями-первыми.** `:core:domain` (уже чист — тривиально) + `:ui:kit`; затем `:core:data`; выделить `:service:mcp-visa`/`:service:llm`/`:app:cli` (deploy-задачи `shadowJar`/`JavaExec` переселить туда); `:app:desktop` последним. **Подъём `KnowledgeRetriever` в `:core:domain` — обязательное пред-условие** выноса rag-кода | нейтр. | средний |
| B3 | **Re-measure + CI-гейт границ.** Повторить cloc/CPD/detekt, дифф к baseline (acceptance: LOC не вырос, dup% ↓, `LargeClass`/`LongMethod` ↓). Чистый **перенос** строк при неизменном поведении — норма для раскола; разбуханием считать только рост *added* при том же поведении (moved-vs-added). Konsist/module-graph-assert как **обязательный статус-чек** | нейтр. | низкий |

### Фаза C — фичевые модули (на будущее, по необходимости)

Выделять `:feature:rag`/`:feature:mcp`/… отдельными Gradle-модулями — **только** когда появится: (а) второй реальный потребитель фичи, (б) измеримая боль времени сборки, или (в) потребность в независимом релизе. До этого фичи живут пакетами, а их изоляцию держит Konsist. Полную 13-модульную раскладку архитектора храним как ориентир, но **не строим её сейчас** — это YAGNI.

---

## 4. Как уменьшить объём кода, не раздув его модуляризацией

Ваша исходная интуиция верна: **наивная модуляризация ДОБАВЛЯЕТ код.** Контр-приёмы (замеры — на этом репозитории):

| Практика | Что сжимает | Правило, чтобы не выстрелить в ногу |
|---|---|---|
| **Дизайн-токены из одного источника** | 302 `.dp`-литерала → ~8 токенов; палитра продублирована в `Light`/`Dark`/`AppColors`/`StatusColors` → единый источник + алиасы | Одноразовые layout-числа (`272`/`700`/`640.dp` — ширины окон) в шкалу **не тащить**. `Style Dictionary`/`tokens.json` — только когда токены нужны и Figma/веб-двойнику; для одного приложения `object`-константы достаточно |
| **Строки в ресурсы** | одинаковые фразы («Закрыть», «Модель», статусы) схлопываются в один ключ | ⚠️ **net-negative только для дублей.** Уникальный литерал при выносе даёт строку-ключ + строку-ссылку = столько же или больше. Выносить **повторяющиеся** фразы (измерить CPD) и **длинные промпты**; уникальные однострочные подписи — оставить инлайн. Гейтить net-LOC. `moko`/Compose-resources ради одного языка без рантайм-локали — **не тащить** |
| **Промпты как данные** | повторяющиеся блоки (роль + анти-галлюцинация + строгий формат) из ~14 файлов → один шаблон | Промпт, встречающийся один раз и намертво связанный с функцией, — не выносить (Rule of Three) |
| **Атомарный UI-kit** | 5 кнопок → 1 (−~60 стр), 6 диалог-каркасов → 1 `PanelDialog`, ~5 карточек → 1 `ResultCard` | Не создавать атом ради одного места; не делать god-компонент с 12 булевыми флагами — это перенос god-object в UI-kit. Слот-API вместо флагов |
| **Дедуп ДО раскола** | 23 копии метода, 8 `HttpClient`, 9 `Json{}` — схлопнуть в одном модуле | Раскол раздутого кода фиксирует раздутость **за границей навсегда** |
| **Убрать мёртвый код** | недостижимый код (detekt + IDE-reachability) — до проведения границы | мёртвый код, перенесённый через границу, костенеет как псевдо-публичный API; headless-входы из удаления исключить |
| **Convention plugins + version catalog** | per-module build ≈ 5 строк вместо ~40; версии в одном месте | Вводить, как только модулей ≥3 |
| **Никаких barrel/re-export** | — | Barrel-файлы добавляют LOC ради индирекции, ломают tree-shaking, плодят циклы |
| **Baseline net-LOC как CI-гейт** | защищает всё выше | LOC читать **в паре** со смелами detekt и ревью — не изолированно (иначе ужмёшь читаемость в кашу) |

⚠️ **Кириллица в регэкспах:** Kotlin `\w`/`\b` по умолчанию ASCII-only → поиск дублей/строк молча пропустит половину из 1554 русских литералов. Использовать `\p{L}`, `(?U)`/`(?iu)` (проект ловил это дважды, Дни 23–24).

---

## 5. Чего НЕ делать (сводка возражений критика)

- ❌ **Не строить 13 модулей.** Для 13k строк соло — оверинжиниринг. Старт: 6–7 модулей, фичи — пакетами.
- ❌ **Не выделять `:feature:local-llm`/`:feature:devassist` модулями сейчас** — они малы, план сам метит local-llm «слить в labs» (YAGNI).
- ❌ **Не вводить api/impl split** — для одной реализации каждого сервиса это чистый оверхед ×2 модулей. Дешёвая инверсия уже есть — порты в `Ports.kt`.
- ❌ **Не выносить все 1554 строки** — на уникальных строках это **растит** LOC. Только дубли + длинные промпты.
- ❌ **Не подключать Style Dictionary** для одного приложения — `object Space`/`Radii` достаточно; генератор окупается при мульти-платформенной/мульти-брендовой выдаче.
- ❌ **Не разносить конструкторный шов на два шага** — критик проверил: клиенты создаются не в `init`, а внутри suspend-методов; весь снос делать целостно на A7 под готовым харнессом.
- ❌ **Не резать по слоям** («все поля в один модуль, все методы в другой») — это рвёт common closure. Резать по фичам/причине изменения.

---

## 5b. Расшивка `ChatState` на держатели — прогресс и выверенный план RAG

Ветка `refactor-phase-a-shrink`. Держатели выносятся по SRP (инъекция зависимостей конструктором, UI зовёт
`state.holder.X`, поля-`mutableStateOf` переносятся дословно). `ChatState` 1945 → 1748 (−197), зон 12 → 10.

| Держатель | Статус | Заметка |
|---|---|---|
| `ServicePanelState` | ✅ `eedd215` | самая чистая зона; инжект `scope` + `{ localLlm.localLlmPrompt }` |
| `LocalLlmPanelState` | ✅ `520002f` | хаб; `setAvailableModels()` — явная точка кросс-записи из RAG |
| **`RagPanelState`** | 📋 **план готов, НЕ исполнен** | самая связанная; см. ниже |

### RagPanelState — план выноса (продукт Mikado-роя, критик: `proceedNow=True`)

**Почему отложено:** это самый крупный и переплетённый вынос, а панель **нельзя GUI-проверить** (окно
gradle-процесса не гранта́ется computer-use). Гейт диффа доказывает целостность *агент-RAG*, но не
корректность самой панели. Исполнять — в сессии, где GUI-проверка панели возможна.

**Граница (Option B).** RAG-зона делит с агентом единый индекс и эмбеддер — поэтому агент-общее **остаётся
в `ChatState`**, а панель получает провайдеры:
- **Остаётся** (агент/dev-docs): поле `knowledge` + `knowledge()`, `newEmbedder()`, `ragQueryEmbedder()`,
  `ragUseOllama` + `chooseRagEmbedder()`, `AGENT_RAG_OPTIONS`, `OLLAMA_EMBEDDER_PREFIX`, сшивка
  `rebuildAgent` (`RagKnowledgeRetriever(knowledge(), ::ragQueryEmbedder, AGENT_RAG_OPTIONS)`),
  `renderRagSources`, `config.ragInAgentEnabled`/`setRagInAgentEnabled`.
- **Уезжает** в `RagPanelState`: поля `ragOpen`/`ragBuilding`/`ragProgress`/`ragNote`/`ragComparison`/
  `ragDocCount`/`ragQuery` + `ragAnswering`/`ragAnswer*`/`gold*`/`ragStrategy…ragFloor`/`ragTrace`/`ragVs*`/
  `ragLocalModel`/`citation*`; методы `openRag`/`closeRag`/`buildIndex`/`ragOptions`/`ragCompare`/
  `ragCompareLocalVsCloud`/`timeRagVs`/`askNegativeExample`/`runGoldAnswers`/`runGoldRetrieval`/
  `runCitationEval`/`ragJob`/`toView`; вложенный `RagVsResult`.

**7 инъекций конструктора:** `scope`, `knowledge: () -> KnowledgeIndex` (**`::knowledge` — та же ссылка, не
копия**, иначе двойной `seedMissing` + рассинхрон векторов), `newEmbedder: () -> Embedder` (`::newEmbedder` —
**разрывает цикл**: тумблер остаётся в ChatState), `useOllama: () -> Boolean` (`{ ragUseOllama }` — для
подсказки в `buildIndex`), `gateway: () -> LlmGatewayClient?` (**нулабельный**, провайдер — `client`
пересобирается), `config: () -> DesktopConfig` (провайдер), `localLlm: LocalLlmPanelState` (для
`openRag → localLlm.setAvailableModels`). `val rag` объявить **строго после** `val localLlm`/`val service`.

**Гейт безопасности (замена GUI для агент-RAG):** после выноса `git diff` по символам
`knowledge`/`ragQueryEmbedder`/`newEmbedder`/`ragUseOllama`/`AGENT_RAG_OPTIONS`/`rebuildAgent:1723` обязан
быть **пустым**. Плюс `compileKotlin` + `runTaskFlowCheck` 12/12.

**Ловушки App.kt (37 точек, править ПРИЦЕЛЬНО, без глобального sed):**
- коллизия: `state.ragInAgentEnabled`/`setRagInAgentEnabled` — **остаются** (коннектор), regex `state.rag→state.rag.rag` их испортит;
- 7 методов **без** rag-префикса regex пропустит: `openRag`/`closeRag`/`buildIndex`/`askNegativeExample`/`runGoldRetrieval`/`runGoldAnswers`/`runCitationEval`;
- `ChatState.RagVsResult` → `RagPanelState.RagVsResult` (вручную);
- **не трогать** `state.ragUseOllama`/`state.chooseRagEmbedder` и `state.localLlm.localLlmModels`.

**⚠️ Load-bearing инвариант — в KDoc `RagPanelState`:** `newEmbedder`/`ragUseOllama`/`chooseRagEmbedder`/
`knowledge` оставлены в `ChatState` **намеренно** (init-order: `rebuildAgent` из `init` до конструирования
`rag`; + общие с агентским `ragQueryEmbedder` и dev-docs `devReindex`/`devQueryEmbedder`). Будущая «доводка»,
перенёсшая тумблер в панель, **вернёт init-order NPE**, который GUI-проверкой не ловится.

---

## 6. Полная 13-модульная раскладка (ориентир на будущее, НЕ строить сейчас)

Сохранена как destination strangler-fig'а, **если проект вырастет до нескольких разработчиков / независимых релизов фич**: `:core:domain`, `:core:data`, `:core:llm`, `:ui:kit`, `:feature:rag`, `:feature:mcp`, `:feature:local-llm`, `:feature:devassist`, `:feature:chat`, `:service:mcp-visa`, `:service:llm`, `:app:desktop`, `:app:cli`. Раскладка по оси «слой × фича» (Now in Android). Переходить к ней **по одному модулю**, только когда конкретная фича даст измеримую боль в текущей упаковке.

---

_Источники метода: Feathers (характеризующие тесты/швы), Fowler (Strangler Fig, Branch by Abstraction, Preparatory Refactoring), Ellnestam & Brolund (Mikado Method), Martin (CCP/CRP, правило зависимостей), Google Now in Android (раскладка модулей, convention plugins), Slack (api/impl), Terhorst-North (CUPID/BSSN), W3C DTCG + Style Dictionary (токены), detekt + PMD CPD (детекция), Konsist/ArchUnit (фитнес-функции границ)._
