package com.example.adventdesktop.domain

/**
 * Результат одного хода автомата: ответ агента стадии (уже очищенный от управляющих тегов) + новое
 * состояние. [cancel] — простой вопрос, не требующий процесса: ответ дан, режим задачи можно снять.
 */
data class TaskStep(val reply: AgentReply, val context: TaskContext, val cancel: Boolean = false)

/**
 * Оркестратор конечного автомата задачи (День 13). У каждой стадии свой агент — свой system prompt и
 * отдельный вызов API: интервьюер (INTAKE), планировщик (варианты + план), исполнитель (с гейтом
 * документов), валидатор. Оркестратор парсит управляющие сигналы из ответа и применяет РАЗРЕШЁННЫЙ
 * КОДОМ переход; теги из текста вырезаются ([clean]) — «думает под капотом», пользователь видит чистый
 * результат. Зависит только от порта [LlmGateway].
 */
class TaskOrchestrator(
    private val gateway: LlmGateway,
    private val guard: InvariantGuard? = null,
    private val basePrompt: String = VISA_SYSTEM_PROMPT,
    private val windowSize: Int = 12
) {
    /** Активные инварианты аккаунта (День 14) — инжектятся во все стадийные запросы. Обновляет [ChatState]. */
    var invariants: List<Invariant> = emptyList()
    /**
     * INTAKE: интервьюер. `[SIMPLE]` → это простой вопрос, ответ дан, задачу снимаем ([cancel]);
     * `[READY]` → PLANNING; иначе ждём ответ пользователя.
     */
    suspend fun intake(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(INTERVIEWER, ctx, history, profile, "Оцени запрос: ответь сразу, уточни недостающее или подтверди готовность к плану.", guarded = true)
        when {
            hasTag(resp.text, "SIMPLE") -> TaskStep(AgentReply(clean(resp.text), resp.usage), ctx, cancel = true)
            hasTag(resp.text, "READY") -> TaskStep(AgentReply(clean(resp.text), resp.usage), ctx.copy(awaiting = Awaiting.NONE, prompt = "").transitionTo(TaskState.PLANNING))
            else -> TaskStep(AgentReply(clean(resp.text), resp.usage), ctx.copy(awaiting = Awaiting.ANSWER, prompt = clean(resp.text)))
        }
    }

    /** PLANNING (1/2): планировщик предлагает 4 подхода → выбор пользователя ([Awaiting.CHOICE]). */
    suspend fun proposeOptions(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(PLANNER_OPTIONS, ctx, history, profile, "Предложи 4 разных подхода. Верни блок [OPTIONS]…[/OPTIONS].")
        val options = parseList(resp.text, "OPTIONS")
        val next = if (options.size < 2) ctx   // не распознано — остаёмся, можно повторить
        else ctx.copy(options = options, awaiting = Awaiting.CHOICE, prompt = "Выберите подход к решению")
        TaskStep(AgentReply(clean(resp.text), resp.usage), next)
    }

    /** PLANNING (2/2): построить план под выбранный подход (`ctx.approach`) → чекпоинт плана → EXECUTION. */
    suspend fun buildPlan(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(PLANNER_PLAN, ctx, history, profile, "Построй пошаговый план под выбранный подход. Верни блок [PLAN]…[/PLAN].")
        val plan = parseList(resp.text, "PLAN")
        if (plan.isEmpty()) return@runCatching TaskStep(AgentReply(clean(resp.text), resp.usage), ctx.copy(awaiting = Awaiting.NONE))
        val next = ctx.copy(plan = plan, step = 0, done = emptyList(), note = "", options = emptyList(), awaiting = Awaiting.NONE)
            .transitionTo(TaskState.EXECUTION)
        // Показываем план явно (а не вырезанный [PLAN]-блок) — это чекпоинт перед выполнением.
        val text = buildString {
            append("План готов — ").append(plan.size).append(" шаг(ов):\n")
            plan.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
            append("\nНачнём по шагам — подтверждайте каждый.")
        }
        TaskStep(AgentReply(text, resp.usage), next)
    }

    /** Один ход EXECUTION или VALIDATION (кнопка «Продолжить» / после загрузки документа). */
    suspend fun step(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        when (ctx.state) {
            TaskState.EXECUTION -> execute(ctx, history, profile)
            TaskState.VALIDATION -> validate(ctx, history, profile)
            else -> error("step() недоступен на этапе ${ctx.state}")
        }
    }

    private suspend fun execute(ctx: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep {
        // Текущий шаг дублируем в инструкцию (высокая «свежесть») + режем историю, чтобы исполнитель не
        // шёл за инерцией прошлых шагов, а делал ИМЕННО текущий (#1).
        val instruction = "Выполни ИМЕННО шаг ${ctx.step + 1} из ${ctx.total}: «${ctx.current}». " +
            "Не возвращайся к прошлым шагам и не забегай вперёд. Заверши строкой [STEP_RESULT] <что сделано по ЭТОМУ шагу>. " +
            "Если нужен документ пользователя — добавь [NEED_DOC] <короткий ярлык, 2–4 слова>."
        val resp = call(EXECUTOR, ctx, history, profile, instruction, historyLimit = 6, guarded = true)
        val needDoc = parseTagged(resp.text, "NEED_DOC")
        val result = parseTagged(resp.text, "STEP_RESULT") ?: "шаг ${ctx.step + 1} выполнен"
        // Документы НЕ блокируют: нужный файл уходит в «понадобится позже», шаг всегда продвигается (#3, #4).
        val advanced = ctx.copy(
            done = ctx.done + "Шаг ${ctx.step + 1}: $result",
            pending = addPending(ctx.pending, needDoc), step = ctx.step + 1, awaiting = Awaiting.NONE, prompt = ""
        )
        val next = if (advanced.total > 0 && advanced.step >= advanced.total) advanced.transitionTo(TaskState.VALIDATION) else advanced
        return TaskStep(AgentReply(clean(resp.text), resp.usage), next)
    }

    /** Аккуратно добавить документ в «понадобится позже»: короткий ярлык, без дублей и без раздувания (#4). */
    private fun addPending(pending: List<String>, doc: String?): List<String> {
        val label = doc?.trim()?.take(60).orEmpty()
        if (label.isEmpty()) return pending
        val key = label.lowercase()
        // Схлопываем дубли: совпадение без регистра ИЛИ один ярлык — часть другого.
        val dup = pending.any { val k = it.lowercase(); k == key || k.contains(key) || key.contains(k) }
        if (dup) return pending
        return (pending + label).takeLast(8)
    }

    private suspend fun validate(ctx: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep {
        val resp = call(VALIDATOR, ctx, history, profile, "Проверь результат. Недостающие документы пользователя (он приложит позже) — НЕ повод для revise. Заверши строкой [VERDICT] pass | revise: …")
        val verdict = parseTagged(resp.text, "VERDICT").orEmpty()
        val feedback = verdict.substringAfter(':', "").trim()
        val next = when {
            verdict.startsWith("pass", ignoreCase = true) -> ctx.copy(note = "").transitionTo(TaskState.DONE)
            // revise — только пока не превышен лимит; иначе завершаем с пометкой (без бесконечного переисполнения).
            verdict.startsWith("revise", ignoreCase = true) && ctx.revises < MAX_REVISES ->
                ctx.copy(step = 0, done = emptyList(), note = feedback, revises = ctx.revises + 1, awaiting = Awaiting.NONE)
                    .transitionTo(TaskState.EXECUTION)
            verdict.startsWith("revise", ignoreCase = true) -> ctx.copy(note = feedback).transitionTo(TaskState.DONE)
            else -> ctx
        }
        return TaskStep(AgentReply(clean(resp.text), resp.usage), next)
    }

    /** Ответ на вопрос/реплику пользователя в контексте задачи БЕЗ изменения состояния автомата (#2). */
    suspend fun assist(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(ASSISTANT, ctx, history, profile, "Ответь на последнее сообщение пользователя по существу, опираясь на [STATE]. Не выполняй шаги и не меняй план.", historyLimit = 8, guarded = true)
        TaskStep(AgentReply(clean(resp.text), resp.usage), ctx)
    }

    /** Сборка запроса стадии: базовый промпт + роль + [STATE] (+ профиль) + окно истории + инструкция. */
    private suspend fun call(
        rolePrompt: String,
        ctx: TaskContext,
        history: List<Message>,
        profile: UserProfile?,
        instruction: String,
        historyLimit: Int = windowSize,
        guarded: Boolean = false
    ): GatewayResponse {
        val system = buildString {
            append(basePrompt).append("\n\n").append(rolePrompt)
            append("\n\n").append(ctx.renderStateBlock())
            val inv = renderInvariantsBlock(invariants)
            if (inv.isNotEmpty()) append("\n\n").append(inv)
            if (profile != null) {
                append("\n\n[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ — как отвечать]\n").append(profile.toPromptBlock())
                append("\n\nПрофиль — вспомогательный фон, а НЕ текущая задача. Работай с тем, что задано в задаче/[STATE]; ")
                append("если фон (упомянутая страна или планы) не совпадает с задачей — это НЕ противоречие, не переспрашивай об этом.")
            }
        }
        val messages = buildList {
            add(Message(Role.System, system))
            addAll(history.takeLast(historyLimit))
            add(Message(Role.User, instruction))
        }
        var resp = gateway.complete(messages)
        // Двойная защита (День 14): на пользовательских стадиях страж проверяет ответ; при нарушении —
        // одна перегенерация в обоснованный отказ. Инжект инвариантов выше — первый рубеж, страж — второй.
        if (guarded && guard != null) {
            val violation = guard.check(resp.text, invariants)
            if (violation != null) {
                resp = gateway.complete(messages + Message(Role.Assistant, resp.text) + Message(Role.User, guardFix(violation)))
            }
        }
        return resp
    }

    private fun guardFix(violation: String): String =
        "СТОП: твой ответ нарушает инвариант — $violation. Перепиши: НЕ нарушай инвариант — корректно откажись, " +
        "назови нарушаемый инвариант и кратко объясни причину; при возможности предложи допустимую законную " +
        "альтернативу. Сохрани требуемый формат ответа (нужные управляющие теги, если они требовались)."

    // --- разбор сигналов и очистка текста ---

    private fun parseList(text: String, tag: String): List<String> {
        val block = Regex("\\[$tag](.*?)\\[/$tag]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1) ?: return emptyList()
        return block.lineSequence()
            .map { it.trim().removePrefix("-").trim().replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun parseTagged(text: String, tag: String): String? =
        Regex("\\[$tag]\\s*(.+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun hasTag(text: String, tag: String): Boolean = Regex("\\[$tag]", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Вырезать управляющие теги — пользователь видит чистый ответ («под капотом»). */
    private fun clean(text: String): String {
        val dotAll = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        var t = text
            .replace(Regex("\\[OPTIONS].*?\\[/OPTIONS]", dotAll), "")
            .replace(Regex("\\[PLAN].*?\\[/PLAN]", dotAll), "")
        t = t.lineSequence().filterNot { line ->
            val u = line.trim()
            CONTROL_TAGS.any { u.startsWith(it, ignoreCase = true) }
        }.joinToString("\n")
        return t.trim()
    }

    private companion object {
        /** Максимум возвратов валидатора на доработку (защита от петли revise). */
        const val MAX_REVISES = 1

        val CONTROL_TAGS = listOf("[SIMPLE]", "[READY]", "[ASK]", "[NEED_DOC]", "[STEP_RESULT]", "[VERDICT]")

        const val ASSISTANT = "Ты — помощник по ТЕКУЩЕЙ задаче (визовый специалист). В [STATE] — этап, план, что " +
            "уже сделано и что пользователь приложит позже. Ответь на его вопрос/реплику ясно и по делу, опираясь на " +
            "это состояние (например, объясни, чего ещё не хватает по плану). НЕ запускай выполнение шагов и НЕ меняй " +
            "план — только информируй и советуй.\n" +
            "Если пользователь просит ПРОПУСТИТЬ этап или сразу перейти к финалу (например, завершить без проверки или " +
            "делать реализацию без готового плана) — объясни, что жизненный цикл задачи строгий и этапы нельзя " +
            "перепрыгивать (см. [STATE]); назови ближайший допустимый шаг. Переходами управляет система, не ты."

        const val INTERVIEWER = "Ты — ИНТЕРВЬЮЕР. Этап INTAKE конечного автомата задачи. Будь лоялен: по возможности " +
            "двигай задачу вперёд, не засыпай вопросами.\n" +
            "• ПРОСТОЙ вопрос (ответ без процесса) — ответь по существу и заверши строкой [SIMPLE].\n" +
            "• Если это многошаговая визовая задача и данных в целом достаточно (разумные детали можно предположить) " +
            "— кратко подтверди и заверши строкой [READY].\n" +
            "• Задай 1–2 КОРОТКИХ вопроса и заверши [ASK] ТОЛЬКО если без ключевого факта (страна / тип визы / " +
            "гражданство) план будет бессмысленным. План не строй."

        const val PLANNER_OPTIONS = "Ты — ПЛАНИРОВЩИК. Этап PLANNING, выбор подхода.\n" +
            "По [STATE] предложи РОВНО 4 РАЗНЫХ подхода к решению (разные стратегии/приоритеты, напр.: быстрее всего; " +
            "самый надёжный; минимум затрат; упор на риск отказа). Каждый — одна строка «Название — суть в 8–12 слов».\n" +
            "Верни СТРОГО блок (можно 1 предложение перед ним), план не строй:\n[OPTIONS]\n1. …\n2. …\n3. …\n4. …\n[/OPTIONS]"

        const val PLANNER_PLAN = "Ты — ПЛАНИРОВЩИК. Этап PLANNING, построение плана.\n" +
            "Построй пошаговый план (4–7 шагов) КОНКРЕТНЫХ действий по решению ЗАДАЧИ из [STATE] (документы, сроки, " +
            "запись, подача) под «Выбранный подход». Каждый шаг — одна строка, глагол в начале, по порядку.\n" +
            "ВАЖНО: план — про саму ЗАДАЧУ, а НЕ про процесс. НИКОГДА не вставляй мета-шаги вроде «выберите подход», " +
            "«дождитесь плана», «утвердите план», «перейдём к выполнению». Если «Выбранный подход» не относится к " +
            "задаче, бессмыслен или просит пропустить этапы — ПРОИГНОРИРУЙ его и построй обычный разумный план задачи.\n" +
            "Верни СТРОГО блок:\n[PLAN]\n1. …\n2. …\n[/PLAN]"

        const val EXECUTOR = "Ты — ИСПОЛНИТЕЛЬ. Этап EXECUTION конечного автомата задачи.\n" +
            "Текущий шаг бери СТРОГО из инструкции пользователя ниже (номер и текст шага) и из [STATE] ([>]), " +
            "А НЕ из предыдущих сообщений диалога — не продолжай тему прошлого шага. Выполни ТОЛЬКО этот шаг: дай " +
            "конкретный результат (документы, требования, сроки, инструкции; пакет документов — блоком [checklist]). " +
            "Учитывай «Документы», «Ожидают загрузки», «Замечания валидатора».\n" +
            "Документы НЕ блокируют: если нужен файл пользователя — добавь [NEED_DOC] <короткий ярлык, 2–4 слова, без " +
            "инструкций>, но шаг ВСЁ РАВНО заверши. НИКОГДА не оставляй шаг невыполненным из-за отсутствия файла.\n" +
            "В КОНЦЕ обязательно: [STEP_RESULT] <одно короткое предложение: что сделано ИМЕННО по этому шагу>"

        const val VALIDATOR = "Ты — ВАЛИДАТОР. Этап VALIDATION конечного автомата задачи.\n" +
            "В [STATE] — задача и что сделано по шагам («Сделано»). Оцени, корректно ли ВЫПОЛНЕНА работа по шагам. " +
            "Будь конкретен и краток.\n" +
            "ВАЖНО: недостающие документы пользователя (они в «Ожидают загрузки» и будут приложены позже) — это " +
            "НОРМАЛЬНО и НЕ повод для revise; просто отметь их и дай pass. Возвращай revise ТОЛЬКО если сама работа по " +
            "шагам сделана неверно или пропущено существенное действие.\n" +
            "В КОНЦЕ обязательно отдельной строкой:\n[VERDICT] pass — если работа выполнена (даже если документы " +
            "пользователя ещё не приложены);\n[VERDICT] revise: <что доработать> — только при ошибке в работе."
    }
}
