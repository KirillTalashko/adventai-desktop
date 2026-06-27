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
    private val windowSize: Int = 12,
    private val tools: ToolGateway? = null
) {
    /** Активные инварианты аккаунта (День 14) — инжектятся во все стадийные запросы. Обновляет [ChatState]. */
    var invariants: List<Invariant> = emptyList()
    /**
     * INTAKE: интервьюер. `[SIMPLE]` → это простой вопрос, ответ дан, задачу снимаем ([cancel]);
     * `[READY]` → PLANNING; иначе ждём ответ пользователя.
     */
    suspend fun intake(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(INTERVIEWER, ctx, history, profile, "Оцени запрос: ответь сразу, уточни недостающее или подтверди готовность к плану.", guarded = true, useTools = true)
        val body = clean(resp.text)
        val shown = withTrace(resp, ctx.docs)
        when {
            hasTag(resp.text, "SIMPLE") -> TaskStep(AgentReply(shown, resp.usage), ctx, cancel = true)
            hasTag(resp.text, "READY") -> TaskStep(AgentReply(shown, resp.usage), ctx.copy(awaiting = Awaiting.NONE, prompt = "").transitionTo(TaskState.PLANNING))
            else -> TaskStep(AgentReply(shown, resp.usage), ctx.copy(awaiting = Awaiting.ANSWER, prompt = body))
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
        return TaskStep(AgentReply(reconcileChecklist(clean(resp.text), ctx.docs), resp.usage), next)
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
        return TaskStep(AgentReply(reconcileChecklist(clean(resp.text), ctx.docs), resp.usage), next)
    }

    /** Ответ на вопрос/реплику пользователя в контексте задачи БЕЗ изменения состояния автомата (#2). */
    suspend fun assist(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(ASSISTANT, ctx, history, profile, "Ответь на последнее сообщение пользователя по существу, опираясь на [STATE]. Не выполняй шаги и не меняй план.", historyLimit = 8, guarded = true, useTools = true)
        TaskStep(AgentReply(withTrace(resp, ctx.docs), resp.usage), ctx)
    }

    /** Префикс-трейс вызванных инструментов — видно в чате, что агент сходил в MCP (Фаза 2). */
    private fun withTrace(resp: GatewayResponse, docs: List<String>): String {
        val body = reconcileChecklist(clean(resp.text), docs)
        if (resp.toolCalls.isEmpty()) return body
        val header = resp.toolCalls.joinToString("\n") { "🔧 $it" }
        return if (body.isEmpty()) header else "$header\n\n$body"
    }

    /**
     * Защита от фантомного «загружен»: статус в [checklist] правим по РЕАЛЬНЫМ [docs]. Статус — свободный
     * текст модели (UI красит по слову), поэтому со слов пользователя «у меня есть» мог появиться «загружен»,
     * хотя файл не приложен. Если документа нет среди приложенных — понижаем «загружен»/«проверен» до «нужен».
     */
    private fun reconcileChecklist(text: String, docs: List<String>): String {
        if (!text.contains(';')) return text
        val docKeys = docs.map { it.substringBefore('→').substringBefore("->").trim().lowercase() }
            .filter { it.isNotEmpty() }
        return text.lineSequence().joinToString("\n") { line ->
            if (!line.trimStart().startsWith("-")) return@joinToString line   // только пункты чек-листа
            val sep = line.lastIndexOf(';')
            if (sep < 0) return@joinToString line
            val status = line.substring(sep + 1).trim().lowercase()
            if (!status.startsWith("загруж") && !status.startsWith("провер")) return@joinToString line
            val label = line.substring(0, sep).removePrefix("-").trim().lowercase()
            val backed = docKeys.any { k -> label.contains(k) || k.contains(label) }
            if (backed) line else line.substring(0, sep) + "; нужен"
        }
    }

    /** Сборка запроса стадии: базовый промпт + роль + [STATE] (+ профиль) + окно истории + инструкция. */
    private suspend fun call(
        rolePrompt: String,
        ctx: TaskContext,
        history: List<Message>,
        profile: UserProfile?,
        instruction: String,
        historyLimit: Int = windowSize,
        guarded: Boolean = false,
        useTools: Boolean = false
    ): GatewayResponse {
        val system = buildString {
            append(basePrompt)
            // Текущая дата: без неё модель считает год по памяти (выдаёт сроки в прошлом). Источник истины — часы.
            append("\n\nСегодня: ").append(java.time.LocalDate.now())
            append(". Используй ИМЕННО эту дату для всех расчётов сроков и дедлайнов; не определяй год по памяти.")
            append("\n\n").append(rolePrompt)
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
        // Фаза 2: на «отвечающих» стадиях даём модели MCP-инструменты; tool-loop ведёт LlmClient.
        val gw = tools
        val toolList = if (useTools && gw != null) runCatching { gw.listTools() }.getOrDefault(emptyList()) else emptyList()
        val executeTool: (suspend (String, String) -> String)? =
            if (gw != null && toolList.isNotEmpty()) { name, args -> gw.callToolJson(name, args) } else null
        var resp = gateway.complete(messages, toolList, executeTool)
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
        val open = "[$tag]"
        val start = text.indexOf(open, ignoreCase = true)
        if (start < 0) return emptyList()
        val from = start + open.length
        // Терпим незакрытый блок: если нет [/tag] — берём до конца текста.
        val end = text.indexOf("[/$tag]", from, ignoreCase = true).let { if (it >= 0) it else text.length }
        val block = text.substring(from, end)
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
        // Вырезаем блоки целиком; терпим незакрытый тег (до конца текста), чтобы сырой [PLAN] не утёк.
        var t = text
            .replace(Regex("\\[OPTIONS][\\s\\S]*?(?:\\[/OPTIONS]|\\z)", setOf(RegexOption.IGNORE_CASE)), "")
            .replace(Regex("\\[PLAN][\\s\\S]*?(?:\\[/PLAN]|\\z)", setOf(RegexOption.IGNORE_CASE)), "")
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
            "перепрыгивать (см. [STATE]); назови ближайший допустимый шаг. Переходами управляет система, не ты.\n" +
            "Если нужны АКТУАЛЬНЫЕ визовые требования по стране (нужна ли виза, документы, сборы, сроки, куда подавать) — " +
            "вызови инструмент get_visa_requirements(destination, citizenship, purpose): он вернёт официальные данные " +
            "с источниками и датой. Конкретные сборы, сроки и перечни документов НЕ бери по памяти — вызови инструмент " +
            "и приводи ссылки на источники и дату; по памяти отвечай только на общие вопросы без точных цифр."

        const val INTERVIEWER = "Ты — ИНТЕРВЬЮЕР. Этап INTAKE. Цель — БЫСТРО довести до плана, не зацикливаясь на " +
            "уточнениях. Выбери ОДНУ ветку по ПРИОРИТЕТУ (сверху вниз) и заверши ровно одним тегом:\n" +
            "1) ПРИВЕТСТВИЕ / болтовня / вопрос о твоих возможностях, где в САМОМ сообщении нет конкретной страны и " +
            "визовой цели — коротко представься, предложи задать визовый вопрос, заверши [SIMPLE]. Страну/месяц из " +
            "профиля или прошлых диалогов при этом ИГНОРИРУЙ: не подставляй их и не спрашивай о них. Инструмент не зови.\n" +
            "2) Явная просьба ОФОРМИТЬ / ПОДГОТОВИТЬСЯ к визе, когда известны страна и гражданство (из сообщения ИЛИ " +
            "профиля) — вызови get_visa_requirements, кратко покажи ключевые требования (источник + дата) и заверши " +
            "[READY]. Разумные детали ПРЕДПОЛОЖИ сам; факты, которые пользователь уже назвал (договор/диплом на руках, " +
            "тип визы и т.п.), СЧИТАЙ истинными и НЕ переспрашивай. Допустим максимум ОДИН короткий вопрос и только если " +
            "без него план реально невозможен — иначе сразу [READY].\n" +
            "3) Информационный визовый вопрос по КОНКРЕТНОЙ стране (нужна ли виза / документы / сборы / сроки / куда " +
            "подавать) — вызови get_visa_requirements и дай ПОЛНЫЙ ответ по его данным (источник + дата), заверши [SIMPLE].\n" +
            "4) Не хватает САМОГО ключевого (страна / тип визы / гражданство) и подставить неоткуда — задай 1–2 коротких " +
            "вопроса, заверши [ASK].\n" +
            "ЗАПРЕТЫ: не пиши «данных достаточно» и подобные пустые фразы; конкретные требования/сборы/сроки бери ТОЛЬКО " +
            "из инструмента (нужны снова — вызови ЗАНОВО), не выдумывай по памяти; план не строй — это сделает планировщик."

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
