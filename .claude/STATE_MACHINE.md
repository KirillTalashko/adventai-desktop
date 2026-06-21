# Состояние задачи — конечный автомат + многоагентный оркестратор (Дни 13 + 15)

Задача как конечный автомат с **агентом на каждую стадию**. Состояние — **данные**, переходы разрешает
**код, а не LLM**. Стиль взаимодействия — как у Claude Code: думает под капотом, уточняет, предлагает
варианты выбором, гейтит шаги документами, показывает статус снизу.

`INTAKE → PLANNING → EXECUTION → VALIDATION → DONE`

## Состояние = данные

`domain/TaskStateMachine.kt`:

```kotlin
enum class TaskState { INTAKE, PLANNING, EXECUTION, VALIDATION, DONE }   // у каждого title + verb (статус)
enum class Awaiting  { NONE, ANSWER, CHOICE, DOCUMENT }                  // чего ждём от пользователя

data class TaskContext(
    task, state, awaiting,
    prompt,                 // текущий запрос пользователю (вопросы / какой документ)
    options, approach,      // 4 варианта подхода и выбранный
    plan, step, done, docs, // план, текущий шаг, сделанное, приложенные документы
    note, paused
) { val total; val current; val expectedAction; fun renderStateBlock() }
```

**Этап** (`state`), **текущий шаг** (`step`/`total`/`current`), **ожидаемое действие** (`expectedAction`)
— три обязательных поля задания. `awaiting` драйвит контрол в UI.

## Переходы разрешает код (День 15 — контролируемый жизненный цикл)

```kotlin
val TRANSITIONS = mapOf(
    INTAKE     to setOf(PLANNING),
    PLANNING   to setOf(EXECUTION),
    EXECUTION  to setOf(VALIDATION, PLANNING),
    VALIDATION to setOf(DONE, EXECUTION),
    DONE       to emptySet()
)
fun TaskContext.canTransition(target)  = target in TRANSITIONS[state].orEmpty()   // разрешён ли
fun TaskContext.tryTransition(target)  = if (canTransition(target)) copy(state=target) else null  // безопасно
fun TaskContext.transitionTo(target)   = tryTransition(target) ?: error("Переход $state → $target запрещён")
```

**Гарантии Дня 15:**
- **Допустимые состояния** — `TaskState`; **разрешённые переходы** — `TRANSITIONS` (единственный источник истины).
- **Нельзя перепрыгнуть этап**: FSM двигает только код через `transitionTo`; LLM состоянием не управляет
  (антипаттерн «решения за LLM в тексте» исключён). Поэтому структурно невозможно:
  - **реализация до плана** — `EXECUTION` достижим только из `PLANNING` после `buildPlan` (план непустой);
  - **финал без проверки** — `DONE` достижим только из `VALIDATION` (`EXECUTION`/`PLANNING` → `DONE` запрещён).
- **Реакция на попытку пропустить**: пользователь просит «сразу финал/без проверки» → запрос идёт в
  `assist`, который по `[STATE]` (там описан жизненный цикл) **объясняет**, что этапы нельзя перепрыгивать,
  и называет ближайший допустимый шаг. Само правило — в коде, промпт лишь объясняет отказ.
- **Пауза/продолжение**: состояние в `conversations/<id>.json`; после перезапуска цикл продолжается с того
  же легального этапа.

## Агент на каждую стадию

`domain/TaskOrchestrator.kt` — свой system prompt + отдельный вызов API на стадию. Управляющие теги
парсятся для переходов и **вырезаются** из текста (`clean()`) — пользователь видит чистый результат
(«думает под капотом»).

| Метод | Агент | Сигнал | Что делает код |
|---|---|---|---|
| `intake` | интервьюер | `[SIMPLE]` / `[READY]` / `[ASK]` | SIMPLE → ответ и снять задачу; READY → PLANNING; иначе `Awaiting.ANSWER` |
| `proposeOptions` | планировщик (варианты) | `[OPTIONS]…[/OPTIONS]` | 4 подхода → `Awaiting.CHOICE` |
| `buildPlan` | планировщик (план) | `[PLAN]…[/PLAN]` | план под выбранный подход → EXECUTION |
| `step` (EXECUTION) | исполнитель | `[STEP_RESULT]` (+опц. `[NEED_DOC]`) | шаг ВСЕГДА завершается → `step++`; нужный документ уходит в `pending` (не блокирует) |
| `step` (VALIDATION) | валидатор | `[VERDICT] pass\|revise:…` | pass → DONE; revise → EXECUTION (note=feedback) |

