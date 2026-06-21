package com.example.adventdesktop.domain

/** Этап задачи — состояния конечного автомата (День 13). `verb` — для статус-строки («агент …»). */
enum class TaskState(val title: String, val verb: String) {
    INTAKE("Уточнение", "уточняет"),
    PLANNING("Планирование", "планирует"),
    EXECUTION("Выполнение", "исполняет"),
    VALIDATION("Проверка", "проверяет"),
    DONE("Готово", "готово")
}

/** Чего автомат ждёт от пользователя на текущем ходу (драйвит контрол в UI). */
enum class Awaiting { NONE, ANSWER, CHOICE, DOCUMENT }

/**
 * Состояние задачи как ДАННЫЕ. Переходы между этапами разрешает КОД ([transitionTo] по [TRANSITIONS]),
 * а не LLM — модель лишь генерирует содержимое стадии. Кладётся в `conversations/<id>.json`, поэтому
 * пауза/возобновление почти бесплатны, а [renderStateBlock] инжектится агенту → продолжение без
 * повторных объяснений.
 */
data class TaskContext(
    val task: String = "",
    val state: TaskState = TaskState.INTAKE,
    val awaiting: Awaiting = Awaiting.NONE,
    val prompt: String = "",                  // текущий запрос пользователю (вопросы / какой документ)
    val options: List<String> = emptyList(),  // варианты подхода для CHOICE
    val approach: String = "",                // выбранный подход к решению
    val plan: List<String> = emptyList(),
    val step: Int = 0,
    val done: List<String> = emptyList(),
    val docs: List<String> = emptyList(),     // приложенные документы: «метка → файл»
    val pending: List<String> = emptyList(),  // документы, отложенные «приложу позже» (надо дозагрузить)
    val note: String = "",                    // замечание валидатора при возврате на доработку
    val revises: Int = 0,                     // сколько раз валидатор уже отправлял на доработку (лимит петли)
    val offer: String = "",                   // активное предложение доп-активности (агент-разведчик); пусто = нет
    val interviewOffered: Boolean = false,    // пробное собеседование уже предлагали (не повторять)
    val paused: Boolean = false
) {
    val total: Int get() = plan.size
    val current: String get() = plan.getOrNull(step).orEmpty()
    val isDone: Boolean get() = state == TaskState.DONE
    val isActive: Boolean get() = task.isNotBlank()

    /** Ожидаемое действие — выводится из состояния (для статус-строки, action-бара и [renderStateBlock]). */
    val expectedAction: String
        get() = when {
            isDone -> "Задача завершена"
            awaiting == Awaiting.ANSWER -> "Ответьте на уточняющие вопросы"
            awaiting == Awaiting.CHOICE -> "Выберите подход к решению"
            awaiting == Awaiting.DOCUMENT -> "Приложите документ: $prompt"
            state == TaskState.INTAKE -> "Собрать вводные по задаче"
            state == TaskState.PLANNING -> "Предложить варианты плана"
            state == TaskState.EXECUTION ->
                if (total > 0) "Выполнить шаг ${step + 1} из $total: $current" else "Выполнить шаги плана"
            state == TaskState.VALIDATION -> "Проверить результат на соответствие задаче"
            else -> ""
        }

    /** Блок состояния для инжекта в промпт стадийного агента — источник «продолжения без объяснений». */
    fun renderStateBlock(): String = buildString {
        append("[STATE]\n")
        append("Задача: ").append(task).append('\n')
        if (approach.isNotBlank()) append("Выбранный подход: ").append(approach).append('\n')
        append("Этап: ").append(state.name)
        if (state == TaskState.EXECUTION && total > 0) append(" (шаг ").append(step + 1).append(" из ").append(total).append(')')
        append('\n')
        if (plan.isNotEmpty()) {
            append("План:\n")
            plan.forEachIndexed { i, s ->
                val mark = when {
                    i < step -> "[x]"
                    i == step && state == TaskState.EXECUTION -> "[>]"
                    else -> "[ ]"
                }
                append("  ").append(mark).append(' ').append(i + 1).append(". ").append(s).append('\n')
            }
        }
        if (done.isNotEmpty()) {
            append("Сделано:\n")
            done.forEach { append("  - ").append(it).append('\n') }
        }
        if (docs.isNotEmpty()) {
            append("Документы (приложены пользователем):\n")
            docs.forEach { append("  - ").append(it).append('\n') }
        }
        if (pending.isNotEmpty()) {
            append("Ожидают загрузки (пользователь приложит позже — учитывай как незавершённое):\n")
            pending.forEach { append("  - ").append(it).append('\n') }
        }
        if (note.isNotBlank()) append("Замечания валидатора: ").append(note).append('\n')
        if (awaiting != Awaiting.NONE && prompt.isNotBlank()) append("Запрошено у пользователя: ").append(prompt).append('\n')
        append("Ожидаемое действие: ").append(expectedAction).append('\n')
        // Пояснение жизненного цикла — чтобы агент мог ОБЪЯСНИТЬ отказ при попытке пропустить этап.
        // Само правило держит код (TRANSITIONS), это лишь текст для объяснения пользователю.
        append("Жизненный цикл (этапы нельзя перепрыгивать): INTAKE → PLANNING → EXECUTION → VALIDATION → DONE. ")
        append("Реализация — только после готового плана; завершение — только после проверки. Если пользователь ")
        append("просит пропустить этап или сразу к финалу — объясни, что это невозможно, и назови ближайший допустимый шаг.\n")
        append("[/STATE]")
    }

    companion object {
        /** Разрешённые переходы автомата — единственный источник истины (код, не LLM). */
        val TRANSITIONS: Map<TaskState, Set<TaskState>> = mapOf(
            TaskState.INTAKE to setOf(TaskState.PLANNING),
            TaskState.PLANNING to setOf(TaskState.EXECUTION),
            TaskState.EXECUTION to setOf(TaskState.VALIDATION, TaskState.PLANNING),
            TaskState.VALIDATION to setOf(TaskState.DONE, TaskState.EXECUTION),
            TaskState.DONE to emptySet()
        )
    }
}

/** Разрешён ли переход в [target] из текущего состояния (День 15). */
fun TaskContext.canTransition(target: TaskState): Boolean =
    target in (TaskContext.TRANSITIONS[state] ?: emptySet())

/** Допустимые следующие состояния из текущего (для UI/диагностики). */
fun TaskContext.allowedNext(): Set<TaskState> = TaskContext.TRANSITIONS[state] ?: emptySet()

/** Безопасный переход: новое состояние или null, если переход недопустим (без исключения). */
fun TaskContext.tryTransition(target: TaskState): TaskContext? =
    if (canTransition(target)) copy(state = target) else null

/** Перевод в [target] с проверкой легальности перехода (нелегальный → исключение — защита от «перепрыгивания»). */
fun TaskContext.transitionTo(target: TaskState): TaskContext =
    tryTransition(target) ?: error("Переход $state → $target запрещён (этапы нельзя перепрыгивать)")
