package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolGateway
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSse
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * MCP-клиент. Реализует доменный порт [ToolGateway] в двух режимах:
 *
 * - **Локальный (stdio, Дни 16–17):** поднимает наш MCP-сервер как **подпроцесс** (`java -cp <classpath>
 *   VisaMcpServerKt`) и общается по stdin/stdout (JSON-RPC). Ключ DeepSeek прокидывается серверу через env.
 * - **Удалённый (SSE, День 18):** подключается к развёрнутому на VPS серверу по URL ([sseUrl]); bearer-токен
 *   ([authToken]) шлётся в `Authorization` на КАЖДОМ запросе (через `defaultRequest` — и на SSE, и на POST).
 *
 * Режим выбирается по [sseUrl]: задан → удалённый, иначе → локальный. Домен про это не знает.
 *
 * @param serverMainClass FQN main-класса локального MCP-сервера (`mcp/VisaMcpServer.kt`).
 * @param deepseekApiKey ключ DeepSeek для локального сервера-подпроцесса (его внутренний LLM-агент).
 * @param sseUrl URL удалённого SSE-сервера (напр. `https://mcp-visa.ru`); null → локальный режим.
 * @param authToken bearer-токен для удалённого сервера.
 */
class McpClient(
    private val serverMainClass: String = "com.example.adventdesktop.mcp.VisaMcpServerKt",
    private val deepseekApiKey: String? = null,
    private val sseUrl: String? = null,
    private val authToken: String? = null,
) : ToolGateway {

    private var client: Client? = null
    private var serverProcess: Process? = null
    private var httpClient: HttpClient? = null
    private var connected = false

    private fun requireClient(): Client = client ?: error("MCP-клиент не подключён")

    private suspend fun ensureConnected() {
        if (!connected) connect()
    }

    override suspend fun connect() {
        if (connected) return
        if (sseUrl != null) connectRemote() else connectLocal()
        connected = true
    }

    /** Удалённый режим (День 18): подключение к VPS-серверу по SSE с bearer-токеном. */
    private suspend fun connectRemote() {
        val http = HttpClient(CIO) {
            install(SSE)
            // Долгоживущий SSE-стрим + небыстрые тулзы (research): дефолтный 15-сек requestTimeout CIO убьёт
            // запрос → отключаем (0 = без таймаута). Иначе get/add дайджеста по сети падают по таймауту.
            engine { requestTimeout = 0 }
            // Токен на КАЖДЫЙ запрос: SDK шлёт служебный POST без кастомных заголовков, поэтому defaultRequest.
            if (!authToken.isNullOrBlank()) {
                defaultRequest { header(HttpHeaders.Authorization, "Bearer $authToken") }
            }
        }
        httpClient = http
        client = http.mcpSse(urlString = sseUrl)   // создаёт и подключает Client по SSE
    }

    /** Локальный режим (Дни 16–17): MCP-сервер как подпроцесс на текущем classpath, транспорт stdio. */
    private suspend fun connectLocal() {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val builder = ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8", "-cp", classpath, serverMainClass)
            .redirectError(ProcessBuilder.Redirect.INHERIT) // stderr сервера — в нашу консоль
        if (!deepseekApiKey.isNullOrBlank()) builder.environment()["DEEPSEEK_API_KEY"] = deepseekApiKey
        val process = builder.start()
        serverProcess = process

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        val c = Client(clientInfo = Implementation(name = "visa-mcp-client", version = "0.1.0"))
        c.connect(transport) // MCP-хэндшейк (initialize)
        client = c
    }

    override suspend fun listTools(): List<Tool> {
        ensureConnected()
        return requireClient().listTools().tools.map { t ->
            val schema = t.inputSchema
            // Реальная JSON-схема инструмента — её передаём модели для tool-calling (Фаза 2).
            val schemaJson = buildJsonObject {
                put("type", "object")
                schema.properties?.let { put("properties", it) }
                put("required", JsonArray((schema.required ?: emptyList()).map { JsonPrimitive(it) }))
            }
            Tool(name = t.name, description = t.description, inputSchema = schemaJson.toString())
        }
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String {
        ensureConnected()
        return requireClient().callTool(name = name, arguments = arguments)
            .content
            .joinToString("\n") { if (it is TextContent) it.text else it.toString() }
    }

    override suspend fun callToolJson(name: String, argumentsJson: String): String {
        val args: Map<String, Any?> = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.content ?: v.toString()
            }
        }.getOrDefault(emptyMap())
        return callTool(name, args)
    }

    override suspend fun close() {
        connected = false
        runCatching { client?.close() }
        runCatching { httpClient?.close() }
        serverProcess?.destroy()
        client = null
        httpClient = null
        serverProcess = null
    }
}
