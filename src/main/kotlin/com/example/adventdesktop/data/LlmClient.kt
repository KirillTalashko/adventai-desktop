package com.example.adventdesktop.data

import com.example.adventdesktop.domain.GatewayResponse
import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Куда и каким ключом ходить. */
data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String)

@Serializable
private data class WireFunctionCall(val name: String = "", val arguments: String = "")

@Serializable
private data class WireToolCall(val id: String = "", val type: String = "function", val function: WireFunctionCall = WireFunctionCall())

@Serializable
private data class WireMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<WireToolCall>? = null,
    val tool_call_id: String? = null,
)

@Serializable
private data class WireFunctionDef(val name: String, val description: String? = null, val parameters: JsonObject)

@Serializable
private data class WireToolDef(val type: String = "function", val function: WireFunctionDef)

@Serializable
private data class WireRequest(
    val model: String,
    val messages: List<WireMessage>,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val reasoning_effort: String? = null,
    val tools: List<WireToolDef>? = null,
)

@Serializable
private data class WireChoice(val message: WireMessage)

@Serializable
private data class WireUsage(val prompt_tokens: Int = 0, val completion_tokens: Int = 0, val total_tokens: Int = 0)

@Serializable
private data class WireResponse(val choices: List<WireChoice> = emptyList(), val usage: WireUsage? = null)

@Serializable
private data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(val message: String? = null, val code: Int? = null)

/** Реализация порта [LlmGateway] на Ktor + JVM-движок CIO. Ошибки API пробрасываются с понятным текстом. */
class LlmClient(private val config: LlmConfig) : LlmGateway {
    // explicitNulls=false → не слать null-поля; encodeDefaults=true → слать поля с дефолтами
    // (иначе у tool-схем выпадает обязательное `type:"function"` → DeepSeek 400).
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    override suspend fun complete(
        messages: List<Message>,
        tools: List<Tool>,
        params: LlmParams,
        executeTool: (suspend (String, String) -> String)?,
    ): GatewayResponse {
        val wire = messages.mapTo(mutableListOf()) { WireMessage(it.role.wire, it.text) }
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map(::toToolDef)
        var pTok = 0; var cTok = 0; var tTok = 0
        val trace = mutableListOf<String>()
        val results = mutableListOf<ToolResult>()

        repeat(MAX_TOOL_ROUNDS) {
            val parsed = send(wire, toolDefs, params)
            parsed.usage?.let { pTok += it.prompt_tokens; cTok += it.completion_tokens; tTok += it.total_tokens }
            val msg = parsed.choices.firstOrNull()?.message
            val calls = msg?.tool_calls

            if (calls.isNullOrEmpty() || executeTool == null) {
                val text = msg?.content?.trim().orEmpty()
                if (text.isEmpty()) {
                    throw IllegalStateException("Модель вернула пустой ответ (возможно, временный лимит). Смените модель или повторите.")
                }
                return GatewayResponse(text, TokenUsage(pTok, cTok, tTok), trace, results)
            }

            // Модель попросила инструмент(ы): добавляем её ход + результаты вызовов и продолжаем цикл.
            wire.add(WireMessage(role = "assistant", content = msg.content, tool_calls = calls))
            for (c in calls) {
                val result = runCatching { executeTool(c.function.name, c.function.arguments) }
                    .getOrElse { "Ошибка инструмента ${c.function.name}: ${it.message}" }
                trace.add("${c.function.name}(${c.function.arguments})")
                results.add(ToolResult(c.function.name, c.function.arguments, result))
                wire.add(WireMessage(role = "tool", content = result, tool_call_id = c.id))
            }
        }

        // Превышен лимит раундов — финальный запрос без инструментов, чтобы модель дала ответ.
        val finalResp = send(wire, null, params)
        finalResp.usage?.let { pTok += it.prompt_tokens; cTok += it.completion_tokens; tTok += it.total_tokens }
        val text = finalResp.choices.firstOrNull()?.message?.content?.trim()
            ?.ifEmpty { null } ?: "Не удалось получить финальный ответ после вызова инструментов."
        return GatewayResponse(text, TokenUsage(pTok, cTok, tTok), trace, results)
    }

    /** Один HTTP-запрос chat/completions (с опциональными tool-схемами и параметрами генерации). */
    private suspend fun send(wire: List<WireMessage>, toolDefs: List<WireToolDef>?, params: LlmParams): WireResponse {
        val response: HttpResponse = http.post(config.baseUrl) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                WireRequest(
                    config.model, wire,
                    temperature = params.temperature ?: DEFAULT_TEMPERATURE,
                    max_tokens = params.maxTokens,
                    reasoning_effort = params.reasoningEffort,
                    tools = toolDefs,
                )
            )
        }
        if (!response.status.isSuccess()) {
            val raw = response.bodyAsText()
            val message = runCatching { json.decodeFromString<ErrorEnvelope>(raw).error?.message }.getOrNull()
                ?: raw.take(300)
            throw IllegalStateException("Ошибка ${response.status.value}: $message")
        }
        return response.body()
    }

    /** Доменный [Tool] → OpenAI-описание функции (parameters — JSON-схема из MCP). */
    private fun toToolDef(t: Tool): WireToolDef {
        val params = runCatching { json.parseToJsonElement(t.inputSchema ?: "{}").jsonObject }
            .getOrElse { buildJsonObject { put("type", "object") } }
        return WireToolDef(function = WireFunctionDef(t.name, t.description, params))
    }

    fun close() = http.close()

    private companion object {
        /** Предел раундов tool-loop (защита от зацикливания вызовов инструментов). */
        const val MAX_TOOL_ROUNDS = 4

        /** Дефолтная temperature, если стадия/вызов её не задал (как было до P2). */
        const val DEFAULT_TEMPERATURE = 0.4
    }
}
