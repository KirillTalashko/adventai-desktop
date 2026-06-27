package com.example.adventdesktop.domain

/** Расход токенов (из ответа API). */
data class TokenUsage(val prompt: Int, val completion: Int, val total: Int)

/** Ответ шлюза к LLM. [toolCalls] — след вызванных инструментов (для показа в UI), если были. */
data class GatewayResponse(val text: String, val usage: TokenUsage?, val toolCalls: List<String> = emptyList())

/**
 * Порт к LLM — доменный слой не знает про HTTP/Ktor (Clean Architecture).
 *
 * Если переданы [tools] и [executeTool], реализация ведёт **tool-loop** (День 17, Фаза 2): отдаёт схемы
 * инструментов модели, и когда та просит вызов (`tool_calls`), исполняет его через [executeTool], скармливает
 * результат обратно и продолжает, пока модель не даст финальный ответ. Без них — обычный одиночный запрос.
 */
interface LlmGateway {
    suspend fun complete(
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
        executeTool: (suspend (name: String, argsJson: String) -> String)? = null,
    ): GatewayResponse
}

/**
 * Порт к MCP-инструментам — доменный слой не знает про SDK/процессы/JSON-RPC.
 * День 16: умеет подключиться и вернуть список доступных инструментов.
 * Вызов инструментов (`callTool`) добавится в Дне 17.
 */
interface ToolGateway {
    /** Установить MCP-соединение (поднять транспорт/сессию). */
    suspend fun connect()

    /** Получить список инструментов, объявленных MCP-сервером. */
    suspend fun listTools(): List<Tool>

    /** Вызвать инструмент по имени и вернуть его текстовый результат (День 16: ping → pong). */
    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): String

    /** Вызвать инструмент с аргументами в виде JSON-строки (как их отдаёт LLM в tool-loop, Фаза 2). */
    suspend fun callToolJson(name: String, argumentsJson: String): String

    /** Закрыть соединение и освободить ресурсы. */
    suspend fun close()
}

/** Хранилище диалогов (персист между сессиями). */
interface ConversationRepository {
    fun listMetas(): List<ConversationMeta>
    fun load(id: String): Conversation?
    fun latest(): Conversation?
    fun create(title: String): Conversation
    fun save(conversation: Conversation)
    fun delete(id: String)
}

/** Хранилище памяти: рабочая (per-диалог) и долговременная (per-агент). */
interface MemoryStore {
    fun loadLongTerm(): LongTermMemory
    fun appendProfile(line: String)
    fun removeProfile(line: String)
    fun addDecision(decision: String)
    fun clearLongTerm()

    fun loadWorking(conversationId: String): WorkingMemory
    fun setGoal(conversationId: String, goal: String)
    fun addConstraint(conversationId: String, constraint: String)
    fun clearWorking(conversationId: String)
}
