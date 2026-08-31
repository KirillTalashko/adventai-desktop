# Архитектура и многомодульность desktop-приложений — исследование под AdventAI Desktop

> Дата сбора доказательств: **2026-08-26**. Все звёзды/даты — снимок на эту дату.
> Бриф: `.claude/META_PROMPT_DESKTOP_ARCHITECTURE.md`. Отвечает на §5 (структура вывода) и §6 (ограничения цели).
> Метки: **[V]** — прочитан первичный источник; **[И]** — инференция из косвенных признаков; **[?]** — не подтверждено.
> Внутренности closed-source приложений помечены **[И]** без исключений.
> **Каждая метка [V] на внешнем источнике раскрыта ссылкой в §8** («Источники по утверждениям»). Утверждение
> без строки в §8 не имеет права носить [V] — если такое встретилось, это дефект отчёта, а не сокращение.

**Замеры целевого проекта (сделаны локально, повторно сверены 2026-08-26 по рабочему дереву ветки
`paper-terracotta-redesign`):**
`app/ui/App.kt` = **2310** строк (49 `@Composable`), `ui/ChatState.kt` = **1617**, `ui/Dialogs.kt` = **566**
(5 диалогов + 13 приватных хелперов), `ui/TaskPanel.kt` = 520. Файлы в активной правке — числа плывут на ±10 строк.
Уже вынесено: `RagPanelState.kt` (320), `LocalLlmPanelState.kt` (162), `ServicePanelState.kt` (97).
Модули: `:core:domain` (единственная зависимость — `api(libs.coroutines.core)`), `:core:data`, `:app`, `:tools:{cli,mcp,service}`.
В `:app` уже есть `libs.coroutines.swing` — это важно для §6.

**Что в проекте уже стоит (сверено по файлам, не по памяти) — от этого отталкивается §6:**

| Факт | Где лежит | Следствие для плана |
|---|---|---|
| `com.jraska.module.graph.assertion` 2.9.1 **применён**, форма `allowed` (default-deny) + `restricted`, `maxHeight = 4` | `build.gradle.kts` (корень), версия в `gradle/libs.versions.toml` | ранг-2 гейт графа **уже есть**; шага «завести module-graph» в §6.4 нет |
| detekt 1.23.8 применяется **ко всем** модулям через convention-плагин | `build-logic/src/main/kotlin/advent.kotlin-jvm.gradle.kts` | fitness-функция god-object'а **уже есть** |
| detekt-конфиг: включены **только** `LargeClass` (600), `LongMethod` (60), `TooManyFunctions` (30), `build.maxIssues: 0` | `config/detekt/detekt.yml` | метрика распила — это число находок `LargeClass`, а не самодельный принтер |
| `detekt-baseline.xml` **на диске нет** (плагин подхватывает условно: `if (detektBaseline.exists())`) | — | `./gradlew check` **сейчас красный**: App.kt и ChatState.kt пробивают `LargeClass`. Первый шаг плана — снять baseline |
| **`.github/` нет**, CI-воркфлоу в репозитории отсутствуют | — | любая фраза «required check» в плане требует сначала завести CI. Это отдельный шаг с трудоёмкостью, а не бесплатное следствие |
| Стриминга в кодовой базе **нет**: `LocalLlmClient` шлёт `stream = false`, в `ChatState.kt` токен `stream` встречается **0 раз** | `core/data/…/LocalLlmClient.kt:41,135` | §4.3 и §6.5 — проектные ограничения на будущее, а **не** починка живых дефектов |
| Gradle **8.13** | `gradle/wrapper/gradle-wrapper.properties` | проходит floor DAGP 3.x (минимум 8.11) |

---

## 1. Пространство решений — слоевые паттерны

### 1.1 Сводная таблица

| Паттерн | Контракт между слоями | Кто владеет состоянием | Побочные эффекты | Тестируемость | Документированный failure mode | Годность под Compose Desktop 1.7.3 / Kotlin 2.1.21 |
|---|---|---|---|---|---|---|
| **Forms & Controls / Flow Synchronization** (то, что сейчас в `App.kt`+`ChatState.kt`) | нет контракта: любой handler читает/пишет любое поле | один объект-форма | инлайн в обработчиках | почти нулевая | god object; Fowler описал это как проигрышный по масштабу | — это диагноз, а не цель |
| **Observer synchronization / Smalltalk MVC** | модель уведомляет, view подписан | модель | в модели | высокая для модели | путь обновления невидим в коде | HIGH как субстрат — Compose snapshot это и есть |
| **MVP (Passive View)** | view за интерфейсом, presenter пушит значения | presenter | в presenter | высокая (view = mock) | второй ручной слепок UI-поверхности; Slack ушёл с MVVM+C на VIPER-вариант, затем построил Circuit | LOW — в декларативном UI пуш не нужен |
| **MVVM / Presentation Model** | биндинг: VM экспонирует состояние, view читает | ViewModel | методы VM | высокая | **god ViewModel**: droidcon Berlin 2025 — реальные VM 1376 / 694 / 687 строк **[V]** | HIGH как форма, но это диагноз, а не лекарство — без правила деления класс растёт |
| **MVI (единый immutable state + reducer)** | Intent → Executor → Message → Reducer → State (+ Label) | Store | только в Executor/`intent{}` | очень высокая (reducer чист) | церемония на каждое действие; Redux Toolkit существует ровно из-за этого **[V]** | MEDIUM — MVIKotlin/Orbit — чистый Kotlin, JVM, но переписывать 9 кластеров сразу |
| **MVU / TEA (Elm)** | `update: Msg -> Model -> (Model, Cmd)` | одна Model | описываются как `Cmd`, исполняет рантайм | максимальная | «один гигантский Msg», нет code-splitting; nested TEA даёт boilerplate и кривую связь child→parent **[V]** | MEDIUM — переносится дисциплина, не механизм |
| **Flux / Redux + slices** | actions = события, чистые reducers, эффекты в middleware | единый store | thunks/middleware | высокая | react-admin v4 удалил Redux: boilerplate, «каждое действие триггерит все reducers» **[V]** | LOW как библиотека, HIGH как правила (slice by data type) |
| **Zustand slices / Jotai atoms** | slice-creator получает `set`/`get`, родитель только композирует | несколько мелких store | внутри slice | высокая | slices молча пишут чужие ключи; middleware-ordering footgun **[V]** | HIGH концептуально — прямой перенос в Kotlin через делегирование |
| **Statecharts / Actor (XState)** | иерархический автомат, события асинхронно, состояние не разделяется | актор + его mailbox | entry/exit actions, spawn | очень высокая | вторая нотация; в Kotlin `channels.actor` помечен `@ObsoleteCoroutinesApi` **[V]** | MEDIUM — только для agent-пайплайна (`TaskStateMachine` уже есть) |
| **Clean Architecture (Мартин)** | зависимости внутрь, внутренние слои объявляют интерфейсы | entities/use cases | через порты | высокая | over-abstraction: интерфейс с одной реализацией; Google сам пишет, что домен «forces you to add use cases even when they are just simple function calls» **[V]** | HIGH для уже имеющегося хребта, LOW для новых слоёв |
| **Hexagonal / Ports & Adapters** | внутри/снаружи, driving и driven адаптеры | приложение | через порты | высокая | нет правила гранулярности портов; ничего не говорит про UI-состояние | HIGH — уже реализовано (`:tools:cli/mcp/service` = три driving-адаптера) |
| **VIPER** | View/Interactor/Presenter/Entity/Router + Contract | Presenter | Interactor | высокая | 4 правки на одно поведение; Slack прошёл MVVM+C → VIPER → двухролевой Circuit **[И]** | LOW — противоположная целевой проблема |
| **Circuit / composable presenter (Slack)** | `@Composable fun present(): UiState`, событие внутри state как `eventSink` | presenter (живёт ровно столько, сколько UI) | Compose-эффекты | высокая, но нужен Molecule+Turbine | бизнес-логика зависит от Compose-компилятора; Cash App сам пишет «не уверены, что это правильные trade-off'ы» **[V]** | **см. §6.4 — совместим ровно с одной версией** |
| **Decompose component tree** | «UI is optional and is pluggable from outside; components do not depend on UI» **[V]** | компонент | `coroutineScope(SupervisorJob()+Dispatchers.Main)` + Essenty lifecycle | высокая | Twine ушёл: PR #1171, 88 файлов, +3157/−4370, чистое **−1213** строки **[V]** | 3.3.0 подходит под пины, 3.4.0+ требует Compose 1.8.2 **[V]** |
| **Двухуровневые state holders (Google)** | business-logic holder vs UI-logic holder; «зависеть можно только на holder с равным или меньшим временем жизни» **[V]** | screen-level holder + мелкие plain-классы | через порты/use cases | высокая | канон молчит про общее состояние между sibling-holder'ами (droidcon 2025) **[V]** | **HIGH — уже частично сделано (`RagPanelState` и т.д.)** |

### 1.2 Контракты, которые стоит выписать дословно

- **Decompose:** «UI is optional and is pluggable from outside of components. Components do not depend on UI, the UI depends on components.» + «Never pass parent's ComponentContext to children» → https://arkivanov.github.io/Decompose/component/overview/ **[V]**
- **Circuit:** «A Presenter and a Ui cannot directly access each other. They can only communicate through state and event emissions»; правило «Presenter logic should not emit any Compose UI» держится **предупреждением** компилятора (`@ComposableTarget("presenter")`), поэтому доки рекомендуют `allWarningsAsErrors` → https://slackhq.github.io/circuit/docs/presenter/ **[V]**
- **Google, state holders:** «State holders can depend on other state holders as long as the dependencies have an equal or shorter lifetime»; анти-паттерн выписан кодом — закомментировано `// private val viewModel: MyScreenViewModel`, вместо него `private val someState: StateFlow<SomeState>, private val doSomething: () -> Unit` → https://developer.android.com/topic/architecture/ui-layer/stateholders **[V]**
- **Google, domain layer:** use cases «should not contain mutable data»; выгода — «It avoids large classes by allowing you to split responsibilities»; тормоз — «forces you to add use cases even when they are just simple function calls to the data layer» → https://developer.android.com/topic/architecture/domain-layer **[V]**
- **MVIKotlin:** Executor — «the place for business logic, all asynchronous operations also happen here»; Reducer чист → https://github.com/arkivanov/MVIKotlin/blob/master/docs/store.md **[V]**
- **Apple, Observation — почему гранулярность на уровне свойства, а не объекта.** Первофирменная формулировка выгоды перехода с `ObservableObject` на `@Observable`: «Updating views based on changes to the observable properties that a view's `body` reads instead of any property changes that occur to an observable object, **which can help improve your app's performance**» → https://developer.apple.com/documentation/swiftui/migrating-from-the-observable-object-protocol-to-the-observable-macro **[V]** (прочитано через `developer.apple.com/tutorials/data/…json` — HTML-страница рендерится скриптом и в текст не отдаётся). Это ровно наш аргумент из §5/§6.11, сказанный вендором: *инвалидация по объекту переcчитывает лишнее, инвалидация по свойству — нет.* Kotlin/Compose-эквивалент `@Observable` — это `var x by mutableStateOf(...)`, которое у нас уже стоит; эквивалент `ObservableObject` — один `StateFlow<BigUiState>`.
- **Apple, WWDC26 SwiftUI Group Lab (session 8006):** «SwiftUI doesn't prescribe an architecture — it's designed to be architecture-agnostic, so use whatever pattern fits your app» **[V]**. При этом Apple *предписывает* владение: «Store data as state in the least common ancestor of the views that need the data» и «Limit the scope of state variables by declaring them as private» **[V]**. Ретированный Cocoa-документ («MVC is central to a good design for a Cocoa application») архивирован с пометкой «may not represent best practices» — то есть Apple сознательно перестал называть паттерн **[V]**.
- **ab-download-manager, `BaseComponent.kt`** — весь desktop-контракт state holder'а в 20 строках, проверено дословно **[V]**:
  ```kotlin
  abstract class BaseComponent(componentContext: ComponentContext) : ComponentContext by componentContext {
      val scope = coroutineScope(SupervisorJob() + Dispatchers.Main)
      fun <T> Flow<T>.withResumedLifecycle() = withLifecycle(lifecycle, minActiveState = Lifecycle.State.STARTED)
  }
  ```

### 1.3 Failure modes — что зафиксировано, а что нет

| Паттерн | Кто ушёл и почему | Сила доказательства |
|---|---|---|
| Voyager ScreenModel | **Mihon** (23 130★) PR #3594, merged 2026-07-15, 91 файл, +1503/−1091 **[V]**; **RaccoonForFriendica** (95★) PR #953 + #1067 «remove references to Voyager» **[V]** | сильная (один очень крупный + один маленький адоптер) |
| Decompose | **Twine** (2386★) PR #1171, merged 2025-06-28, −1213 строк нетто **[V]**; причина в PR не указана | средняя (факт есть, мотив нет) |
| Redux | **react-admin v4** — boilerplate, «Changing the store shape at runtime is challenging», «a central store means every action triggers all reducers» **[V]** | сильная |
| MVVM(+C) | **Slack** — «found it wasn't opinionated enough, and we were seeing a lot of inconsistencies in how it was implemented» **[V]**; **Tripadvisor** → TCA («coordinators can launch any other coordinator, creating a web of navigation… nearly impossible to document») **[V]** | сильная |
| MVVM (ViewModel-слой) на SwiftUI | **Ice Cubes** — правило в репозитории: «new features should NOT use ViewModels» **[V]** | сильная |
| eager Components → lazy Services | **JetBrains IntelliJ** — «Plugins using Components don't support dynamic loading» + «Executing code on application startup should be avoided… it slows down startup» **[V]** | сильная |
| per-crate settings → централизованная модель | **Zed** — «no unified, strongly-typed model of all settings», макро-«tangle», и сломанный авто-апдейт: «the app couldn't update itself» **[V]** | сильная |
| Realms shim → QuickJS-в-WASM | **Figma** — «uses the same JavaScript VM for all code both inside and outside the sandbox» **[V]** | сильная |
| **TCA** | **ноль подтверждённых миграций.** Retrospective Рода Шмидта — личная рекомендация, а не запись о переходе команды; истории Lapse и Arc до первоисточника не доведены | **слабая — паттерн недоисследован** |
| **Circuit, Molecule, MVIKotlin, Orbit, FlowRedux, Ballast** | публичных уходов не найдено | **недоисследованы** |
| zustand slice pattern | уходов нет; критика только эргономическая (типизация комбинированных slices) **[V]** | слабая |

### 1.4 Развёрнутые карточки — пять паттернов, которые реально в игре для нас

Остальные разобраны таблицей 1.1; здесь — те, между которыми делается выбор в §6.

---

**A. Двухуровневые state holders (Google) — рекомендуемая база**

- *Контракт.* Два вида holder'а с разным временем жизни. **Business-logic holder** производит состояние экрана из домена/данных, уникален для своего UI, не переиспользуется. **UI-logic holder** — обычный класс, созданный через `remember`, живёт ровно столько, сколько композиция, переиспользуем. Композиция holder'ов — это правило времени жизни, а не вкус: зависеть можно только на holder с равным или меньшим временем жизни; «it wouldn't make sense a screen level state holder depends on another screen level state holder» **[V]**.
- *Владение состоянием.* Приватное поле + read-only наружу (`var x by mutableStateOf(...); private set`). «Never modify the UI state in the UI directly unless the UI itself is the sole source of its data» **[V]**.
- *Побочные эффекты.* Через порты/use cases, запуск — в собственном scope holder'а. Порядок фиксирован: «business logic must always be applied before UI logic» **[V]**.
- *Тестируемость.* Полная и без UI — если holder тестируется только с целым приложением, значит разрез был тематический, а не структурный.
- *Failure mode.* Канон **молчит** ровно про наш случай: где живёт общее состояние экрана между sibling-holder'ами и как они общаются. Это названо на droidcon Berlin 2025 против реальных VM 1376 / 694 / 687 строк **[V]**; лекарство оттуда же — screen-scoped **Mediator** со `StateFlow<ScreenState>`, DI-скоупнутый, с lint-правилом, запрещающим инжектить его куда-либо кроме holder'ов.
- *На чём именно строится в Compose Desktop.* На `remember` + обычных классах; ViewModel не требуется (см. §6.1) — «A ViewModel is just an implementation detail of a state holder with certain responsibilities» **[V]**.
- *Гранулярность наблюдения — подтверждено вторым вендором.* Правило «holder держит набор наблюдаемых свойств, а не один агрегат» — не наша самодеятельность и не единственный полевой отчёт. Apple прошёл этот путь публично: `ObservableObject` инвалидировал подписчиков **целиком** (любое `@Published` дёргало `objectWillChange`), и `@Observable` был введён, чтобы «updating views based on changes to the observable properties that a view's `body` reads instead of any property changes that occur to an observable object» **[V]**. Наши `var … by mutableStateOf(…); private set` — это уже целевое состояние по обоим канонам; §6.11(1) на этом и стоит.
- *Ответ на дырку в каноне — у Microsoft, а не у Google.* Пробел «как sibling-holder'ы общаются, не держа ссылок друг на друга» первофирменно закрыт в MVVM Toolkit: `ObservableRecipient` = `ObservableObject` + `IMessenger`, плюс свойство `IsActive` с парой `OnActivated`/`OnDeactivated`, где «By default, `OnDeactivated` automatically unregisters the current instance from all registered messages… This pattern allows a viewmodel to be enabled/disabled multiple times, while being safe to collect **without the risk of memory leaks**» **[V]**. Два переносимых вывода: (1) горизонтальная связь holder'ов — через шину сообщений или общий нижний источник, но **никогда** прямой ссылкой; (2) у holder'а обязан быть явный момент деактивации, на котором снимаются все подписки — у нас это `dispose()`/отмена scope в `ChatSession`.

---

**B. Объект на беседу (Cline `Controller`/`Task`; Cherry Studio по `topicId`)**

