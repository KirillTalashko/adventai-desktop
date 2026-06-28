package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.CliSkillRunner
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.DesktopConfig
import com.example.adventdesktop.data.DocStore
import com.example.adventdesktop.data.InvariantStore
import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.ModelOption
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.data.ProfileStore
import com.example.adventdesktop.data.PromptOverride
import com.example.adventdesktop.data.PromptOverrideStore
import com.example.adventdesktop.data.SkillDocs
import com.example.adventdesktop.data.resolveLlmConfig
import com.example.adventdesktop.domain.Account
import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.BUILT_IN_INVARIANTS
import com.example.adventdesktop.domain.Invariant
import com.example.adventdesktop.domain.InvariantGuard
import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.ConversationRepository
import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryExtractor
import com.example.adventdesktop.domain.MemoryStore
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.MockInterviewAgent
import com.example.adventdesktop.domain.OfferAgent
import com.example.adventdesktop.domain.PromptProposal
import com.example.adventdesktop.domain.PromptTuneAnalyzer
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.SkillEngine
import com.example.adventdesktop.domain.SkillRunner
import com.example.adventdesktop.domain.TaskContext
import com.example.adventdesktop.domain.TaskOrchestrator
import com.example.adventdesktop.domain.TaskState
import com.example.adventdesktop.domain.TaskStep
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolGateway
import com.example.adventdesktop.domain.UserProfile
import com.example.adventdesktop.domain.VISA_SYSTEM_PROMPT
import com.example.adventdesktop.domain.VisaAgent
import com.example.adventdesktop.domain.WorkingMemory
import com.example.adventdesktop.domain.transitionTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private const val DEFAULT_TITLE = "Новая сессия"
private const val TITLE_MAX = 42
private const val EXTRACT_WINDOW = 4

/** Предел авто-продвижения стадий за один запуск (защита от зацикливания, напр. бесконечного revise). */
private const val MAX_AUTO_CHAIN = 16

/** День 19: один шаг пайплайна композиции MCP-инструментов — для показа цепочки и передачи данных в UI. */
data class PipelineStep(val tool: String, val title: String, val output: String, val ok: Boolean)

/** День 20: результат одного прогона коннектора (MCP или Skill) — ответ, след вызовов и токены (для сравнения). */
data class ConnectorRun(val reply: String, val steps: List<PipelineStep>, val usage: TokenUsage?)

/** Тулы пайплайна композиции (День 19). Их НЕ отдаём основному агенту-консультанту — только пайплайн-демо. */
private val PIPELINE_TOOL_NAMES = setOf("visa_search", "visa_summarize", "save_report")

private const val PIPELINE_AGENT_PROMPT =
    "Ты — оркестратор пайплайна из MCP-инструментов. Доступны: visa_search (поиск → сырые источники), " +
        "visa_summarize (сжать присланный текст), save_report (сохранить контент в файл-отчёт). " +
        "Чтобы выполнить цель пользователя, ВЫЗОВИ их строго по очереди, передавая вывод предыдущего на вход " +
        "следующего: 1) visa_search(query); 2) visa_summarize(text = вывод visa_search); " +
        "3) save_report(filename = осмысленное имя .md по теме, content = вывод visa_summarize). " +
        "Не пропускай шаги и не выдумывай данные. После сохранения кратко подтверди путь к файлу."

/**
 * Держатель UI-состояния и оркестратор. Управляет локальными аккаунтами (Day 12): у каждого свои
 * диалоги, память и профиль предпочтений; профиль подмешивается в каждый запрос. Ключи/модель — глобальные.
 */
