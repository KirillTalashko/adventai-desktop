package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.CliSkillRunner
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.DesktopConfig
import com.example.adventdesktop.data.DocStore
import com.example.adventdesktop.data.HttpProxy
import com.example.adventdesktop.data.InvariantStore
import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.LlmGatewayClient
import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.data.fetchOllamaModels
import com.example.adventdesktop.data.ModelOption
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.data.ProfileStore
import com.example.adventdesktop.data.ProjectDocsIndex
import com.example.adventdesktop.data.PromptOverride
import com.example.adventdesktop.data.PromptOverrideStore
import com.example.adventdesktop.data.SkillDocs
import com.example.adventdesktop.data.resolveLlmConfig
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.data.KnowledgeIndex
import com.example.adventdesktop.data.OllamaEmbedder
import com.example.adventdesktop.data.HashingEmbedder
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.data.RagKnowledgeRetriever
import com.example.adventdesktop.domain.rag.CitationCheck
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.rag.GoldRetrieval
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.Account
import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.BUILT_IN_INVARIANTS
import com.example.adventdesktop.domain.Invariant
import com.example.adventdesktop.domain.InvariantGuard
import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.ConversationRepository
import com.example.adventdesktop.domain.DevAssistant
import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryExtractor
import com.example.adventdesktop.domain.LlmParams
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
import com.example.adventdesktop.domain.ToolCallGuard
import com.example.adventdesktop.domain.ToolGateway
import com.example.adventdesktop.domain.UserProfile
import com.example.adventdesktop.domain.VISA_SYSTEM_PROMPT
import com.example.adventdesktop.domain.VisaAgent
import com.example.adventdesktop.domain.WorkingMemory
import com.example.adventdesktop.domain.transitionTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.example.adventdesktop.domain.runCatchingCancellable

private const val DEFAULT_TITLE = "Новая сессия"
private const val TITLE_MAX = 42
private const val EXTRACT_WINDOW = 4

/** Предел авто-продвижения стадий за один запуск (защита от зацикливания, напр. бесконечного revise). */
private const val MAX_AUTO_CHAIN = 16

/** День 19: один шаг пайплайна композиции MCP-инструментов — для показа цепочки и передачи данных в UI. */
data class PipelineStep(val tool: String, val title: String, val output: String, val ok: Boolean)

/** День 20: результат одного прогона коннектора (MCP или Skill) — ответ, след вызовов и токены (для сравнения). */
data class ConnectorRun(val reply: String, val steps: List<PipelineStep>, val usage: TokenUsage?)

// --- День 21 (RAG): вью-модели для панели индексации базы знаний ---

/** Статистика одной стратегии chunking для таблицы сравнения. */
data class RagStrategyView(
    val strategy: String, val chunks: Int, val avgChars: Int, val minChars: Int, val maxChars: Int,
    val avgTokens: Int, val sections: Int, val buildMs: Long, val embedderId: String,
)

/** Сравнение двух стратегий chunking (fixed vs structural) + число документов. */
data class RagComparisonView(val docCount: Int, val fixed: RagStrategyView, val structural: RagStrategyView, val contextual: RagStrategyView)

/** Тулы пайплайна композиции (День 19). Их НЕ отдаём основному агенту-консультанту — только пайплайн-демо. */
/** День 31 — префикс команды ассистента разработчика и предел показа списка файлов корпуса. */
private const val HELP_COMMAND = "/help"
private const val DEV_DOCS_PREVIEW = 25

/** Префикс [OllamaEmbedder.id] — по нему восстанавливаем эмбеддер, которым построен индекс доков. */
private const val OLLAMA_EMBEDDER_PREFIX = "ollama:"

/**
 * Опции RAG для агента (дефолт: contextual + реранк + порог) — общие для ретривера и выбора эмбеддера.
 * Уровень файла, а НЕ свойство класса: `rebuildAgent()` вызывается из `init`, то есть до инициализации
 * свойств, объявленных ниже по телу класса (иначе — NPE «parameter options is null» при старте окна).
 */
