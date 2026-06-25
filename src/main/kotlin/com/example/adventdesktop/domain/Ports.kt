package com.example.adventdesktop.domain

/** Расход токенов (из ответа API). */
data class TokenUsage(val prompt: Int, val completion: Int, val total: Int)

/** Ответ шлюза к LLM. */
data class GatewayResponse(val text: String, val usage: TokenUsage?)

/** Порт к LLM — доменный слой не знает про HTTP/Ktor (Clean Architecture). */
interface LlmGateway {
    suspend fun complete(messages: List<Message>): GatewayResponse
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
