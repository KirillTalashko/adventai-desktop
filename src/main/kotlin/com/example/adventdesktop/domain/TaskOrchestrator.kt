package com.example.adventdesktop.domain

import com.example.adventdesktop.domain.rag.CountryScope
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.rag.KnowledgeRetriever
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    private val tools: ToolGateway? = null,
    /** Досмотр исходящих tool-calls ПЕРЕД исполнением (защита от «логических бомб»). null = без досмотра. */
    private val toolGuard: ToolCallGuard? = null,
    /**
     * P3 «модель-под-задачу»: дешёвый служебный шлюз для МЕХАНИЧЕСКИХ под-задач (извлечение досье), чтобы
     * не жечь основную (выбранную пользователем) модель на рутине. null → используется основной [gateway].
     * Пользователь-видимые стадии (интервью/план/исполнение/валидация) всегда на основном [gateway].
     */
    private val serviceGateway: LlmGateway? = null,
    /**
     * P5 «рой агентов»: число параллельных валидаторов-проверяющих (каждый со своим ракурсом) на стадии
     * VALIDATION. >1 включает консилиум на дешёвом [serviceGateway]; 1 (или нет serviceGateway) — обычный
     * одиночный валидатор. Консилиум перекрёстно ловит то, что один проверяющий упускает.
     */
    private val consiliumSize: Int = 3,
    /**
     * День 25: поиск по ВНУТРЕННЕЙ базе знаний (RAG). На отвечающих стадиях (INTAKE/ASSIST/EXECUTION) перед
     * вызовом LLM достаём релевантные выдержки и кладём блоком [БАЗА ЗНАНИЙ] в промпт + возвращаем источники.
     * Аддитивно к MCP: RAG — внутренние документы с цитатами, MCP ([СПРАВКА]) — живые офиц. ссылки. null = выкл.
     */
    private val retriever: KnowledgeRetriever? = null,
) {
    /** Активные инварианты аккаунта (День 14) — инжектятся во все стадийные запросы. Обновляет [ChatState]. */
    var invariants: List<Invariant> = emptyList()

    /**
     * День 20 (prompt-tune): пер-аккаунт персонализация ролей — `roleId` → доп. строки. ТОЛЬКО АДДИТИВНО
     * (перило A): дописываются после базового промта роли блоком `[ПЕРСОНАЛИЗАЦИЯ]`, не переопределяя
     * безопасные правила. Заполняется [ChatState] из локального стора одобренных пользователем добавок.
     */
    var promptOverrides: Map<String, List<String>> = emptyMap()

    /**
     * Писарь досье (День 18): детерминированно заполняет [CaseFile] из слов пользователя на стадии INTAKE.
     * P3: механическое извлечение → на дешёвом [serviceGateway] (если задан), иначе на основном.
     */
    private val caseExtractor = CaseExtractor(serviceGateway ?: gateway)
    /**
     * INTAKE: интервьюер. Заполняет [CaseFile] из слов пользователя ([parseCase]); готовность к плану
     * решает КОД ([CaseFile.isReadyForPlan]), а не флака-тег. `[SIMPLE]` → инфо/привет/недопустимо (кейс
     * не заводим). Разворот (смена страны/цели) — через подтверждение ([TaskContext.pivotTo]).
     */
    suspend fun intake(ctx0: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatchingCancellable {
        // Ждали «да/нет» на разворот? Разбираем ДО обращения к LLM.
        if (ctx0.pivotTo.isNotBlank()) return@runCatchingCancellable resolvePivot(ctx0, history)

        // 1) СНАЧАЛА детерминированно обновляем досье из слов пользователя (отдельный «писарь»).
        val updatedCase = caseExtractor.update(history, ctx0.caseFile)
        // Разворот посреди кейса (страна/цель сменились при готовом плане) → подтверждение, не молча.
        if (ctx0.plan.isNotEmpty() && updatedCase.isPivotFrom(ctx0.caseFile)) {
            val to = updatedCase.destination.ifBlank { updatedCase.purpose }
            val q = "Вы переключаетесь на «$to»? Это новый кейс — ответьте «да», и я начну заново (текущий план сбросится), или «нет», чтобы продолжить текущий."
            return@runCatchingCancellable TaskStep(AgentReply(q, null), ctx0.copy(awaiting = Awaiting.ANSWER, prompt = q, pivotTo = to))
        }
        // Гражданство — устойчивый признак личности: если пользователь не назвал его в диалоге, засеваем из
        // профиля (анти-бленд для фактов ПОЕЗДКИ — страна/даты/цель — сохраняется: их из профиля не берём).
        val seededCase = seedCitizenship(updatedCase, profile)
        val ctx = ctx0.copy(caseFile = seededCase)

        // День 25: поиск во внутренней базе знаний (RAG) под последний вопрос — выдержки + источники в ответ.
        val (ragBlock, hits) = ragContext(ctx, history)
        // 2) Интервьюер ВЕДЁТ разговор, видя актуальное [ДОСЬЕ]; классифицирует и спрашивает недостающее.
        val resp = call(INTERVIEWER, ctx, history, profile, INTAKE_INSTRUCTION, guarded = true, useTools = true, roleId = TunableRole.INTERVIEWER.id, ragBlock = ragBlock)
        val shown = withTrace(resp, ctx.docs)
        if (hasTag(resp.text, "SIMPLE")) return@runCatchingCancellable TaskStep(AgentReply(shown, resp.usage, hits), ctx0, cancel = true)

        // День 19: если интервьюер уже вызвал get_visa_requirements — переиспользуем его синтез (актуальные
        // данные + ОФИЦ. ссылки), кладём в [research], чтобы цитировали все стадии без повторного платного вызова.
        val ctxR = captureResearch(ctx, resp)

        // 3) Готовность к плану решает КОД по досье (а не флака-тег).
        if (seededCase.isReadyForPlan) {
            TaskStep(AgentReply(shown, resp.usage, hits), ctxR.copy(awaiting = Awaiting.NONE, prompt = "").transitionTo(TaskState.PLANNING))
        } else {
            TaskStep(AgentReply(shown, resp.usage, hits), ctxR.copy(awaiting = Awaiting.ANSWER, prompt = clean(resp.text)))
        }
    }

    /** День 19: достать синтез get_visa_requirements из tool-loop и сохранить в [TaskContext.research] (один раз). */
    private fun captureResearch(ctx: TaskContext, resp: GatewayResponse): TaskContext {
        if (ctx.research.isNotBlank()) return ctx
        val r = resp.toolResults.lastOrNull { isUsableResearch(it.name, it.result) } ?: return ctx
        return ctx.copy(research = capResearch(r.result))
    }

    /**
     * Фолбэк-ресёрч: если [research] ещё пуст, а инструмент и ключевые факты (страна+гражданство) есть — ОДИН раз
     * детерминированно зовём get_visa_requirements и сохраняем синтез (со ссылками) для всех стадий плана/выполнения.
     */
    private suspend fun ensureResearch(ctx: TaskContext): TaskContext {
        if (ctx.research.isNotBlank()) return ctx
        val gw = tools ?: return ctx
        val cf = ctx.caseFile
        if (cf.destination.isBlank() || cf.citizenship.isBlank()) return ctx
        val args = buildString {
            append("{\"destination\":").append(jsonStr(cf.destination))
            append(",\"citizenship\":").append(jsonStr(cf.citizenship))
            if (cf.purpose.isNotBlank()) append(",\"purpose\":").append(jsonStr(cf.purpose))
            append("}")
        }
        val result = runCatchingCancellable { gw.callToolJson("get_visa_requirements", args) }.getOrNull()
        return if (result != null && isUsableResearch("get_visa_requirements", result)) ctx.copy(research = capResearch(result)) else ctx
    }

    private fun isUsableResearch(name: String, result: String): Boolean =
        name == "get_visa_requirements" && result.isNotBlank() && !result.startsWith("Ошибка инструмента")

    private fun capResearch(s: String): String {
        val t = s.trim()
        return if (t.length <= RESEARCH_CAP) t else t.take(RESEARCH_CAP) + "\n… (сводка усечена; полные данные — по ссылкам выше)"
    }

    private fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    /** Засев гражданства из профиля, если оно не названо в диалоге (см. [UserProfile.citizenship]). */
    private fun seedCitizenship(case: CaseFile, profile: UserProfile?): CaseFile {
        if (case.citizenship.isNotBlank()) return case
        val fromProfile = profile?.citizenship().orEmpty()
        return if (fromProfile.isNotBlank()) case.copy(citizenship = fromProfile) else case
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
    suspend fun proposeOptions(ctx0: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatchingCancellable {
        val ctx = ensureResearch(ctx0)   // справка (актуальные данные + ссылки) — один раз, перед планированием
        val resp = call(PLANNER_OPTIONS, ctx, history, profile, "Предложи 4 разных подхода. Верни блок [OPTIONS]…[/OPTIONS].", roleId = TunableRole.PLANNER.id, params = LlmParams(temperature = TEMP_CREATIVE))
        val options = parseList(resp.text, "OPTIONS")
        val next = if (options.size < 2) ctx   // не распознано — остаёмся, можно повторить
        else ctx.copy(options = options, awaiting = Awaiting.CHOICE, prompt = "Выберите подход к решению")
        TaskStep(AgentReply(clean(resp.text), resp.usage), next)
    }

    /** PLANNING (2/2): построить план под выбранный подход (`ctx.approach`) → чекпоинт плана → EXECUTION. */
    suspend fun buildPlan(ctx0: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatchingCancellable {
        val ctx = ensureResearch(ctx0)
        val resp = call(PLANNER_PLAN, ctx, history, profile, "Построй пошаговый план под выбранный подход. Верни блок [PLAN]…[/PLAN].", roleId = TunableRole.PLANNER.id)
        val plan = parseList(resp.text, "PLAN")
        if (plan.isEmpty()) return@runCatchingCancellable TaskStep(AgentReply(clean(resp.text), resp.usage), ctx.copy(awaiting = Awaiting.NONE))
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
    suspend fun step(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatchingCancellable {
        when (ctx.state) {
            TaskState.EXECUTION -> execute(ctx, history, profile)
            TaskState.VALIDATION -> validate(ctx, history, profile)
            else -> error("step() недоступен на этапе ${ctx.state}")
        }
    }

    private suspend fun execute(ctx0: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep {
        val ctx = ensureResearch(ctx0)   // [СПРАВКА] со ссылками должна быть в [STATE] и на выполнении (День 19)
        // Текущий шаг дублируем в инструкцию (высокая «свежесть») + режем историю, чтобы исполнитель не
        // шёл за инерцией прошлых шагов, а делал ИМЕННО текущий (#1).
        val instruction = "Выполни ИМЕННО шаг ${ctx.step + 1} из ${ctx.total}: «${ctx.current}». " +
            "Не возвращайся к прошлым шагам и не забегай вперёд. Используй [СПРАВКА ПО ВИЗЕ] и приводи официальные ССЫЛКИ. " +
            "Заверши строкой [STEP_RESULT] <что сделано по ЭТОМУ шагу>. " +
            "Если нужен документ пользователя — добавь [NEED_DOC] <короткий ярлык, 2–4 слова>."
        // День 25: RAG под ТЕКУЩИЙ шаг (запрос = текст шага + страна/цель) — выдержки базы + источники.
        val (ragBlock, hits) = ragContext(ctx, history, queryOverride = ctx.current)
        val resp = call(EXECUTOR, ctx, history, profile, instruction, historyLimit = 6, guarded = true, useTools = true, roleId = TunableRole.EXECUTOR.id, ragBlock = ragBlock, params = LlmParams(temperature = TEMP_PRECISE))
        // Отказ стража ([SIMPLE]: guardFix переписал ответ исполнителя на EXECUTION) — шаг НЕ выполнен.
        // Раньше отсутствие [STEP_RESULT] дефолтилось в пустышку «шаг N выполнен» → шаг молча продвигался, а
        // отказ маскировался под успех и штамповался VALIDATION (Bug: kotlin-diagnostics). Теперь показываем
        // отказ и НЕ продвигаем план.
        if (hasTag(resp.text, "SIMPLE")) {
            return TaskStep(AgentReply(withTrace(resp, ctx.docs), resp.usage, hits), ctx.copy(awaiting = Awaiting.NONE, prompt = ""))
        }
        val needDoc = parseTagged(resp.text, "NEED_DOC")
        // Нет тега, но есть реальный ответ → берём его как результат (а не пустышку), чтобы в [Сделано]/
        // VALIDATION попало СОДЕРЖАНИЕ. Совсем пусто → шаг не завершён, не продвигаем (не штампуем DONE).
        val result: String = parseTagged(resp.text, "STEP_RESULT")
            ?: clean(resp.text).ifBlank { null }
            ?: return TaskStep(AgentReply(withTrace(resp, ctx.docs), resp.usage, hits), ctx.copy(note = "шаг не завершён результатом — повторите", awaiting = Awaiting.NONE))
        // Документы НЕ блокируют: нужный файл уходит в «понадобится позже», шаг всегда продвигается (#3, #4).
        val advanced = ctx.copy(
            done = ctx.done + "Шаг ${ctx.step + 1}: $result",
            pending = addPending(ctx.pending, needDoc), step = ctx.step + 1, awaiting = Awaiting.NONE, prompt = ""
        )
        val next = if (advanced.total > 0 && advanced.step >= advanced.total) advanced.transitionTo(TaskState.VALIDATION) else advanced
        return TaskStep(AgentReply(withTrace(resp, ctx.docs), resp.usage, hits), next)
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

    /** VALIDATION: консилиум (рой проверяющих) если включён и есть служебный шлюз, иначе одиночный валидатор. */
    private suspend fun validate(ctx: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep =
        if (consiliumSize > 1 && serviceGateway != null) consiliumValidate(ctx, history, profile)
        else validateSingle(ctx, history, profile)

    /** Одиночный валидатор (исходное поведение). */
    private suspend fun validateSingle(ctx: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep {
        val resp = call(VALIDATOR, ctx, history, profile, "Проверь результат. Недостающие документы пользователя (он приложит позже) — НЕ повод для revise. Заверши строкой [VERDICT] pass | revise: …", roleId = TunableRole.VALIDATOR.id, params = LlmParams(temperature = TEMP_PRECISE))
        val verdict = parseTagged(resp.text, "VERDICT").orEmpty()
        return applyVerdict(ctx, verdict, reconcileChecklist(clean(resp.text), ctx.docs), resp.usage)
    }

    /**
     * P5 «рой агентов»: запускаем [consiliumSize] валидаторов ПАРАЛЛЕЛЬНО, каждый со своим РАКУРСОМ
     * ([VALIDATOR_ANGLES]), на дешёвом служебном шлюзе. Сводим детерминированно: хоть один обоснованный
     * revise → доработка с объединённым фидбэком; иначе pass. Перекрёстная проверка ловит больше, чем один.
     */
    private suspend fun consiliumValidate(ctx: TaskContext, history: List<Message>, profile: UserProfile?): TaskStep {
        val gw = serviceGateway ?: gateway
        val state = ctx.renderStateBlock()
        val angles = VALIDATOR_ANGLES.take(consiliumSize.coerceAtMost(VALIDATOR_ANGLES.size))
        val verdicts = coroutineScope {
            angles.map { angle ->
                async {
                    val sys = buildString {
                        append(basePrompt)
                        append("\n\nСегодня: ").append(java.time.LocalDate.now())
                        append("\n\n").append(CONDUCTOR)
                        append("\n\n").append(VALIDATOR)
                        append("\n\n[ТВОЙ РАКУРС ПРОВЕРКИ] ").append(angle)
                        append("\n\n").append(state)
                    }
                    val msgs = listOf(Message(Role.System, sys)) + history.takeLast(windowSize) +
                        Message(Role.User, "Проверь результат СТРОГО со своего ракурса. Недостающие документы пользователя (приложит позже) — НЕ повод для revise. Заверши строкой [VERDICT] pass | revise: <что доработать>.")
                    val text = runCatchingCancellable { gw.complete(msgs, params = LlmParams(temperature = TEMP_PRECISE)) }.getOrNull()?.text.orEmpty()
                    parseTagged(text, "VERDICT").orEmpty()
                }
            }.awaitAll()
        }
        val revises = verdicts.filter { it.startsWith("revise", ignoreCase = true) }
            .map { it.substringAfter(':', "").trim() }.filter { it.isNotEmpty() }
        val merged = if (revises.isNotEmpty()) "revise: " + revises.joinToString("; ").take(600) else "pass"
        val display = if (revises.isEmpty())
            "Консилиум из ${verdicts.size} проверяющих: разногласий нет, проверка пройдена."
        else
            "Консилиум из ${verdicts.size} проверяющих: ${revises.size} за доработку.\n• " + revises.joinToString("\n• ")
        return applyVerdict(ctx, merged, display, null)
    }

    /** Применить вердикт (pass/revise) к автомату — общая логика для одиночного валидатора и консилиума. */
    private fun applyVerdict(ctx: TaskContext, verdict: String, displayText: String, usage: TokenUsage?): TaskStep {
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
        return TaskStep(AgentReply(displayText, usage), next)
    }

    /** Ответ на вопрос/реплику пользователя в контексте задачи БЕЗ изменения автомата (#2). Распознаёт разворот. */
    suspend fun assist(ctx: TaskContext, history: List<Message>, profile: UserProfile?): Result<TaskStep> = runCatchingCancellable {
        val (ragBlock, hits) = ragContext(ctx, history)   // День 25: контекст из внутренней базы под вопрос
        val resp = call(ASSISTANT, ctx, history, profile, ASSIST_INSTRUCTION, historyLimit = 8, guarded = true, useTools = true, roleId = TunableRole.ASSISTANT.id, ragBlock = ragBlock)
        val shown = withTrace(resp, ctx.docs)
        // Пользователь хочет ДРУГУЮ страну/цель → ассистент пометил [PIVOT] <страна>: ждём подтверждения, план не трогаем.
        val pivot = parseTagged(resp.text, "PIVOT")
        if (pivot != null) {
            return@runCatchingCancellable TaskStep(AgentReply(shown, resp.usage, hits), ctx.copy(awaiting = Awaiting.ANSWER, prompt = clean(resp.text), pivotTo = pivot))
        }
        TaskStep(AgentReply(shown, resp.usage, hits), ctx)
    }

    /** Префикс-трейс вызванных инструментов — видно в чате, что агент сходил в MCP (Фаза 2), но по-человечески. */
    private fun withTrace(resp: GatewayResponse, docs: List<String>): String {
        val body = reconcileChecklist(clean(resp.text), docs)
        val traces = friendlyToolTraces(resp)
        if (traces.isEmpty()) return body
        val header = traces.joinToString("\n")
        return if (body.isEmpty()) header else "$header\n\n$body"
    }

    /**
     * Человекочитаемый след вызванных инструментов вместо сырого `name({"json":…})` — понятно обычному
     * пользователю, а не разработчику. Берём структурные вызовы ([GatewayResponse.toolResults]); если их нет —
     * парсим строковый след.
     */
    private fun friendlyToolTraces(resp: GatewayResponse): List<String> {
        val calls = if (resp.toolResults.isNotEmpty()) resp.toolResults.map { it.name to it.args }
        else resp.toolCalls.map { raw -> raw.substringBefore('(').trim() to raw.substringAfter('(', "").substringBeforeLast(')') }
        return calls.map { (name, args) -> friendlyTool(name, args) }
    }

    private fun friendlyTool(name: String, argsJson: String): String {
        fun arg(key: String): String = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(argsJson)?.groupValues?.get(1)?.trim().orEmpty()
        return when (name) {
            "get_visa_requirements" -> {
                val head = listOfNotNull(arg("destination").ifBlank { null }, arg("purpose").ifBlank { null }).joinToString(", ")
                val cit = arg("citizenship")
                val tail = buildString {
                    if (head.isNotBlank()) append(head)
                    if (cit.isNotBlank()) append(if (isEmpty()) "гражданство $cit" else " · гражданство $cit")
                }
                if (tail.isBlank()) "🔎 Сверяю актуальные визовые требования по официальным источникам…"
                else "🔎 Сверяю актуальные требования: $tail"
            }
            "get_visa_digest" -> "📰 Смотрю свежий визовый дайджест…"
            "list_digest_countries" -> "📋 Проверяю страны в дайджесте…"
            "add_digest_country" -> arg("country").ifBlank { arg("destination") }
                .let { if (it.isBlank()) "➕ Добавляю страну в дайджест…" else "➕ Добавляю в дайджест: $it" }
            else -> "🔧 Обращаюсь к сервису данных…"
        }
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

    /**
     * День 25: собрать блок [БАЗА ЗНАНИЙ] и источники из внутренней базы (RAG) под ответ. Запрос — из
     * последнего сообщения пользователя (или [queryOverride], напр. текущий шаг) + подсказок кейса (страна,
     * цель) для точности ретрива. Пусто = релевантного нет → блок не добавляем (агент идёт на MCP/[СПРАВКА]).
     *
     * **Страновой скоуп:** страна кейса ограничивает МНОЖЕСТВО кандидатов ещё до косинуса ([CountryScope]) —
     * иначе документ чужой страны выигрывает по «общевизовым» словам и агент отвечает по её правилам.
     * Страж ниже — второй, независимый рубеж (сработает, даже если порт подменят другой реализацией).
     */
    private suspend fun ragContext(ctx: TaskContext, history: List<Message>, queryOverride: String? = null): Pair<String, List<KnowledgeHit>> {
        val r = retriever ?: return EMPTY_RAG
        val base = queryOverride?.takeIf { it.isNotBlank() }
            ?: history.lastOrNull { it.role == Role.User }?.text?.trim().orEmpty()
        if (base.isBlank()) return EMPTY_RAG
        val cf = ctx.caseFile
        val hint = listOf(cf.destination, cf.purpose).filter { it.isNotBlank() }.joinToString(" ")
        val query = if (hint.isBlank()) base else "$hint $base"
        val scope = CountryScope.scopeFor(
            destination = cf.destination,
            fallbackText = history.lastOrNull { it.role == Role.User }?.text.orEmpty(),
        )
        val hits = runCatchingCancellable { r.retrieve(query, scope) }.getOrDefault(emptyList())
            .filter { scope.allows(it.source) }   // страж выдачи: чужая страна не проходит и здесь
        if (hits.isEmpty()) return EMPTY_RAG
        val block = buildString {
            append("[БАЗА ЗНАНИЙ — выдержки из наших внутренних визовых документов; ссылайся на источники как [S1], [S2]. ")
            append("Точные сборы/сроки/куда подавать бери из [СПРАВКА ПО ВИЗЕ] (живые офиц. ссылки), если она есть; ")
            append("общие процедуры и пояснения — отсюда. Не выдумывай фактов сверх этих выдержек.")
            // Честная деградация: страна известна, но своего документа в базе нет — запрещаем перенос чужих правил.
            if (scope.isKnown && !scope.hasCountryDoc) {
                append("\nВНИМАНИЕ: в базе НЕТ документа по стране «").append(cf.destination).append("» — ниже только общие материалы. ")
                append("Страновые правила бери из [СПРАВКА ПО ВИЗЕ]/инструментов или честно скажи, что данных по стране нет; ")
                append("НЕ переноси правила других стран.")
            }
            append("]\n")
            hits.forEachIndexed { i, h ->
                append("[S").append(i + 1).append("] (").append(h.source)
                if (h.section.isNotBlank()) append(" › ").append(h.section)
                append(" · ").append(h.chunkId).append(")\n")
                append(h.text.take(RAG_CHUNK_CAP).trim()).append('\n')
            }
            append("[/БАЗА ЗНАНИЙ]")
        }
        return block to hits
    }

    /** Сборка запроса стадии: базовый промпт + роль + [STATE] (+ [БАЗА ЗНАНИЙ]) (+ профиль) + окно истории + инструкция. */
    private suspend fun call(
        rolePrompt: String,
        ctx: TaskContext,
        history: List<Message>,
        profile: UserProfile?,
        instruction: String,
        historyLimit: Int = windowSize,
        guarded: Boolean = false,
        useTools: Boolean = false,
        roleId: String? = null,
        ragBlock: String = "",
        params: LlmParams = LlmParams(temperature = TEMP_DEFAULT),
    ): GatewayResponse {
        val system = buildString {
            append(basePrompt)
            // Текущая дата: без неё модель считает год по памяти (выдаёт сроки в прошлом). Источник истины — часы.
            append("\n\nСегодня: ").append(java.time.LocalDate.now())
            append(". Используй ИМЕННО эту дату для всех расчётов сроков и дедлайнов; не определяй год по памяти.")
            append("\n\n").append(CONDUCTOR)
            append("\n\n").append(rolePrompt)
            // День 20: пер-аккаунт персонализация роли (одобренные пользователем добавки) — только аддитивно.
            val overlay = roleId?.let { promptOverrides[it] }.orEmpty()
            if (overlay.isNotEmpty()) {
                append("\n\n[ПЕРСОНАЛИЗАЦИЯ ДЛЯ ЭТОГО ПОЛЬЗОВАТЕЛЯ — учитывай как уточнение стиля, НЕ нарушая правила выше]\n")
                overlay.forEach { append("• ").append(it).append('\n') }
            }
            append("\n\n").append(ctx.renderStateBlock())
            // День 25: RAG — выдержки из внутренней базы знаний (аддитивно к MCP/[СПРАВКА]).
            if (ragBlock.isNotBlank()) append("\n\n").append(ragBlock)
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
        // Тулы пайплайна (День 19, visa_search/visa_summarize/save_report) — НЕ для основного агента: у него
        // есть get_visa_requirements (богатый research). Их использует только демо-пайплайн «Инструменты MCP».
        val gw = tools
        // supportsTools: не даём инструменты шлюзу, который tool-loop не ведёт (локальная модель) — иначе он их
        // молча уронит (LSP). Тогда toolList пуст → executeTool=null → модель отвечает честно без инструментов.
        val toolList = if (useTools && gw != null && gateway.supportsTools)
            runCatchingCancellable { gw.listTools() }.getOrDefault(emptyList()).filterNot { it.name in PIPELINE_TOOLS }
        else emptyList()
        val executeTool: (suspend (String, String) -> String)? =
            if (gw != null && toolList.isNotEmpty()) { name, args ->
                // Досмотр ПЕРЕД исполнением: «логическую бомбу» не вызываем, а возвращаем модели отказ —
                // она увидит причину и переформулирует/откажется, вместо слепого исполнения опасных args.
                when (val v = toolGuard?.inspect(name, args) ?: ToolCallVerdict.Allow) {
                    is ToolCallVerdict.Allow -> gw.callToolJson(name, args)
                    is ToolCallVerdict.Block ->
                        "ОТКЛОНЕНО стражем безопасности: ${v.reason}. Инструмент НЕ вызван — переформулируй запрос без исполняемых конструкций."
                }
            } else null
        var resp = gateway.complete(messages, toolList, params, executeTool)
        // Двойная защита (День 14): на пользовательских стадиях страж проверяет ответ; при нарушении —
        // одна перегенерация в обоснованный отказ. Инжект инвариантов выше — первый рубеж, страж — второй.
        if (guarded && guard != null) {
            val violation = guard.check(resp.text, invariants)
            if (violation != null) {
                // Переписать в отказ — задача на точность: низкая temperature.
                resp = gateway.complete(
                    messages + Message(Role.Assistant, resp.text) + Message(Role.User, guardFix(violation)),
                    params = LlmParams(temperature = TEMP_PRECISE),
                )
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
            .replace(Regex("\\[/?(ДОСЬЕ|STATE|СПРАВКА)]", RegexOption.IGNORE_CASE), "")
        t = t.lineSequence().filterNot { line ->
            val u = line.trim()
            CONTROL_TAGS.any { u.startsWith(it, ignoreCase = true) }
        }.joinToString("\n")
        return t.trim()
    }

    private companion object {
        /**
         * Температуры под стадии (P2): точность для исполнителя/валидатора/отказа, разнообразие — для
         * генерации вариантов, нейтральная по умолчанию. Управляем параметром явно, а не хардкодом в клиенте.
         */
        const val TEMP_DEFAULT = 0.4
        const val TEMP_PRECISE = 0.2
        const val TEMP_CREATIVE = 0.7

        /** Максимум возвратов валидатора на доработку (защита от петли revise). */
        const val MAX_REVISES = 1

        /** P5: ракурсы консилиума — каждый проверяющий смотрит со своей стороны (перекрёстная проверка). */
        val VALIDATOR_ANGLES = listOf(
            "ПОЛНОТА — все ли необходимые шаги и документы учтены, ничего существенного не пропущено.",
            "СООТВЕТСТВИЕ ДОСЬЕ — совпадает ли результат со страной, целью, датами и числом заявителей из [ДОСЬЕ].",
            "СРОКИ И РИСКИ — реалистичны ли сроки относительно дат поездки и учтены ли риски отказа.",
        )

        /** Предел длины сохраняемого синтеза [TaskContext.research] в [STATE] (баланс «ссылки сохранены / токены»). */
        const val RESEARCH_CAP = 3500

        /** День 25: предел длины одной выдержки [БАЗА ЗНАНИЙ] (RAG) в промпте — баланс «контекст / токены». */
        const val RAG_CHUNK_CAP = 450

        /** Пустой результат RAG (блок + источники) — когда ретривер выключен или релевантного контекста нет. */
        val EMPTY_RAG: Pair<String, List<KnowledgeHit>> = "" to emptyList()

        /** Тулы пайплайна композиции (День 19) — основному агенту не отдаём (их использует только демо-пайплайн). */
        val PIPELINE_TOOLS = setOf("visa_search", "visa_summarize", "save_report")

        val CONTROL_TAGS = listOf("[SIMPLE]", "[READY]", "[ASK]", "[NEED_DOC]", "[STEP_RESULT]", "[VERDICT]", "[PIVOT]")

        /** Слова подтверждения/отказа разворота (разбор «да/нет» кодом). */
        val PIVOT_YES = listOf("да", "давай", "ага", "верно", "начин", "новый", "сброс", "переключ", "хочу друг", "ok", "ок", "yes")
        val PIVOT_NO = listOf("нет", "не надо", "не меняй", "оставь", "продолж", "верни", "тек", "no")

        /** Дирижёрский преамбул — единые правила для ВСЕХ ролей оркестра (инжектится перед ролью). */
        const val CONDUCTOR = "[ОРКЕСТР — общие правила для всех ролей]\n" +
            "• ЕДИНСТВЕННЫЙ источник фактов кейса — блок [ДОСЬЕ] в [STATE]. Не выдумывай факты и не бери их из профиля/фона как данность.\n" +
            "• Не показывай пользователю служебное: управляющие теги, слова «инвариант», «[STATE]», «[ДОСЬЕ]», «[СПРАВКА]». Если " +
            "перечисляешь, что уже знаешь о кейсе — говори «как я понял»/«по вашим данным», а НЕ «досье»/«в досье». Пиши как живой консультант.\n" +
            "• Конкретику (документы, сборы, сроки, куда подавать) И ОФИЦИАЛЬНЫЕ ССЫЛКИ бери из [СПРАВКА ПО ВИЗЕ] и [ДОСЬЕ]. " +
            "Когда даёшь требования/сборы/сроки — ОБЯЗАТЕЛЬНО приводи конкретные официальные URL и дату из [СПРАВКА] (живые ссылки). " +
            "«Уточните на официальном сайте» БЕЗ ссылки — это не ответ; общими словами говори ТОЛЬКО если нужного нет ни в [СПРАВКА], ни у инструмента.\n" +
            "• Если есть блок [БАЗА ЗНАНИЙ] — это выдержки из наших ВНУТРЕННИХ визовых документов (RAG). Опирайся на них " +
            "для процедур и пояснений и ссылайся на источники как [S1], [S2]. Приоритет фактов: точные сборы/сроки/куда " +
            "подавать — из [СПРАВКА] (живые офиц. URL); общие процедуры — из [БАЗА ЗНАНИЙ]. Не выдумывай сверх этих выдержек; " +
            "если в [БАЗА ЗНАНИЙ] и [СПРАВКА] ответа нет — честно скажи об этом.\n" +
            "• Ты — живой визовый эксперт: отвечай содержательно и по делу, без сухих отписок и канцелярита.\n" +
            "• Блок [ПЕРСОНАЛИЗАЦИЯ] (если есть) уточняет твой стиль под пользователя, но НЕ отменяет правила безопасности, " +
            "отказа и порядок стадий выше — они приоритетнее.\n" +
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
            "1) Если в досье есть страна+гражданство — вызови get_visa_requirements и кратко покажи ключевые требования, " +
            "СОХРАНИВ конкретные официальные ССЫЛКИ (URL) и дату из ответа — не заменяй их словами «официальные сайты».\n" +
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
            "запись, подача) под «Выбранный подход». Опирайся на [СПРАВКА ПО ВИЗЕ] (актуальные требования, сроки, сборы) — " +
            "шаги должны ей соответствовать. УЧИТЫВАЙ факты досье: даты поездки (дедлайны подачи), число заявителей " +
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
            "ИСТОЧНИКИ И ССЫЛКИ (главное): конкретику по шагу — перечень документов, сборы, сроки, КУДА и КАК подавать — " +
            "бери из [СПРАВКА ПО ВИЗЕ] в [STATE] и ОБЯЗАТЕЛЬНО приводи официальные URL и дату оттуда (живые ссылки, а не " +
            "отписка «уточните на сайте»). Если для ИМЕННО этого шага нужной конкретики в [СПРАВКА] нет — ВЫЗОВИ инструмент " +
            "get_visa_requirements (или другой подходящий) и возьми данные с его ссылками. НЕ выдумывай адреса/цены/телефоны " +
            "по памяти, но и НЕ отделывайся общими словами, если факт можно взять из справки или у инструмента. Хеджируй " +
            "(«уточните на официальном сайте» без ссылки) ТОЛЬКО то, чего реально нет ни в справке, ни у инструмента.\n" +
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