- *Контракт.* Три объекта вместо одного. Host владеет жизненным циклом вью. **Controller** — «single source of truth» для настроек, списка задач, персистентности и раздачи состояния. **Task** владеет **одним** прогоном беседы: запросами к API, вызовами инструментов, approval'ами, сигналом abort, чекпоинтами. UI держит только зеркальную read-only проекцию и шлёт intent'ы назад **[V]**.
- *Владение состоянием.* Кросс-беседное — у Controller; всё, что живёт в пределах беседы, — у Task, включая его собственный `CoroutineScope`.
- *Побочные эффекты.* Внутри Task; отмена — structured concurrency, а не флаг.
- *Тестируемость.* Task конструируется в тесте отдельно от UI; desktop-пример Cline идёт дальше и выносит рантайм в отдельный процесс с протоколом `{command | response | event}` поверх HTTP+WS **[V]**.
- *Failure mode.* **Масса не исчезает, а концентрируется**: у самого Cline `McpHub.ts` = 84 КБ, `message-translator.ts` = 99 КБ. «Единый источник истины» — это god object под другим именем, если Task не владеет прогоном по-настоящему.
- *Почему это наш случай.* Пять режимов памяти, RAG, MCP и учёт токенов — все **побеседные** заботы, которые сейчас живут в процесс-глобальном объекте.

---

**C. MVI-контейнер (Orbit / MVIKotlin) — чистый Kotlin, JVM, годен**

- *Контракт.* Orbit: `intent { }` (может suspend, делает IO) → `reduce { state.copy(...) }` (атомарно) → `postSideEffect(...)`. Наружу два потока: `stateFlow` и `sideEffectFlow`. Хост — **любой** класс: «Android ViewModel is the most common example, but it's not required» **[V]**. MVIKotlin разворачивает то же в пять ролей (Bootstrapper/Executor/Reducer + Intent/Action/Message/State/Label).
- *Владение состоянием.* Контейнер; state и side effects обязаны быть сравнимыми, рекомендуется immutable.
- *Побочные эффекты.* Только в `intent{}`/Executor — Reducer чист по контракту.
- *Тестируемость.* Наивысшая в выборке: `orbit-test`, `ballast-test`, time-travel у MVIKotlin и MVICore.
- *Failure mode.* Церемония на каждое взаимодействие; лёгкое злоупотребление (долгая работа внутри `reduce`). И ключевое предупреждение из первоисточника TCA, применимое ко всему семейству: делиться логикой **через действия** — «an inefficient way to share logic… Sending actions is not as lightweight of an operation as, say, calling a method on a class» **[V]**. Правильный приём — приватные методы, а не рост sealed-класса интентов. Это и есть механизм, которым MVI-holder'ы разбухают.
- *Вердикт для нас.* Годится по стеку, но переписывать 9–14 кластеров в триплеты Intent/Message/State — не тот масштаб работы. Взять **правило** (логика делится методами, не событиями), не библиотеку.

---

**D. Circuit / composable presenter — образец контракта, но не зависимость**

