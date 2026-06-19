package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.DesktopConfig
import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.ModelOption
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.data.ProfileStore
import com.example.adventdesktop.data.resolveLlmConfig
import com.example.adventdesktop.domain.Account
import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.ConversationRepository
import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryExtractor
import com.example.adventdesktop.domain.MemoryStore
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.UserProfile
import com.example.adventdesktop.domain.VisaAgent
import com.example.adventdesktop.domain.WorkingMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val DEFAULT_TITLE = "Новая сессия"
private const val TITLE_MAX = 42
private const val EXTRACT_WINDOW = 4

/**
 * Держатель UI-состояния и оркестратор. Управляет локальными аккаунтами (Day 12): у каждого свои
 * диалоги, память и профиль предпочтений; профиль подмешивается в каждый запрос. Ключи/модель — глобальные.
 */
class ChatState(
    private val accounts: AccountStore,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope
) {
    // --- глобальное (общее для аккаунтов) ---
    var config by mutableStateOf(configStore.load())
        private set
    var model by mutableStateOf(Models.byId(configStore.load().modelId))
        private set
    private var client: LlmClient? = null
    private var agent: VisaAgent? = null
    private var extractorClient: LlmClient? = null
    private var memoryExtractor: MemoryExtractor? = null

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

    // --- состояние чата ---
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
    val hasKey: Boolean get() = agent != null
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
        scope.launch {
            val working = memory?.loadWorking(updated.id) ?: WorkingMemory()
            val longTerm = memory?.loadLongTerm() ?: LongTermMemory()
            activeAgent.ask(updated, working, longTerm, userProfile, fill)
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
        val extractorLlm = resolveLlmConfig(Models.byId("deepseek-chat"), config)
        extractorClient = extractorLlm?.let { LlmClient(it) }
        memoryExtractor = extractorClient?.let { MemoryExtractor(it) }
    }

    private fun refreshList() {
        conversationList = conversations?.listMetas() ?: emptyList()
    }

    private fun titleFrom(text: String): String {
        val single = text.replace("\n", " ").trim()
        return if (single.length <= TITLE_MAX) single else single.take(TITLE_MAX).trimEnd() + "…"
    }
}
