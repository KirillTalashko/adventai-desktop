# Состояние и инварианты (stateful-агент) — роадмап

Концепты недели «Memory and State». В desktop **пока не реализовано** — следующий шаг к «полноценному агенту».

## State machine — конечный автомат задачи

Задача как автомат: `PLANNING → EXECUTION → VALIDATION → DONE`. Состояние — это **данные**:

```kotlin
data class TaskContext(
    val task: String, val state: TaskState,
    val step: Int, val total: Int,
    val plan: List<String>, val done: List<String>, val current: String
)
```

Переходы разрешает **код, а не LLM**:
```kotlin
val transitions = mapOf(
    PLANNING to listOf(EXECUTION),
    EXECUTION to listOf(VALIDATION, PLANNING),
    VALIDATION to listOf(DONE, EXECUTION),
    DONE to emptyList()
)
fun transition(ctx: TaskContext, target: TaskState): TaskContext {
    require(target in transitions[ctx.state]!!) { "${ctx.state} → $target запрещён" }
    return ctx.copy(state = target)
}
```
Состояние инжектится в промпт блоком `[STATE] …`.

## Инварианты — правила, которые нельзя нарушить

```kotlin
abstract class Invariant { abstract val description: String; abstract fun check(response: String): Boolean }
```
**Двойная защита**: инвариант инжектится в промпт **и** проверяется после ответа; при нарушении — `retry`.
```kotlin
when (val r = validate(response, invariants)) {
    is Pass -> send(r.response)
    is Fail -> retry(query, r.violations)
}
```

## Pause & Resume

Уже есть по факту: состояние = данные на диске (`conversations/`, `working/`), диалог продолжается между
сессиями. Останется добавить сюда `TaskContext`.

## Куда добавить в desktop

- `domain/`: `TaskState`, `TaskContext`, `transition()`, `Invariant`, `validate()`.
- Хранить состояние задачи рядом с `Conversation.derived` (новое поле) → `conversations/<id>.json`.
- Пост-проверка инвариантов — в `VisaAgent.ask` после ответа модели (retry при Fail).
- UI: индикатор текущего этапа/шага и список активных инвариантов.
