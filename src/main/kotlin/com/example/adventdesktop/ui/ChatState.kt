package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.DesktopConfig
import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.ModelOption
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.data.resolveLlmConfig
import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.ConversationRepository
import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryExtractor
import com.example.adventdesktop.domain.MemoryStore
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.VisaAgent
import com.example.adventdesktop.domain.WorkingMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val DEFAULT_TITLE = "Новая сессия"
private const val TITLE_MAX = 42

/** Сколько последних сообщений отдаём фоновому агенту памяти на извлечение фактов. */
private const val EXTRACT_WINDOW = 4

/** Держатель UI-состояния и оркестратор. Управление памятью — под капотом (без режимов). */
class ChatState(
    private val conversations: ConversationRepository,
    private val memory: MemoryStore,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope
) {
    var config by mutableStateOf(configStore.load())
        private set
    var model by mutableStateOf(Models.byId(configStore.load().modelId))
        private set
    private var client: LlmClient? = null
    private var agent: VisaAgent? = null
    private var extractorClient: LlmClient? = null
    private var memoryExtractor: MemoryExtractor? = null

    var conversationList by mutableStateOf<List<ConversationMeta>>(emptyList())
        private set
    var current by mutableStateOf<Conversation?>(null)
        private set
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        rebuildAgent()
        current = conversations.latest() ?: conversations.create(DEFAULT_TITLE)
        refreshList()
    }

    val messages: List<Message> get() = current?.messages ?: emptyList()
    val hasKey: Boolean get() = agent != null
    val sessionTokens: Int get() = messages.sumOf { it.usage?.total ?: 0 }
    val sessionCost: Double
        get() = messages.sumOf { m -> m.usage?.let { model.costUsd(it.prompt, it.completion) } ?: 0.0 }

    /** Заполнение контекстного окна (0..1) по токенам последнего ответа vs лимит модели. */
    val contextFill: Float
        get() {
            val lastPrompt = messages.lastOrNull { it.usage != null }?.usage?.prompt ?: 0
            return if (model.contextLimit > 0) lastPrompt.toFloat() / model.contextLimit else 0f
        }

    fun send() {
        val text = input.trim()
        val conv = current ?: return
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
        if (isFirstUser) {
            updated = updated.copy(title = titleFrom(text))
        }
        current = updated
        conversations.save(updated)
        refreshList()

        val fill = contextFill
        scope.launch {
            val working = memory.loadWorking(updated.id)
            val longTerm = memory.loadLongTerm()
            activeAgent.ask(updated, working, longTerm, fill)
                .onSuccess { turn ->
                    val withReply = updated
                        .withMessage(Message(Role.Assistant, turn.reply.text, usage = turn.reply.usage))
                        .copy(derived = turn.derived)
                    if (current?.id == updated.id) current = withReply
                    conversations.save(withReply)
                    refreshList()
                    scope.launch { runExtraction(withReply) }
                }
                .onFailure { error = it.message ?: "Ошибка запроса" }
            loading = false
        }
    }

    /** Фоновый агент памяти: тихо извлекает новые факты из последних реплик и пополняет слои памяти. */
    private suspend fun runExtraction(conv: Conversation) {
        val extractor = memoryExtractor ?: return
        val recent = conv.messages.takeLast(EXTRACT_WINDOW)
        val working = memory.loadWorking(conv.id)
        val longTerm = memory.loadLongTerm()
        val update = runCatching { extractor.extract(recent, working, longTerm) }.getOrNull() ?: return
        if (update.isEmpty) return
        update.goal?.let { memory.setGoal(conv.id, it) }
        update.constraints.forEach { memory.addConstraint(conv.id, it) }
        update.profile.forEach { memory.appendProfile(it) }
        update.decisions.forEach { memory.addDecision(it) }
    }

    fun newConversation() {
        current = conversations.create(DEFAULT_TITLE)
        refreshList()
    }

    fun open(id: String) {
        conversations.load(id)?.let { current = it }
    }

    fun deleteConversation(id: String) {
        conversations.delete(id)
        memory.clearWorking(id)
        if (current?.id == id) {
            current = conversations.latest() ?: conversations.create(DEFAULT_TITLE)
        }
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
    fun longTerm(): LongTermMemory = memory.loadLongTerm()
    fun working(): WorkingMemory = current?.let { memory.loadWorking(it.id) } ?: WorkingMemory()
    fun addProfile(line: String) = memory.appendProfile(line)
    fun addDecision(line: String) = memory.addDecision(line)
    fun setGoal(goal: String) { current?.let { memory.setGoal(it.id, goal) } }
    fun addConstraint(value: String) { current?.let { memory.addConstraint(it.id, value) } }
    fun clearWorking() { current?.let { memory.clearWorking(it.id) } }
    fun clearLongTerm() = memory.clearLongTerm()

    fun dispose() {
        client?.close()
        extractorClient?.close()
    }

    private fun rebuildAgent() {
        client?.close()
        extractorClient?.close()
        val llm = resolveLlmConfig(model, config)
        client = llm?.let { LlmClient(it) }
        agent = client?.let { VisaAgent(it) }
        // Фоновый агент памяти всегда на deepseek-chat (для стабильности), если есть ключ DeepSeek.
        val extractorLlm = resolveLlmConfig(Models.byId("deepseek-chat"), config)
        extractorClient = extractorLlm?.let { LlmClient(it) }
        memoryExtractor = extractorClient?.let { MemoryExtractor(it) }
    }

    private fun refreshList() {
        conversationList = conversations.listMetas()
    }

    private fun titleFrom(text: String): String {
        val single = text.replace("\n", " ").trim()
        return if (single.length <= TITLE_MAX) single else single.take(TITLE_MAX).trimEnd() + "…"
    }
}