- *Контракт.* `interface Presenter<UiState : CircuitUiState> { @Composable fun present(): UiState }`; наружу ровно два типа — `CircuitUiState` с полем `eventSink: (Event) -> Unit` и подтипы `CircuitUiEvent`. Пары ключуются `Screen`-типом, фабрики регистрируются в `Circuit`-конфиге, `Navigator` приходит в фабрику ассистед-инъекцией.
- *Владение состоянием.* Presenter, время жизни которого равно времени жизни UI — то есть retained-scope проблема, ради которой существует ViewModel, просто не возникает (а на desktop её и нет).
- *Побочные эффекты.* Обычные Compose-эффекты: `LaunchedEffect`, `rememberCoroutineScope`, `rememberRetained`.
- *Декомпозиция встроена.* Presenter'ы — композаблы, поэтому вложены: presenter экрана просто вызывает sub-presenter'ы как функции. Три названных приёма: **Presenter Decomposition** (приватные `@Composable`-функции для наблюдения + не-composable функции для обработки событий), **Composite Presenters** (родитель встраивает полноценные дочерние presenter'ы — для dashboard-экранов), **StateProducer** (переиспользуемые компоненты, производящие состояние, но **не** реализующие `Presenter`) **[V]**.
- *Три названных антипаттерна* — «Giant Presenters», «Boolean Flag Soup», «Event Handler Spaghetti». **У нас присутствуют все три.**
- *Failure mode.* Тесты требуют Compose-рантайма (`presenterTestOf()` «leverages Molecule and Turbine»); правило «presenter не эмитит UI» держится лишь warning'ом; эргономичный DI — кодогенерация `@CircuitInject`, чего koin-core не даёт. Плюс сигнал долговечности: крупнейший внешний адоптер **Tivi заархивирован 2024-11-12** **[V]**.

---

**E. Decompose + Essenty — единственный desktop-корректный lifecycle-скоупинг**

- *Контракт.* Компонент — обычный класс, берущий `ComponentContext` в конструктор и делегирующий ему (`class Foo(ctx: ComponentContext) : ComponentContext by ctx`). Context агрегирует `LifecycleOwner` + `StateKeeperOwner` + `InstanceKeeperOwner` + `BackHandlerOwner` (всё из Essenty). Вниз — `Value<T>` (в Compose через `subscribeAsState()`), вверх — вызовы методов интерфейса компонента. Навигация — это состояние: `childStack(...)` даёт `Value<ChildStack<Config, Child>>`, конфигурации обязаны быть `@Serializable` и уникальны по equality.
- *Владение состоянием.* Компонент; дети создаются **родителем** через child factory, поэтому DI — конструкторный, локатор в UI не нужен.
- *Побочные эффекты.* Скоупятся Essenty: `coroutineScope(SupervisorJob() + Dispatchers.Main)` отменяется на destroy, `Flow.withLifecycle(lifecycle, minActiveState = STARTED)` гейтит сбор.
- *Failure mode.* Вводит **параллельный** observable-тип (`Value`/`MutableValue`) вместо StateFlow; требование `@Serializable` конфигураций покупает выживание при process death, которого на desktop не бывает; уход Twine стоил −1213 строк нетто.
- *Что берём без библиотеки.* Форму `BaseComponent` (см. §1.2) и правило `childContext`. Essenty публикуется под `jvm` отдельно, без Compose и без Android — если понадобится lifecycle-скоупинг, берётся именно он, а не весь Decompose.

---

## 2. Модели многомодульности и механизмы принуждения

### 2.1 Механизмы принуждения, ранжированные по силе

| Ранг | Механизм | Что именно нельзя нарушить | Примеры (первоисточник) | Можно ли заглушить |
|---|---|---|---|---|
| **1** | **Компилятор / analysis-phase** | несуществующее ребро графа; `internal` за границей source set; `implementation` не попадает в compile classpath потребителя; `explicitApi()` | Gradle java-library **[V]**; Kotlin: «модуль — это Gradle source set» **[V]**; Bazel: default `//visibility:private`, «A target will fail to build during the analysis phase» **[V]**; Swift SE-0386 `package` **[V]**; Rust `pub`-opt-in | **нет** |
| **2** | **Build gate (`check`)** | правило падает сборкой | App Platform `checkModuleStructureDependencies`, «no other module but the final application module is allowed to depend on :impl modules» **[V]**; `modules-graph-assert` (`maxHeight`, `-X>`) **[V]**; dependency-analysis `severity("fail")` **[V]**; VS Code `valid-layers-check` — 6 независимых tsc-проектов, «A file that reaches out to an API of another layer fails to compile here» **[V]** | только правкой конфига (видно в diff) |
| **3** | **Test gate** | JUnit-тест архитектуры | **Konsist** (1716★, но последний релиз **0.17.3 от 2024-12-08** **[V]**): `Layer("Domain","…domain..").dependsOnNothing()`, и главное — «by integrating Konsist into a single module (e.g. app module), Konsist can still access the entire project» **[V]**; ArchUnit — байткод с test classpath, отсюда его слабость в module-as-layer **[V]** | удалением/`@Ignore` теста |
| **4** | **Lint** | сообщение линтера | VS Code `local/code-import-patterns` **[V]**; Nx `depConstraints` **[V]**; Turborepo `boundaries` (experimental) **[V]**; кастомные detekt-правила **[V]** | построчным `disable` |
| **5** | **Convention** | ничего; делает правильное дешёвым | Gradle: cross-project configuration «can prevent optimizations like configuration-on-demand» **[V]**; `build-logic` как included build **[V]** | — |
| **6** | **Документация / code review** | ничего | комментарий в `settings.gradle.kts` целевого проекта | — |

**Важная поправка по ранг-4.** Кейс microsoft/vscode#227752 в популярном пересказе звучит как «lint-забор протекает: 18 файлов с `eslint-disable`». Проверено: issue **закрыт как `completed` 2025-03-24**, все 18 чекбоксов отмечены, milestone «March 2025», labels `important`+`debt` **[V]**. То есть артефакт доказывает обратное: lint + трекаемый issue + владелец = долг гасится. Вывод «только компиляторная граница работает» остаётся верным по другим основаниям (ранг 1 в принципе незаглушаем), но **этой ссылкой его подпирать нельзя**.

### 2.2 Таксономии модулей

| Таксономия | Состав | Правила зависимостей | Принуждение | Цена | Источник |
|---|---|---|---|---|---|
| **Слоевой стек** (текущий у нас) | `:core:domain → :core:data → :app` + driving-адаптеры | строго вверх, циклы запрещает Gradle | ранг 1 | при росте слоя парализуется параллелизм; правка `:core:data` инвалидирует `:app` целиком | Gradle java-library **[V]** |
| **NiA: `:core:*` + `:feature:*:{api,impl}`** | 34 Gradle-проекта на приложение с двумя экранами; 16 `:core:*` (`:core:notifications` — **один**, не вложенный) | feature api не зависит от чужого api/impl; impl зависит только на чужой **api**; core не зависит на feature/app | convention plugins `AndroidFeatureApiConventionPlugin` / `…ImplConventionPlugin` **[V]** | удвоение числа модулей; сами мейнтейнеры признают риск «overmodularizing a relatively small app» **[V]** | https://github.com/android/nowinandroid **[V]** |
| **api/impl (Square → App Platform)** | `:public / :impl / :internal / :testing / :robots / :app` | «no other module but the final application module is allowed to depend on `:impl`» | **build gate** `checkModuleStructureDependencies` в `:check` | оправдание — параллельная компиляция: «build times get longer roughly linear to the size of the module, because individual build steps such as Kotlin compilation can't be parallelized» **[V]** | https://vrallev.github.io/app-platform/module-structure/ **[V]** (репозиторий теперь **vRallev/app-platform**, 321★) |
| **API/DI (без лишнего модуля)** | интерфейсы в общем нижнем модуле, реализации в impl, конкретика — только в composition root | feature не зависит на feature; app знает конкретные типы | ранг 1 + DI-граф | вся конкретика собирается в одном файле `:app` | Android patterns **[V]**; Gherschon: API/IMPL «quickly becomes a mess when the number of modules grows» **[V]** |
| **Гибрид Tivi** | `ui/<feature>` ×15/16 + `core/<capability>` ×8 + один `domain` + один `data` + per-platform app-модули (**есть `desktop-app`**) | `:ui:discover` объявляет `implementation(projects.domain)` и **не имеет ни одной `:data:*` зависимости** — проверено дословно **[V]** | ранг 1 + typesafe project accessors | 15 модулей UI; репозиторий **ARCHIVED 2024-11-12** | https://github.com/chrisbanes/tivi/blob/main/ui/discover/build.gradle.kts **[V]** |
| **VS Code: слой × среда выполнения** | `base/platform/editor/workbench/code` × `common/browser/node/electron-*` | `common:[]`, `node:[common]`, `electron-browser:[common,browser]` … + «there cannot be any dependency from outside `vs/workbench/contrib` into `vs/workbench/contrib`» | ранг 2 (шесть tsconfig-проектов) + ранг 4 (54 файла в `.eslint-plugin-local`) | ось «среда выполнения» бессмысленна для одно-JVM-процесса | https://github.com/microsoft/vscode/wiki/Source-Code-Organization **[V]** |
| **Crate-per-subsystem (Zed)** | **250 workspace members** (243 `crates/`, 4 `extensions/`, 3 `tooling/`) | путь-зависимости внутри workspace; версии пиннятся один раз в `[workspace.dependencies]` | ранг 1 бесплатно (crate = compile unit = visibility unit) | компиляция: контрибьюторы пишут, что «about year ago 16GB RAM was enough… now even 32GB is not enough» **[V]** | https://raw.githubusercontent.com/zed-industries/zed/main/Cargo.toml **[V]** |
| **Локальные SPM-пакеты (Apple)** | Food Truck = **2** юнита (`FoodTruckKit` с `dependencies: []` + `App/`); Backyard Birds = **3** (Data ← ArtworkLibrary ← UI) | строго ациклично, объявлено в манифестах | ранг 1 | оба сэмпла устарели (2023-08-18 / 2023-12-11) | **[V]** |
| **Ice Cubes** | 13 локальных пакетов: Models → NetworkClient → Env → DesignSystem → фичи | **нет** api/impl, **разрешены** feature→feature (Timeline → StatusKit) | ранг 1 + **политика потоков в build-файле**: UI-пакеты несут `.defaultIsolation(MainActor.self)`, data-пакеты — нет **[V]** | связность фич возвращается | https://github.com/Dimillian/IceCubesApp **[V]** |

### 2.3 Контрпримеры, которые стоит держать перед глазами

- **Gitnuro** (2745★, desktop-only Compose): модули называются `:app :ui :domain :data :common`, но `domain/build.gradle.kts` объявляет `implementation(compose.desktop.currentOs)` + Ktor + JGit + Dagger **[V]**. Имена слоёв без компиляторной границы — это README, а не архитектура.
- **macai** (915★, активный multi-provider macOS AI-чат): **ноль** локальных пакетов, один таргет, только папки **[V]**. Модуляризация не является предусловием для отгрузки продукта.
- **Askimo** (367★, Kotlin + Compose Desktop + 5 Gradle-модулей + собственный detekt-ruleset): `Main.kt` = **2347** строк, `ChatViewModel.kt` = **1476** **[V]**. Это наш контроль: **числа почти совпадают с нашими** (2310 / 1617). Разрезание на модули ограничивает *направление* зависимостей и никогда — *размер* узла.
- **microsoft/vscode-copilot-chat** (9976★): фичу вынесли в отдельный репозиторий, а 2026-05-20 **заархивировали и вернули в монорепо** **[V]**. Дробить по *скорости изменения*, а не по имени фичи.
- **Uber**: 20+ отдельных репозиториев в 2013-14 → dependency hell («days of engineering work to resolve breaking changes»), сборка 15+ мин; в 2016 консолидация в монорепо → <5 мин fresh, <1 мин incremental **[V]**.

### 2.4 Как выглядит принуждение в коде — четыре формы, которые можно скопировать

**(1) Ранг 2, Gradle-граф — `modules-graph-assert`.** Правила — регексы по путям модулей, задача `assertModuleGraph` вешается на `check`. **Ниже — конфиг, который уже стоит в корневом `build.gradle.kts` целевого проекта**, а не пожелание; копировать надо именно его:

```kotlin
moduleGraphAssert {
    // :tools:cli -> :tools:mcp -> :core:data -> :core:domain — самая длинная цепочка.
    maxHeight = 4
    // Форма allowed = default-deny: разрешено РОВНО перечисленное, любое иное ребро роняет задачу.
    allowed = arrayOf(
        ":app -> :core:.*",
        ":app -> :tools:mcp",        // ТОЛЬКО runtimeOnly: подпроцесс MCP по java.class.path
        ":core:data -> :core:domain",
        ":tools:.* -> :core:.*",
        ":tools:cli -> :tools:mcp",
    )
    // restricted дублирует allowed по смыслу — но даёт внятное сообщение об ошибке.
    restricted = arrayOf(
        ":core:.* -X> :app",
        ":core:.* -X> :tools:.*",
        ":core:domain -X> :core:data",
    )
}
```
**Две вещи, в которых легко ошибиться, и обе стоят красной сборки.**
*Первая — `maxHeight`.* Он не для красоты: граф, выродившийся в цепочку, даёт полную стоимость модулей и ноль параллелизма. Но число должно соответствовать **фактически самой длинной цепочке**; у нас это `:tools:cli → :tools:mcp → :core:data → :core:domain`, то есть **4**. Поставить 3 — значит уронить `assertModuleGraph` на первом же запуске.
*Вторая — `allowed` против `restricted`.* Это не синонимы и не вопрос вкуса. `allowed` — **allowlist**: неперечисленное ребро запрещено, включая то, которое никто не догадался предвидеть. `restricted` — **blocklist**: запрещено только названное, всё остальное проходит молча. По ранжированию §2.1 первая форма — это единственный доступный в Gradle аналог bazel'евского default-deny, поэтому **отказ от `allowed` в пользу одного `restricted` — это понижение ранга принуждения**, а не упрощение. Побочная выгода `allowed`: намеренное исключение `:app -> :tools:mcp` (runtimeOnly-подпроцесс) стоит в конфиге явной строкой с комментарием — то есть санкционировано, а не забыто.
Известная оговорка — плагин может не увидеть модули при `configuration-on-demand` (нужен `--no-configure-on-demand`) **[V]**. Плагин: **639★**, релиз 2.9.1 от 2026-04-12, push 2026-08-24 **[V]**.

**(2) Ранг 2, гигиена зависимостей — `dependency-analysis-gradle-plugin`.** Ловит именно ту болезнь, которая делает многомодульность медленнее монолита:

```kotlin
dependencyAnalysis { issues { all { onAny { severity("fail") } } } }
// ./gradlew buildHealth   — агрегированный отчёт
// ./gradlew :app:projectHealth   — по модулю
// ./gradlew reason --id …        — почему именно этот совет
```
Отчёт различает неиспользованные зависимости, транзитивные-но-используемые и **`api` там, где хватило бы `implementation`**. Touchlab измерил цену обратного на 100+ модульном KMP: «api деps appear on compile classpaths. This can have significant ripple of recompilations in a multi-module setup» **[V]**.

**Version floor, который надо записать наравне с floor'ами Circuit/Decompose/Molecule.** DAGP 3.0.0 — «[Breaking] minimum supported version of Gradle is now 8.11» **[V]** (CHANGELOG, стр. 223). Целевой проект на Gradle **8.13** → проходит. Плагин: 2178★, v3.19.1 от 2026-08-26 — самый живой инструмент в выборке **[V]**.
**И честная оценка первого запуска.** `severity("fail")` глобально на кодовой базе, где `buildHealth` **никогда не запускался**, — это не «0.5 дня, низкий риск». В целевом проекте есть минимум три формы, которые DAGP штатно помечает на первом прогоне: (а) file-collection зависимости `files(rootProject.file("libs/pdfbox-2.0.31.jar"), …)` в `:core:data` и `:tools:cli` — у них нет координат, и советы по ним бесполезны; (б) намеренные `api(libs.coroutines.core)` в `:core:domain` и `api(project(":core:domain"))` в `:core:data` — DAGP должен *увидеть* ABI-обоснование, иначе предложит понизить до `implementation` и сломает транзитивную видимость домена в `:app`; (в) агрегированные Compose-аксессоры (`compose.desktop.currentOs`, `compose.material3`) в `:app`. Реалистичный первый прогон — красная сборка и день триажа, поэтому порядок обязателен: **сначала report-only `buildHealth`, разбор, только потом `severity("fail")`** (см. шаг 0в в §6.4).

**(3) Ранг 3, пакетные слои — Konsist, обычный JUnit-тест:**

```kotlin
@Test fun `layers respect direction`() = Konsist.scopeFromProject().assertArchitecture {
    val domain = Layer("Domain", "com.example.adventdesktop.domain..")
    val data   = Layer("Data",   "com.example.adventdesktop.data..")
    val ui     = Layer("UI",     "com.example.adventdesktop.ui..")
    domain.dependsOnNothing()
    data.dependsOn(domain)
    ui.dependsOn(domain, data)
}
```
Решающее свойство для нашей раскладки: scope строится **из файлов на диске**, а не из compile classpath, поэтому один тест в одном модуле полицейски покрывает весь проект **[V]**. ArchUnit так не умеет — он импортирует классы с test classpath, и тест в `:app` физически не видит модуль, который зависит от `:app`.

**Оговорка по живости — та же мерка, которой отвергнуты PreCompose и Voyager.** Konsist: **1716★**, но последний релиз — **v0.17.3 от 2024-12-08**, и на default-ветке с тех пор ровно **один** коммит («Remove Dokka», 2026-01-08) **[V]**. То есть релизной активности нет ~20 месяцев, и 0.17.3 старше Kotlin 2.1.0 целиком. Если мы отвергаем PreCompose за «без коммитов с 2025-02-20» и Voyager за самообъявленную заморозку, то Konsist по этой шкале — **не лучше**, и это надо назвать вслух, а не спрятать в «совместимость не проверена». Практическое следствие: Konsist можно брать как *полезный* гейт, но **нельзя** делать его несущей опорой для нескольких последующих шагов. Отсюда — перенос шага Konsist за распил и явный запасной путь в §6.4.

**(4) Ранг 2, «модуль не может нарушить структуру» — App Platform.** Плагин сам валидирует именование, сам добавляет `:impl → :public`, и регистрирует `checkModuleStructureDependencies` в `:check`, с явными аварийными выходами (`allowLibraryImplToImplDependencies`, `enableDependencyCheck false`) **[V]**. Это ближайший в Kotlin-мире аналог bazel'евского default-deny, и его идея переносится в наш `build-logic` даже без самого фреймворка.

**(5) Ранг 4 с честным приёмом — централизовать протечки, а не подавления.** VS Code собирает все неизбежные обратные ссылки в **один** файл `terminalContribExports.ts` с комментарием: «HACK: Export some commands from `terminalContrib/`… These are soft layer breakers… explicitly defined here to avoid an eslint line override» **[V]**. Один растущий barrel виден в ревью и считается по строкам; N невидимых `disable` — нет. Chromium формализует это ещё строже: маркер `!` = «терпим сегодня, предупреждаем на новых использованиях», а `new_usages_require_review = True` требует **+1 от OWNER** депенденси-директории, чтобы расширить правило **[V]**.

---

## 3. Матрица приложений

| Приложение | Вендор | Стек | Слои | Управление состоянием | Стратегия модулей | Принуждение | Урок для нас |
|---|---|---|---|---|---|---|---|
| **Claude Desktop** | Anthropic | Electron + собственный Node.js runtime **[И]**; Windows = MSIX x64/arm64 **[И]** — источник ни по тому, ни по другому не открывался, см. §8.5(2) | web-in-shell; агент-луп нативный на устройстве, исполнение кода — в VM (Apple Virtualization.framework / Hyper-V) **[V]** | **[И] неизвестно** — рендерер = тот же web-бандл, что claude.ai; вендор объясняет: «a nice way to share code so we're guaranteed that features across web and desktop have the same look and feel» **[V]** | `.mcpb` (бывш. `.dxt`) — zip с `manifest.json`, `server.type node\|python\|binary`, `user_config` с флагом `"sensitive": true` → OS keychain **[И]** (схема без ссылки, §8.5(2)) | Group Policy: `HKLM\SOFTWARE\Policies\Claude` перебивает `HKCU` и in-app allowlist; ключи `isDesktopExtensionEnabled`, `isLocalDevMcpEnabled`, `allowedWorkspaceFolders`, `secureVmFeaturesEnabled` **[V]** | **Копировать tool plane** (конфиг-файл → подпроцесс → per-server stderr-лог → изоляция отказов → approval на действие). **Не копировать state story**: у них есть чужой деплой, у нас нет |
| **Claude Cowork (внутри Desktop)** | Anthropic | — | **Единственный опубликованный «мы передумали»**: изначально весь агент-луп жил в гостевой VM, «any failure during VM startup made Cowork unusable», луп вынесли на хост, в VM оставили только исполнение кода; локальные MCP-серверы тоже вынесли **[И]** — цитата без ссылки, §8.5(3) | — | — | mount-режимы `read-only / read-write / read-write-no-delete`, MITM-прокси внутри VM **[V]** | **Резать state holder по домену отказа**: чат обязан пережить смерть MCP-подпроцесса, недоступный Ollama, упавший embedding. Побочно: в июле 2026 зафиксирован побег из песочницы («SharedRoot», CVE-2026-46331, ~500 тыс. пользователей macOS), после чего облачные сессии стали дефолтом **[V]** |
| **ChatGPT Desktop** | OpenAI | macOS — нативный **[И]** (косвенно: инцидент 2024 — приложение вне App Store, не в песочнице; «косвенно» и [V] несовместимы, метка исправлена); Windows — Electron **[И]**, единственный аргумент — размер установки ~260 МБ, и он взят из **пересказа**, а не из вендорского артефакта (см. §7.14) | тонкая нативная оболочка + OS-интеграция; тулинг **не в клиенте** | **[И] неизвестно** | Remote-only MCP: «Supported MCP protocols: SSE and streaming HTTP» **[V]**; локальный доступ — отдельный механизм (Accessibility API) | — | Approval-скоуп, на который сошёлся живой продукт: «remember the approve or deny choice for a given tool **for a conversation**», новые беседы спрашивают заново **[V]**. И предупреждение: до v1.2024.171 беседы лежали **открытым текстом** в `~/Library/Application Support/com.openai.chat/conversations-{uuid}/` **[V]** — ровно тот дефект, что у нас в `~/.adventai/config.json` с ключами |
| **Kimi Work** | Moonshot | Electron + **три** зашитых рантайма: CPython 3.12, Node v24.15.0, uv **[И]** (единственный источник — неназванный teardown-репозиторий на 4★; §8.5(1)) | 4 дерева: main asar (UI) · gateway (`openclaw/clawhub`) · agent runtime (`@kimi/daimon`) · browser bridge | **[И] неизвестно** | физическая изоляция: у каждого дерева свои native-модули (`node-pty`, `sqlite-vec`, `better-sqlite3`, `koffi`) | — | **Локальный gateway между UI и агентом** — это ровно наш `:tools:service`. `sqlite-vec` + `better-sqlite3` намекают на локальный embedded-вектор-стор **[И]** — альтернатива нашему файловому `KnowledgeIndex` |
| **Cursor** | Anysphere | Electron, форк Code-OSS | наследует двухосевую слоистость VS Code + собственный AI-слой | **[И] неизвестно**; shadow workspace — «a hidden Electron window», IPC через gRPC+buf, авто-убийство через 15 мин простоя **[V]** | `.cursor/mcp.json` (проект) поверх `~/.cursor/mcp.json` (глобально); правила как файлы `.cursor/rules/*.mdc` в 4 режимах + вложенный `AGENTS.md` **[V]** | «Cursor asks for approval before using MCP tools by default»; «Cursor isolates server failures to prevent one server from affecting others» **[V]** | **Priompt** — приоритетная сборка промпта (см. §6.9). История чата — локально в SQLite (`cursorDiskKV`, ключи `composerData:<id>` / `bubbleId:<conv>:<msg>`) **[И], третьи лица**. Router с режимами Cost/Balance/Intelligence — **только Teams/Enterprise** **[V]**, не универсальная абстракция |
| **Windsurf / Cascade** | Codeium → Cognition | форк VS Code | Cascade помечен как **legacy agent**, заменяется Devin Local **[V]** | **[И] неизвестно** | один глобальный `~/.codeium/windsurf/mcp_config.json` | «Cascade has a limit of **100 total tools** at any given time»; allowlist fail-closed: «once you allowlist even a single MCP server, all non-allowlisted servers will be blocked» **[V]** | **Ограничивать список инструментов** и **держать agent loop за доменным портом** — пользователи Windsurf оказались привязаны к устаревшему пути конфигурации |
| **Zed** | Zed Industries | Rust + GPUI (89 255★) | **нет** framework-free домена: `language_model` и `agent` зависят от `gpui`; более того, `crates/agent/Cargo.toml` объявляет `ui.workspace = true` в `[dependencies]` **[V]** — логика тянет дизайн-систему | App владеет всем состоянием; `Entity<T>` — инертный типизированный хэндл + refcount; доступ только через контекст; `notify()/emit()` кладут эффекты в очередь, `flush_effects()` их сливает — реентрантность спроектирована прочь **[V]** | **250 workspace members**; `language_model_core` (типы) ← `language_model` (трейт+реестр, **ноль** провайдерских крейтов) ← `language_models` (реализации) + по крейту на вендора | ранг 1 (Cargo); `xtask package_conformity` — **только advisory** (`eprintln!` + `Ok(())`) **[V]** | **Копировать таксономию провайдеров** и правило `X` / `X_ui`. Но именно на `agent → ui` видно, что «одностороннее» правило у них соблюдено не полностью |
| **VS Code** | Microsoft | Electron + TypeScript (189 619★) | 6 слоёв × 6 сред; contrib не импортируется извне | сервисы через `createDecorator` (622 файла) + `registerSingleton` (423) **[V]** | 107 директорий `platform/`, 100 `contrib/`, из терминала выделено 25 фич в `terminalContrib/` | ранг 2 + ранг 4; `check-cyclic-dependencies`; `monaco-compile-check` | **Слоистость не спасает от god object**: `chatInputPart.ts` = **5231** строка и ~40 инжектируемых сервисов **[V]**. И честный приём: все неизбежные протечки собраны в один файл `terminalContribExports.ts` с комментарием «HACK… soft layer breakers… explicitly defined here to avoid an eslint line override» **[V]** |
| **IntelliJ Platform** | JetBrains | JVM (Java+Kotlin), Swing/EDT (20 488★) | **2246** JPS-модулей (включая поддерево `fleet.*`) **[V]** | scope-as-lifetime: application / project / module; **«Using constructor injection of dependency services is deprecated (and not supported in Light Services) for performance reasons»** **[V]**, сервис берут в точке использования и не кэшируют в поле | Plugin Model v2: content-модули с `loading="optional"` | ранг 1 внутри репо; `@ApiStatus.Internal` + отдельный `intellij-plugin-verifier` (209★) на границе плагинов; для split-debugger — только рантайм-лог с префиксом `[Split debugger]` **[V]** | **Scope-as-lifetime вместо map'ов по id.** Про threading: «Writing data is only allowed on EDT», но там же «There is an in-progress effort to allow writing data from any thread», а `SlowOperations`-ассерты включены **только в EAP/dev**-сборках **[V]** — контракт слабее, чем кажется |
| **Ollama desktop** | Ollama | **Go**, хостит OS-webview (179 489★) | всё состояние — за локальным HTTP-сервером, который тот же, что у CLI | UI не владеет ничем | Go-пакеты в `app/`, per-OS файлы | **типы TS генерируются из Go-структур** (`typescriptify-golang-structs`) — дрейф контракта невозможен по построению **[V]** | Если локальный сервис уже владеет состоянием — UI не должен держать его копию. Наш аналог генерации: `:core:domain` буквально общий для `:app`, `:tools:cli`, `:tools:service` |
| **JetBrains Toolbox** | JetBrains | 100% Kotlin + Compose Desktop | **не раскрыто** | **не раскрыто** | не раскрыто | — | Только два цитируемых факта: idle-RAM упала с «at least 200 MiB», и Material-компоненты пришлось заменить на desktop-ориентированные **[V]**. **Нельзя** цитировать как доказательство какой-либо слоистости |
| **Jan** | Menlo Research | Tauri v2 + Rust (44 190★) | web-app → `@janhq/core` + extensions (**всё в webview**) → Tauri IPC → Rust core | 7 маленьких zustand-store + ~40 hook-store; `ServiceHub` = ports/adapters на TS: `types.ts` (интерфейс) + `default.ts` / `tauri.ts` / `mobile.ts` **[V]** | yarn workspaces; extension = пакет | границы IPC (Rust/JS) | Ports&adapters работает — и **не спасает**: `containers/ChatInput.tsx` = 2650 строк, `custom-chat-transport.ts` = 1713 **[V]**. Плюс: при переезде на Tauri **сознательно отказались** от Node Extension Host, разменяв изоляцию процесса на портируемость **[V]** |
| **Cherry Studio** | CherryHQ | Electron + React (51 092★) | **лучший найденный артефакт**: две ортогональные оси, 4 слоя строго вниз, запрещены как категории `shared→feature` и `feature→feature` **[V]** | **нет глобального UI-store**: durable — через `useQuery`/DataApi (SQLite), настройки — `usePreference`, эфемерное — трёхуровневый `useCache`; Redux+Dexie удалялись помодульно (PR #13871, #16415, #13340, #13295) **[V]** | `src/{main,preload,renderer,shared}` + `packages/*`; `services/<topic>/` как ступень перед фичей | eslint-зоны, **сгенерированные из файловой системы** (`readdirSync`), с раздельными severity: layer-рёбра `error`, `page→page` пока `warn` за env-флагом **[V]** | **Шаблон целиком**: (а) written layering doc с запретами-категориями, (б) авто-зоны + стадийные severity, (в) таблица «target vs current», позволяющая писать правила **до** миграции, (г) capability-дескрипторы, (д) **письменный post-mortem** переноса стриминга из UI |
| **Lobe Chat** (репо переименован в **lobehub/lobehub**) | LobeHub | Next.js + Electron (82 018★) | routes → features → store → services → packages | zustand slice pattern на пределе: `src/store/chat` = **13 slices / 124 файла**; порог зафиксирован в доках — «30+ states → modular cohesion using slices» **[V]** | **93 пакета**, `model-runtime` с **85** директориями провайдеров + `runtimeMap.ts` | pnpm + eslint-зоны + knip | Slicing работает **и недостаточен**: крупнейшие файлы репозитория лежат внутри slices — `apps/server/src/services/aiAgent/index.ts` = **5819 строк / 247 КБ** (пересчитано; в первой редакции стояло «267 КБ» — завышение, исправлено) **[V]**. Плюс issue #10228 «Performance is Awful» — 93 пакета не купили отзывчивость **[V]** |
| **Continue** | Continue Dev | TS-монорепо (35 643★) | host-agnostic `core/` ↔ `protocol` ↔ `gui` ↔ `extensions/{vscode,intellij}` | **reducers-only**: `sessionSlice.ts` = 1097 строк чистых редьюсеров + **19** отдельных thunk-файлов **[V]** | `core/llm/llms` = 79 файлов, по файлу на провайдера + письменный 4-шаговый рецепт | типы протокола = контракт | **Прямой перенос**: state holder держит состояние, а `sendMessage/regenerate/ragQuery` — отдельные тестируемые use cases. Их же анти-паттерн: метаданные модели дублируются в GUI-конфиги |
| **Cline** | Cline Bot | TS, VS Code + CLI + Hub (66 885★) | `WebviewProvider → Controller (single source of truth) → Task (одна беседа)` **[V]** | webview держит только зеркальную проекцию | `apps/*` + `sdk/packages/*`; desktop-пример = агент в **отдельном процессе**, UI подписан на события по HTTP+WS | protobuf между webview и host | **Объект на беседу с собственным scope** — прямой рецепт для `ChatState` |
| **LibreChat** | danny-avila | Node/TS + React (42 480★) | опубликованная таблица зависимостей workspace'ов + strangler-правило «All new backend code must be TypeScript in /packages/api» **[V]** | ~33 крошечных atom-модуля, `families.ts` = per-conversation `atomFamily` + react-query для серверного состояния | npm workspaces | typecheck | **Ключевать состояние по id беседы** — самое дешёвое лекарство от god object. **Оговорка:** эталон работает на **Recoil, который заархивирован** (facebookexperimental/Recoil, last push 2025-01-01) **[V]** |
| **Open WebUI** | Open WebUI | SvelteKit + FastAPI (149 996★) | нет | ~50 глобальных writable в `stores/index.ts` (388 строк) | **ноль** пакетов, нет architecture doc | нет | `Chat.svelte` = **4663** строки, `middleware.py` = **6335** **[V]**. Звёзды — не архитектурное доказательство. Плюс: изолированный out-of-process плагин-фреймворк проиграл — в README `open-webui/pipelines` буквально «**DO NOT USE PIPELINES!**», репо не двигается с 2025-08-18 **[V]** |
| **AnythingLLM** | Mintplex Labs | Node + React (65 234★) | server / collector (отдельный процесс) / frontend | — | 39 директорий провайдеров | нет | **Анти-паттерн реестра**: 39 провайдеров разводятся **switch'ами** в одном файле на 762 строки (`getLLMProvider` стр. 136, `getLLMProviderClass` стр. 363) — и там их **как минимум шесть** **[V]** |
| **Chatbox** | chatboxai | Electron + React (41 575★) | `src/{main,preload,renderer,shared}` | **jotai + zustand + swr + react-query одновременно** | по пакету `@ai-sdk/*` на провайдера | — | Позитив: исполнение инструментов в main-процессе через `@anthropic-ai/sandbox-runtime` с deny-read/deny-write списками. Негатив: четыре библиотеки состояния — тот же god object, вид сбоку |
| **Enchanted** | A. Malinauskas | SwiftUI + SwiftData (5999★) | Stores / Services / Models | **4** `@Observable`-store вместо одного | один таргет | — | **Главный эмпирический факт лензы** (см. §4.3): токены буферизуются и сливаются троттлером 0.1 с, потому что «updating UI for each stream message sometimes freezes the UI» **[V]** |
| **Ice Cubes** | T. Ricouard | SwiftUI, Swift 6 (7048★) | Models → NetworkClient → Env → DesignSystem → фичи | MV как политика: «new features should NOT use ViewModels»; экранное состояние — локальный enum `ViewState` **[V]** | 13 пакетов, у каждого свои тесты | ранг 1 + **политика изоляции потоков в манифесте** | Копировать: отдельный модуль `Env` (app-wide state + DI) и отдельный `DesignSystem`; правило «состояние экрана — один enum, а не три булевых поля» |
| **Askimo** | askimo-ai | **Kotlin + Compose Desktop + Gradle** (367★) | cli / shared / desktop / desktop-shared / detekt-rules | один `ChatViewModel` | 5 модулей + свой detekt-ruleset | ранг 1 + ранг 4 (правила про Compose-корректность, **не** про границы) | **Контрольная группа**: `Main.kt` 2347, `ChatViewModel` 1476 — наши числа. Наш диагноз не в модулях |
| **DeepSeek** | DeepSeek AI | нет desktop-клиента; вместо него **DeepSeek Harness (`dsh`)** — Node-рантайм, поднимающий локальное web-приложение на `127.0.0.1:3080` **[V]** | «Everything is a Plugin» (на Cordis): адаптер модели, реестр инструментов и **сам agent loop** — заменяемые плагины **[V]** | — | MIT, 197 398★, создан 2026-08-13 **[V]** | — | Крупнейшая ставка отрасли на «agent loop как заменяемый компонент за портом». Наш `:core:domain` это позволяет, а `ChatState` — нет |
| **Microsoft Copilot / Windows MCP** | Microsoft | приложение — WebView2-PWA с приватной копией Edge **[И]** (teardown'ы третьих лиц, при этом MS называет приложение «native») | — | — | — | **на уровне ОС**: «All MCP client-server interactions are routed through a trusted Windows proxy»; «Users must explicitly approve each client-tool pair, with support for per-resource granularity»; «Servers must declare privileges they require» **[V]** | Взять **словарь**: у каждого MCP-сервера/скилла — объявленный набор capability, approval по паре (фича, инструмент) |
| **Perplexity Desktop** | Perplexity | **не установлено** — официальный help-center отдаёт HTTP 403, первичного артефакта нет | — | — | — | — | Единственный вывод — дистрибутивный: публикация через store принуждает объявлять нужные папки и возможности. Всё остальное о Perplexity в этом отчёте отсутствует намеренно |
| **Gemini (macOS)** | Google | Google называет «native macOS experience», архитектура **не публикуется**; teardown'а нет | — | — | — | — | Только то, что глобальный hotkey-overlay (Option+Space) стал нормой жанра. Как архитектурное доказательство не используется |
| **Grok** | xAI | первофирменного desktop-клиента не найдено **[И]** (все «Grok desktop» на GitHub — сторонние обёртки) | — | — | — | — | Поле тоньше, чем кажется по маркетингу |
| **LM Studio** | Element Labs | Electron + Node v22.21.1 для плагинов | — | — | SDK `lmstudio-js` (1761★) открыт | манифест плагина: `runner`, `sandbox: {fileSystem: 'restricted'\|'none', network: {hosts}}` — **и у каждого гранта обязательное поле `reason`** **[V]** | Отличная форма контракта. **Существенная оговорка**: в том же файле схема валидации отвергает манифест словами «Sandbox is only supported for deno runners» — node-плагин **вообще не может быть засэндбоксен** **[V]** |
| **Msty** | Cloudstack | Electron + переименованный бинарь Ollama как sidecar **[И]** | — | — | — | — | Только упаковочный урок: вендорить inference-бинарь как управляемый sidecar |
| **WinUI 3 + MVVM Toolkit** (первофирменный десктоп-канон Microsoft; не AI-клиент, взят как вендорский эталон MVVM по §3.B брифа) | Microsoft | .NET + XAML | классический MVVM: View ← binding ← ViewModel ← Model | `ObservableObject` (реализует `INotifyPropertyChanged`) — **инвалидация по свойству**, а не по объекту; **`ObservableRecipient`** = то же + `IMessenger` для связи VM↔VM без прямых ссылок **[V]** | сборки/проекты .NET; MVVM Toolkit — отдельный NuGet-пакет, не зависящий от UI-фреймворка (WinUI/WPF/MAUI) | компилятор (сборка = граница видимости) | **Закрывает ровно ту дырку, про которую молчит канон Google** (§1.4A): sibling-holder'ы общаются **сообщениями**, а не ссылками. И вторая половина рецепта — явная деактивация: `IsActive` + `OnActivated`/`OnDeactivated`, где «`OnDeactivated` automatically unregisters the current instance from all registered messages… safe to collect **without the risk of memory leaks**» **[V]**. У нас эквивалент — отмена scope в `dispose()` дочернего holder'а. **Чего тут нет:** ни Microsoft, ни toolkit не дают правила *деления* VM по размеру — то есть первофирменный MVVM-канон подтверждает диагноз §6.0 (нотация не лечит god object), а не опровергает |

---

## 4. Сходящиеся выводы

**4.1. Слоистость держит *направление* графа и никогда — *размер* узла.**
`chatInputPart.ts` = 5231 строка внутри самой строго слоистой десктоп-кодовой базы мира; `editor.rs` = 12 781 строка внутри 243-крейтового workspace; `ChatInput.tsx` = 2650 строк в приложении с образцовыми ports&adapters; `ChatViewModel.kt` = 1476 в пятимодульном Kotlin/Compose Desktop проекте. Разрезание на модули и разрезание god object — **две независимые работы**, и вторая не следует из первой. Все четыре числа перепроверены построчно 2026-08-26, ссылки на конкретные файлы — в §8.1.

**4.2. Единственные незаглушаемые границы — компилятор и процесс.**
Всё остальное — договорённость с дополнительными шагами. Bazel формулирует эталон: default-deny плюс «A target will fail to build during the analysis phase». Kotlin даёт половину бесплатно: «модуль — это Gradle source set», значит каждый новый Gradle-модуль автоматически превращает `internal` в ошибку компиляции у вызывающей стороны. Figma и Petrenko приходят к одному ранжированию с разных сторон: изоляция, делящая рантайм, — не граница (Realms shim; дублированный `kotlin-stdlib` между classloader'ами → `LinkageError`), поэтому «process isolation first, classloader tricks second».

**4.3. Токены LLM нельзя писать в UI-состояние по одному.** Три независимых подтверждения на трёх стеках:
- Enchanted (SwiftUI): буфер + `Throttler(delay: 0.1)`, причина записана комментарием в исходнике **[V]**;
- TCA `Performance.md`: «High-frequency actions, such as sending dozens of actions per second, should be avoided» + рецепт «report the progress at most 100 times» **[V]**;
- Tripadvisor: «excessive action dispatching during high-frequency events (e.g. scrolling)» потребовало debounce **[V]**.

> **Применимость к целевому проекту сегодня — нулевая, и это надо сказать прямо.** В кодовой базе **нет стриминга**: `LocalLlmClient` отправляет `stream = false` с комментарием «encodeDefaults=true → обязательно шлём `stream:false` (иначе Ollama стримит NDJSON и парсинг ломается)» (`core/data/…/LocalLlmClient.kt:41,135`), а в `ChatState.kt` токен `stream` не встречается **ни разу**. Все LLM-вызовы — один запрос, один ответ целиком. Поэтому 4.3 — **проектное ограничение на будущее** («когда стриминг появится, вот чего нельзя делать»), а не описание живого дефекта. Соответственно §6.5 вынесена из нумерованного плана миграции: чинить там нечего.

**4.4. Абстракция провайдеров сошлась на одной форме во всех сериях:** один юнит на бэкенд, реестр для диспетчеризации, **capability-дескриптор как данные** для UI. Cherry Studio формулирует это как правило: хост «dispatches on agent.type through runtimeDriverRegistry and **never branches on a concrete runtime**», UI «reads `AGENT_RUNTIME_CAPABILITIES[agent.type]` and never branches on a concrete runtime either», полнота обеспечивается `satisfies Record<AgentType, …>` + тестом реестра **[V]**. Zed делает то же типами (`language_model` не знает ни одного вендора). AnythingLLM показывает провал: два (на деле шесть) параллельных switch'а в одном файле.

**4.5. Правило владения состоянием у Google и Apple совпадает дословно.** Apple: «Store data as state in the least common ancestor of the views that need the data» + «Limit the scope of state variables by declaring them as private». Google: «hoist UI state to the lowest common ancestor between all the composables that read and write it» + «hold state as low as possible while maintaining proper ownership». Оба запрещают передавать вниз сам holder; Google выписывает это кодом, Elm — паттерном Translator, Decompose — правилом `childContext`.

**4.6. Дробить надо по домену отказа и скорости изменения, а не по имени фичи.** Anthropic вынес agent loop из VM, потому что падение VM убивало продукт. Microsoft вернул Copilot Chat в монорепо. NiA обосновывает api/impl **инвалидацией билда и владением командами**, а не чистотой. Uber откатил 20+ репозиториев в монорепо.

**4.7. Все первофирменные вендоры, документирующие модуляризацию, документируют и её передоз.** Google: «Every module brings a certain amount of overhead… If overhead counteracts scalability improvements, you should consider consolidating some modules». NiA: «finding the right balance between overmodularizing a relatively small app». Apple: флагманские сэмплы — 2 и 3 пакета.

**4.8. Изоляция плагинов проигрывает удобству, если безопасный путь не самый простой.** `open-webui/pipelines` несёт от мейнтейнеров «DO NOT USE PIPELINES!»; Jan сознательно убил Node Extension Host; LM Studio разрешает песочницу только deno-раннерам. Вывод для нас — отрицательный и конкретный: **не заводить in-JVM загрузчик сторонних скиллов**; всё недоверенное — за границей процесса, где уже стоит `McpClient`.

**4.9. Convention plugins — не принуждение, а носитель.** Их ценность в том, что новый модуль стоит две строки, а `check`-задача, Konsist-зависимость и `explicitApi()` включаются из одного места. Wealthfront специально писал генератор модулей и file-mover; Dropbox зафиксировал человеческую цену без них: «engineers who had been with the company for 6+ months… still had no idea how to create a new module».

---

## 5. Спорные выводы — где расходятся вменяемые команды

| Спор | Позиция A | Позиция B | В чём на самом деле разногласие | Как это решается у нас |
|---|---|---|---|---|
| **Может ли домен видеть UI-фреймворк?** | **VS Code** — нет, и это проверяется компилятором (`tsconfig.browser.json` c `types: []`) | **Zed** — да: `language_model` и `agent` зависят от `gpui`, а `agent` ещё и от `ui` **[V]**, потому что entity-модель GPUI *и есть* их модель состояния | Есть ли **второй потребитель** домена. Если один — второй state-система дороже связности | У нас **три** потребителя (`:tools:cli/mcp/service`) → правило «домен не знает про Compose» верно и должно быть **ужесточено** |
| **Конструкторный DI или локатор?** | Google/Square/NiA — конструкторный | **JetBrains** — «Using constructor injection… is deprecated (and not supported in Light Services) for performance reasons», сервис берут в точке использования **[V]** | Cold start на 2246 модулях против видимости зависимостей | Koin-конструкторный DI остаётся. Но урок берём: **ленивость** — не строить RAG-индекс, MCP-клиент и реестр моделей в composition root |
| **Одноразовые события: state или канал?** | Google — «Do not send events from the ViewModel to the UI» (Strongly recommended), делать nullable-поле + `consumed()` **[V]** | MVIKotlin `Label`, Orbit `postSideEffect`; Nek.12: «State is, events happen», иначе «the state property amount grows unnaturally large… dozens of properties that mean essentially nothing» **[V]** | Гарантия доставки при переживании продюсера — на desktop окно не пересоздаётся, аргумент Google слабее | **Решено: state + явный ack.** Всё одноразовое (ошибка отправки, «скопировано», результат тула) — nullable-поле в holder'е, которое UI гасит вызовом `consume…()`. Канал (`Channel`/`SharedFlow`) **не заводим вовсе**. Причина, по которой позиция Google здесь сильнее вопреки слабости её главного аргумента: у нас `App.kt` уже держит диалоги булевыми флагами, и добавление второго транспорта событий даст **две** несогласованные модели навигации в одном holder'е — ровно то, что §6.6 велит убрать. Навигация и диалоги переезжают в `sealed interface Destination` (тоже state), значит канал не нужен даже для них |
| **api/impl или API/DI?** | NiA/Square/App Platform — api/impl | Gherschon: «quickly becomes a mess when the number of modules grows»; Ryan Harter уже выкинул отдельный `impl-wiring` | Число модулей и число **команд**. Оба выигрыша (инвалидация, владение) масштабо-зависимы | **API/DI.** У нас `:core:domain` уже *является* api-модулем; удваивать модули нечем оправдать |
| **Много мелких StateFlow или один UiState?** | MVI-канон — один immutable UiState | **Два вендора против одного канона.** (а) **Apple**, первофирменно, как обоснование замены `ObservableObject` на `@Observable`: «Updating views based on changes to the observable properties that a view's `body` reads instead of any property changes that occur to an observable object, which can help improve your app's performance» **[V]**. (б) **Microsoft**, MVVM Toolkit: `ObservableObject` уведомляет **по свойству** (`INotifyPropertyChanged`), а не по объекту **[V]**. (в) полевой отчёт: один большой UiState даёт «frequent and sometimes unnecessary recompositions… even when only a small part of the state actually changes», ответ — «Expose multiple StateFlows» **[V]** | Гарантия «невозможных состояний» против гранулярности инвалидации | У нас `var … by mutableStateOf` — гранулярность уже как у Jotai и как у `@Observable`. **Переход на один UiState — задокументированный шаг назад по обоим первофирменным канонам.** Мотив дробления holder'ов — только сопровождаемость. Гарантию «невозможных состояний» покупаем точечно: `sealed`-типы там, где флаги действительно взаимоисключающие (§6.6), а не один агрегат на весь экран |
| **Нужна ли статикартная модель для агента?** | FlowRedux/XState — да, `inState<S>{ on<Action> … collectWhileInState }` делает нелегальные переходы непредставимыми | «Мешок независимых тумблеров» (5 режимов памяти + RAG + MCP + picker) взрывается по состояниям | Является ли поток настоящим автоматом | Да — **только для agent-пайплайна** (`TaskStateMachine` уже существует). Нет — для панелей и диалогов |
| **Стоит ли фреймворк своих денег?** | Circuit/TCA/Decompose дают готовый контракт | Максимальный измеренный счёт TCA: 319 против 579 LOC на ту же фичу, cold build 1.1 с против 32.4 с, 16 транзитивных зависимостей, «Maintained by essentially two people» **[V]**; сами мейнтейнеры: «We do not recommend people use TCA when they are first learning» **[V]** | Размер команды и версия тулчейна | При пинах Compose 1.7.3 / Kotlin 2.1.21 фреймворк — чистый риск. Берём **документацию**, не зависимость |

---

## 6. Рекомендация для AdventAI Desktop

### 6.0 Диагноз одной строкой

`ChatState` — не «неправильный паттерн», а **Presentation Model, который так и не получил правила деления**; `App.kt` — Flow Synchronization по Фаулеру. Смена нотации (на MVI, на Clean с мапперами, на VIPER) лечит не ту болезнь. Askimo — контрольная группа с теми же цифрами при пяти Gradle-модулях и собственном detekt-ruleset — доказывает, что модули этого не решают.

### 6.1 Что заблокировано пинами — явно

**Сначала — граница, которую отчёт до этого называл двумя разными способами.** Правило проекта звучит **не** «`:core:*` без Compose», а точнее:

> **`:core:domain` — чистый Kotlin + korutiny и ничего больше.** Это правило реально: `settings.gradle.kts` описывает так только домен, а `core/domain/build.gradle.kts` имеет единственную зависимость `api(libs.coroutines.core)`. Остальные `:core:*` модули **не** ограничены — брать UI-зависимости им можно, если это оправдано числом потребителей.

Отсюда два следствия, которые надо было развести. (1) `:core:design` (шаг 2) — **полностью Compose-модуль**, и это не нарушение: он ниже `:app`, но выше домена, потребителей у него много. (2) Молекула отвергается **не** по этому основанию — см. строку ниже, аргумент заменён на настоящий.

| Опция | Вердикт | Основание |
|---|---|---|
| **Circuit как зависимость** | **Заблокирована де-факто.** Совместим ровно один релиз — **0.27.1** (2025-04-15): `kotlin = "2.1.10"`, `compose-jb = "1.7.3"` **[V]**. Head требует `kotlin = 2.4.10`, `jb-compose = 1.11.1` **[V]**. (Про JDK 23 — миф: `jdk = "23"` есть уже в 0.27.1, а `jvmTarget = "11"` в обоих; потребителя это не касается.) Плюс эргономичный путь — кодогенерация `@CircuitInject`, чего koin-core не даёт | заморозка на релизе полуторагодичной давности без канала багфиксов |
| **Circuit как документация** | **Принять сейчас** | `presenter-patterns` — единственный первофирменный текст, отвечающий «как разрезать гигантский presenter» |
| **Decompose** | Технически проходит: **3.3.0** собран на `jetbrainsCompose 1.7.0` / `kotlin 2.1.0` / `essenty 2.5.0` **[V]**; 3.4.0 → Compose 1.8.2 **[V]**. Kotlin остаётся 2.1.0 вплоть до 3.5.0 — **упирается только Compose-пин**. **Не принимать** как фреймворк: тянет собственную навигацию и `Value<T>` вместо StateFlow; Twine удалил его с чистым −1213 строк | лишняя модель навигации для одно-оконного приложения |
| **Essenty отдельно** (`lifecycle` + `lifecycle-coroutines`) | **Возможно**, если понадобится lifecycle-скоупинг: публикуется под `jvm`, не тянет ни Compose, ни Android **[V]** | 584★, один мейнтейнер — key-person risk |
| **Molecule** | Отложить — **но по другой причине, чем было написано в первой редакции.** Прежний довод («требует Compose-компилятор в модуле presenter'а → конфликт с `:core:* без Compose`») **снят**: такого правила у проекта нет (см. врезку выше), а Compose-модули ниже `:app` разрешены. Настоящие основания два: (1) Molecule 2.1.0 собран на Kotlin 2.1.20 и тянет **Compose runtime**, чей floor под пином 1.7.3 **не проверен** — это [И], а не [V] (§7.3); (2) Molecule нужен, только если presenter пишется как `@Composable`-функция, то есть в связке с Circuit-подобным контрактом, а Circuit мы уже отвергли. Библиотека без своего паттерна — чистая зависимость без выгоды | не «нарушает границу», а «не проверена и не нужна без Circuit» |
| **koin-compose** | **Заблокирован**: тянет Compose 1.8.2 (уже зафиксировано в `app/build.gradle.kts`) | — |
| **Koin 4.2.x** | **Заблокирован**: метаданные Kotlin 2.3.20 | — |
| **androidx ViewModel** | **НЕ заблокирован — поправка к брифу.** Проверено по самому артефакту, а не по чужому пересказу: POM `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-desktop:2.8.3` (repo1.maven.org) объявляет `runtime-desktop 1.6.11`, `runtime-saveable-desktop 1.6.11`, `ui-desktop 1.6.11`, `kotlin-stdlib 1.9.24`, `lifecycle-viewmodel-desktop 2.8.5` **[V]** — то есть все floor'ы **ниже** нашего пина, Compose за 1.7.3 он не тянет. Живой прецедент того же выбора: **Twine** ушёл с Decompose именно **на** этот стек — заголовок PR #1171 буквально «Migrate from Decompose to Compose Navigation and ViewModel», в описании «Remove decompose and essenty dependencies» **[V]**. Нужен `kotlinx-coroutines-swing` — **он уже в `:app`**. Ограничение «нельзя вызвать `viewModel()` без параметров» относится **только к non-JVM** таргетам **[V]**. Google сам называет **Koin первым** среди DI-альтернатив для KMP ViewModel **[V]**. **Вердикт всё равно — не брать**: на desktop нет ни configuration change, ни process death, значит остаётся церемония без выгоды; plain-класс санкционирован самим Google («A ViewModel is just an implementation detail of a state holder») | решение по существу, а не по доступности |
| **Voyager / PreCompose** | Отвергнуть. PreCompose — без коммитов с 2025-02-20. Voyager: коллаборатор прямо пишет «Actively maintained **no** because Voyager Core still stable… Main plans… upgrade Kotlin and other libraries» **[V]** — режим заморозки, плюс два завершённых ухода | — |
| **Workflow (Square)** | Отвергнуть: нет Compose Desktop-биндинга | — |

### 6.2 Целевой список модулей

```
:core:domain     чистый Kotlin + coroutines. Порты, use cases, MemoryMode/ContextAssembler,
                 TaskStateMachine, ProviderId (sealed) + ProviderDescriptor.  ← + explicitApi()
:core:data       реализации портов; все data sources помечены internal.       ← + explicitApi()
:core:design     Theme / Type / Dimens / AppColors / StatusColors + общие композаблы.  [НОВЫЙ, шаг 2]
                 ЯВНО Compose-модуль. Это законно: правило «чистый Kotlin» связывает только
                 :core:domain, а не всё поддерево :core:*.  ← нужен свой convention-плагин, см. шаг 2
:app             оболочка: Main, окно, composition root (Koin), хост диалогов,
                 навигация, ChatState как тонкий фасад.
:tools:cli       driving-адаптер и harness (уже есть)
:tools:mcp       runtimeOnly, подпроцесс (уже есть)
:tools:service   локальный HTTP-сервис (уже есть)

Позже, по одному и только после того, как API перестанет двигаться:
:feature:rag     (кандидат №1 — RagPanelState уже вынесен и устоялся)
:feature:mcp
:feature:devtools  (prompt-tune + /help + mock-interview — всё, что не про основной чат)
```

**`:feature:*:api` не заводим.** Интерфейсы фич — это порты, а порты уже живут в `:core:domain`. Схема API/DI, а не api/impl: конкретика называется только в composition root `:app`. Обоснование — §5, строка «api/impl или API/DI», плюс собственное правило Google про порог накладных расходов.

### 6.3 Декомпозиция `ChatState` (1617 строк → фасад)

Замеренные кластеры и целевые holder'ы. Имена полей и методов сверены грепом по `ChatState.kt`; **строка «стриминг», стоявшая в первой редакции в кластере 4, удалена — её в коде нет** (`grep -c stream ChatState.kt` = 0, все LLM-вызовы синхронные, см. врезку в §4.3). Это меняет оценку: кластер 4 — по-прежнему самый рискованный, но он **меньше**, чем был описан.

| # | Кластер в текущем `ChatState` | Целевой holder | Время жизни | Куда уходит логика |
|---|---|---|---|---|
| 1 | `accountList`, `activeAccount`, `profile`, `needsOnboarding`, `completeOnboarding/switchAccount/logout/deleteAccount/saveProfile/syncProfileFacts/activate` | **`AccountState`** | приложение | `SyncProfileFacts` → use case |
| 2 | `conversationList`, `current`, `refreshList`, `newConversation`, `open`, `deleteConversation`, `titleFrom` | **`ConversationsState`** (реестр + выбор) | приложение | чтение — Flow из репозитория |
| 3 | `input`, `submitComposer` | **`ComposerState`** | экран | — |
| 4 | `send`, `askDuringTask`, `loading`, `error`, `runStage`, `nextAutoAction`, `opStartedAtMs`, `lastOpSeconds`, токены | **`ChatSession`** — **один объект на открытую беседу**, со своим `CoroutineScope` и своим `Job` | беседа | `SendMessage`, `RunTaskStage` → `:core:domain` |
| 5 | `startTask/advanceTask/answerTask/chooseApproach/provideDocument/provideDocumentFor` | внутрь **`ChatSession`** (задача принадлежит беседе) | беседа | `TaskStateMachine` уже в домене |
| 6 | `memoryPlanner`, `pendingDerived`, `longTerm/working/addProfileFact/addDecision/setGoal/addConstraint/clearWorking/clearLongTerm`, 5 режимов | **`MemoryState`** | беседа | `AssembleContext(mode, budget)` → домен (см. §6.9) |
| 7 | `invariants`, `addInvariant/removeInvariant/toggleInvariant/userInvariants/setUserInvariants` | **`InvariantsState`** — **отдельный holder, альтернатива снята** | приложение | — |
| 8 | `mcpDialogOpen`, `mcpConnecting`, `mcpTools`, `mcpError`, `mcpChecking`, `mcpCheckResult`, `mcpVisa*`, `mcpPipeline*`, `mcpExtra*` (~14 полей) | **`McpState`** + супервизор `Map<ServerId, ServerStatus>` | приложение | `ConnectMcp`, `RunMcpPipeline` → домен |
| 9 | `promptTuneRunning/Note/promptProposals`, `promptAnalyzer`, `overrideStore` | **`PromptTuneState`** | приложение | `TunePrompt` → домен |
| 10 | `interviewOpen/Messages/Loading/Finished/Input` | **`InterviewState`** | side-сессия | `RunInterviewTurn` → домен |
| 11 | `projectDocs`, `devGateway`, `devHelp*`, `devQuery*` (`/help`) | **`DevAssistantState`** | приложение | `RunDevQuery` → домен |
| 12 | `config`, `model`, `chooseModel`, `saveConfig`, `hasKey`, `noKeyError` | **`ModelState`** + `Map<ProviderId, ProviderDescriptor>` | приложение | §6.8 |
| 13 | `lastPromptTokens`, `sessionTokens`, `sessionCost`, `contextFill` | **производные от `ChatSession`**, отдельного holder'а не нужно | — | — |
| 14 | `rebuildAgent`, `dispose`, все приватные `client/agent/orchestrator/extractor/offerAgent/interviewAgent/agentTools/skillEngine` | **уходит в Koin-граф**, а не в поля holder'а | — | — |

Уже вынесенные `RagPanelState` (320), `LocalLlmPanelState` (162), `ServicePanelState` (97) — доказательство, что приём работает в этой кодовой базе; они становятся образцом (как `ui-exploration` у animeko, объявленный каноном).

**Два железных правила, без которых сплит схлопнется обратно:**
1. **Никаких обратных ссылок.** Дочерний holder никогда не получает `ChatState`. Только значения и лямбды — Google выписывает это кодом (`// private val viewModel: MyScreenViewModel` закомментировано), Decompose запрещает структурный эквивалент («Never pass parent's ComponentContext to children»), Elm решает это паттерном Translator.
2. **Правило времени жизни.** «Зависеть можно только на holder с равным или меньшим временем жизни» — отсюда колонка «Время жизни» в таблице. `ChatSession` живёт ровно столько, сколько беседа; `McpState` — сколько приложение; связь между ними — только вниз.

**Одно следствие из правила 2, которое снимает единственную развилку в таблице.** Инварианты живут **приложение** (это профиль пользователя: «страна — Италия», «не выдумывать сроки»), память — **беседу** (5 режимов переключаются в композере конкретного диалога). Два разных времени жизни, значит по правилу 2 они физически **не могут** быть одним holder'ом: `MemoryState` пришлось бы пересоздавать при смене беседы, унося с собой инварианты приложения. Поэтому кластер 7 — **`InvariantsState`, отдельный, точка**; вариант «или внутрь `MemoryState`» из первой редакции удалён как противоречащий собственному правилу отчёта.

**Форма фасада** (zustand slice pattern, переведённый в Kotlin):

```kotlin
class ChatState(deps…) {
    val accounts      = AccountState(…)
    val conversations = ConversationsState(…)
    val models        = ModelState(…)
    val mcp           = McpState(…)
    val rag           = RagPanelState(…)      // уже есть
    val devTools      = DevAssistantState(…)
    val session: ChatSession? get() = sessions[conversations.currentId]
}
```
На переходный период публичные имена сохраняются делегированием (`by`), чтобы `App.kt` не пришлось править одним коммитом. **Обязательная оговорка Kotlin:** «members overridden in this way do not get called from the members of the delegate object» **[V]** — делегаты не должны рассчитывать на переопределение.

### 6.4 Ранжированный и упорядоченный план миграции

Каждый шаг: **что меняется · зачем (доказательство) · трудоёмкость · риск · откат · механическое принуждение.**

---

**ШАГ 0а — гейт графа модулей: УЖЕ СДЕЛАНО, шага нет.**
`com.jraska.module.graph.assertion` 2.9.1 применён в корневом `build.gradle.kts` — в форме **`allowed` (default-deny) + `restricted`**, с `maxHeight = 4`. Версия объявлена в `gradle/libs.versions.toml`. Делать здесь нечего; единственное, что остаётся, — **не понизить это по неосторожности**: замена `allowed` на один `restricted` превращает allowlist в blocklist (понижение ранга принуждения, §2.4(1)), а `maxHeight = 3` роняет `assertModuleGraph` на первом же запуске, потому что фактическая длиннейшая цепочка — `:tools:cli → :tools:mcp → :core:data → :core:domain`. Единственная строка, которую сюда стоит **дописать** (и именно сейчас, пока это бесплатно) — правило `":feature:.* -X> :feature:.*"`; она нужна к шагу 7, см. там.
*Трудоёмкость:* 0. *Риск:* 0.

---

**ШАГ 0б — снять `detekt-baseline.xml` и тем сделать `check` зелёным.**
*Что:* `./gradlew detektBaseline` (или `detektMain --baseline`) в `:app`; конвенция уже подхватывает файл условно — `val detektBaseline = file("detekt-baseline.xml"); if (detektBaseline.exists()) baseline = detektBaseline`.
*Зачем — и почему это первый шаг, а не гигиена.* detekt как fitness-функция god-object'а в проекте **уже стоит**: конвенция `advent.kotlin-jvm` применяет его ко всем модулям, `config/detekt/detekt.yml` включает **только** `LargeClass` (600) / `LongMethod` (60) / `TooManyFunctions` (30) при `build.maxIssues: 0`. Baseline на диске нет, а App.kt (2310) и ChatState.kt (1617) заведомо выше порога — значит **`./gradlew check` сейчас красный**, и он останется красным на протяжении всего распила, если долг не зафиксировать. Baseline переводит правило из «сборка сломана» в «сборка зелёная, новые нарушения ловятся», и одновременно **даёт метрику**: число записей в baseline — это и есть счётчик прогресса шагов 4 и 6 (см. §6.13(5)).
*Трудоёмкость:* 0.5 дня (сгенерировать + прочитать глазами, что туда не попало лишнее).
*Риск:* низкий, но одна ловушка: baseline фиксирует долг **по сигнатурам**, поэтому переименование класса при распиле вернёт находку. Это не баг — это ровно тот сигнал, ради которого он заводится.
*Откат:* удалить файл (конвенция перестанет его подхватывать сама).
*Принуждение после шага:* **ранг 2** — `LargeClass` на новых классах роняет сборку.

---

**ШАГ 0в — CI, которого в репозитории нет.**
*Что:* `.github/workflows/ci.yml`: `assemble` + `test` + `detekt` + `assertModuleGraph` на PR.
*Зачем:* это **предусловие** для каждой фразы «required check» в этом документе (шаги 5 и §6.13(4)). В репозитории **нет каталога `.github`** и нет ни одного воркфлоу — то есть в первой редакции два шага из восьми опирались на инфраструктуру, которой не существует, и стоила она ноль дней и ноль риска. Без этого шага гейты рангов 2–3 работают только у того, кто вручную запустит `gradlew check` — то есть не работают.
*Трудоёмкость:* 0.5–1 день (кэш Gradle, JDK 21, вопрос про офлайн-режим Maven в этой сети — см. §7).
*Риск:* низкий. *Откат:* удалить файл.

---

**ШАГ 0г — `dependency-analysis-gradle-plugin`, в два приёма.**
*Что:* подключить плагин; **сначала** прогнать `./gradlew buildHealth` в режиме отчёта, разобрать находки, **и только потом** включать `dependencyAnalysis { issues { all { onAny { severity("fail") } } } }`.
*Зачем:* ловит болезнь, которая делает многомодульность медленнее монолита — `api` там, где хватило бы `implementation` (Touchlab: «api деps appear on compile classpaths… significant ripple of recompilations» **[V]**). Самый живой инструмент выборки: 2178★, v3.19.1 от 2026-08-26 **[V]**.
*Version floor — записываем наравне с прочими:* DAGP 3.0.0 — «minimum supported version of Gradle is now 8.11» **[V]**; проект на Gradle **8.13** → проходит.
*Трудоёмкость:* **1 день**, не полдня. *Риск:* **средний**, не низкий. Обоснование обоих пересмотров — в §2.4(2): в проекте есть три формы, которые DAGP штатно помечает на первом прогоне (jar'ы через `files(…)` в `:core:data` и `:tools:cli`; намеренные `api(…)` в обоих `:core:*`; агрегированные Compose-аксессоры в `:app`), и включать `severity("fail")` вслепую — значит начать с красной сборки.
*Откат:* удалить блок из корневого build-файла.

**ШАГ 0д — атомарная запись файлов (`FileStore.writeText`).**
*Что:* писать во временный файл и переименовывать: `<name>.tmp` → `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`. Правка ~5 строк в одном методе `core/data/…/Files.kt`.
*Зачем:* сейчас `FileConversationRepository.save()` сериализует **всю** беседу и отдаёт её в `FileStore.writeText`, который делает `file.writeText(content)` **поверх существующего файла** — без tmp и без атомарного переименования. Падение или заполнение диска в середине записи оставляет усечённый JSON, а `load()` обёрнут в `runCatching{}.getOrNull()` — то есть **беседа теряется целиком и молча**. Разбор — §6.10.
*Почему это идёт до распила:* самое дешёвое действие всего документа с самой высокой ценой отказа. Один метод чинит все репозитории сразу (беседы, working, profile, config), потому что все ходят через ту же обёртку. Доменный порт не меняется — значит шаг ни с чем не конфликтует и ничего не блокирует.
*Трудоёмкость:* ~1 час. *Риск:* низкий. *Откат:* вернуть прежнее тело метода.
*Принуждение:* тест в `core/data/src/test` — записать, прервать, прочитать; каталог тестов в модуле уже заведён.

---

**ШАГ 1 — `explicitApi()` на `:core:domain` и `:core:data` + пометить data sources `internal`.**
*Что:* `kotlin { explicitApi() }` в обоих модулях; всё, кроме портов и репозиториев, — `internal`.
*Зачем:* ранг 1, бесплатно. Google формулирует прямо: «Data sources should only be accessible by repositories from the same module… You can enforce this by using Kotlin's private or internal visibility keyword» **[V]**. Побочный эффект — механическая инвентаризация реальной публичной поверхности.
*Трудоёмкость:* 1 день механической правки. *Риск:* низкий (ошибки компиляции, не рантайма).
*Откат:* убрать `explicitApi()`.
*Честная оговорка:* у Kotlin нет уровня между `internal` (один source set) и `public`. Swift решил это `package`-модификатором в SE-0386 — «to access a symbol in another module, that symbol needs to be declared public» **[V]**. У нас эквивалента нет; при появлении `:feature:*` общие хелперы придётся делать public (единственный обходной путь — недокументированный `-Xfriend-paths`).

**ШАГ 2 — Выделить `:core:design`.**
*Что:* `Theme.kt`, `Type.kt`, `Dimens.kt`, `AppColors`, `StatusColors` + общие композаблы из `App.kt` (`IconActionButton`, `DropdownChip`, `ResultCard`, `ExpandableText`, `HintChip`, `MarkdownText`).
*Зачем:* это тот модуль, который и Apple (`LayeredArtworkLibrary`), и Ice Cubes (`DesignSystem`), и Google (`:core:designsystem`) выделяют первым; много потребителей, медленно меняется, **не требует решать вопрос state holder'а**. Даёт правилу «hex в композаблах не хардкодить» компиляторный дом. Заодно снимает с `App.kt` несколько сотен строк.
*Скрытая работа, которую надо назвать до старта:* существующие convention-плагины Compose **не умеют**. `advent.kotlin-jvm` даёт `kotlin("jvm")` + JVM-target + detekt; `advent.kotlin-library` добавляет только `java-library`. Значит `:core:design` требует либо нового плагина `advent.kotlin-compose` (`kotlin("jvm")` + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.compose` + detekt), либо применения этих двух id прямо в build-файле модуля. Первое правильнее — тот же плагин потом переиспользует `:feature:rag` (шаг 7).
*Трудоёмкость:* **2–3 дня** (пересмотрено с «1–2»: перемещение файлов действительно тривиально, но convention-плагин под Compose пишется впервые). *Риск:* низкий — ошибки будут компиляционные. *Откат:* вернуть файлы в `:app`, убрать модуль из `settings.gradle.kts`.
*Принуждение:* ранг 1 — `:core:design` зависит только от Compose и ни от чего из `:core:data`/`:app`. Правило «`:core:*` без Compose» здесь **не нарушается, потому что его не существует**: чистым Kotlin'ом ограничен только `:core:domain` (см. врезку в §6.1).

**ШАГ 3 — Konsist: пакетные слои внутри `:app`.**
*Что:* один тестовый модуль (можно прямо в `:app`), правила: `ui` не зависит на `io.ktor.*`/`java.io.File`; `com.example.adventdesktop.domain..` — `dependsOnNothing()`; ни один `ui.state.*` не импортирует `androidx.compose.ui.*`.
*Зачем:* Konsist строит scope **из исходников на диске**, поэтому «by integrating Konsist into a single module (e.g. app module), Konsist can still access the entire project» **[V]** — один тест полицейски покрывает все Gradle-модули. ArchUnit этого не может: он читает байткод с test classpath.
*Трудоёмкость:* 0.5 дня. *Риск:* **средний**, и риск не только в парсинге.
*Живость инструмента — по той же мерке, которой отвергнуты PreCompose и Voyager.* Konsist: 1716★, последний релиз **0.17.3 от 2024-12-08**, на default-ветке с тех пор **один** коммит (2026-01-08) **[V]**. Релиз старше Kotlin 2.1.0 целиком, так что «совместимость с 2.1.21 не заявлена» — это мягкая формулировка: она не заявлена, потому что релиза после 2.1.0 не было.
*Решение (а не список вариантов):* **шаг переставлен — он идёт ПОСЛЕ шага 4, а не до него.** Причина: в первой редакции шаги 4, 6 и 7 называли Konsist своим механизмом принуждения, то есть три шага висели на непроверенной и почти не сопровождаемой зависимости. Распил `ChatState` не должен ждать смоук-теста стороннего линтера.
*Запасной путь, если смоук-тест 0.17.3 × 2.1.21 падает:* правило слоёв деградирует до **кастомного detekt-правила** (detekt в проекте уже применяется ко всем модулям — носитель есть) либо до простейшего `forbidden-imports`-теста на `File.readText` + регексы по импортам. Оба варианта слабее по выразительности, но оба живы и уже стоят в сборке.
*Откат:* удалить тест.

**ШАГ 4 — Пакетная декомпозиция `ChatState` (без новых Gradle-модулей).**
*Что:* по таблице §6.3, **по одному кластеру за PR**, снизу вверх по времени жизни: сначала `PromptTuneState`, `InterviewState`, `DevAssistantState` (изолированные, низкий риск), затем `McpState`, `ModelState`, `AccountState`, `MemoryState`, и последним — `ChatSession`.
*Зачем:* это единственный приём с задокументированным результатом. VS Code выносит фичи из терминала в `terminalContrib/` **с 2023 года** — 25 директорий, по PR на фичу, при этом `terminalInstance.ts` всё ещё 126 430 байт **[V]**. Владелец формулирует правило: «The idea with `terminalContrib/` is to keep the core slim to reduce complexity. It enforces layer checks to ensure that it builds upon the core and not in it» **[V]**.
*Метод:* **Branch by Abstraction + Mikado.** Сначала публикуем интерфейс кластера, тела остаются на месте; затем переносим тела по одному. Правило Mikado — при провале **ревертить**, а не чинить на длинной ветке.
*Трудоёмкость:* 8–12 PR, 2–4 недели календарно.
*Риск:* средний. Главная ловушка — обратные ссылки; лечится правилом §6.3.
*Откат:* каждый PR откатывается независимо (public API сохранён делегированием).
*Принуждение:* Konsist-правило «ни один `ui.state.*` не импортирует другой `ui.state.*`, кроме через конструктор».

**ШАГ 5 — Вынести оркестрацию в use cases `:core:domain`.**
*Что:* `SendMessage`, `RunTaskStage`, `AssembleContext`, `ExtractMemory`, `TunePrompt`, `RunDevQuery`, `ConnectMcp`, `RunMcpPipeline`.
*Критерий отбора — объективный, не вкусовой:* **«мог бы это вызвать headless-харнесс из `:tools:cli`?»** Если да — это use case; если нет — это код state holder'а. Это прямое применение гексагона (у нас уже три driving-адаптера) и снимает оба известных провала сразу: и «мапперы ради мапперов» (Google: «forces you to add use cases even when they are just simple function calls»), и «интерфейс с одной реализацией» (Three Dots Labs).
*Зачем:* Continue — рабочий эталон: `sessionSlice.ts` = 1097 строк **чистых редьюсеров** + 19 отдельных thunk-файлов, и именно поэтому стриминг у них покрыт тестами на 68 КБ **[V]**.
*Трудоёмкость:* 1–2 недели, параллельно шагу 4.
*Риск:* низкий. *Откат:* инлайнить обратно.
*Принуждение:* новый job в CI (заведённом на шаге 0в) — **`runTaskFlowCheck` из `:tools:cli`, и только он**. Это приём Monaco (`monaco-compile-check`): второй потребитель ловит связность, которую никто не догадался запретить правилом.
**Почему не `runAgentProbe`, вопреки первой редакции.** Он не может быть required check **никогда**: его собственный build-файл документирует требования — «нужны: ключ DeepSeek, доступ к VPS-MCP, Ollama для RAG». То есть платный ключ, живой приватный VPS и локальный демон Ollama. Такой job негерметичен и недетерминирован по построению; сделав его обязательным, мы получим красную сборку при недоступности чужого сервиса и научим команду игнорировать красный CI. `runAgentProbe` остаётся **ручным аудиторским харнессом** (запуск руками, транскрипты в `build/agent-audit/`), а единственный офлайновый — `runTaskFlowCheck`, у которого в описании прямо стоит «без сети».

**ШАГ 6 — `App.kt` (2310 строк, 49 композаблов): разрезать по типовой оси.**
*Что:* правило VS Code `window → page → component → primitive`, где `page` не импортирует другой `page`, а `component` не импортирует `page`. Практически: `App.kt` оставляет только окно + хост роутинга + хост диалогов; `Sidebar`, `ChatPane`, `Composer`, `EmptyState` — отдельные файлы.
*Точные числа, потому что весь §6 стоит на том, что числа тут настоящие (пересчитано по файлу):* в `App.kt` **49** `@Composable`-функций, из них **14** — RAG/eval-вьюхи (`RagDialog`, `RagVsView`, `RagVsCard`, `RagTraceView`, `RagAnswerCard`, `RagEvidence`, `RagComparisonTable`, `RagPipelineControls`, `GoldRetrievalView`, `GoldRetrievalRow`, `GoldAnswersView`, `GoldAnswerRow`, `CitationEvalView`, `CitationEvalRow`) → уезжают в `ui/rag/`. В первой редакции стояло «больше двадцати» — **завышение примерно в полтора раза, исправлено**.
*Диалоги — не greenfield-каталог.* В `App.kt` живут **4** диалога (`RagDialog`, `LocalLlmDialog`, `ConnectorsDialog`, `McpToolsDialog`), но **`ui/Dialogs.kt` уже существует** (566 строк) и держит ещё **5**: `SettingsDialog`, `ProfileDialog`, `MemoryDialog`, `InvariantsDialog`, `InterviewDialog` — плюс 13 приватных хелперов (`ModalScaffold`, `ModalBlock`, `ModalField`, `AppSwitch`, `ModelSelector`, …). Поэтому шаг звучит так: **создать `ui/dialogs/` и свести туда все девять** — четыре из `App.kt` и пять из `Dialogs.kt`, с общими хелперами в `ui/dialogs/Modal.kt`. Завести каталог только под четвёрку из `App.kt` — значит получить диалоги в двух конвенциях сразу, то есть ровно ту полу-миграцию, от которой отчёт предостерегает в других местах.
*Зачем:* это **независимая от шага 4 работа**. Ricouard формулирует правило: view model не должна «exist only because the view body is too long» — сначала дели вью, потом заводи слой.
*Трудоёмкость:* 3–5 PR. *Риск:* низкий (перемещения). *Откат:* по PR.

**ШАГ 7 — Первый `:feature:*` Gradle-модуль — и только один.**
*Что:* `:feature:rag` (кандидат №1: `RagPanelState` уже вынесен и не двигается; RAG/eval-вьюх в `App.kt` ровно 14 из 49 композаблов).
*Зачем:* и Cherry Studio, и animeko делают ровно так — объявляют один модуль каноничным и мигрируют остальное на него («ui-exploration и ui-onboarding показывают текущую best practice», при этом монолит `app/shared/src` честно назван «历史遗留… ui-* модулями» **[V]**).
*Условие входа:* Konsist-правило для этого пакета зелёное **несколько недель без подавлений**.
*Трудоёмкость:* 2–3 дня, из них половина — convention plugin `advent.kotlin-feature`.
*Риск:* средний — риск преждевременности; поэтому один модуль, а не пять.
*Откат:* сложить пакет обратно в `:app`, удалить `include` (Google сам это санкционирует: «you should consider consolidating some modules»).
*Принуждение:* добавить в `modules-graph-assert` правило `':feature:.* -X> :feature:.*'` **до того, как появится второй feature-модуль** — сейчас это бесплатно, потом дорого. Ice Cubes показывает цену пропуска: Timeline зависит напрямую от StatusKit.

**ШАГ 7-предусловие — convention plugin `advent.kotlin-feature`.**
Пишется **раньше** первого feature-модуля, а не после третьего. Обоснование — Dropbox («engineers 6+ months in still had no idea how to create a new module») и Wealthfront (генератор модулей + file-mover как часть проекта). Плагин включает: Kotlin/JVM target, Compose, `explicitApi()`, Konsist-тест-зависимость, регистрацию в `check`.

### 6.5 Стриминг — НЕ шаг миграции. Проектное ограничение на фичу, которой пока нет

> **Поправка к первой редакции.** Здесь было написано, что три перечисленных ниже бага «у нас все три сейчас возможны». Это неверно: **стриминга в кодовой базе нет вообще**. `LocalLlmClient` шлёт `stream = false` с комментарием «иначе Ollama стримит NDJSON и парсинг ломается» (`core/data/…/LocalLlmClient.kt:41,135`), облачный путь тоже синхронный, а в `ChatState.kt` токен `stream` не встречается ни разу. Раздел **выведен из нумерованного плана** и переписан как контракт на будущее: чинить нечего, а вот записать правило до того, как стриминг напишут, — дёшево.

**Правило, которое действует в момент появления стриминга:** Flow принадлежит **`ChatSession`**, он же пишет в репозиторий; UI только рендерит и не владеет транспортом. Cherry Studio опубликовал post-mortem ровно трёх багов, которые получает всякий, кто нарушит это правило **[V]**:
1. закрытие/размонтирование убивало транспорт и теряло уже пришедший ответ;
2. `reconnectToStream()` всегда возвращал null — возврат в тему терял живой прогресс;
3. персистентность **в рендерере** теряла ответ при падении между концом стрима и коммитом.

Все три — следствия того, что владельцем прогона был UI, а не объект беседы. Наша защита от них — не патч, а сама форма кластера 4 (`ChatSession` со своим scope), которую мы и так строим.

**Батчинг токенов — решение, а не список кандидатов.** По умолчанию: **`conflate()` на пути дописывания в сообщение**, потому что при дописывании в буфер промежуточные значения не нужны — важен последний, а `conflate` именно роняет промежуточные без таймера и без лишнего состояния. `sample(100.ms)` и «`StringBuilder` + тик» — не берём: первый вводит задержку даже там, где её не требуется, второй дублирует то, что Compose уже делает батчингом снапшотов.
**Оговорка, которая остаётся честной:** ни один первоисточник по Compose Desktop под это не читался (§7.19). Три доказательства из §4.3 — со SwiftUI, TCA и Tripadvisor, то есть с чужих стеков. Поэтому `conflate()` — **дефолт до первого замера**, а не измеренный результат; мерить на своей машине в момент, когда стриминг появится.

### 6.6 Диалоги: булевы флаги → `sealed interface Destination`

Сейчас в `ChatState` минимум шесть независимых булевых: `mcpDialogOpen`, `interviewOpen`, `mcpConnecting`, `mcpChecking`, `promptTuneRunning`, `mcpVisaLoading`, `mcpPipelineRunning`. Circuit называет это «Boolean Flag Soup» и предписывает sealed-класс или nullable sub-state **[V]**. Point-Free формулирует выгоду: enum-назначения дают «compile time proof that two destinations cannot be active at the same time» **[V]**, и он же называет цену обратного — Scrumdinger «uses mostly fire-and-forget style navigation, which means you can't easily deep link into any screen of the app». Плюс правило Ice Cubes: состояние экрана — **один** `ViewState`-enum, а не тройка `isLoading`/`error`/`data`.

### 6.7 MCP: то, что уже сделано верно, и три добавки

Верно: сервер как подпроцесс, `:tools:mcp` только в `runtimeOnly` — это настоящая компиляторная граница, а не соглашение, и она совпадает с моделью Claude Desktop / Cursor / Windsurf.
Добавить:
1. **Супервизор на сервер**: `StateFlow<Map<ServerId, ServerStatus>>` (процесс + статус + последняя ошибка + путь к логу), `McpState` наблюдает, никто не трогает `Process`. Аналог: per-server `mcp-server-<NAME>.log` со stderr **[V]** и «Cursor isolates server failures» **[V]**.
2. **Бюджет инструментов**: не отдавать модели всё подряд. Windsurf упёрся в жёсткий потолок 100 **[V]**.
3. **Объявленные capability** на каждый MCP-сервер и скилл (`reads-files` / `writes-files` / `network` / `spawns-process`) + approval по паре *(фича, инструмент)*. Словарь — Microsoft («Servers must declare privileges they require»), форма — LM Studio с обязательным человекочитаемым `reason` на каждый грант **[V]**.

### 6.8 Провайдеры и модели

```kotlin
// :core:domain
sealed interface ProviderId { DeepSeek; OpenRouter; Ollama; PrivateService }
data class ProviderDescriptor(
    val id: ProviderId, val streaming: Boolean, val tools: Boolean,
    val vision: Boolean, val contextWindow: Int, val local: Boolean,
)
val PROVIDERS: Map<ProviderId, ProviderDescriptor>   // полнота — тестом реестра
```
UI **не ветвится** по провайдеру — читает дескриптор (правило Cherry Studio). Полнота обеспечивается исчерпывающим `when` по sealed-типу + тестом «каждый `ProviderId` резолвится в Koin-графе» — у нас для этого уже есть `AppModuleTest`. Анти-паттерн, которого избегаем, замерен: AnythingLLM разводит 39 провайдеров шестью switch'ами в файле на 762 строки **[V]**.

### 6.9 Сборка контекста: 5 режимов памяти → таблицы приоритетов

Сейчас режимы — это ветки усечения внутри `ContextAssembler`/`ChatState`. Cursor/Anysphere опубликовали более сильную форму (Priompt): каждый блок объявляет приоритет, рендерер **бинарным поиском** ищет минимальный порог, при котором промпт влезает в бюджет — «the minimum value such that |Prompt(p_cutoff)| ≤ T» **[V]**.

Перенос в Kotlin, без единой новой зависимости:
```kotlin
data class PromptPart(val priority: Int, val tokens: Int, val render: () -> String)
fun assemble(parts: List<PromptPart>, budget: Int): String   // бинарный поиск порога
```
Кандидаты в `PromptPart`: system prompt, профиль, инварианты, `[STATE]` задачи, тексты документов, RAG-чанки, последние ходы, свёртка. **Пять режимов памяти становятся пятью таблицами приоритетов — данными, тестируемыми без модели, — а шестой режим стоит ноль кода.**

Плюс `isolate()` для кэш-стабильного префикса (system + профиль + инварианты): он должен рендериться байт-в-байт одинаково, иначе один длинный документ молча ломает кэш промпта и воспроизводимость `runAgentProbe`.

**Две честные оговорки.** Priompt как библиотека мёртв: HEAD default-ветки — **2025-02-03**, ~18 месяцев без коммитов **[V]**. И его собственный README предупреждает: «The Priompt renderer has no builtin support for creating cacheable prompts. If you overuse priorities, it is easy to make hard-to-cache prompts», плюс «adding priorities to everything is sort of an anti-pattern» **[V]**. Берём идею, не зависимость, и `isolate` пишем сами.

### 6.10 Персистентность и секреты

- **Ключи API в `~/.adventai/config.json` открытым текстом — это дефект, а не конфигурация.** Ровно его OpenAI чинил на macOS в v1.2024.171 после публичного разбора **[V]**. Anthropic решает помечая поля `"sensitive": true` → OS keychain **[V]**. Наше действие: перенести ключи в Windows Credential Manager, `config.json` оставить для несекретного.
- **`conversations/<id>.json` — проверено, и дефект хуже, чем предполагала первая редакция.** Условное «если репозиторий переписывает весь файл» снято: **переписывает**, и делает это неатомарно. `FileConversationRepository.save()` (`core/data/…/FileConversationRepository.kt:33-35`) сериализует **всю** беседу и зовёт `store.writeText(...)`; `FileStore.writeText` (`Files.kt:17-20`) — это `file.parentFile?.mkdirs(); file.writeText(content)`, то есть **прямая запись поверх существующего файла без временного файла и без атомарного переименования**.
  Следствие не «окно повреждения на последнем ходе», а хуже: падение процесса, отказ диска или заполнение тома **в середине** записи оставляют усечённый JSON — и теряется **вся беседа целиком**, а не последний ход, потому что при следующем чтении `decodeFromString` бросит, а `load()` обёрнут в `runCatching { … }.getOrNull()` и молча вернёт `null`. Отказ тихий.
  **Починка — ~5 строк в `:core:data`, порт не меняется:** писать в `<id>.json.tmp`, затем `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` внутри `FileStore.writeText`. Один метод, все репозитории (беседы, working, profile, config) чинятся разом, потому что все ходят через ту же обёртку.
  **Приоритет: делать до шага 4, а не после.** Это самый дешёвый пункт всего документа с самой высокой ценой отказа — сохранность пользовательских данных против 2–4 недель распила holder'ов. Он не конфликтует ни с одним шагом и ничего не блокирует.
  Отдельно, как направление, а не как задача: O(n)-запись на сообщение остаётся. Cursor ключует сообщения по отдельности (`composerData:<id>`, `bubbleId:<conv>:<msg>` в SQLite) **[И], третьи лица** — брать это стоит только если длинные беседы реально начнут тормозить; sqlite-jdbc в проекте уже есть.
- **Версионирование схемы бесед** — отдельный именованный файл миграции. macai держит `V1ToV3Migration.swift` первоклассным артефактом **[V]**; с пятью режимами памяти и persисted-задачами смена схемы — вопрос «когда», а не «если».

### 6.11 Что НЕ делать

1. **Не заводить один `data class ChatUiState` под `StateFlow` ради производительности.** Сейчас у нас per-property `mutableStateOf` — гранулярность уже как у Jotai. Одна большая структура — задокументированный шаг назад, и это подтверждают **два вендора первофирменно**, а не один анонимный полевой отчёт:
   - **Apple** прошёл этот путь публично и назвал причину в документе о миграции: `@Observable` пришёл на смену `ObservableObject` ради «updating views based on changes to the observable properties that a view's `body` reads instead of any property changes that occur to an observable object, **which can help improve your app's performance**» **[V]**. `ObservableObject` — это в точности «один агрегат под одной подпиской», то есть форма, к которой нас звал бы единый `ChatUiState`; Apple от неё ушёл.
   - **Microsoft**, MVVM Toolkit: базовый `ObservableObject` уведомляет **по свойству** через `INotifyPropertyChanged`, а не по объекту **[V]**.
   - Плюс полевой отчёт: «frequent and sometimes unnecessary recompositions… even when only a small part of the state actually changes» **[V]**.

   Итог: `var … by mutableStateOf(…); private set` — это **целевое** состояние по всем трём источникам, а не временное решение, которое когда-нибудь заменят на «правильный» UiState.
2. **Не принимать фреймворк архитектуры под текущие пины.** Максимальный измеренный счёт: +81% LOC и ×29 cold build (TCA) **[V]**; для Circuit — заморозка на релизе апреля 2025 плюс требование кодогенерирующего DI.
3. **Не делать api/impl.** Оба обоснования (параллельная компиляция `:impl`, владение командами) требуют масштаба, которого нет.
4. **Не заводить in-JVM загрузчик сторонних скиллов.** §4.8.
5. **Не децентрализовывать схему конфигурации** вместе с поведением. Zed это сделал и откатил: «no unified, strongly-typed model of all settings» + сломанный авто-апдейт **[V]**. `config.json`/схема бесед остаются одной типизированной моделью в `:core:data` даже после появления `:feature:*`.
6. **Не переносить ось «среда выполнения» из VS Code.** У нас один JVM-процесс; это изобретёт границы, которые ничего не покупают.

### 6.12 Сводка шагов

| Шаг | Что | Трудоёмкость | Риск | Откат | Принуждение после шага |
|---|---|---|---|---|---|
| **0а** | `modules-graph-assert` — **уже стоит** (`allowed` default-deny, `maxHeight=4`) | **0** | — | — | **ранг 2** — граф модулей (действует сейчас) |
| **0б** | `detekt-baseline.xml` — снять долг, вернуть `check` в зелёное | 0.5 дня | низкий | удалить файл | **ранг 2** — `LargeClass` на новом коде роняет сборку |
| **0в** | **завести CI** (`.github/workflows`: assemble+test+detekt+assertModuleGraph) | 0.5–1 день | низкий | удалить файл | предусловие для любого «required check» ниже |
| **0г** | `dependency-analysis`: сперва `buildHealth` (отчёт), потом `severity("fail")` | **1 день** | **средний** | удалить блок | ранг 2 — `api` против `implementation` |
| **0д** | **атомарная запись** `FileStore.writeText` (tmp + `ATOMIC_MOVE`) — §6.10 | **~1 час** | низкий | вернуть `writeText` | — (защита данных, не граница) |
| 1 | `explicitApi()` + `internal` на data sources | 1 день | низкий | снять флаг | **ранг 1** — публичная поверхность `:core:*` |
| 2 | `:core:design` + новый плагин `advent.kotlin-compose` | **2–3 дня** | низкий | вернуть файлы, снять `include` | **ранг 1** — «hex не хардкодить» получает дом |
| 4 | распил `ChatState` (8–12 PR) | 2–4 недели | средний | по PR (фасад через `by`) | ранг 2 — detekt `LargeClass`, baseline сжимается |
| 5 | use cases в `:core:domain` + **`runTaskFlowCheck`** в CI | 1–2 недели (∥ шагу 4) | низкий | инлайнить обратно | **второй потребитель как required check** |
| 3 | Konsist: пакетные слои — **перенесён после 4** | 0.5 дня | **средний** (0.17.3 от 2024-12) | удалить тест | ранг 3, **если** смоук-тест пройдёт; иначе detekt-правило |
| 6 | распил `App.kt` (14 RAG-вьюх → `ui/rag/`, 4+5 диалогов → `ui/dialogs/`) | 3–5 PR | низкий | по PR | ранг 3 — `page` не импортирует `page` |
| 7 | `advent.kotlin-feature` + `:feature:rag` | 2–3 дня | средний (преждевременность) | сложить пакет обратно | ранг 2 — `:feature:.* -X> :feature:.*` |

*Изменения против первой редакции:* шаг 0 распался на пять (один из них — нулевой трудоёмкости, потому что сделан; два — новые: CI и атомарная запись); шаг 3 переставлен за шаг 4, чтобы распил не висел на непроверенной зависимости; шаги 2 и 0г переоценены вверх.

### 6.13 Как это проверяется — тестовая петля

Разрез считается состоявшимся, только если извлечённый holder тестируется **без окна**. Это не эстетика: GUI-смоук в этой среде невозможен (окно gradle-процесса не гранта́ется, distributable не собрать), значит тестопригодность — единственный доступный сигнал.

1. **Юнит-тест на holder.** Каждый извлечённый `*State` конструируется в тесте с фейковыми портами. Если для этого нужно поднять весь Koin-граф — разрез был тематическим, а не структурным. Этот же критерий формулируют все четыре MVI-фреймворка выборки, у каждого есть отдельный тест-модуль ровно поэтому.
2. **Управляемые зависимости.** Непокрытый остаток god object'а — это всегда не-портированные системы: `System.currentTimeMillis`, `UUID.randomUUID`, прямой `java.io`, счётчики токенов. Портируем их и добавляем **Koin test-scope**, подменяющий `LlmGateway`, `Clock`, `FileSystem` фейками. Формулировка мотива из первоисточника: неконтролируемые зависимости «make it difficult to write fast, deterministic tests» **[V]**. Это единственный способ вообще утверждать что-то о поведении агента.
3. **`AppModuleTest` расширяется** до проверки полноты реестра провайдеров (каждый `ProviderId` резолвится) — тот же приём, что `registerDrivers.test.ts` у Cherry Studio.
4. **`runTaskFlowCheck` из `:tools:cli` — required CI job** (шаг 5, после того как CI заведён шагом 0в). Это приём Monaco: второй потребитель домена ломает сборку при дрейфе логики в `:app` — той связности, которую никто не догадался запретить правилом. **`runAgentProbe` в эту роль не годится и не предлагается**: его build-файл требует ключ DeepSeek, доступ к приватному VPS-MCP и запущенную Ollama — негерметичный и недетерминированный job обязательным не делают. Он остаётся ручным аудиторским прогоном.
5. **Метрику не изобретаем — она уже стоит в сборке.** В первой редакции здесь предлагалось печатать «топ-10 самых больших файлов и число конструкторных зависимостей на класс». Это **самодельный и более слабый дубликат** того, что в проекте уже есть: detekt применён ко всем модулям через `advent.kotlin-jvm`, а `config/detekt/detekt.yml` включает ровно и только правила размера — `LargeClass` (600), `LongMethod` (60), `TooManyFunctions` (30) — при `build.maxIssues: 0`. Печатать список — значит смотреть; detekt — **ронять сборку**, то есть ранг 2 против ранга 6.
   **Двигаемая метрика:** число записей `LargeClass`/`LongMethod` в `detekt-baseline.xml`. Baseline снимается шагом 0б и после этого **только сокращается**: каждый извлечённый holder убирает из него строки, а любое новое нарушение в baseline не попадает и валит сборку сразу. Ноль записей = распил закончен.
   Числа, на которых стоят утверждения этого отчёта о god object'ах, — это байты и строки: 5231 / 12 781 / 4663 / 2650 / 2347 / **2310** / **1617**; ссылка на конкретный файл по каждому — в §8.1, поэтому любое из них можно перепроверить, а не принять на слово. Программа выноса, которую не измеряют, тихо останавливается — так у VS Code `terminalInstance.ts` за три года остался на 126 430 байт.

### 6.14 Порядок в одну строку

```
0а граф модулей — УЖЕ СТОИТ (0 работы)
0б detekt-baseline (check → зелёный)  →  0в CI  →  0г buildHealth, потом severity(fail)
0д атомарная запись FileStore (1 час, данные пользователя)
   → 1 explicitApi+internal → 2 :core:design + advent.kotlin-compose
   → 4 распил ChatState (8–12 PR) ∥ 5 use cases + runTaskFlowCheck в CI
   → 3 Konsist (после 4; если 0.17.3 не парсит 2.1.21 — detekt-правило)
   → 6 распил App.kt (14 RAG-вьюх, 9 диалогов) → 7 advent.kotlin-feature → :feature:rag
```
Шаги 0б–0г и 1 обратимы за минуты и не трогают прикладной код; 0д трогает четыре строки в `:core:data`. Шаг 7 не начинается, пока шаги 4–6 не устоялись.
**Три отличия порядка от первой редакции, каждое — следствие сверки с рабочим деревом:** гейт графа модулей исчез из плана (он уже применён); появились CI и атомарная запись (первого не существовало, второе было гипотезой); Konsist уехал за распил, чтобы три шага не висели на релизе от декабря 2024.

---

## 7. Нерешённое

**Неподтверждённые совместимости (проверять сборкой, а не цитатой):**
1. **Konsist 0.17.3 × Kotlin 2.1.21** — нигде не заявлено, какие версии Kotlin он парсит и справляется ли с исходниками под Compose-компилятором. **Уточнение этой редакции:** дело не только в парсинге. Konsist — 1716★, но последний релиз **0.17.3 от 2024-12-08**, и на default-ветке с тех пор один коммит (2026-01-08) **[V]**, то есть релиз старше Kotlin 2.1.0 целиком. По той же мерке, которой отвергнуты PreCompose («без коммитов с 2025-02-20») и Voyager (самообъявленная заморозка), Konsist — не лучше. Отсюда две правки плана: шаг 3 переставлен **за** шаг 4 и снабжён запасным путём (кастомное detekt-правило). Связка с п. 9 ниже: механических проверок границ не нашлось ни в одном крупном Kotlin-проекте выборки — возможно, ровно потому, что единственный удобный инструмент этого класса почти не сопровождается.
2. **`modules-graph-assert` 2.9.1** — минимальная версия Gradle не заявлена; плагин собран на более новом Kotlin, что нормально (он живёт на classloader'е Gradle), но это **[И]**. Практически вопрос закрыт: плагин **уже применён** в корне проекта на Gradle 8.13 и работает.
3. **Molecule 2.1.0 под Compose 1.7.3** — **[И]** из «зависит только от Compose runtime» + читаемости метаданных Kotlin 2.1.20. Не тестировалось. Это и есть настоящее основание отложить Molecule (§6.1); прежний довод про «`:core:*` без Compose» снят как опирающийся на несуществующее правило.
4. **Разрешено частично — операционная часть закрыта.** Проверено по самому артефакту, а не по пересказу: POM `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-desktop:2.8.3` на repo1.maven.org объявляет `runtime-desktop 1.6.11`, `runtime-saveable-desktop 1.6.11`, `ui-desktop 1.6.11`, `kotlin-stdlib 1.9.24`, `lifecycle-viewmodel-desktop 2.8.5` **[V]**. То есть артефакт реален и **Compose за 1.7.3 не тянет** — единственный вопрос, который влиял на решение, снят. **Остаётся нерешённым только библиографическое расхождение:** `kotlinlang.org` называет для CMP 1.7.3 версию 2.8.3, а CHANGELOG самого CMP в разделе 1.7.3 строки про lifecycle не содержит (только Runtime/UI/Foundation 1.7.6, Material3 1.3.1). На вердикт §6.1 («не брать по существу — на desktop нет ни configuration change, ни process death») это не влияет.
5. **Многооконность и `viewModel()`** (один `ViewModelStoreOwner` на окно или на приложение под 1.7.3) — не проверялось. Актуально, только если появится второе окно.

**Пробелы в доказательствах:**
6. **Ноль подтверждённых миграций прочь от Circuit, Molecule, MVIKotlin, Orbit MVI, FlowRedux, Ballast и TCA.** По критерию брифа («паттерн без зафиксированного отказа — недоисследованный паттерн») их разделы «критика» неполны. Особо: retrospective Рода Шмидта про TCA — **не** запись о миграции команды, а личная рекомендация; истории Lapse и Arc до первоисточника не доведены.
7. **Мотив ухода Twine с Decompose — частично разрешён.** Дельта проверена заново через API: PR #1171, merged 2025-06-28, 88 файлов, +3157/−4370 = чистое **−1213** **[V]**. Причина словами по-прежнему не названа, **но названо направление**, и это информативно: заголовок — «Migrate from Decompose to **Compose Navigation and ViewModel**», тело PR перечисляет «Add ViewModel and Navigation dependencies… Remove decompose and essenty dependencies» **[V]**. То есть уход был не «в самописное», а **на официальный CMP-стек** (androidx lifecycle multiplatform + Compose Navigation). Для §6.1 это живой прецедент того самого выбора, который мы там обсуждаем; на наш вердикт («ViewModel не брать — на desktop нет configuration change и process death») он не влияет, но показывает, что путь рабочий. Поста автора с рационале по-прежнему не найдено.
8. **Публичного постмортема «мы перемодуляризировались и слили модули обратно» не найдено.** Ближайшее — оговорка самого Google и репо-консолидация Uber. Отсутствие результата тоже информативно: об успехах публикуют охотнее, чем об откатах.
9. **Механические проверки границ (module-graph-assert, ArchUnit/Konsist, кастомные Gradle-правила) не найдены** ни в Tivi, ни в animeko, ни в ab-download-manager, ни в KotlinConf app. Везде — Gradle-classpath + code review. Отсутствие доказательства ≠ доказательство отсутствия, но приём, который мы берём на шаге 0, у крупных Kotlin-проектов **не наблюдается**.
10. **ArchUnit #487** («Cannot test a layered architecture in a multi module projet») — закрыт **по неактивности** 2021-01-27, а не решён. Механизм (байткод с test classpath) — **[И]**, не цитата из документации. Преимущество Konsist по источникам — сильнее.

**Недоступные источники:**
11. **Perplexity Desktop** — официальный help-center отдаёт **HTTP 403**; ни teardown'а, ни вендорского заявления. Все архитектурные утверждения о нём из отчёта удалены.
12. **Внутренности JetBrains Toolbox** не публичны; кейс 2021 года описывает только миграцию языка/UI. Как доказательство слоистости не используется.
13. **Kimi Work** — единственный источник teardown-репозиторий на **4★**, один наблюдатель; вендорского архитектурного документа нет. `apps/kimi-desktop` в открытом `MoonshotAI/kimi-code` не открывался — связь с отгружаемым приложением неизвестна.
14. **ChatGPT Windows = Electron** держится на **одном** аргументе (размер установки ~260 МБ) в пересказе. UI-фреймворк macOS-приложения (SwiftUI vs AppKit) не установлен.
15. **Microsoft Copilot = WebView2-PWA** — только teardown'ы Windows Latest / Windows News, и это **противоречит** собственному слову Microsoft («native»). Сколько из Build-2025 дизайна Windows MCP отгружено дефолтом, а не осталось Insider-only — не установлено.
16. **Slack Engineering о Circuit** — `slack.engineering/introducing-circuit/` отдаёт **404**; мотивация цитируется только по докам Circuit. Страница доклада droidcon 2023 тоже 404, транскрипт не читался.
17. **Manuel Vivo, «One-off event antipatterns»** — Medium 403. Всё, что ему приписывается, взято из пересказа Google и из ответной статьи; счётчики аплодисментов — вторичны.
18. Medium-хосты (ASOS, Trendyol, Revolut, proandroiddev) массово отдают 403. Цифры оттуда в отчёт **не включены**.

**Сознательно за скоупом (названо брифом, но не исследовано — с причиной):**
22. **.NET MAUI — исключён намеренно.** Бриф §3.B называет его среди вендорских источников; в отчёт он не вошёл и не должен был войти молча. Причина: MAUI — мобильно-первый кросс-платформенный тулкит, чья слоистость наследуется у XAML/MVVM, а на десктопе он не даёт ни одного механизма принуждения границ, отличного от .NET-сборок (уже покрыто рангом 1 в §2.1). Первофирменный XAML-канон в отчёте представлен **WinUI + MVVM Toolkit** (см. строку в §3) — он ближе к Compose Desktop по классу задачи и покрывает ровно тот вопрос, ради которого брались вендоры: связь sibling-holder'ов и владение состоянием.
23. **WinUI/MVVM Toolkit взят частично.** Прочитаны и процитированы `ObservableObject`/`ObservableRecipient`/`IMessenger`/`IsActive` **[V]**. **Не** искалось: публичный разбор «god ViewModel» в WinUI-приложениях Microsoft и правила деления VM по размеру. Предварительный вывод (**[И]**): такого правила у Microsoft нет — что усиливает §6.0, а не ослабляет, потому что означает: ни один из трёх первофирменных MVVM-канонов (Google, Apple, Microsoft) не отвечает на вопрос «когда holder слишком большой».
24. **Apple SwiftData и SPM как единица модуляризации** — SPM разобран (§2.2, локальные пакеты Apple + Ice Cubes), SwiftData **нет**: у нас нет ORM и нет намерения его заводить, `KnowledgeIndex` файловый. Строка в матрице (Enchanted) — упоминание стека, а не архитектурное доказательство.

**Специфичное для нас, не проверявшееся ни в одном источнике:**
19. **Ни один первоисточник по Compose Desktop** не был прочитан по двум самым операционным рекомендациям. Совет по батчингу токенов опирается на SwiftUI/TCA/Tripadvisor-доказательства и **не проверен** против семантики Compose snapshot (`conflate`/`sample`/`derivedStateOf`/батчинг рекомпозиции). Совет «замерить рендер длинного транскрипта» экстраполирован с `NSTextView`, а не с issue-трекера Compose Multiplatform. И то и другое надо мерить на нашей машине. **Смягчающее обстоятельство, добавленное в этой редакции:** вопрос перестал быть срочным — стриминга в кодовой базе нет вообще (все вызовы `stream = false`), поэтому §6.5 выведена из плана миграции, а `conflate()` там записан как **дефолт до первого замера**, а не как измеренный результат.
20. **Пропорция эфемерного UI-состояния в `ChatState`** оценена по грепу деклараций (~60 `var … by mutableStateOf` в 9–14 кластерах), а не полным чтением 1617 строк. Точная доля «поднять вниз в `remember`» не измерена — это первое, что стоит померить перед шагом 4, потому что это самый дешёвый разрез: чистое удаление без доменного риска.
21. **NiA PR #1277 «Clean architecture experiment [DO NOT MERGE]»** (74 файла, закрыт без merge) — тимлид Google оценил инверсию домена в «around 1 hour's work» и **сознательно не влил**. Это ровно наш случай (`:core:data → :core:domain` через порты). Причины отказа Google не разобраны — стоит прочитать перед тем, как ужесточать инверсию дальше.

---

## 8. Источники по утверждениям

Раздел добавлен во второй редакции. Причина: в первой на ~200 меток **[V]** приходилось 11 ссылок, то есть
проверить утверждение читатель не мог — а неподкреплённые побайтовые замеры чужих репозиториев это самый
опасный класс утверждений в документе. Порядок приоритета — сперва то, на чём стоит §6.

**Все цифры пересчитаны заново 2026-08-26** (звёзды и `pushed_at` — через GitHub API, строки и байты —
загрузкой сырого файла и подсчётом). Расхождения с первой редакцией отмечены явно.

### 8.1 Замеры файлов — построчно, со ссылкой на конкретный файл

| Утверждение | Ссылка (ветка `main`, если не указано иное) | Пересчёт 2026-08-26 |
|---|---|---|
| **Askimo `Main.kt` = 2347** — контрольная группа §6.0 | https://github.com/askimo-ai/askimo/blob/main/desktop/src/main/kotlin/io/askimo/desktop/Main.kt | **2347 строк / 124 705 байт** ✓ |
| **Askimo `ChatViewModel.kt` = 1476** — там же | https://github.com/askimo-ai/askimo/blob/main/desktop-shared/src/main/kotlin/io/askimo/ui/chat/ChatViewModel.kt | **1476 строк / 59 345 байт** ✓ |
| Askimo — репозиторий и модули | https://github.com/askimo-ai/askimo | **367★**, push **2026-08-25**, создан 2025-08-05; модули верхнего уровня: `cli`, `desktop`, `desktop-shared`, `shared`, `detekt-rules` ✓ (пять, как и заявлено) |
| **VS Code `chatInputPart.ts` = 5231** | https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/input/chatInputPart.ts | **5231 строка / 248 026 байт** ✓ (в первой редакции путь не приводился; фактический — `…/chat/browser/widget/input/`) |
| **VS Code `terminalInstance.ts` = 126 430 байт** | https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/terminal/browser/terminalInstance.ts | **126 430 байт / 2984 строки** ✓ |
| **Zed `editor.rs` = 12 781** | https://github.com/zed-industries/zed/blob/main/crates/editor/src/editor.rs | **12 781 строка / 468 449 байт** ✓ |
| **Zed 250 workspace members** | https://github.com/zed-industries/zed/blob/main/Cargo.toml | **250** записей в `[workspace] members` ✓ |
| **Open WebUI `Chat.svelte` = 4663** | https://github.com/open-webui/open-webui/blob/main/src/lib/components/chat/Chat.svelte | **4663 строки / 132 856 байт** ✓ |
| **Open WebUI `middleware.py` = 6335** | https://github.com/open-webui/open-webui/blob/main/backend/open_webui/utils/middleware.py | **6335 строк / 283 433 байта** ✓ |
| **Jan `ChatInput.tsx` = 2650** | https://github.com/menloresearch/jan/blob/main/web-app/src/containers/ChatInput.tsx | **2650 строк / 98 100 байт** на `main` ✓. Оговорка: на ветке `dev` файл уже **2886** строк — тезис §4.1 со временем усиливается, а не слабеет |
| **Continue `sessionSlice.ts` = 1097** | https://github.com/continuedev/continue/blob/main/gui/src/redux/slices/sessionSlice.ts | **1097 строк / 35 077 байт** ✓ |
| **Cline `McpHub.ts` = 84 КБ** | https://github.com/cline/cline/blob/main/apps/vscode/src/services/mcp/McpHub.ts | **84 155 байт / 2195 строк** ✓ |
| **Cline `message-translator.ts` = 99 КБ** | https://github.com/cline/cline/blob/main/apps/vscode/src/sdk/message-translator.ts | **99 575 байт / 2779 строк** ✓ |
| **Lobe `aiAgent/index.ts` = 267 КБ** | https://github.com/lobehub/lobehub/blob/main/apps/server/src/services/aiAgent/index.ts | **253 290 байт (247 КБ) / 5819 строк** — первая редакция **завышала**, в тексте §3 исправлено |
| **AnythingLLM `getLLMProvider` в файле на 762 строки** | https://github.com/Mintplex-Labs/anything-llm/blob/master/server/utils/helpers/index.js | **762 строки / 29 366 байт** ✓ |
| **Целевой проект: `App.kt` / `ChatState.kt`** | локально, ветка `paper-terracotta-redesign` | **2310** (49 `@Composable`, из них 14 RAG/eval, 4 диалога) **/ 1617** ✓ |

### 8.2 Репозитории — звёзды и свежесть на 2026-08-26 (GitHub API)

| Репозиторий | ★ | Последний push | Примечание |
|---|---|---|---|
| https://github.com/zed-industries/zed | 89 256 | 2026-08-26 | — |
| https://github.com/microsoft/vscode | 189 620 | 2026-08-26 | — |
| https://github.com/deepseek-ai/deepseek-harness | 197 481 | 2026-08-21 | создан **2026-08-13** — 197k★ за 13 дней |
| https://github.com/ollama/ollama | 179 491 | 2026-08-26 | — |
| https://github.com/open-webui/open-webui | 150 005 | 2026-08-26 | сам репозиторий жив; мёртв `open-webui/pipelines` (§4.8) |
| https://github.com/lobehub/lobehub | 82 018 | 2026-08-26 | бывший `lobe-chat` |
| https://github.com/cline/cline | 66 888 | 2026-08-26 | — |
| https://github.com/Mintplex-Labs/anything-llm | 65 235 | 2026-08-26 | — |
| https://github.com/CherryHQ/cherry-studio | 51 095 | 2026-08-26 | — |
| https://github.com/menloresearch/jan | 44 191 | 2026-08-26 | — |
| https://github.com/danny-avila/LibreChat | 42 480 | 2026-08-26 | — |
| https://github.com/chatboxai/chatbox | 41 574 | 2026-08-14 | — |
| https://github.com/continuedev/continue | 35 642 | 2026-08-26 | — |
| https://github.com/mihonapp/mihon | 23 131 | 2026-08-26 | — |
| https://github.com/JetBrains/intellij-community | 20 488 | 2026-08-26 | — |
| https://github.com/microsoft/vscode-copilot-chat | 9975 | 2026-05-20 | **`archived: true`** — подтверждает §2.3 (вернули в монорепо) |
| https://github.com/Dimillian/IceCubesApp | 7048 | 2026-08-25 | — |
| https://github.com/AugustDev/enchanted | 5999 | 2026-07-07 | — |
| https://github.com/anysphere/priompt | 2850 | 2025-10-15 | **HEAD default-ветки — 2025-02-03** ✓ (§6.9: идея жива, библиотека нет) |
| https://github.com/JetpackDuba/Gitnuro | 2745 | 2026-08-25 | — |
| https://github.com/msasikanth/twine | 2386 | 2026-08-26 | — |
| https://github.com/autonomousapps/dependency-analysis-gradle-plugin | 2178 | 2026-08-26 | v**3.19.1** от 2026-08-26 |
| https://github.com/lmstudio-ai/lmstudio-js | 1761 | 2026-08-21 | — |
| https://github.com/LemonAppDev/konsist | 1716 | 2026-08-17 | **релиз 0.17.3 от 2024-12-08**; на default-ветке один коммит с тех пор (2026-01-08) — см. §7.1 |
| https://github.com/Renset/macai | 915 | 2026-08-16 | — |
| https://github.com/jraska/modules-graph-assert | 639 | 2026-08-24 | релиз **2.9.1** от 2026-04-12 |
| https://github.com/arkivanov/Essenty | 584 | 2026-08-09 | один мейнтейнер — key-person risk (§6.1) |
| https://github.com/askimo-ai/askimo | 367 | 2026-08-25 | контрольная группа §6.0 |
| https://github.com/vRallev/app-platform | 321 | 2026-08-23 | — |

### 8.3 Записи о миграциях — PR и issue целиком (проверено через API)

| Ссылка | Статус | Цифры |
|---|---|---|
| https://github.com/mihonapp/mihon/pull/3594 — «Migrate to AndroidX ViewModel from Voyager ScreenModel» | **merged 2026-07-15** | 91 файл, **+1503 / −1091** ✓ |
| https://github.com/msasikanth/twine/pull/1171 — «Migrate from Decompose to Compose Navigation and ViewModel» | **merged 2025-06-28** | 88 файлов, **+3157 / −4370 = −1213** ✓. Тело PR: «Remove decompose and essenty dependencies» — направление ухода названо (§7.7) |
| https://github.com/android/nowinandroid/pull/1277 — «Clean architecture experiment [DO NOT MERGE]» | **CLOSED**, не влит | 74 файла ✓ |
| https://github.com/microsoft/vscode/issues/227752 — «Do not disable `local/code-import-patterns` ESLint rule» | **CLOSED / COMPLETED, 2025-03-24** ✓ | подтверждает поправку в §2.1: долг погашен, то есть ссылка доказывает обратное популярному пересказу |

### 8.4 Первофирменные документы, на которых стоят решения §5–§6

| Утверждение | Ссылка |
|---|---|
| **Apple, Observation** — «updating views based on changes to the observable properties that a view's `body` reads instead of any property changes that occur to an observable object, which can help improve your app's performance» (опора §5, §6.11(1)) | https://developer.apple.com/documentation/swiftui/migrating-from-the-observable-object-protocol-to-the-observable-macro — прочитано через `developer.apple.com/tutorials/data/documentation/swiftui/migrating-from-the-observable-object-protocol-to-the-observable-macro.json` (HTML рендерится скриптом и в текст не отдаётся) |
| **Microsoft, MVVM Toolkit / `ObservableRecipient`** — `IMessenger`, `IsActive`, «`OnDeactivated` automatically unregisters the current instance from all registered messages… safe to collect without the risk of memory leaks» (опора §1.4A, строка WinUI в §3) | https://learn.microsoft.com/en-us/dotnet/communitytoolkit/mvvm/observablerecipient |
| **Microsoft, WinUI + MVVM Toolkit** — вводный первофирменный туториал | https://learn.microsoft.com/en-us/windows/apps/tutorials/winui-mvvm-toolkit/intro |
| **Google, state holders** — правило времени жизни, анти-паттерн выписан кодом (опора §6.3) | https://developer.android.com/topic/architecture/ui-layer/stateholders |
| **Google, domain layer** — «forces you to add use cases even when they are just simple function calls» (опора §6.4, шаг 5) | https://developer.android.com/topic/architecture/domain-layer |
| **Decompose** — «UI is optional and is pluggable from outside…», «Never pass parent's ComponentContext to children» (опора §6.3, правило 1) | https://arkivanov.github.io/Decompose/component/overview/ |
| **Circuit, presenter-patterns** — Presenter Decomposition / Composite Presenters / StateProducer + три антипаттерна (опора §6.6 и метода шага 4) | https://slackhq.github.io/circuit/docs/presenter/ |
| **Cherry Studio, слоистость рендерера** — «Four layers. Dependencies may only flow **downward**»; `feature → feature` и `shared → feature` запрещены **как категории**, «so one `import/no-restricted-paths` rule enforces them» (эталон §4.4 и §6.2) | https://github.com/CherryHQ/cherry-studio/blob/main/docs/references/architecture/renderer.md |
| **Cherry Studio, `@shared`** — два инварианта слоя (cross-process; no mutable runtime state) | https://github.com/CherryHQ/cherry-studio/blob/main/docs/references/architecture/shared-layer.md |
| **Cline, Controller/Task** — «Extension entry point (extension.ts) -> webview -> controller -> task»; `controller/` = «Handles webview messages and task management», `task/` = «Executes API requests and tool operations» (опора §1.4B и кластера 4 в §6.3) | https://github.com/cline/cline/blob/main/apps/vscode/src/core/README.md |
| **App Platform** — «no other module but the final application module is allowed to depend on `:impl` modules» (ранг 2 в §2.1) | https://vrallev.github.io/app-platform/module-structure/ |
| **VS Code, Source Code Organization** — слой × среда выполнения, запрет входа в `contrib` (§2.2) | https://github.com/microsoft/vscode/wiki/Source-Code-Organization |
| **DAGP, version floor** — «[Breaking] minimum supported version of Gradle is now 8.11» (v3.0.0; опора шага 0г) | https://github.com/autonomousapps/dependency-analysis-gradle-plugin/blob/main/CHANGELOG.md (строка 223) |
| **androidx lifecycle под CMP** — POM с floor'ами Compose 1.6.11 / kotlin-stdlib 1.9.24 (опора §6.1 и §7.4) | https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-compose-desktop/2.8.3/lifecycle-viewmodel-compose-desktop-2.8.3.pom |
| **Claude Desktop, enterprise-конфигурация** — `HKLM\SOFTWARE\Policies\Claude`, `allowedWorkspaceFolders`, приоритет над in-app allowlist (строка Claude Desktop в §3) | https://support.claude.com/en/articles/12622667-enterprise-configuration-for-claude-desktop и https://support.claude.com/en/articles/12622703-deploy-claude-desktop-for-windows |
| **ChatGPT macOS — беседы открытым текстом до v1.2024.171** (опора §6.10) | https://pvieito.com/2024/07/chatgpt-unprotected-conversations (первоисточник — сам обнаруживший, Pedro José Pereira Vieito) + https://www.macrumors.com/2024/07/04/chatgpt-mac-app-stored-chats-plain-text/ |
| **Целевой проект** — гейт графа, detekt, отсутствие CI, `stream = false`, неатомарная запись | локальные файлы: `build.gradle.kts`, `build-logic/src/main/kotlin/advent.kotlin-jvm.gradle.kts`, `config/detekt/detekt.yml`, `core/data/…/LocalLlmClient.kt:41,135`, `core/data/…/FileConversationRepository.kt:33-35`, `core/data/…/Files.kt:17-20`, `tools/cli/build.gradle.kts` |

### 8.5 Что осталось без ссылки — и потому понижено в статусе

Ниже — утверждения, которые в первой редакции носили **[V]**, но первоисточник по ним при этой ревизии
**не открывался**. Метка снижена до **[И]**; использовать их как основание решения нельзя.

1. **Kimi Work** — «CPython 3.12, Node v24.15.0, uv», четыре дерева, список native-модулей (`node-pty`, `sqlite-vec`, `better-sqlite3`, `koffi`). Единственный источник — неназванный teardown-репозиторий на 4★ (§7.13). **[И]**, один наблюдатель.
2. **Claude Desktop** — «Electron + собственный Node.js runtime», «Windows = MSIX x64/arm64», схема `.mcpb`/`.dxt` (`server.type`, `user_config.sensitive` → keychain). Enterprise-часть подтверждена (§8.4), **упаковка и рантайм — нет**. **[И]**.
3. **Claude Cowork** — цитата «any failure during VM startup made Cowork unusable». Это единственный артефакт «мы передумали» в §4.6, и он **без ссылки** → **[И]** до подтверждения. Вывод §4.6 при этом опирается ещё на три независимых случая (VS Code Copilot Chat, NiA, Uber) и без цитаты Anthropic не рушится.
4. **Cursor** — схема хранения (`cursorDiskKV`, `composerData:<id>`, `bubbleId:<conv>:<msg>`). Честно помечено «третьи лица» уже в первой редакции; §6.10 переписан так, что рекомендация на этом **больше не стоит** (см. verified-находку про неатомарную запись).
5. **Структурные подсчёты чужих репозиториев** — IntelliJ «2246 JPS-модулей», VS Code «622 файла `createDecorator` / 423 `registerSingleton`», «107 директорий `platform/`, 100 `contrib/`, 25 `terminalContrib/`», «54 файла в `.eslint-plugin-local`», Lobe «93 пакета, 85 директорий провайдеров», Cherry Studio PR #13871/#16415/#13340/#13295. Порядок величин правдоподобен и согласуется с прочитанными файлами, но **пересчёт не делался** → **[И]**. Ни один вывод §6 на них не стоит: они иллюстрируют масштаб, а не обосновывают шаг.
6. **Version floor'ы Circuit / Decompose / Molecule** (Circuit 0.27.1 → `kotlin 2.1.10` / `compose-jb 1.7.3`, head → `kotlin 2.4.10` / `jb-compose 1.11.1`; Decompose 3.3.0 → jetbrainsCompose 1.7.0 / kotlin 2.1.0, 3.4.0 → Compose 1.8.2; Molecule 2.1.0 → Kotlin 2.1.20) — при этой ревизии заново **не** открывались → **[И]**. На вердикты §6.1 это не влияет: все три отвергнуты **и** по существу (Circuit требует кодогенерирующий DI, Decompose — вторую модель навигации и `Value<T>`, Molecule бессмыслен без Circuit), а не только по версиям.
7. **Практикующие цитаты без URL** — droidcon Berlin 2025 (VM 1376/694/687), TCA-бенчмарк (319 против 579 LOC, 1.1 с против 32.4 с), Uber (20+ репо → <5 мин), Dropbox («6+ months… no idea how to create a new module»), Touchlab («api deps appear on compile classpaths»), контрибьютор Zed про 32 ГБ RAM, Apple WWDC26 session 8006. **[И]**. Из них на решение влияет только Touchlab (обоснование шага 0г) — и то как иллюстрация к механизму, который DAGP документирует сам.
