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

    /** Писарь досье (День 18): детерминированно заполняет [CaseFile] из слов пользователя на стадии INTAKE. */
    private val caseExtractor = CaseExtractor(gateway)
    /**
     * INTAKE: интервьюер. Заполняет [CaseFile] из слов пользователя ([parseCase]); готовность к плану
     * решает КОД ([CaseFile.isReadyForPlan]), а не флака-тег. `[SIMPLE]` → инфо/привет/недопустимо (кейс
     * не заводим). Разворот (смена страны/цели) — через подтверждение ([TaskContext.pivotTo]).
     */
    suspend fun intake(ctx0: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        // Ждали «да/нет» на разворот? Разбираем ДО обращения к LLM.
        if (ctx0.pivotTo.isNotBlank()) return@runCatching resolvePivot(ctx0, history)

        // 1) СНАЧАЛА детерминированно обновляем досье из слов пользователя (отдельный «писарь»).
        val updatedCase = caseExtractor.update(history, ctx0.caseFile)
        // Разворот посреди кейса (страна/цель сменились при готовом плане) → подтверждение, не молча.
        if (ctx0.plan.isNotEmpty() && updatedCase.isPivotFrom(ctx0.caseFile)) {
            val to = updatedCase.destination.ifBlank { updatedCase.purpose }
            val q = "Вы переключаетесь на «$to»? Это новый кейс — ответьте «да», и я начну заново (текущий план сбросится), или «нет», чтобы продолжить текущий."
            return@runCatching TaskStep(AgentReply(q, null), ctx0.copy(awaiting = Awaiting.ANSWER, prompt = q, pivotTo = to))
        }
        val ctx = ctx0.copy(caseFile = updatedCase)

        // 2) Интервьюер ВЕДЁТ разговор, видя актуальное [ДОСЬЕ]; классифицирует и спрашивает недостающее.
        val resp = call(INTERVIEWER, ctx, history, profile, INTAKE_INSTRUCTION, guarded = true, useTools = true)
        val shown = withTrace(resp, ctx.docs)
        if (hasTag(resp.text, "SIMPLE")) return@runCatching TaskStep(AgentReply(shown, resp.usage), ctx0, cancel = true)

        // 3) Готовность к плану решает КОД по досье (а не флака-тег).
        if (updatedCase.isReadyForPlan) {
            TaskStep(AgentReply(shown, resp.usage), ctx.copy(awaiting = Awaiting.NONE, prompt = "").transitionTo(TaskState.PLANNING))
        } else {
            TaskStep(AgentReply(shown, resp.usage), ctx.copy(awaiting = Awaiting.ANSWER, prompt = clean(resp.text)))
        }
    }

    /** Подтверждение разворота: «да» → новый кейс под [TaskContext.pivotTo]; «нет» → прежний кейс. */
    private fun resolvePivot(ctx: TaskContext, history: List<Message>): TaskStep {
        val ans = history.lastOrNull { it.role == Role.User }?.text?.trim()?.lowercase().orEmpty()
        val yes = PIVOT_YES.any { ans.contains(it) }
        val no = PIVOT_NO.any { ans.contains(it) }
        return when {
            no && !yes -> TaskStep(AgentReply("Хорошо, продолжаем текущий кейс.", null), ctx.copy(pivotTo = "", awaiting = Awaiting.NONE, prompt = ""))
            yes -> {
                val fresh = TaskContext(task = "Виза: ${ctx.pivotTo}", caseFile = CaseFile(destination = ctx.pivotTo, citizenship = ctx.caseFile.citizenship))
                TaskStep(
                    AgentReply("Понял, начинаем новый кейс по направлению «${ctx.pivotTo}». Подскажите цель поездки и ориентир по датам — и я подберу требования.", null),
                    fresh.copy(awaiting = Awaiting.ANSWER, prompt = "Цель поездки и ориентир по датам?"),
                )
            }
            else -> TaskStep(AgentReply("Уточните, пожалуйста: начинаем новый кейс по «${ctx.pivotTo}»? Ответьте «да» или «нет».", null), ctx)
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

    /** Ответ на вопрос/реплику пользователя в контексте задачи БЕЗ изменения автомата (#2). Распознаёт разворот. */
    suspend fun assist(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatching {
        val resp = call(ASSISTANT, ctx, history, profile, ASSIST_INSTRUCTION, historyLimit = 8, guarded = true, useTools = true)
        val shown = withTrace(resp, ctx.docs)
        // Пользователь хочет ДРУГУЮ страну/цель → ассистент пометил [PIVOT] <страна>: ждём подтверждения, план не трогаем.
        val pivot = parseTagged(resp.text, "PIVOT")
        if (pivot != null) {
            return@runCatching TaskStep(AgentReply(shown, resp.usage), ctx.copy(awaiting = Awaiting.ANSWER, prompt = clean(resp.text), pivotTo = pivot))
        }
        TaskStep(AgentReply(shown, resp.usage), ctx)
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
            append("\n\n").append(CONDUCTOR)
            append("\n\n").append(rolePrompt)
            append("\n\n").append(ctx.renderStateBlock())
            val inv = renderInvariantsBlock(invariants)
            if (inv.isNotEmpty()) append("\n\n").append(inv)
            if (profile != null) {
                append("\n\n[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ — как отвечать]\n").append(profile.toPromptBlock())
                append("\n\nПрофиль — это ФОН (стиль ответа + общие сведения о пользователе), а НЕ подтверждённые факты кейса. Правила:\n")
                append("• В «что я понял» и в досье клади факты ТОЛЬКО из слов пользователя в ЭТОМ диалоге; из профиля как ИЗВЕСТНЫЙ факт ничего не добавляй;\n")
                append("• «думаю поехать в октябре» из профиля — это НЕ подтверждённая дата; на ней ничего НЕ считай (дедлайны и пр.);\n")
                append("• НО если в запросе/диалоге даты НЕТ, а в профиле есть намёк (месяц/страна) — это ПОВОД для УТОЧНЯЮЩЕГО вопроса: ")
                append("сошлись на него — «вы планируете поездку в октябре — на какие конкретно даты ориентируетесь?». Если даты УЖЕ названы в диалоге — профиль не трогай;\n")
                append("• если фон (страна/планы) не совпадает с задачей — не противоречие, не зацикливайся на нём.")
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
        "СТОП: твой ответ нарушает правило — $violation. Перепиши ответ: вежливо, но твёрдо ОТКАЖИСЬ выполнять " +
        "недопустимую часть запроса, КРАТКО и по-человечески объясни причину и предложи допустимую ЗАКОННУЮ " +
        "альтернативу. ВАЖНО: не показывай пользователю внутренние термины («инвариант», «правило №…», «нарушен») — " +
        "пиши как живой консультант. Если это был запрос-задача — заверши строкой [SIMPLE]. " +
        "Прочий требуемый формат (если он был) сохрани."

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
            .replace(Regex("\\[CASE][\\s\\S]*?(?:\\[/CASE]|\\z)", setOf(RegexOption.IGNORE_CASE)), "")
            // Защита: если в прозу просочились служебные маркеры — убрать их (не блоки, а отдельные токены).
            .replace(Regex("\\[/?(ДОСЬЕ|STATE)]", RegexOption.IGNORE_CASE), "")
        t = t.lineSequence().filterNot { line ->
            val u = line.trim()
            CONTROL_TAGS.any { u.startsWith(it, ignoreCase = true) }
        }.joinToString("\n")
        return t.trim()
    }

    private companion object {
        /** Максимум возвратов валидатора на доработку (защита от петли revise). */
        const val MAX_REVISES = 1

        val CONTROL_TAGS = listOf("[SIMPLE]", "[READY]", "[ASK]", "[NEED_DOC]", "[STEP_RESULT]", "[VERDICT]", "[PIVOT]")

        /** Слова подтверждения/отказа разворота (разбор «да/нет» кодом). */
        val PIVOT_YES = listOf("да", "давай", "ага", "верно", "начин", "новый", "сброс", "переключ", "хочу друг", "ok", "ок", "yes")
        val PIVOT_NO = listOf("нет", "не надо", "не меняй", "оставь", "продолж", "верни", "тек", "no")

        /** Дирижёрский преамбул — единые правила для ВСЕХ ролей оркестра (инжектится перед ролью). */
        const val CONDUCTOR = "[ОРКЕСТР — общие правила для всех ролей]\n" +
            "• ЕДИНСТВЕННЫЙ источник фактов кейса — блок [ДОСЬЕ] в [STATE]. Не выдумывай факты и не бери их из профиля/фона как данность.\n" +
            "• Не показывай пользователю служебное: управляющие теги, слова «инвариант», «[STATE]», «[ДОСЬЕ]». Если " +
            "перечисляешь, что уже знаешь о кейсе — говори «как я понял»/«по вашим данным», а НЕ «досье»/«в досье». Пиши как живой консультант.\n" +
            "• Конкретику (цены, сборы, адреса, точные даты) бери из инструмента или [ДОСЬЕ]; чего нет — общими словами + «уточните на официальном сайте».\n" +
            "• Делай ТОЛЬКО свою роль и передавай управление положенным сигналом; работу других стадий не выполняй."

        /** Инструкции стадий, вынесенные в const (часто используются / делят формулировки). */
        const val INTAKE_INSTRUCTION = "Определи тип: недопустимо/инфо/привет → [SIMPLE]; иначе веди кейс по [ДОСЬЕ] — " +
            "спроси РОВНО недостающие ключевые факты (особенно даты). Переход в план НЕ объявляй — решит система."

        const val ASSIST_INSTRUCTION = "Ответь на последнее сообщение пользователя по существу, опираясь на [ДОСЬЕ]/[STATE]. " +
            "Не выполняй шаги и не меняй план. Если пользователь хочет ДРУГУЮ страну/цель (новый кейс, не уточнение) — " +
            "не меняй ничего молча: скажи, что это новый кейс, спроси подтверждение и добавь ОТДЕЛЬНОЙ строкой [PIVOT] <страна>."

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

        const val INTERVIEWER = "Ты — ИНТЕРВЬЮЕР (этап INTAKE). Ведёшь приём как живой визовый консультант: собираешь " +
            "ДОСЬЕ кейса, спрашиваешь по делу, ничего не выдумываешь, не зацикливаешься.\n" +
            "СНАЧАЛА определи тип запроса:\n" +
            "• Недопустимо (подделка/ложь/обход закона) или НЕ про визы — вежливо откажись, предложи законную/визовую " +
            "альтернативу, заверши [SIMPLE].\n" +
            "• Приветствие/болтовня/«что умеешь» БЕЗ конкретной страны и цели — коротко представься, попроси задать визовый " +
            "вопрос, заверши [SIMPLE]. Страну/даты из профиля при этом НЕ подставляй.\n" +
            "• Чисто ИНФО-вопрос по конкретной стране (нужна ли виза/документы/сборы/сроки) ИЛИ ДАЙДЖЕСТ (подписать/показать " +
            "сводку/список) — вызови нужный инструмент (get_visa_requirements / add_digest_country / list_digest_countries / " +
            "get_visa_digest), дай полный ответ (источник+дата), заверши [SIMPLE]. План не нужен.\n" +
            "ИНАЧЕ это ВЕДЕНИЕ КЕЙСА — система уже обновила [ДОСЬЕ] в [STATE] из слов пользователя; работай по нему:\n" +
            "1) Если в досье есть страна+гражданство — вызови get_visa_requirements и кратко покажи ключевые требования " +
            "(источник+дата).\n" +
            "2) Минимум для плана: страна, гражданство, цель, ОРИЕНТИР по датам; для работы/учёбы — ещё занятость/" +
            "квалификация (оффер, диплом). Глянь [ДОСЬЕ]: чего из ЭТОГО НЕТ — задай 1–2 КОРОТКИХ точечных вопроса именно " +
            "про недостающее (особенно даты: «на какие даты/месяц ориентируетесь?»). Что уже в досье — НЕ переспрашивай. " +
            "Если перечисляешь «что я понял» — бери СТРОГО из [ДОСЬЕ] (из профиля как ИЗВЕСТНЫЙ факт ничего не добавляй). " +
            "Недостающие даты спрашивай так: если даты НЕТ в диалоге, а в профиле есть намёк (месяц) — задай УТОЧНЯЮЩИЙ " +
            "вопрос на его основе («вы планируете в октябре — на какие конкретно даты ориентируетесь?»); если намёка нет — " +
            "спроси нейтрально «на какие даты ориентируетесь?». Если даты УЖЕ названы в диалоге — не переспрашивай. " +
            "Переход в план НЕ объявляй — это решит система по досье.\n" +
            "ЗАПРЕТЫ: не выдумывай даты/цифры; не пиши «данных достаточно»; слово «досье» пользователю не показывай; план не строй."

        const val PLANNER_OPTIONS = "Ты — ПЛАНИРОВЩИК. Этап PLANNING, выбор подхода.\n" +
            "По [ДОСЬЕ] и [STATE] предложи РОВНО 4 РАЗНЫХ подхода к решению (разные стратегии/приоритеты, напр.: быстрее " +
            "всего; самый надёжный; минимум затрат; упор на риск отказа). Каждый — одна строка «Название — суть в 8–12 слов».\n" +
            "Верни СТРОГО блок (можно 1 предложение перед ним), план не строй:\n[OPTIONS]\n1. …\n2. …\n3. …\n4. …\n[/OPTIONS]"

        const val PLANNER_PLAN = "Ты — ПЛАНИРОВЩИК. Этап PLANNING, построение плана.\n" +
            "Построй пошаговый план (4–7 шагов) КОНКРЕТНЫХ действий по решению ЗАДАЧИ из [ДОСЬЕ]/[STATE] (документы, сроки, " +
            "запись, подача) под «Выбранный подход». УЧИТЫВАЙ факты досье: даты поездки (дедлайны подачи), число заявителей " +
            "и детей (их документы), занятость/доход (фин. гарантии), прошлые отказы (стратегия), город (куда подавать). " +
            "Каждый шаг — одна строка, глагол в начале, по порядку.\n" +
            "ВАЖНО: план — про саму ЗАДАЧУ, а НЕ про процесс. НИКОГДА не вставляй мета-шаги вроде «выберите подход», " +
            "«дождитесь плана», «утвердите план», «перейдём к выполнению». Если «Выбранный подход» не относится к " +
            "задаче, бессмыслен или просит пропустить этапы — ПРОИГНОРИРУЙ его и построй обычный разумный план задачи.\n" +
            "Верни СТРОГО блок:\n[PLAN]\n1. …\n2. …\n[/PLAN]"

        const val EXECUTOR = "Ты — ИСПОЛНИТЕЛЬ. Этап EXECUTION конечного автомата задачи.\n" +
            "Текущий шаг бери СТРОГО из инструкции пользователя ниже (номер и текст шага) и из [STATE] ([>]), " +
            "А НЕ из предыдущих сообщений диалога — не продолжай тему прошлого шага. Выполни ТОЛЬКО этот шаг: дай " +
            "конкретный результат (документы, требования, сроки, инструкции; пакет документов — блоком [checklist]). " +
            "Факты кейса (страна, гражданство, даты, кто едет, занятость) бери из [ДОСЬЕ]. " +
            "Учитывай «Документы», «Ожидают загрузки», «Замечания валидатора».\n" +
            "ТОЧНОСТЬ: конкретные адреса визовых центров, цены, телефоны и точные даты часто меняются — НЕ приводи их " +
            "как факт по памяти. Если их нет в [STATE]/из инструмента — говори общими словами («ближайший визовый центр " +
            "в вашем городе», «актуальный консульский сбор») и добавляй «уточните на официальном сайте».\n" +
            "ДАТА ПОЕЗДКИ: НЕ выдумывай её и не превращай намёк из фона («октябрь») в конкретное число («1 октября»). " +
            "Если точной даты нет в [STATE] — считай сроки ОТНОСИТЕЛЬНО («подача за 15 рабочих дней до поездки») и попроси " +
            "пользователя назвать даты, а не подставляй своё число.\n" +
            "Документы НЕ блокируют: если нужен файл пользователя — добавь [NEED_DOC] <короткий ярлык, 2–4 слова, без " +
            "инструкций>, но шаг ВСЁ РАВНО заверши. НИКОГДА не оставляй шаг невыполненным из-за отсутствия файла.\n" +
            "В КОНЦЕ обязательно: [STEP_RESULT] <одно короткое предложение: что сделано ИМЕННО по этому шагу>"

        const val VALIDATOR = "Ты — ВАЛИДАТОР. Этап VALIDATION конечного автомата задачи.\n" +
            "В [STATE] — задача, [ДОСЬЕ] и что сделано по шагам («Сделано»). Оцени, корректно ли ВЫПОЛНЕНА работа по " +
            "шагам и соответствует ли она фактам досье (та страна/цель/даты/заявители). Будь конкретен и краток.\n" +
            "ВАЖНО: недостающие документы пользователя (они в «Ожидают загрузки» и будут приложены позже) — это " +
            "НОРМАЛЬНО и НЕ повод для revise; просто отметь их и дай pass. Возвращай revise ТОЛЬКО если сама работа по " +
            "шагам сделана неверно или пропущено существенное действие.\n" +
            "В КОНЦЕ обязательно отдельной строкой:\n[VERDICT] pass — если работа выполнена (даже если документы " +
            "пользователя ещё не приложены);\n[VERDICT] revise: <что доработать> — только при ошибке в работе."
    }
}