class ChatState(
    private val accounts: AccountStore,
    private val configStore: ConfigStore,
    private val toolGatewayFactory: (deepseekKey: String?, remoteUrl: String?, remoteToken: String?) -> ToolGateway,
    private val scope: CoroutineScope
) {
    // --- глобальное (общее для аккаунтов) ---
    var config by mutableStateOf(configStore.load())
        private set
    var model by mutableStateOf(Models.byId(configStore.load().modelId))
        private set
    private var client: LlmClient? = null
    private var agent: VisaAgent? = null
    private var orchestrator: TaskOrchestrator? = null
    private var extractorClient: LlmClient? = null
    private var memoryExtractor: MemoryExtractor? = null
    private var offerAgent: OfferAgent? = null
    private var interviewAgent: MockInterviewAgent? = null
    /** Постоянный MCP-гейтвей для оркестратора (Фаза 2): инструменты интервьюеру/ассистенту. */
    private var agentTools: ToolGateway? = null
    /** День 20: движок локального навыка (Skill + CLI) — альтернатива MCP. */
    private var skillEngine: SkillEngine? = null
    private var skillRunner: SkillRunner? = null
    private var promptAnalyzer: PromptTuneAnalyzer? = null
    private var overrideStore: PromptOverrideStore? = null
    // День 20 (prompt-tune): объявлено ВЫШЕ init — activate() трогает promptProposals на старте.
    var promptTuneRunning by mutableStateOf(false)
        private set
    var promptTuneNote by mutableStateOf<String?>(null)
        private set
    var promptProposals by mutableStateOf<List<PromptProposal>>(emptyList())
        private set
    private var lastAnalyzedMs = 0L

    // --- пробное собеседование (side-сессия; НЕ меняет состояние задачи) ---
    var interviewOpen by mutableStateOf(false)
        private set
    var interviewMessages by mutableStateOf<List<Message>>(emptyList())
        private set
    var interviewLoading by mutableStateOf(false)
        private set
    var interviewFinished by mutableStateOf(false)
        private set
    var interviewInput by mutableStateOf("")

    // --- аккаунт / профиль ---
    var accountList by mutableStateOf<List<Account>>(emptyList())
        private set
    var activeAccount by mutableStateOf<Account?>(null)
        private set
    var profile by mutableStateOf(UserProfile())
        private set
    var needsOnboarding by mutableStateOf(false)
        private set

    private var conversations: ConversationRepository? = null
    private var memory: MemoryStore? = null
    private var profileStore: ProfileStore? = null
    private var docStore: DocStore? = null
    private var invariantStore: InvariantStore? = null

    /** Инварианты (День 14): встроенные жёсткие + пользовательские. Учитываются в каждом ответе. */
    var invariants by mutableStateOf(BUILT_IN_INVARIANTS)
        private set

    /** Момент старта текущей операции стадии (для таймера в статус-строке). */
    var opStartedAtMs by mutableStateOf(0L)
        private set

    /** Длительность последней завершённой операции стадии, сек (для статус-строки в покое). */
    var lastOpSeconds by mutableStateOf(0L)
        private set

    // --- состояние чата ---
    var conversationList by mutableStateOf<List<ConversationMeta>>(emptyList())
        private set
    var current by mutableStateOf<Conversation?>(null)
        private set
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    // --- MCP (День 16): подключение к серверу и список инструментов (демо в окне) ---
    var mcpDialogOpen by mutableStateOf(false)
        private set
    var mcpConnecting by mutableStateOf(false)
        private set
    var mcpTools by mutableStateOf<List<Tool>>(emptyList())
        private set
    var mcpError by mutableStateOf<String?>(null)
        private set
    var mcpChecking by mutableStateOf(false)
        private set
    var mcpCheckResult by mutableStateOf<String?>(null)
        private set
    /** Где живёт сервер: удалённый (развёрнутый на VPS, 24/7) или локальный подпроцесс. */
    val mcpIsRemote: Boolean get() = config.mcpRemoteUrl.isNotBlank()
    val mcpServerUrl: String get() = config.mcpRemoteUrl.ifBlank { "локальный подпроцесс (stdio)" }
    var mcpVisaCountry by mutableStateOf("Испания")
    var mcpVisaCitizenship by mutableStateOf("Россия")
    var mcpVisaLoading by mutableStateOf(false)
        private set
    var mcpVisaResult by mutableStateOf<String?>(null)
        private set
    // --- День 19: композиция MCP-инструментов (пайплайн visa_search → visa_summarize → save_report) ---
    var mcpPipelineQuery by mutableStateOf("Испания туристическая виза для граждан России: документы, сборы, сроки")
    var mcpPipelineRunning by mutableStateOf(false)
        private set
    var mcpPipelineMode by mutableStateOf<String?>(null)
        private set
    var mcpPipelineSteps by mutableStateOf<List<PipelineStep>>(emptyList())
        private set
    private var mcpGateway: ToolGateway? = null

    init {
        rebuildAgent()
        val state = accounts.state()
        accountList = state.accounts
        // Пустой activeId = пользователь вышел из аккаунта → показать выбор. Непустой, но «висячий»
        // (аккаунт удалён) → восстановиться на первый. Нет аккаунтов → онбординг.
        val active = when {
            state.accounts.isEmpty() -> null
            state.activeId.isBlank() -> null
            else -> state.accounts.firstOrNull { it.id == state.activeId } ?: state.accounts.first()
        }
        if (active == null) needsOnboarding = true else activate(active)
    }

    val messages: List<Message> get() = current?.messages ?: emptyList()
    val task: TaskContext? get() = current?.task
    val hasKey: Boolean get() = agent != null
    val lastPromptTokens: Int get() = messages.lastOrNull { it.usage != null }?.usage?.prompt ?: 0
    val sessionTokens: Int get() = messages.sumOf { it.usage?.total ?: 0 }
    val sessionCost: Double
        get() = messages.sumOf { m -> m.usage?.let { model.costUsd(it.prompt, it.completion) } ?: 0.0 }
    val contextFill: Float
        get() {
            val lastPrompt = messages.lastOrNull { it.usage != null }?.usage?.prompt ?: 0
            return if (model.contextLimit > 0) lastPrompt.toFloat() / model.contextLimit else 0f
        }

    // --- аккаунты ---

    fun completeOnboarding(newProfile: UserProfile) {
        val account = accounts.create(newProfile.name.ifBlank { "Профиль" })
        accounts.profiles(account.id).save(newProfile)
        // Засев фактов профиля (имя/описание) в долговременную память с нуля: «ЧТО известно» — в одном слое.
        syncProfileFacts(accounts.memory(account.id), UserProfile(), newProfile)
        accountList = accounts.state().accounts
        activate(account)
    }

    fun startNewAccount() {
        needsOnboarding = true
    }

    fun cancelOnboarding() {
        if (activeAccount != null) needsOnboarding = false
    }

    fun switchAccount(id: String) {
        if (id == activeAccount?.id) return
        accountList.firstOrNull { it.id == id }?.let { activate(it) }
    }

    /** Выход из аккаунта: данные не трогаем, лишь забываем активного и возвращаемся к экрану выбора. */
    fun logout() {
        accounts.setActive("")
        activeAccount = null
        conversations = null
        memory = null
        profileStore = null
        docStore = null
        invariantStore = null
        invariants = BUILT_IN_INVARIANTS
        orchestrator?.invariants = invariants
        current = null
        conversationList = emptyList()
        profile = UserProfile()
        input = ""
        error = null
        needsOnboarding = true
    }

    fun deleteAccount(id: String) {
        accounts.delete(id)
        val state = accounts.state()
        accountList = state.accounts
        if (activeAccount?.id == id) {
            val next = state.accounts.firstOrNull { it.id == state.activeId } ?: state.accounts.firstOrNull()
            if (next != null) activate(next) else { activeAccount = null; needsOnboarding = true }
        }
    }

    fun saveProfile(newProfile: UserProfile) {
        val old = profile
        profileStore?.save(newProfile)
        profile = newProfile
        memory?.let { syncProfileFacts(it, old, newProfile) }
    }

    /**
     * Держит факты профиля (имя/описание) в долговременной памяти актуальными: убирает только
     * изменившиеся старые факты и добавляет новые, не трогая факты, добавленные [MemoryExtractor] из
     * диалога. При онбординге [old] — пустой [UserProfile] (засев с нуля).
     */
    private fun syncProfileFacts(mem: MemoryStore, old: UserProfile, new: UserProfile) {
        val oldFacts = old.factLines()
        val newFacts = new.factLines()
        (oldFacts - newFacts.toSet()).forEach(mem::removeProfile)
        (newFacts - oldFacts.toSet()).forEach(mem::appendProfile)
    }

    private fun activate(account: Account) {
        activeAccount = account
        accounts.setActive(account.id)
        conversations = accounts.conversations(account.id)
        memory = accounts.memory(account.id)
        profileStore = accounts.profiles(account.id)
        docStore = accounts.docs(account.id)
        invariantStore = accounts.invariants(account.id)
        invariants = BUILT_IN_INVARIANTS + (invariantStore?.load() ?: emptyList())
        orchestrator?.invariants = invariants
        overrideStore = accounts.promptOverrides(account.id)   // День 20: персонализация ролей этого аккаунта
        promptProposals = emptyList()
        applyOverrides()
        profile = profileStore?.load() ?: UserProfile(name = account.name)
        current = conversations?.latest() ?: conversations?.create(DEFAULT_TITLE)
        refreshList()
        needsOnboarding = false
    }

    // --- чат ---

    fun send() {
        val text = input.trim()
        val conv = current ?: return
        val repo = conversations ?: return
        val activeAgent = agent
        if (text.isEmpty() || loading) return
        if (activeAgent == null) {
            error = "Нет ключа для провайдера «${model.provider}». Откройте «Настройки»."
            return
        }
        input = ""
        error = null
        loading = true

        val isFirstUser = conv.messages.none { it.role == Role.User }
        var updated = conv.withMessage(Message(Role.User, text))
        if (isFirstUser) updated = updated.copy(title = titleFrom(text))
        current = updated
        repo.save(updated)
        refreshList()

        val fill = contextFill
        val userProfile = profile
        val activeInvariants = invariants
        scope.launch {
            val working = memory?.loadWorking(updated.id) ?: WorkingMemory()
            val longTerm = memory?.loadLongTerm() ?: LongTermMemory()
            activeAgent.ask(updated, working, longTerm, userProfile, activeInvariants, fill)
                .onSuccess { turn ->
                    val withReply = updated
                        .withMessage(Message(Role.Assistant, turn.reply.text, usage = turn.reply.usage))
                        .copy(derived = turn.derived)
                    if (current?.id == updated.id) current = withReply
                    repo.save(withReply)
                    refreshList()
                    scope.launch { runExtraction(withReply) }
                }
                .onFailure { error = it.message ?: "Ошибка запроса" }
            loading = false
        }
    }

    // --- задача (конечный автомат, День 13) ---

    /** Начать задачу: добавить формулировку как сообщение, перевести диалог в режим задачи и запустить INTAKE. */
    fun startTask(text: String) {
        val raw = text.trim()
        val conv = current ?: return
        val repo = conversations ?: return
        if (raw.isEmpty() || loading || orchestrator == null) {
            if (orchestrator == null) error = noKeyError()
            return
        }
        input = ""
        error = null
        val isFirstUser = conv.messages.none { it.role == Role.User }
        var updated = conv.withMessage(Message(Role.User, raw))
        if (isFirstUser) updated = updated.copy(title = titleFrom(raw))
        updated = updated.copy(task = TaskContext(task = raw))   // state = INTAKE
        current = updated
        repo.save(updated)
        refreshList()
        runStage { o, c, h, p -> o.intake(c, h, p) }
    }

    /** Кнопка «Продолжить» — один ход текущей стадии (когда ничего не ждём от пользователя). */
    fun advanceTask() {
        val ctx = current?.task ?: return
        if (loading || ctx.isDone || ctx.paused || ctx.awaiting != Awaiting.NONE) return
        when (ctx.state) {
            TaskState.INTAKE -> runStage { o, c, h, p -> o.intake(c, h, p) }
            TaskState.PLANNING -> runStage { o, c, h, p -> o.proposeOptions(c, h, p) }
            TaskState.EXECUTION, TaskState.VALIDATION -> runStage { o, c, h, p -> o.step(c, h, p) }
            TaskState.DONE -> {}
        }
    }

    /** Ответ пользователя интервьюеру на этапе INTAKE (Awaiting.ANSWER). */
    fun answerTask(text: String) {
        val raw = text.trim()
        val conv = current ?: return
        val repo = conversations ?: return
        val ctx = conv.task ?: return
        if (raw.isEmpty() || loading || ctx.awaiting != Awaiting.ANSWER) return
        input = ""
        val updated = conv.withMessage(Message(Role.User, raw)).copy(task = ctx.copy(awaiting = Awaiting.NONE, prompt = ""))
        current = updated
        repo.save(updated)
        refreshList()
        runStage { o, c, h, p -> o.intake(c, h, p) }
    }

    /** Выбор подхода из 4 вариантов (Awaiting.CHOICE) → построение плана. */
    fun chooseApproach(approach: String) {
        val chosen = approach.trim()
        val conv = current ?: return
        val repo = conversations ?: return
        val ctx = conv.task ?: return
        if (chosen.isEmpty() || loading || ctx.awaiting != Awaiting.CHOICE) return
        input = ""
        val updated = conv.withMessage(Message(Role.User, "Выбран подход: $chosen"))
            .copy(task = ctx.copy(approach = chosen, awaiting = Awaiting.NONE, options = emptyList(), prompt = ""))
        current = updated
        repo.save(updated)
        refreshList()
        runStage { o, c, h, p -> o.buildPlan(c, h, p) }
    }

    /** Приложить файл (кнопка «+»). Если ждали документ — он закрывает запрос и шаг перевыполняется. */
    fun provideDocument(file: File) {
        val conv = current ?: return
        val repo = conversations ?: return
        val ctx = conv.task ?: return
        val ds = docStore ?: return
        if (loading) return
        val saved = ds.save(file) ?: run { error = "Не удалось сохранить файл"; return }
        val label = if (ctx.awaiting == Awaiting.DOCUMENT && ctx.prompt.isNotBlank()) ctx.prompt else saved
        val entry = "$label → $saved"
        // Снять из «ожидают загрузки», если этот документ откладывали ранее.
        val pending = ctx.pending.filterNot { label.isNotBlank() && it.contains(label, ignoreCase = true) }
        val updated = conv.withMessage(Message(Role.User, "Приложен документ: $saved ($label)"))
            .copy(task = ctx.copy(docs = ctx.docs + entry, pending = pending, awaiting = Awaiting.NONE, prompt = ""))
        current = updated
        repo.save(updated)
        refreshList()
        if (ctx.awaiting == Awaiting.DOCUMENT) runStage { o, c, h, p -> o.step(c, h, p) }
    }

    /** Приложить файл под КОНКРЕТНЫЙ документ из «ожидают загрузки» (в т.ч. в DONE): снять его из pending. */
    fun provideDocumentFor(label: String, file: File) {
        val conv = current ?: return
        val repo = conversations ?: return
        val ctx = conv.task ?: return
        val ds = docStore ?: return
        if (loading) return
        val saved = ds.save(file) ?: run { error = "Не удалось сохранить файл"; return }
        val updated = conv.withMessage(Message(Role.User, "Приложен документ: $saved ($label)"))
            .copy(task = ctx.copy(docs = ctx.docs + "$label → $saved", pending = ctx.pending - label))
        current = updated
        repo.save(updated)
        refreshList()
    }

    /**
     * «Приложу позже»: документ откладывается, шаг идёт дальше, а необходимость дозагрузки запоминается
     * в рабочей памяти (для полной картины задачи) и в [TaskContext.pending] (видно валидатору в [STATE]).
     */
    fun deferDocument() {
        val conv = current ?: return
        val repo = conversations ?: return
        val ctx = conv.task ?: return
        if (loading || ctx.awaiting != Awaiting.DOCUMENT) return
        val doc = ctx.prompt.ifBlank { "документ" }
        memory?.addConstraint(conv.id, "Дозагрузить документ: $doc")
        val advanced = ctx.copy(
            done = ctx.done + "Шаг ${ctx.step + 1}: выполнен, ожидает документ «$doc» (приложить позже)",
            pending = ctx.pending + doc, step = ctx.step + 1, awaiting = Awaiting.NONE, prompt = ""
        )
        val nextCtx = if (advanced.total > 0 && advanced.step >= advanced.total) advanced.transitionTo(TaskState.VALIDATION) else advanced
        val updated = conv.copy(task = nextCtx)
        current = updated
        repo.save(updated)
        refreshList()
    }

    fun setTaskPaused(paused: Boolean) {
        val conv = current ?: return
        val ctx = conv.task ?: return
        val updated = conv.copy(task = ctx.copy(paused = paused))
        current = updated
        conversations?.save(updated)
    }

    /** Сброс задачи: диалог возвращается в режим свободного чата, сообщения сохраняются. */
    fun resetTask() {
        val conv = current ?: return
        if (conv.task == null) return
        val updated = conv.copy(task = null)
        current = updated
        conversations?.save(updated)
    }

    // --- MCP (День 16): живая демонстрация подключения в приложении ---

    /** Подключиться к локальному MCP-серверу и получить список инструментов (показ в окне). */
    fun connectMcp() {
        if (mcpConnecting) return
        mcpDialogOpen = true
        mcpConnecting = true
        mcpError = null
        mcpTools = emptyList()
        mcpCheckResult = null
        mcpVisaResult = null
        mcpPipelineSteps = emptyList()
        mcpPipelineMode = null
        scope.launch {
            val gateway = toolGatewayFactory(
                resolveLlmConfig(Models.byId("deepseek-chat"), config)?.apiKey,
                config.mcpRemoteUrl.ifBlank { null }, config.mcpRemoteToken.ifBlank { null },
            )
            mcpGateway = gateway
            runCatching {
                gateway.connect()
                gateway.listTools()
            }.onSuccess { mcpTools = it }
                .onFailure { mcpError = it.message ?: "Не удалось подключиться к MCP" }
            mcpConnecting = false
        }
    }

    /** Реальный round-trip к подключённому серверу (повторный запрос списка инструментов) с таймингом —
     *  наглядно, что ответ приходит ЖИВЬЁМ с развёрнутого сервера. */
    fun checkConnection() {
        val gateway = mcpGateway ?: return
        if (mcpChecking) return
        mcpChecking = true
        mcpCheckResult = null
        scope.launch {
            val t0 = System.currentTimeMillis()
            runCatching { gateway.listTools() }
                .onSuccess { mcpCheckResult = "✓ сервер ответил за ${System.currentTimeMillis() - t0} мс · инструментов: ${it.size}" }
                .onFailure { mcpCheckResult = "✗ нет ответа: ${it.message}" }
            mcpChecking = false
        }
    }

    /** Вызвать инструмент get_visa_requirements (День 17 — умный визовый сервис из приложения). */
    fun callVisaRequirements() {
        val gateway = mcpGateway ?: return
        val destination = mcpVisaCountry.trim()
        val citizenship = mcpVisaCitizenship.trim().ifBlank { "Россия" }
        if (destination.isEmpty() || mcpVisaLoading) return
        mcpVisaLoading = true
        mcpVisaResult = null
        scope.launch {
            runCatching {
                gateway.callTool(
                    "get_visa_requirements",
                    mapOf("destination" to destination, "citizenship" to citizenship, "purpose" to "туризм"),
                )
            }
                .onSuccess { mcpVisaResult = it }
                .onFailure { mcpVisaResult = "ошибка: ${it.message}" }
            mcpVisaLoading = false
        }
    }

    /**
     * День 19 — ДЕТЕРМИНИРОВАННЫЙ пайплайн: код сам вызывает три тула по порядку, передавая вывод
     * предыдущего на вход следующего (visa_search → visa_summarize → save_report). Наглядно видно цепочку
     * и корректность передачи данных между инструментами.
     */
    fun runPipelineDeterministic() {
        val gateway = mcpGateway ?: return
        val query = mcpPipelineQuery.trim()
        if (query.isEmpty() || mcpPipelineRunning) return
        mcpPipelineRunning = true
        mcpPipelineMode = "детерминированный — код вызывает 3 тула по порядку"
        mcpPipelineSteps = emptyList()
        scope.launch {
            val steps = mutableListOf<PipelineStep>()
            fun push(s: PipelineStep) { steps.add(s); mcpPipelineSteps = steps.toList() }
            // Шаг 1 — ПОЛУЧИТЬ данные.
            val r1 = runCatching { gateway.callTool("visa_search", mapOf("query" to query)) }
                .getOrElse { "ошибка: ${it.message}" }
            push(PipelineStep("visa_search", "1. visa_search — получить данные", r1, !r1.startsWith("ошибка")))
            // Шаг 2 — ОБРАБОТАТЬ вывод шага 1.
            val r2 = runCatching { gateway.callTool("visa_summarize", mapOf("text" to r1, "focus" to query)) }
                .getOrElse { "ошибка: ${it.message}" }
            push(PipelineStep("visa_summarize", "2. visa_summarize — обработать вывод шага 1", r2, !r2.startsWith("ошибка")))
            // Шаг 3 — СОХРАНИТЬ вывод шага 2.
            val fname = "visa-report-${System.currentTimeMillis()}.md"
            val r3 = runCatching { gateway.callTool("save_report", mapOf("filename" to fname, "content" to r2)) }
                .getOrElse { "ошибка: ${it.message}" }
            push(PipelineStep("save_report", "3. save_report — сохранить вывод шага 2", r3, !r3.startsWith("ошибка")))
            mcpPipelineRunning = false
        }
    }

    /**
     * День 19 — АГЕНТНЫЙ пайплайн: даём модели те же три тула и цель; tool-loop ([LlmClient]) САМ вызывает их
     * по порядку, передавая данные между ними. Показываем фактический след вызовов (🔧) и итог.
     */
    fun runPipelineAgent() {
        val gateway = mcpGateway ?: return
        val llm = client ?: run { mcpError = noKeyError(); return }
        val query = mcpPipelineQuery.trim()
        if (query.isEmpty() || mcpPipelineRunning) return
        mcpPipelineRunning = true
        mcpPipelineMode = "агентный — LLM сам решает и вызывает тулы (tool-loop)"
        mcpPipelineSteps = emptyList()
        scope.launch {
            val steps = mutableListOf<PipelineStep>()
            runCatching {
                val pipelineTools = gateway.listTools().filter { it.name in PIPELINE_TOOL_NAMES }
                val messages = listOf(
                    Message(Role.System, PIPELINE_AGENT_PROMPT),
                    Message(Role.User, "Цель: по теме «$query» собери актуальную выжимку и сохрани её в файл-отчёт."),
                )
                val resp = llm.complete(messages, pipelineTools) { name, args -> gateway.callToolJson(name, args) }
                resp.toolResults.forEachIndexed { i, tr ->
                    steps.add(PipelineStep(tr.name, "${i + 1}. 🔧 ${tr.name}(${tr.args.take(80)})", tr.result, true))
                }
                steps.add(PipelineStep("(итог)", "Ответ агента", resp.text, true))
                mcpPipelineSteps = steps.toList()
            }.onFailure {
                mcpPipelineSteps = listOf(PipelineStep("(ошибка)", "Сбой пайплайна", it.message ?: "неизвестно", false))
            }
            mcpPipelineRunning = false
        }
    }

    /** Закрыть окно MCP и остановить серверный подпроцесс. */
    fun closeMcpDialog() {
        mcpDialogOpen = false
        mcpCheckResult = null
        mcpVisaResult = null
        mcpPipelineSteps = emptyList()
        mcpPipelineMode = null
        val gateway = mcpGateway
        mcpGateway = null
        scope.launch { runCatching { gateway?.close() } }
    }

    // --- День 20: коннекторы агента (переключатели MCP / Skill) + демо-прогон и сравнение токенов ---

    val mcpEnabled: Boolean get() = config.mcpEnabled
    val skillDocsEnabled: Boolean get() = config.skillDocsEnabled
    val skillPromptTuneEnabled: Boolean get() = config.skillPromptTuneEnabled

    var connectorsOpen by mutableStateOf(false)
        private set
    var connectorAsk by mutableStateOf("Какие документы я уже приложил?")
    var connectorRunning by mutableStateOf(false)
        private set
    var connectorVia by mutableStateOf<String?>(null)
        private set
    var connectorMcpRun by mutableStateOf<ConnectorRun?>(null)
        private set
    var connectorSkillRun by mutableStateOf<ConnectorRun?>(null)
        private set

    fun openConnectors() { connectorsOpen = true; maybeAnalyzePrompts() }
    fun closeConnectors() { connectorsOpen = false }

    /** Переключатель MCP: пересобираем агента (с MCP выключенным агент идёт без MCP-схем). */
    fun setMcpEnabled(value: Boolean) {
        config = config.copy(mcpEnabled = value)
        configStore.save(config)
        rebuildAgent()
    }

    fun setSkillDocsEnabled(value: Boolean) {
        config = config.copy(skillDocsEnabled = value)
        configStore.save(config)
    }

    fun setSkillPromptTuneEnabled(value: Boolean) {
        config = config.copy(skillPromptTuneEnabled = value)
        configStore.save(config)
    }

    /** Спросить агента ЧЕРЕЗ MCP (схемы тулзов грузятся в запрос → tool-loop). Для сравнения с навыком. */
    fun askViaMcp() {
        val llm = client ?: run { error = noKeyError(); return }
        val goal = connectorAsk.trim()
        if (goal.isEmpty() || connectorRunning) return
        connectorRunning = true; connectorVia = "MCP"; connectorMcpRun = null
        scope.launch {
            val temp = agentTools == null
            val gw = agentTools ?: toolGatewayFactory(
                resolveLlmConfig(Models.byId("deepseek-chat"), config)?.apiKey,
                config.mcpRemoteUrl.ifBlank { null }, config.mcpRemoteToken.ifBlank { null },
            )
            runCatching {
                val tools = gw.listTools()
                val messages = listOf(Message(Role.System, VISA_SYSTEM_PROMPT), Message(Role.User, goal))
                llm.complete(messages, tools) { name, args -> gw.callToolJson(name, args) }
            }.onSuccess { resp ->
                val steps = resp.toolResults.map { PipelineStep(it.name, "🔧 ${it.name}(${it.args.take(60)})", it.result, true) }
                connectorMcpRun = ConnectorRun(resp.text, steps, resp.usage)
            }.onFailure { connectorMcpRun = ConnectorRun("ошибка: ${it.message}", emptyList(), null) }
            if (temp) runCatching { gw.close() }
            connectorRunning = false
        }
    }

    /** Спросить агента ЧЕРЕЗ Skill+CLI (один SKILL.md по требованию → текстовый skill-loop). */
    fun askViaSkill() {
        val engine = skillEngine ?: run { error = noKeyError(); return }
        val goal = connectorAsk.trim()
        if (goal.isEmpty() || connectorRunning) return
        connectorRunning = true; connectorVia = "Skill"; connectorSkillRun = null
        scope.launch {
            runCatching {
                engine.run(SkillDocs.load("visa-cli"), emptyList(), goal)
            }.onSuccess { r ->
                val steps = r.calls.map { PipelineStep("visa-cli", "[CLI] ${it.command}", it.result, true) }
                connectorSkillRun = ConnectorRun(r.reply, steps, r.usage)
            }.onFailure { connectorSkillRun = ConnectorRun("ошибка: ${it.message}", emptyList(), null) }
            connectorRunning = false
        }
    }

    // --- День 20: навык prompt-tune (самоулучшение промтов, человек в контуре) ---
    // (состояние promptTuneRunning/promptTuneNote/promptProposals объявлено выше — до init)

    /** Текущая персонализация ролей (одобренные добавки) — для показа и кнопки «Сбросить». */
    val personalization: List<PromptOverride> get() = overrideStore?.load().orEmpty()

    /** Толкнуть оверлеи в оркестратор (вызывать после смены аккаунта / применения / сброса). */
    private fun applyOverrides() {
        orchestrator?.promptOverrides = overrideStore?.asMap() ?: emptyMap()
    }

    /** Анализ диалогов → предложения улучшить промты (через CLI `prompt-tune collect` + LLM-суждение). */
    fun analyzePrompts() {
        val analyzer = promptAnalyzer ?: run { error = noKeyError(); return }
        val runner = skillRunner ?: return
        if (promptTuneRunning) return
        promptTuneRunning = true; promptTuneNote = null
        scope.launch {
            runCatching {
                val data = runner.run("visa-cli prompt-tune collect")
                analyzer.analyze(data)
            }.onSuccess { props ->
                promptProposals = props
                promptTuneNote = if (props.isEmpty()) "Пока нечего предложить — мало сигналов в диалогах." else null
            }.onFailure { promptTuneNote = "Ошибка анализа: ${it.message}" }
            lastAnalyzedMs = System.currentTimeMillis()
            promptTuneRunning = false
        }
    }

    /** Авто-анализ при открытии панели (перило B соблюдено: только ПРЕДЛАГАЕМ, не применяем). */
    private fun maybeAnalyzePrompts() {
        if (!config.skillPromptTuneEnabled || promptTuneRunning || promptProposals.isNotEmpty()) return
        if (System.currentTimeMillis() - lastAnalyzedMs < 60_000) return
        analyzePrompts()
    }

    /** Применить предложение (перило B — только по клику пользователя): аддитивная добавка в оверлей роли. */
    fun applyProposal(p: PromptProposal) {
        overrideStore?.add(p.role, p.add, p.why)
        applyOverrides()
        promptProposals = promptProposals - p
    }

    fun dismissProposal(p: PromptProposal) { promptProposals = promptProposals - p }

    /** Перило D: сбросить всю персонализацию — мгновенный возврат к базовому поведению. */
    fun resetPersonalization() {
        overrideStore?.clear()
        applyOverrides()
        promptProposals = emptyList()
        promptTuneNote = "Персонализация сброшена."
    }

    // --- агент-разведчик: предложение доп-активности (пробное собеседование) ---

    /** После выполненного шага ищет, не предложить ли пробное собеседование (один раз за задачу). */
    private suspend fun maybeOfferInterview(conv: Conversation) {
        val agent = offerAgent ?: return
        val ctx = conv.task ?: return
        if (ctx.interviewOffered || ctx.offer.isNotBlank()) return
        if (ctx.state != TaskState.EXECUTION && ctx.state != TaskState.VALIDATION) return
        val justDone = ctx.plan.getOrNull(ctx.step - 1).orEmpty()
        val offer = runCatching { agent.check(ctx.task, justDone) }.getOrNull() ?: return
        val cur = current ?: return
        if (cur.id != conv.id) return
        val curCtx = cur.task ?: return
        if (curCtx.interviewOffered || curCtx.offer.isNotBlank()) return
        val updated = cur.copy(task = curCtx.copy(offer = offer.title, interviewOffered = true))
        current = updated
        conversations?.save(updated)
    }

    /** Отказ от предложения: убрать плашку (повторно не предлагаем). */
    fun declineOffer() {
        val conv = current ?: return
        val ctx = conv.task ?: return
        if (ctx.offer.isBlank()) return
        val updated = conv.copy(task = ctx.copy(offer = ""))
        current = updated
        conversations?.save(updated)
    }

    /** Принять предложение: открыть окно пробного собеседования (side-сессия, задача не двигается). */
    fun startInterview() {
        val conv = current ?: return
        val ctx = conv.task ?: return
        val ia = interviewAgent ?: run { error = noKeyError(); return }
        if (interviewOpen || interviewLoading) return
        current = conv.copy(task = ctx.copy(offer = ""))   // плашку убрать, согласие принято
        current?.let { conversations?.save(it) }
        interviewMessages = emptyList()
        interviewFinished = false
        interviewInput = ""
        interviewOpen = true
        interviewLoading = true
        val taskText = ctx.task
        scope.launch {
            runCatching { ia.turn(taskText, emptyList()) }
                .onSuccess { interviewMessages = listOf(Message(Role.Assistant, it.text, usage = it.usage)) }
                .onFailure { error = it.message ?: "Ошибка собеседования" }
            interviewLoading = false
        }
    }

    /** Ответ пользователя на вопрос офицера → следующий вопрос. */
    fun interviewSubmit() {
        val text = interviewInput.trim()
        val ia = interviewAgent ?: return
        val taskText = current?.task?.task.orEmpty()
        if (text.isEmpty() || interviewLoading || interviewFinished) return
        interviewInput = ""
        interviewMessages = interviewMessages + Message(Role.User, text)
        val history = interviewMessages
        interviewLoading = true
        scope.launch {
            runCatching { ia.turn(taskText, history) }
                .onSuccess { interviewMessages = interviewMessages + Message(Role.Assistant, it.text, usage = it.usage) }
                .onFailure { error = it.message ?: "Ошибка собеседования" }
            interviewLoading = false
        }
    }

    /** Завершить собеседование и получить оценку готовности. */
    fun finishInterview() {
        val ia = interviewAgent ?: return
        val taskText = current?.task?.task.orEmpty()
        if (interviewLoading || interviewFinished || interviewMessages.isEmpty()) return
        val history = interviewMessages
        interviewLoading = true
        scope.launch {
            runCatching { ia.evaluate(taskText, history) }
                .onSuccess {
                    interviewMessages = interviewMessages + Message(Role.Assistant, it.text, usage = it.usage)
                    interviewFinished = true
                }
                .onFailure { error = it.message ?: "Ошибка оценки" }
            interviewLoading = false
        }
    }

    /** Закрыть окно: вернуться к задаче (она осталась на своём шаге). Итог запоминаем в диалоге. */
    fun closeInterview() {
        val conv = current
        if (conv?.task != null && interviewFinished) {
            val verdict = interviewMessages.lastOrNull { it.role == Role.Assistant }?.text.orEmpty()
            val updated = conv.withMessage(Message(Role.Assistant, "📋 Пройдено пробное собеседование (вне плана).\n\n$verdict"))
            current = updated
            conversations?.save(updated)
            refreshList()
        }
        interviewOpen = false
        interviewMessages = emptyList()
        interviewFinished = false
        interviewInput = ""
    }

    /**
     * Единая точка ввода (кнопки запуска нет — задача часть агента). Нет задачи → старт; уточнение →
     * ответ; выбор → свой вариант; завершено → новая задача; иначе — реплика и ход стадии.
     */
    fun submitComposer() {
        val raw = input.trim()
        if (raw.isEmpty() || loading) return
        val t = task
        when {
            t == null -> startTask(raw)
            t.awaiting == Awaiting.ANSWER -> answerTask(raw)
            t.awaiting == Awaiting.CHOICE -> chooseApproach(raw)
            t.isDone -> askDuringTask(raw)        // задача завершена — отвечаем как помощник по итогу
            else -> askDuringTask(raw)            // во время задачи: вопрос/реплика → ответ, шаги двигает кнопка
        }
    }

    /** Реплика/вопрос во время задачи: агент ОТВЕЧАЕТ по контексту (#2), не продвигая автомат. */
    private fun askDuringTask(text: String) {
        val conv = current ?: return
        val repo = conversations ?: return
        if (conv.task == null) return
        input = ""
        val updated = conv.withMessage(Message(Role.User, text))
        current = updated
        repo.save(updated)
        refreshList()
        runStage { o, c, h, p -> o.assist(c, h, p) }
    }

    /**
     * Запуск стадии с АВТО-ПРОДВИЖЕНИЕМ: выполняет переданную стадию, затем сам цепляет следующие
     * ([nextAutoAction]) до точки, где нужен пользователь (ответ/выбор) или задача завершена. Так не
     * нужно жать «предложить план» и «следующий шаг» — агент идёт сам. Лимит [MAX_AUTO_CHAIN] защищает
     * от зацикливания.
     */
    private fun runStage(firstAction: suspend (TaskOrchestrator, TaskContext, List<Message>, UserProfile?) -> Result<TaskStep>) {
        val conv0 = current ?: return
        val repo = conversations ?: return
        if (conv0.task == null) return
        val orch = orchestrator
        if (loading) return
        if (orch == null) { error = noKeyError(); return }
        error = null
        loading = true
        opStartedAtMs = System.currentTimeMillis()
        val userProfile = profile
        scope.launch {
            var conv = conv0
            var next: (suspend (TaskOrchestrator, TaskContext, List<Message>, UserProfile?) -> Result<TaskStep>)? = firstAction
            var chain = 0
            while (chain < MAX_AUTO_CHAIN) {
                val act = next ?: break
                val ctx = conv.task ?: break
                val result = act(orch, ctx, conv.messages, userProfile)
                if (result.isFailure) { error = result.exceptionOrNull()?.message ?: "Ошибка запроса"; break }
                val taskStep = result.getOrThrow()
                var updated = conv
                if (taskStep.reply.text.isNotBlank()) {
                    updated = updated.withMessage(Message(Role.Assistant, taskStep.reply.text, usage = taskStep.reply.usage))
                }
                // cancel — простой вопрос: ответ дан, режим задачи снимаем (свободный чат).
                updated = updated.copy(task = if (taskStep.cancel) null else taskStep.context)
                if (current?.id == updated.id) current = updated
                repo.save(updated)
                refreshList()
                conv = updated
                next = conv.task?.let { nextAutoAction(it) }
                chain++
            }
            scope.launch { runExtraction(conv) }
            scope.launch { maybeOfferInterview(conv) }
            lastOpSeconds = ((System.currentTimeMillis() - opStartedAtMs) / 1000).coerceAtLeast(0)
            loading = false
        }
    }

    /**
     * Авто-цепляются только «служебные» переходы: уточнение → предложить варианты, и последний
     * шаг → проверка. Шаги ВЫПОЛНЕНИЯ идут пошагово (по «Продолжить»), а перед первым шагом —
     * чекпоинт плана (пользователь видит план и запускает выполнение). Документы не блокируют.
     */
    private fun nextAutoAction(
        ctx: TaskContext
    ): (suspend (TaskOrchestrator, TaskContext, List<Message>, UserProfile?) -> Result<TaskStep>)? {
        if (ctx.isDone || ctx.awaiting != Awaiting.NONE) return null
        return when (ctx.state) {
            TaskState.PLANNING -> if (ctx.plan.isEmpty()) ({ o, c, h, p -> o.proposeOptions(c, h, p) }) else null
            TaskState.VALIDATION -> ({ o, c, h, p -> o.step(c, h, p) })
            else -> null   // EXECUTION — пошагово вручную; INTAKE — ждём ответ
        }
    }

    private fun noKeyError() = "Нет ключа для провайдера «${model.provider}». Откройте «Настройки»."

    private suspend fun runExtraction(conv: Conversation) {
        val extractor = memoryExtractor ?: return
        val store = memory ?: return
        val recent = conv.messages.takeLast(EXTRACT_WINDOW)
        val update = runCatching { extractor.extract(recent, store.loadWorking(conv.id), store.loadLongTerm()) }
            .getOrNull() ?: return
        if (update.isEmpty) return
        update.goal?.let { store.setGoal(conv.id, it) }
        update.constraints.forEach { store.addConstraint(conv.id, it) }
        update.profile.forEach { store.appendProfile(it) }
        update.decisions.forEach { store.addDecision(it) }
    }

    fun newConversation() {
        current = conversations?.create(DEFAULT_TITLE)
        refreshList()
    }

    fun open(id: String) {
        conversations?.load(id)?.let { current = it }
    }

    fun deleteConversation(id: String) {
        val repo = conversations ?: return
        repo.delete(id)
        memory?.clearWorking(id)
        if (current?.id == id) current = repo.latest() ?: repo.create(DEFAULT_TITLE)
        refreshList()
    }

    fun chooseModel(option: ModelOption) {
        model = option
        config = config.copy(modelId = option.id)
        configStore.save(config)
        rebuildAgent()
    }

    fun saveConfig(newConfig: DesktopConfig) {
        configStore.save(newConfig)
        config = newConfig
        model = Models.byId(newConfig.modelId)
        rebuildAgent()
        error = null
    }

    // --- память (диалог «Память») ---
    fun longTerm(): LongTermMemory = memory?.loadLongTerm() ?: LongTermMemory()
    fun working(): WorkingMemory = current?.let { memory?.loadWorking(it.id) } ?: WorkingMemory()
    fun addProfileFact(line: String) { memory?.appendProfile(line) }
    fun addDecision(line: String) { memory?.addDecision(line) }
    fun setGoal(goal: String) { current?.let { memory?.setGoal(it.id, goal) } }
    fun addConstraint(value: String) { current?.let { memory?.addConstraint(it.id, value) } }
    fun clearWorking() { current?.let { memory?.clearWorking(it.id) } }
    fun clearLongTerm() { memory?.clearLongTerm() }

    // --- инварианты (День 14) ---

    fun addInvariant(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        setUserInvariants(userInvariants() + Invariant("usr-${System.currentTimeMillis()}", clean))
    }

    fun removeInvariant(id: String) = setUserInvariants(userInvariants().filterNot { it.id == id })

    fun toggleInvariant(id: String) =
        setUserInvariants(userInvariants().map { if (it.id == id) it.copy(active = !it.active) else it })

    private fun userInvariants(): List<Invariant> = invariants.filterNot { it.builtIn }

    /** Пересобрать список (встроенные + пользовательские), сохранить на диск, обновить оркестратор. */
    private fun setUserInvariants(user: List<Invariant>) {
        invariants = BUILT_IN_INVARIANTS + user
        invariantStore?.save(user)
        orchestrator?.invariants = invariants
    }

    fun dispose() {
        client?.close()
        extractorClient?.close()
        mcpGateway?.let { g -> scope.launch { runCatching { g.close() } } }
        agentTools?.let { g -> scope.launch { runCatching { g.close() } } }
    }

    private fun rebuildAgent() {
        client?.close()
        extractorClient?.close()
        // Служебный клиент (deepseek) — для извлечения памяти и стража инвариантов: дёшево и стабильно.
        val extractorLlm = resolveLlmConfig(Models.byId("deepseek-chat"), config)
        extractorClient = extractorLlm?.let { LlmClient(it) }
        memoryExtractor = extractorClient?.let { MemoryExtractor(it) }
        val guard = extractorClient?.let { InvariantGuard(it) }
        offerAgent = extractorClient?.let { OfferAgent(it) }

        // MCP-инструменты для оркестратора (Фаза 2): постоянный гейтвей, лениво подключается при первом вызове.
        // День 18: если задан удалённый MCP (VPS) — агент ходит за инструментами туда (SSE+токен), иначе локально.
        // День 20: если MCP выключен переключателем — гейтвей не создаём (агент идёт без MCP-схем).
        agentTools?.let { old -> scope.launch { runCatching { old.close() } } }
        agentTools = if (config.mcpEnabled) toolGatewayFactory(
            extractorLlm?.apiKey,
            config.mcpRemoteUrl.ifBlank { null }, config.mcpRemoteToken.ifBlank { null },
        ) else null

        val llm = resolveLlmConfig(model, config)
        client = llm?.let { LlmClient(it) }
        agent = client?.let { VisaAgent(it, guard) }
        orchestrator = client?.let { TaskOrchestrator(it, guard, tools = agentTools).apply { invariants = this@ChatState.invariants } }
        interviewAgent = client?.let { MockInterviewAgent(it) }
        // День 20: навык (Skill + CLI). CLI читает активный аккаунт сам (accounts.json), поэтому id не пробрасываем.
        val runner = CliSkillRunner(accountId = null)
        skillRunner = runner
        skillEngine = client?.let { SkillEngine(it, runner) }
        promptAnalyzer = extractorClient?.let { PromptTuneAnalyzer(it) }
        applyOverrides()
    }

    private fun refreshList() {
        conversationList = conversations?.listMetas() ?: emptyList()
    }

    private fun titleFrom(text: String): String {
        val single = text.replace("\n", " ").trim()
        return if (single.length <= TITLE_MAX) single else single.take(TITLE_MAX).trimEnd() + "…"
    }
}