Каждому инжектится `renderStateBlock()` (`[STATE]`: задача, подход, план, сделано, документы, текущий шаг)
— отсюда **продолжение без повторных объяснений**.

## Взаимодействие (ChatState) — без кнопки запуска

Задача — часть полноценного агента: отдельной кнопки «Начать задачу» нет. Единая точка — `submitComposer`:
нет задачи → `startTask` (авто-старт с первого сообщения); `Awaiting.ANSWER` → `answerTask`;
`Awaiting.CHOICE` → `chooseApproach` (печать = свой вариант); завершено → новая задача; иначе —
`commentDuringTask` (реплика + ход стадии). Инлайн-кнопка «Продолжить» → `advanceTask`.
`runStage()` — общий запуск стадии (вызвать оркестратор, добавить чистый ответ, сохранить); `cancel`
из `[SIMPLE]` снимает режим задачи.

## Документы

**Исполнение НИКОГДА не блокируется документами** (иначе агент зацикливался на шаге 1). Исполнитель
всегда завершает шаг и `step++`, а нужный файл мягко кладёт в `pending` (виден в `[STATE]` и в диалоге
«📎 Позже приложите…»). Документы догружаются в любой момент и проверяются на этапе VALIDATION.

Реальная загрузка: кнопка «+» в композере → AWT `FileDialog` (фильтр Word/PDF) → `DocStore` копирует в
`accounts/<id>/docs/`; запись «метка → файл» кладётся в `TaskContext.docs` и `[STATE]`. (Жёсткий
`Awaiting.DOCUMENT`-гейт с «Приложить файл/позже» в коде остался, но исполнением больше не вызывается.)

**Приложу позже** (`deferDocument`): шаг идёт дальше, документ попадает в `TaskContext.pending` (виден
валидатору в `[STATE]` как незавершённое) и в **рабочую память** (`addConstraint` «Дозагрузить
документ: …») — агент помнит, что нужно дозагрузить для полной картины.

## Pause & Resume

`paused` + всё состояние в `conversations/<id>.json`. После перезапуска зона задачи показывает текущий
этап/шаг и нужный контрол; продолжение инжектит `[STATE]` — без повторов.

## UI (День 13, как Claude Code) — без панелей

- Панелей (ни сверху, ни снизу) НЕТ. Всё взаимодействие — в потоке диалога.
- **Статус-строка** под последним сообщением: эмблема `✳` (или спиннер), этап (думает/планирует/
  исполняет/проверяет/ждёт…), время операции, токены промпта (`ui/TaskPanel.kt::TaskStatusLine`).
- **Инлайн-действия** в ленте чата (`TaskInlineActions`): Продолжить / 4 варианта (клик или свой вариант
  текстом в композере) / Приложить файл · Приложу позже.
- Кнопка **«+»** в композере — приложить Word/PDF, когда агент просит документ.
- План/шаги/ответы — обычными сообщениями; управляющие теги скрыты (`clean()`).

## Карта файлов

- `domain/TaskStateMachine.kt` — `TaskState`, `Awaiting`, `TaskContext`, `transitionTo`, `renderStateBlock`.
- `domain/TaskOrchestrator.kt` — 5 стадийных агентов, парсинг сигналов, очистка тегов, один ход.
- `domain/Model.kt` — `Conversation.task: TaskContext?`.
- `data/Dto.kt` — `TaskContextDto` (+ awaiting/options/approach/docs) и сериализация.
- `data/DocStore.kt` — копирование приложенных файлов в `docs/`.
- `ui/ChatState.kt` — `submitComposer/startTask/advanceTask/answerTask/chooseApproach/commentDuringTask/
  provideDocument/deferDocument/resetTask`; `opStartedAtMs`, `lastOpSeconds`, `lastPromptTokens`.
- `ui/TaskPanel.kt` — `TaskStatusLine`, `TaskInlineActions`, `AttachButton` (всё в потоке диалога).
- `ui/App.kt` — статус+действия трейлингом в ленте чата; «+» в композере; единый ввод `submitComposer`.

## Инварианты — реализовано (День 14)

Двойная защита (инжект правил в промпт + пост-проверка стражем, перегенерация в отказ). Подробности —
[INVARIANTS.md](INVARIANTS.md).

## Рой агентов (итог недели)

Восемь специализированных ролей вокруг автомата: интервьюер · планировщик (варианты) · планировщик (план) ·
исполнитель · валидатор · ассистент по задаче · `MemoryExtractor` (память) · `InvariantGuard` (страж).
Дни 11–15 соединены: память (11) + персонализация (12) + автомат задачи (13) + инварианты (14) +
контролируемые переходы (15).
