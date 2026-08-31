package com.example.adventdesktop.domain

/** Расход токенов (из ответа API). */
data class TokenUsage(val prompt: Int, val completion: Int, val total: Int)

/** Один исполненный вызов инструмента в tool-loop: имя, аргументы (JSON) и текстовый результат. */
data class ToolResult(val name: String, val args: String, val result: String)

/**
 * Ответ шлюза к LLM. [toolCalls] — след вызванных инструментов (для показа в UI), если были.
 * [toolResults] — те же вызовы С их результатами (День 19): нужны, чтобы переиспользовать тяжёлый
 * синтез `get_visa_requirements` (актуальные данные + официальные ссылки), а не звать инструмент заново.
 */
data class GatewayResponse(
    val text: String,
    val usage: TokenUsage?,
    val toolCalls: List<String> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
)

/**
 * Параметры генерации одного запроса к LLM (P2). Раньше `temperature` была захардкожена в клиенте —
 * теперь её (и `max_tokens` / `reasoning_effort`) задаём ПОД ЗАДАЧУ/СТАДИЮ. `null`-поля провайдеру не
 * отправляются (берётся его дефолт), поэтому существующие вызовы ведут себя ровно как прежде.
 *
 * Зачем: разным стадиям оркестра нужно разное — исполнителю/валидатору важна ТОЧНОСТЬ (низкая
 * temperature), генератору вариантов — РАЗНООБРАЗИЕ (высокая). Понимать и управлять этими параметрами —
 * базовый навык работы с API напрямую, а не через агрегатор, который их скрывает.
 */
data class LlmParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
)

/**
 * Порт к LLM — доменный слой не знает про HTTP/Ktor (Clean Architecture).
 *
 * Если переданы [tools] и [executeTool], реализация ведёт **tool-loop** (День 17, Фаза 2): отдаёт схемы
 * инструментов модели, и когда та просит вызов (`tool_calls`), исполняет его через [executeTool], скармливает
 * результат обратно и продолжает, пока модель не даст финальный ответ. Без них — обычный одиночный запрос.
 * [params] — параметры генерации (temperature и т.п.); по умолчанию — дефолты провайдера.
 */
interface LlmGateway {
    suspend fun complete(
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
        params: LlmParams = LlmParams(),
        executeTool: (suspend (name: String, argsJson: String) -> String)? = null,
    ): GatewayResponse

    /**
     * Ведёт ли реализация tool-loop (День 17). `true` — модели можно дать [Tool]-схемы, и она умеет вызвать их
     * через `executeTool`; `false` — реализация инструменты НЕ исполняет (напр. локальная Ollama на `/api/chat`).
     * Оркестратор ОБЯЗАН проверять флаг и не передавать инструменты в шлюз с `false`. Иначе — молчаливый LSP-разрыв:
     * жирный [complete] принимает `tools`, но часть реализаций их игнорирует без ошибки (локальная модель при
     * включённом MCP теряет инструменты, ответ тихо строится без свежих данных/ссылок).
     */
    val supportsTools: Boolean get() = true
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

/**
 * День 32 (DI): фабрика визового MCP-шлюза. Именованный тип, а НЕ голый
 * `(String?, String?, String?, Boolean, Boolean) -> ToolGateway`, по двум причинам:
 *  1. Koin регистрирует провайдеры по `KClass`, а у функциональных типов аргументы стираются —
 *     два разных `() -> ToolGateway` в графе схлопнулись бы в один `Function0`.
 *  2. Пятерка безымянных параметров на месте вызова читается хуже, чем именованный тип.
 * `operator fun invoke` оставляет все места вызова прежними: `toolGatewayFactory(key, url, ...)`.
 */
fun interface ToolGatewayFactory {
    operator fun invoke(
        deepseekKey: String?,
        remoteUrl: String?,
        remoteToken: String?,
        includeVisa: Boolean,
        includeExtra: Boolean,
    ): ToolGateway
}

/**
 * День 32 (DI): фабрика шлюза ассистента разработчика (`/help`). Отдельный тип от [ToolGatewayFactory] —
 * иначе Koin не отличил бы их в графе (оба стёрлись бы до `FunctionN`), см. коммент выше.
 */
fun interface DevToolGatewayFactory {
    operator fun invoke(): ToolGateway
}

/**
 * Порт к локальному **Skill + CLI** (День 20) — альтернатива MCP. Домен знает лишь «выполни команду нашего
 * CLI и верни текст»; запуск процесса/безопасность — в `data`. Грузится по требованию (idle = 0), в отличие
 * от MCP-схем, которые идут в каждый sampling call.
 */
interface SkillRunner {
    /** Выполнить команду вида «visa-cli <subcommand> [args]»; вернуть stdout (или текст ошибки). */
    suspend fun run(command: String): String
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