private val AGENT_RAG_OPTIONS = RagOptions()

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
    private val toolGatewayFactory: (deepseekKey: String?, remoteUrl: String?, remoteToken: String?, includeVisa: Boolean, includeExtra: Boolean) -> ToolGateway,
    /** День 31: отдельный MCP-гейтвей ассистента разработчика (git-инструменты по проекту). */
    private val devToolGatewayFactory: () -> ToolGateway,
    private val scope: CoroutineScope
) {
    // --- глобальное (общее для аккаунтов) ---
    var config by mutableStateOf(configStore.load())
        private set
    var model by mutableStateOf(Models.byId(configStore.load().modelId))
        private set
    private var client: LlmGatewayClient? = null
    private var agent: VisaAgent? = null
    private var orchestrator: TaskOrchestrator? = null
    private var extractorClient: LlmClient? = null
    private var memoryExtractor: MemoryExtractor? = null
    private var offerAgent: OfferAgent? = null
    private var interviewAgent: MockInterviewAgent? = null
    /** Постоянный MCP-гейтвей для оркестратора (Фаза 2): инструменты интервьюеру/ассистенту. */
    private var agentTools: ToolGateway? = null
    /** День 31: индекс документации САМОГО проекта и MCP-гейтвей с git-инструментами (команда `/help`). */
    private var projectDocs: ProjectDocsIndex? = null
    private var devGateway: ToolGateway? = null
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
    // День 20: тест-пинг стороннего MCP (server-everything) в окне «Инструменты MCP».
    var mcpExtraTesting by mutableStateOf(false)
        private set
    var mcpExtraTestResult by mutableStateOf<String?>(null)
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
        // День 31 — команда ассистента разработчика: отвечает по докам проекта + живому git-контексту.
        if (text.startsWith(HELP_COMMAND, ignoreCase = true)) {
            sendDevHelp(text, conv, repo)
            return
        }
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

    // --- День 31: ассистент разработчика (команда /help) ---

    /**
     * Обработка `/help`: идёт НЕ через визового агента, а через [DevAssistant] — RAG по документации
     * САМОГО проекта + живой git-контекст через MCP. Весь вывод — прямо в ленте чата.
     */
    private fun sendDevHelp(raw: String, conv: Conversation, repo: ConversationRepository) {
        val arg = raw.trim().removePrefix(HELP_COMMAND).trim()
        input = ""
        error = null
        loading = true

        val isFirstUser = conv.messages.none { it.role == Role.User }
        var updated = conv.withMessage(Message(Role.User, raw))
        if (isFirstUser) updated = updated.copy(title = titleFrom(raw))
        current = updated
        repo.save(updated)
        refreshList()

        scope.launch {
            val reply = try {
                when {
                    arg.isEmpty() -> devHelpOverview()
                    arg.equals("index", ignoreCase = true) || arg.equals("индекс", ignoreCase = true) -> devReindex()
                    else -> devAnswer(arg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "⚠️ Ошибка ассистента разработчика: ${e.message}"
            } finally {
                loading = false
            }
            val withReply = updated.withMessage(Message(Role.Assistant, reply))
            if (current?.id == updated.id) current = withReply
            repo.save(withReply)
            refreshList()
        }
    }

    /** `/help` без аргумента — что умею, состояние индекса и какие файлы в корпусе. */
    private fun devHelpOverview(): String {
        val index = projectDocs()
        val stats = index.stats()
        val docs = index.documents()
        return buildString {
            appendLine("**Ассистент разработчика** — отвечаю на вопросы об этом проекте по его документации.")
            appendLine()
            appendLine("• `/help <вопрос>` — ответ по докам проекта + текущая git-ветка (через MCP)")
            appendLine("• `/help index` — переиндексировать документацию проекта")
            appendLine()
            if (stats == null) appendLine("⚠️ Индекс ещё не построен — выполните `/help index`.")
            else appendLine("Индекс: **${stats.docCount} документов**, ${stats.chunkCount} чанков, эмбеддер `${stats.embedderId}`.")
            appendLine()
            appendLine("Корпус (${docs.size} файлов):")
            docs.take(DEV_DOCS_PREVIEW).forEach { appendLine("  • ${it.source}") }
            if (docs.size > DEV_DOCS_PREVIEW) append("  … и ещё ${docs.size - DEV_DOCS_PREVIEW}")
        }.trim()
    }

    /** `/help index` — построить индекс по README + markdown из `.claude` + схемам данных; показать состав. */
    private suspend fun devReindex(): String {
        val embedder = newEmbedder()
        return try {
            val index = projectDocs()
            val stats = index.rebuild(embedder)
            val docs = index.documents()
            buildString {
                appendLine(
                    "✅ Документация проекта проиндексирована: **${stats.docCount} документов**, " +
                        "${stats.chunkCount} чанков за ${stats.buildMs} мс (эмбеддер `${stats.embedderId}`).",
                )
                appendLine()
                appendLine("В индексе:")
                docs.take(DEV_DOCS_PREVIEW).forEach { appendLine("  • ${it.source}") }
                if (docs.size > DEV_DOCS_PREVIEW) append("  … и ещё ${docs.size - DEV_DOCS_PREVIEW}")
            }.trim()
        } finally {
            (embedder as? OllamaEmbedder)?.close()
        }
    }

    /** `/help <вопрос>` — ответ по докам проекта; источники и ветка дописываются приложением. */
    private suspend fun devAnswer(question: String): String {
        val gateway = client ?: return noKeyError()
        if (projectDocs().stats() == null) return "⚠️ Индекс доков проекта пуст — сначала выполните `/help index`."

        val answer = DevAssistant(
            gateway = gateway,
            retrieveDocs = { q -> devRetrieve(q) },
            gitContext = { devBranch() },
        ).help(question)

        return buildString {
            append(answer.text)
            answer.branch?.let { appendLine(); appendLine(); append("🌿 Ветка проекта: `$it`") }
            if (answer.sources.isNotEmpty()) {
                appendLine(); appendLine()
                append("📄 Источники: ${answer.sources.joinToString(", ")}")
            }
        }
    }

    /**
     * Эмбеддер ДЛЯ ЗАПРОСА берём по тому, чем построен индекс, а не по тумблеру RAG-панели. Вектора разных
     * моделей несопоставимы, а размерность у обеих 768 — проверка размера не сработает, косинус посчитается
     * «успешно» и вернёт мусор. Симптом был бы тихим: `/help` отвечал бы «в документации этого нет».
     */
    private fun devQueryEmbedder(): Embedder {
        val id = projectDocs().stats()?.embedderId ?: return newEmbedder()
        return if (id.startsWith(OLLAMA_EMBEDDER_PREFIX)) OllamaEmbedder(model = id.removePrefix(OLLAMA_EMBEDDER_PREFIX))
        else HashingEmbedder()
    }

    /** Поиск по индексу доков проекта → доменные [KnowledgeHit] (эмбеддер живёт только на вызов). */
    private suspend fun devRetrieve(query: String): List<KnowledgeHit> {
        val embedder = devQueryEmbedder()
        return try {
            // client — тот же шлюз, что отвечает: он же переписывает вопрос в поисковый запрос с англ. терминами.
            projectDocs().search(embedder, query, gateway = client).map { s ->
                KnowledgeHit(
                    source = s.chunk.meta.source,
                    section = s.chunk.meta.section,
                    chunkId = s.chunk.meta.chunkId,
                    score = s.score,
                    text = s.chunk.text.trim(),
                )
            }
        } finally {
            (embedder as? OllamaEmbedder)?.close()
        }
    }

    /** Текущая git-ветка ЖИВЬЁМ через MCP-инструмент `git_current_branch` (best-effort: нет MCP → null). */
    private suspend fun devBranch(): String? {
        val gateway = devGateway ?: devToolGatewayFactory().also { devGateway = it }
        return runCatchingCancellable { gateway.callTool("git_current_branch") }
            .getOrNull()?.trim()?.ifBlank { null }
    }

    /** Индекс доков проекта: своя база `~/.adventai/devdocs`, корпус читается из рабочего каталога процесса. */
    private fun projectDocs(): ProjectDocsIndex =
        projectDocs ?: ProjectDocsIndex(File(appHomeDir(), "devdocs"), File(System.getProperty("user.dir")))
            .also { projectDocs = it }

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
        else commentOnDocsViaSkill(saved)   // День 20: навык docs (если включён) реагирует на приложенный файл
    }

    /**
     * День 20 — наглядность навыка: после приложения документа навык **docs** (Skill + CLI), если включён,
     * САМ зовёт `visa-cli docs` и комментирует — что уже приложено и чего ещё не хватает по визе. Так видно,
     * что скилл реагирует на загрузку файла (а не просто молчит).
     */
    private fun commentOnDocsViaSkill(justAdded: String) {
        if (!config.skillDocsEnabled) return
        val engine = skillEngine ?: return
        val repo = conversations ?: return
        scope.launch {
            val conv = current ?: return@launch
            val goal = "Пользователь только что приложил документ «$justAdded». Вызови `visa-cli docs check` " +
                "(он покажет содержимое каждого файла). Сверь: на ОДНО ли лицо оформлены документы (одинаковые ФИО) " +
                "и бьются ли даты с поездкой. Кратко скажи, что приложено и чего не хватает по визе; и ВАЖНО — " +
                "если документы похоже на РАЗНЫХ людей или данные противоречат, ЯВНО предупреди (⚠️) и не принимай " +
                "пакет как валидный. Не выдумывай — опирайся на извлечённый текст; если текст не извлёкся (скан), скажи это."
            val run = runCatchingCancellable { engine.run(SkillDocs.load("visa-cli"), conv.messages, goal) }.getOrNull() ?: return@launch
            val trace = run.calls.joinToString("\n") { "🔧 ${it.command}" }
            val text = "🧰 Навык docs (Skill + CLI)\n" + (if (trace.isNotBlank()) "$trace\n\n" else "") + run.reply
            val base = current ?: return@launch
            val withMsg = base.withMessage(Message(Role.Assistant, text, usage = run.usage))
            if (current?.id == withMsg.id) current = withMsg
            repo.save(withMsg)
            refreshList()
        }
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
        mcpExtraTestResult = null
        scope.launch {
            val gateway = toolGatewayFactory(
                resolveLlmConfig(Models.byId("deepseek-chat"), config)?.apiKey,
                config.mcpRemoteUrl.ifBlank { null }, config.mcpRemoteToken.ifBlank { null },
                config.mcpEnabled, config.extraMcpEnabled,
            )
            mcpGateway = gateway
            try {
                runCatchingCancellable {
                    // Таймаут: первый запуск стороннего npx-сервера может качать пакет — но окно не должно висеть вечно.
                    withTimeoutOrNull(40_000) {
                        gateway.connect()
                        gateway.listTools()
                    } ?: error("MCP-сервер не ответил за 40 с (недоступен? первый запуск npx мог скачивать пакет — повторите).")
                }.onSuccess { mcpTools = it }
                    .onFailure { mcpError = it.message ?: "Не удалось подключиться к MCP" }
            } finally {
                mcpConnecting = false   // всегда: иначе окно «Инструменты MCP» зависнет в состоянии подключения
            }
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
            try {
                runCatchingCancellable { gateway.listTools() }
                    .onSuccess { mcpCheckResult = "✓ сервер ответил за ${System.currentTimeMillis() - t0} мс · инструментов: ${it.size}" }
                    .onFailure { mcpCheckResult = "✗ нет ответа: ${it.message}" }
            } finally {
                mcpChecking = false   // всегда: иначе при отмене/сбое спиннер залипнет, а гейт не пустит повтор
            }
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
            try {
                runCatchingCancellable {
                    gateway.callTool(
                        "get_visa_requirements",
                        mapOf("destination" to destination, "citizenship" to citizenship, "purpose" to "туризм"),
                    )
                }
                    .onSuccess { mcpVisaResult = it }
                    .onFailure { mcpVisaResult = "ошибка: ${it.message}" }
            } finally {
                mcpVisaLoading = false   // всегда: иначе при отмене/сбое спиннер залипнет, а гейт не пустит повтор
            }
        }
    }

    /**
     * День 20: тест-прогон tools СТОРОННЕГО MCP (server-everything) — вызывает два его инструмента
     * (`echo` и `add`) и показывает вход→выход каждого. Доказывает, что инструменты второго сервера реально
     * работают (как пайплайн-демо, но для нового MCP).
     */
    fun testExtraMcp() {
        val gateway = mcpGateway ?: return
        if (mcpExtraTesting) return
        mcpExtraTesting = true; mcpExtraTestResult = null
        scope.launch {
            val t0 = System.currentTimeMillis()
            val sb = StringBuilder()
            try {
                runCatchingCancellable { gateway.callTool("echo", mapOf("message" to "ping от приложения")) }
                    .onSuccess { sb.append("🔧 echo(\"ping от приложения\") → ").append(it.replace("\n", " ").take(120)) }
                    .onFailure { sb.append("🔧 echo → ✗ ${it.message}") }
                sb.append('\n')
                runCatchingCancellable { gateway.callTool("get-sum", mapOf("a" to 2, "b" to 3)) }
                    .onSuccess { sb.append("🔧 get-sum(2, 3) → ").append(it.replace("\n", " ").take(120)) }
                    .onFailure { sb.append("🔧 get-sum → ✗ ${it.message}") }
                mcpExtraTestResult = "✓ за ${System.currentTimeMillis() - t0} мс:\n$sb"
            } finally {
                mcpExtraTesting = false   // всегда: иначе при отмене/сбое гейт не пустит повторный прогон
            }
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
            try {
                // Шаг 1 — ПОЛУЧИТЬ данные.
                val r1 = runCatchingCancellable { gateway.callTool("visa_search", mapOf("query" to query)) }
                    .getOrElse { "ошибка: ${it.message}" }
                push(PipelineStep("visa_search", "1. visa_search — получить данные", r1, !r1.startsWith("ошибка")))
                // Шаг 2 — ОБРАБОТАТЬ вывод шага 1.
                val r2 = runCatchingCancellable { gateway.callTool("visa_summarize", mapOf("text" to r1, "focus" to query)) }
                    .getOrElse { "ошибка: ${it.message}" }
                push(PipelineStep("visa_summarize", "2. visa_summarize — обработать вывод шага 1", r2, !r2.startsWith("ошибка")))
                // Шаг 3 — СОХРАНИТЬ вывод шага 2.
                val fname = "visa-report-${System.currentTimeMillis()}.md"
                val r3 = runCatchingCancellable { gateway.callTool("save_report", mapOf("filename" to fname, "content" to r2)) }
                    .getOrElse { "ошибка: ${it.message}" }
                push(PipelineStep("save_report", "3. save_report — сохранить вывод шага 2", r3, !r3.startsWith("ошибка")))
            } finally {
                mcpPipelineRunning = false   // всегда: иначе кнопка «прогнать» останется мёртвой до конца сессии
            }
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
            try {
                runCatchingCancellable {
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
            } finally {
                mcpPipelineRunning = false   // общий флаг с runPipelineDeterministic — сбрасываем так же надёжно
            }
        }
    }

    /** Закрыть окно MCP и остановить серверный подпроцесс. */
    fun closeMcpDialog() {
        mcpDialogOpen = false
        mcpCheckResult = null
        mcpVisaResult = null
        mcpPipelineSteps = emptyList()
        mcpPipelineMode = null
        mcpExtraTestResult = null
        val gateway = mcpGateway
        mcpGateway = null
        scope.launch { runCatchingCancellable { gateway?.close() } }
    }

    // --- День 21: индексация базы знаний (RAG) — пайплайн chunking → эмбеддинги → SQLite-индекс ---

    /** Общий для приложения индекс визовой базы знаний (`~/.adventai/rag/`). */
    private var knowledge: KnowledgeIndex? = null

    var ragOpen by mutableStateOf(false)
        private set
    /** Строить настоящей Ollama (nomic-embed-text) или офлайн-фолбэком (без сети). */
    var ragUseOllama by mutableStateOf(true)
        private set
    var ragBuilding by mutableStateOf(false)
        private set
    var ragProgress by mutableStateOf("")
        private set
    var ragNote by mutableStateOf<String?>(null)
        private set
    var ragComparison by mutableStateOf<RagComparisonView?>(null)
        private set
    var ragDocCount by mutableStateOf(0)
        private set
    var ragQuery by mutableStateOf("Сколько дней можно находиться в Шенгене?")

    private fun knowledge(): KnowledgeIndex =
        knowledge ?: KnowledgeIndex(File(appHomeDir(), "rag")).also { it.seedMissing(); knowledge = it }

    private fun newEmbedder(): Embedder = if (ragUseOllama) OllamaEmbedder() else HashingEmbedder()

    /**
     * Эмбеддер ЗАПРОСА для агентского RAG — по `embedder_id` ИНДЕКСА, а не по тумблеру панели. Иначе при
     * офлайн-фолбэке поиск по ollama-индексу вернёт произвольные документы БЕЗ единой ошибки: размерности
     * совпадают (768), поэтому проверка размера не срабатывает, а ответ тихо строится на случайных выдержках.
     * Тот же приём, что в [devQueryEmbedder] для индекса доков проекта.
     */
    private fun ragQueryEmbedder(): Embedder {
        val id = knowledge().stats(AGENT_RAG_OPTIONS.strategy)?.embedderId ?: return newEmbedder()
        return if (id.startsWith(OLLAMA_EMBEDDER_PREFIX)) OllamaEmbedder(model = id.removePrefix(OLLAMA_EMBEDDER_PREFIX))
        else HashingEmbedder()
    }

    fun chooseRagEmbedder(useOllama: Boolean) { ragUseOllama = useOllama }

    fun openRag() {
        ragOpen = true
        ragNote = null
        val k = knowledge()
        ragDocCount = k.documents().size
        val f = k.stats("fixed")
        val s = k.stats("structural")
        val c = k.stats("contextual")
        ragComparison = if (f != null && s != null && c != null) RagComparisonView(ragDocCount, f.toView(), s.toView(), c.toView()) else null
        // День 28 — установленные Ollama-модели для выбора локальной модели в сравнении (эмбеддеры отфильтрованы).
        scope.launch {
            val m = fetchOllamaModels()
            localLlm.setAvailableModels(m)   // RAG переиспользует список моделей панели «Локальная LLM»
            if (ragLocalModel !in m && m.isNotEmpty()) ragLocalModel = m.first()
        }
    }

    fun closeRag() { ragOpen = false }

    /** Построить индекс ОБЕИХ стратегий выбранным эмбеддером и обновить сравнение. */
    fun buildIndex() {
        if (ragBuilding) return
        ragBuilding = true
        ragNote = null
        ragProgress = "Подготовка…"
        scope.launch {
            val k = knowledge()
            ragDocCount = k.documents().size
            val emb = newEmbedder()
            try {
                k.rebuild(emb).collect { event ->
                    when (event) {
                        is KnowledgeIndex.RebuildEvent.Progress ->
                            ragProgress = "${event.strategy}: ${event.done}/${event.total} чанков"
                        is KnowledgeIndex.RebuildEvent.Completed -> {
                            val c = event.comparison
                            ragComparison = RagComparisonView(c.docCount, c.fixed.toView(), c.structural.toView(), c.contextual.toView())
                            ragProgress = ""
                            ragNote = "Индекс построен (эмбеддер ${emb.id}) для $ragDocCount документов."
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e   // не глотаем отмену — пробрасываем для корректного завершения scope
            } catch (e: Exception) {
                ragProgress = ""
                ragNote = "Ошибка: ${e.message}" +
                    if (ragUseOllama) "\nСовет: запусти Ollama (`ollama serve` + `ollama pull nomic-embed-text`) или выключи Ollama выше — сработает офлайн-фолбэк." else ""
            } finally {
                (emb as? OllamaEmbedder)?.close()
                ragBuilding = false
            }
        }
    }

    private fun IndexStats.toView() = RagStrategyView(
        strategy, chunkCount, avgChars, minChars, maxChars, avgTokens, sectionCount, buildMs, embedderId,
    )

    // --- Неделя 6, День 26: локальная LLM (Ollama) — dev-панель «Локальная LLM» ---
    // --- Неделя 6, День 26: панель «Локальная LLM» вынесена в LocalLlmPanelState (расшивка god-object) ---
    /** Держатель dev-панели локальной LLM (Ollama) + A/B-оптимизация. Список моделей переиспользует панель RAG. */
    val localLlm = LocalLlmPanelState(scope)

    // --- День 30: панель «Обращение к сервису по HTTP» вынесена в ServicePanelState (расшивка god-object) ---
    /** Держатель dev-панели HTTP-сервиса (первый срез из ChatState по SRP). Промпт `/chat` — из панели «Локальная LLM». */
    val service = ServicePanelState(scope) { localLlm.localLlmPrompt }

    /**
     * Общий каркас async-прогона RAG-панели: `launch` → [newEmbedder] → [block] → закрыть эмбеддер → [onDone].
     * Сводит 5 копий одного скелета (ragCompare / ragCompareLocalVsCloud / runGoldAnswers / runGoldRetrieval /
     * runCitationEval). Guard (`if (running) return`), взвод флага и сброс полей — в самом методе (различны);
     * [block] сам решает onSuccess/onFailure. Закрытие эмбеддера теперь в ОДНОМ месте (было 5 копий — там же
     * чинилась «утечка при исключении»).
     */
    private fun ragJob(onDone: () -> Unit, block: suspend (Embedder) -> Unit) {
        scope.launch {
            val emb = newEmbedder()
            block(emb)
            (emb as? OllamaEmbedder)?.close()
            onDone()
        }
    }

    // --- День 22: RAG-ответ (два режима: с RAG / без) + контрольный набор из 10 вопросов ---

    var ragAnswering by mutableStateOf(false)
        private set
    var ragAnswerRag by mutableStateOf<RagAnswer?>(null)
        private set
    var ragAnswerPlain by mutableStateOf<RagAnswer?>(null)
        private set
    var goldRunning by mutableStateOf(false)
        private set
    var goldProgress by mutableStateOf("")
        private set
    var goldAnswers by mutableStateOf<List<GoldAnswer>>(emptyList())
        private set

    // --- День 23: улучшенный поиск (стратегия contextual + реранк + порог + query rewrite) ---
    // Опции пайплайна — публично-изменяемые из панели (без setX, чтобы не конфликтовать со сгенерированными сеттерами).
    var ragStrategy by mutableStateOf("contextual")
    var ragRerank by mutableStateOf(RerankMode.HEURISTIC)
    var ragRewrite by mutableStateOf(false)
    var ragFloor by mutableStateOf(0.50f)
    var ragTrace by mutableStateOf<RetrievalTrace?>(null)
        private set
    var goldRetrievalRunning by mutableStateOf(false)
        private set
    var goldRetrievalProgress by mutableStateOf("")
        private set
    var goldRetrieval by mutableStateOf<List<GoldRetrieval>>(emptyList())
        private set

    /** Текущие настройки пайплайна из состояния панели. */
    private fun ragOptions() = RagOptions(strategy = ragStrategy, rerank = ragRerank, rewrite = ragRewrite, floor = ragFloor)

    /** Сравнить ответ агента С RAG и БЕЗ RAG на текущем вопросе ([ragQuery]). С RAG — по [ragOptions]. */
    fun ragCompare() {
        val q = ragQuery.trim()
        val gw = client
        if (q.isEmpty() || ragAnswering) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        ragAnswering = true
        ragNote = null
        ragAnswerRag = null
        ragAnswerPlain = null
        ragTrace = null
        ragJob({ ragAnswering = false }) { emb ->
            runCatchingCancellable {
                val k = knowledge()
                ragAnswerPlain = k.answer(gw, emb, q, useRag = false)          // без RAG — из общих знаний модели
                val (ans, trace) = k.answerWithTrace(gw, emb, q, ragOptions())  // с RAG — улучшенный пайплайн
                ragAnswerRag = ans
                ragTrace = trace
            }.onFailure { ragNote = "Ошибка ответа: ${it.message}" }
        }
    }

    // --- День 28: RAG локально vs облако (один локальный retrieval → генерация двумя моделями) ---

    /** Результат генерации одной моделью поверх общего retrieval: метка, доступность, ответ, задержка, ошибка. */
    data class RagVsResult(
        val label: String,
        val available: Boolean,
        val answer: RagAnswer?,
        val ms: Long,
        val error: String?,
    )

    var ragVsRunning by mutableStateOf(false)
        private set
    var ragVsLocal by mutableStateOf<RagVsResult?>(null)
        private set
    var ragVsCloud by mutableStateOf<RagVsResult?>(null)
        private set
    /** Локальная модель Ollama для колонки «локаль» (список установленных подтягивается в [openRag]). */
    var ragLocalModel by mutableStateOf(LocalLlmClient.DEFAULT_MODEL)

    /**
     * День 28 — один ЛОКАЛЬНЫЙ retrieval (эмбеддер) → ответ генерируют ЛОКАЛЬНАЯ (Ollama) и ОБЛАЧНАЯ модели
     * поверх ОДНОГО набора чанков. Сравнение честное: контекст идентичен, отличается только генератор.
     * Локальная колонка = RAG полностью без облака. Замеряем задержку и токены.
     */
    fun ragCompareLocalVsCloud() {
        val q = ragQuery.trim()
        if (q.isEmpty() || ragVsRunning) return
        ragVsRunning = true; ragNote = null; ragVsLocal = null; ragVsCloud = null
        ragJob({ ragVsRunning = false }) { emb ->
            val localGw = LocalLlmClient(model = ragLocalModel)
            val cloudGw = resolveLlmConfig(Models.byId("deepseek-chat"), config)?.let { LlmClient(it) }
            runCatchingCancellable {
                val k = knowledge()
                val after = k.retrieveLocal(emb, ragOptions(), q)   // локальный retrieval один раз, без облака
                ragVsLocal = timeRagVs("Локальная · $ragLocalModel", true) { k.generate(localGw, after, q) }
                ragVsCloud = if (cloudGw != null) timeRagVs("Облачная · deepseek-chat", true) { k.generate(cloudGw, after, q) }
                    else RagVsResult("Облачная", false, null, 0, "Нет облачного ключа — RAG работает и без него, чисто локально.")
            }.onFailure { ragNote = "Ошибка сравнения: ${it.message}" }
            runCatchingCancellable { localGw.close() }
            runCatchingCancellable { cloudGw?.close() }
        }
    }

    private suspend fun timeRagVs(label: String, available: Boolean, block: suspend () -> RagAnswer): RagVsResult {
        val start = System.currentTimeMillis()
        return runCatchingCancellable { block() }.fold(
            onSuccess = { RagVsResult(label, available, it, System.currentTimeMillis() - start, null) },
            onFailure = { RagVsResult(label, available, null, System.currentTimeMillis() - start, it.message) },
        )
    }

    /** Подставить вопрос-ловушку (нет в базе) и сравнить — наглядно: без RAG выдумает, с RAG честно откажет. */
    fun askNegativeExample() {
        ragQuery = knowledge().goldQuestions().firstOrNull { it.isNegative }?.question
            ?: "Как оформить визу для экспедиции на Северный полюс?"
        ragCompare()
    }

    /** Прогнать весь набор (Вариант B): по каждому вопросу — ответ С RAG и без RAG, для сравнения качества. */
    fun runGoldAnswers() {
        val gw = client
        if (goldRunning) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        goldRunning = true
        ragNote = null
        goldAnswers = emptyList()
        goldProgress = "Подготовка…"
        ragJob({ goldRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().goldAnswers(gw, emb, ragOptions()) { i, n -> goldProgress = "вопрос $i/$n" }
            }.onSuccess { goldAnswers = it; goldProgress = "" }
                .onFailure { ragNote = "Ошибка прогона набора: ${it.message}"; goldProgress = "" }
        }
    }

    /**
     * День 23: сравнить КАЧЕСТВО ПОИСКА по набору «без фильтра vs с фильтром» — детерминированно (без LLM,
     * если query rewrite выкл). Показывает, что реранк+фильтр поднимают recall и отсекают мусор на ловушке.
     */
    fun runGoldRetrieval() {
        if (goldRetrievalRunning) return
        goldRetrievalRunning = true
        ragNote = null
        goldRetrieval = emptyList()
        goldRetrievalProgress = "Подготовка…"
        ragJob({ goldRetrievalRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().goldRetrieval(client, emb, ragOptions()) { i, n -> goldRetrievalProgress = "вопрос $i/$n" }
            }.onSuccess { goldRetrieval = it; goldRetrievalProgress = "" }
                .onFailure { ragNote = "Ошибка сравнения поиска: ${it.message}"; goldRetrievalProgress = "" }
        }
    }

    // --- День 24: проверка цитат/источников/faithfulness по набору (нужен LLM: генерация + судья) ---
    var citationEvalRunning by mutableStateOf(false)
        private set
    var citationEvalProgress by mutableStateOf("")
        private set
    var citationChecks by mutableStateOf<List<CitationCheck>>(emptyList())
        private set

    /** Прогнать 10 вопросов и проверить: есть источники / есть цитаты / смысл ответа совпал с цитатами. */
    fun runCitationEval() {
        val gw = client
        if (citationEvalRunning) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        citationEvalRunning = true
        ragNote = null
        citationChecks = emptyList()
        citationEvalProgress = "Подготовка…"
        ragJob({ citationEvalRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().citationEval(gw, emb, ragOptions()) { i, n -> citationEvalProgress = "вопрос $i/$n" }
            }.onSuccess { citationChecks = it; citationEvalProgress = "" }
                .onFailure { ragNote = "Ошибка проверки цитат: ${it.message}"; citationEvalProgress = "" }
        }
    }

    // --- День 20: коннекторы агента (переключатели MCP / Skill) + демо-прогон и сравнение токенов ---

    val mcpEnabled: Boolean get() = config.mcpEnabled
    val ragInAgentEnabled: Boolean get() = config.ragInAgentEnabled
    val skillDocsEnabled: Boolean get() = config.skillDocsEnabled
    val skillPromptTuneEnabled: Boolean get() = config.skillPromptTuneEnabled
    val extraMcpEnabled: Boolean get() = config.extraMcpEnabled

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

    /** Переключатель СТОРОННЕГО MCP (server-everything): пересобираем агента — gateway станет маршрутизатором. */
    fun setExtraMcpEnabled(value: Boolean) {
        config = config.copy(extraMcpEnabled = value)
        configStore.save(config)
        rebuildAgent()
    }

    /** День 25: переключатель RAG в агенте — пересобираем оркестратор с/без ретривера по внутренней базе. */
    fun setRagInAgentEnabled(value: Boolean) {
        config = config.copy(ragInAgentEnabled = value)
        configStore.save(config)
        rebuildAgent()
    }

    /** Режим разработчика: только видимость инженерных витрин (без пересборки агента). */
    fun setDeveloperMode(value: Boolean) {
        config = config.copy(developerMode = value)
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
                true, config.extraMcpEnabled,   // «через MCP»: visa нужен для сравнения по токенам
            )
            runCatchingCancellable {
                val tools = gw.listTools()
                val messages = listOf(Message(Role.System, VISA_SYSTEM_PROMPT), Message(Role.User, goal))
                llm.complete(messages, tools) { name, args -> gw.callToolJson(name, args) }
            }.onSuccess { resp ->
                val steps = resp.toolResults.map { PipelineStep(it.name, "🔧 ${it.name}(${it.args.take(60)})", it.result, true) }
                connectorMcpRun = ConnectorRun(resp.text, steps, resp.usage)
            }.onFailure { connectorMcpRun = ConnectorRun("ошибка: ${it.message}", emptyList(), null) }
            if (temp) runCatchingCancellable { gw.close() }
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
            runCatchingCancellable {
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
            runCatchingCancellable {
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
        val offer = runCatchingCancellable { agent.check(ctx.task, justDone) }.getOrNull() ?: return
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
            runCatchingCancellable { ia.turn(taskText, emptyList()) }
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
            runCatchingCancellable { ia.turn(taskText, history) }
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
            runCatchingCancellable { ia.evaluate(taskText, history) }
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
    /**
     * День 25: понятный пользователю список источников из внутренней базы (RAG) под ответом агента. Показываем
     * человекочитаемые «крошки» раздела (в них уже есть название документа), а технический chunk_id/имя файла
     * не выносим — это деталь для dev-панели, а не для клиента.
     */
    private fun renderRagSources(sources: List<KnowledgeHit>): String {
        if (sources.isEmpty()) return ""
        return "\n\n📚 Источники (наша база знаний):\n" + sources.mapIndexed { i, s ->
            "[S${i + 1}] " + s.section.ifBlank { docTitleFromFile(s.source) }
        }.joinToString("\n")
    }

    /** Имя файла базы → человекочитаемый заголовок (fallback, если у чанка нет «крошек» раздела). */
    private fun docTitleFromFile(file: String): String =
        file.substringBeforeLast('.').replace('-', ' ').replaceFirstChar { it.uppercase() }

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
                    // День 25: всегда показываем источники из внутренней базы (RAG) под ответом агента.
                    val text = taskStep.reply.text + renderRagSources(taskStep.reply.sources)
                    updated = updated.withMessage(Message(Role.Assistant, text, usage = taskStep.reply.usage))
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
        val update = runCatchingCancellable { extractor.extract(recent, store.loadWorking(conv.id), store.loadLongTerm()) }
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
        mcpGateway?.let { g -> scope.launch { runCatchingCancellable { g.close() } } }
        agentTools?.let { g -> scope.launch { runCatchingCancellable { g.close() } } }
        // День 31: гасим подпроцесс dev-MCP и закрываем базу индекса доков проекта.
        devGateway?.let { g -> scope.launch { runCatchingCancellable { g.close() } } }
        projectDocs?.close()
    }

    private fun rebuildAgent() {
        // Прокси из настроек — ДО создания клиентов (их Ktor-движки читают HttpProxy при конструировании).
        HttpProxy.url = config.httpProxy
        client?.close()
        extractorClient?.close()
        // День 27 — интеграция локальной LLM в основной чат: если выбрана Ollama-модель, главный шлюз агента =
        // LocalLlmClient (localhost, без прокси и без облака); иначе — облачный LlmClient. Весь агент ниже
        // (VisaAgent/TaskOrchestrator/…) строится из этого client, поэтому чат целиком отвечает выбранной моделью.
        client = if (model.local) LocalLlmClient(model = model.id)
        else resolveLlmConfig(model, config)?.let { LlmClient(it) }

        // Служебный шлюз (память/страж/оффер/CaseExtractor). Облако — дешёвый и стабильный `deepseek`, чтобы не жечь
        // основную модель на рутине. День 27: при локальной модели служебные вызовы идут в ТУ ЖЕ локаль → НИ ОДНОГО
        // обращения в облако (требование «работает без облачных моделей»). Облачный extractorClient создаём лишь для cloud.
        val extractorLlm = if (model.local) null else resolveLlmConfig(Models.byId("deepseek-chat"), config)
        extractorClient = extractorLlm?.let { LlmClient(it) }
        val serviceGateway = if (model.local) client else extractorClient
        memoryExtractor = serviceGateway?.let { MemoryExtractor(it) }
        val guard = serviceGateway?.let { InvariantGuard(it) }
        offerAgent = serviceGateway?.let { OfferAgent(it) }

        // MCP-инструменты для оркестратора (Фаза 2): постоянный гейтвей, лениво подключается при первом вызове.
        // День 18: если задан удалённый MCP (VPS) — агент ходит за инструментами туда (SSE+токен), иначе локально.
        // День 20: если MCP выключен переключателем — гейтвей не создаём (агент идёт без MCP-схем).
        agentTools?.let { old -> scope.launch { runCatchingCancellable { old.close() } } }
        agentTools = if (config.mcpEnabled || config.extraMcpEnabled) toolGatewayFactory(
            extractorLlm?.apiKey,
            config.mcpRemoteUrl.ifBlank { null }, config.mcpRemoteToken.ifBlank { null },
            config.mcpEnabled, config.extraMcpEnabled,
        ) else null

        agent = client?.let { VisaAgent(it, guard) }
        // День 25: RAG в агенте — ретривер по внутренней базе знаний (детерминированно, без LLM). Тумблер в настройках.
        val retriever = if (config.ragInAgentEnabled) RagKnowledgeRetriever(knowledge(), ::ragQueryEmbedder, AGENT_RAG_OPTIONS) else null
        orchestrator = client?.let { TaskOrchestrator(it, guard, tools = agentTools, toolGuard = ToolCallGuard(), serviceGateway = serviceGateway, retriever = retriever).apply { invariants = this@ChatState.invariants } }
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
