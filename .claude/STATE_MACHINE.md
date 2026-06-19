# Состояние задачи — конечный автомат + многоагентный оркестратор (День 13)

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

## Переходы разрешает код

```kotlin
val TRANSITIONS = mapOf(
    INTAKE     to setOf(PLANNING),
    PLANNING   to setOf(EXECUTION),
    EXECUTION  to setOf(VALIDATION, PLANNING),
    VALIDATION to setOf(DONE, EXECUTION),
    DONE       to emptySet()
)
fun TaskContext.transitionTo(target) { require(target in TRANSITIONS[state]!!); copy(state = target) }
```

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

## Инварианты — расширение (роадмап)

Двойная защита (инжект правил в промпт + пост-проверка ответа, `retry` при нарушении). Точка вставки —
после ответа стадийного агента в `TaskOrchestrator`.
