package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolGateway
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File

/**
 * MCP-клиент (Вариант 2, День 16). Реализует доменный порт [ToolGateway]:
 * поднимает наш локальный MCP-сервер как **подпроцесс** и общается с ним по **stdio**
 * (JSON-RPC через stdin/stdout). Так домен не знает ни про SDK, ни про процессы.
 *
 * Сервер запускается тем же JVM и тем же classpath, что и клиент (без сборки fat-jar):
 * `java -cp <текущий classpath> <serverMainClass>`. Для локальной разработки этого достаточно.
 *
 * @param serverMainClass FQN main-класса MCP-сервера (см. `mcp/VisaMcpServer.kt`)
 */
class McpClient(
    private val serverMainClass: String = "com.example.adventdesktop.mcp.VisaMcpServerKt",
) : ToolGateway {

    private val client = Client(
        clientInfo = Implementation(name = "visa-mcp-client", version = "0.1.0"),
    )
    private var serverProcess: Process? = null

    override suspend fun connect() {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8", "-cp", classpath, serverMainClass)
            .redirectError(ProcessBuilder.Redirect.INHERIT) // stderr сервера — в нашу консоль
            .start()
        serverProcess = process

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        client.connect(transport) // выполняет MCP-хэндшейк (initialize)
    }

    override suspend fun listTools(): List<Tool> =
        client.listTools().tools.map { t ->
            val schema = t.inputSchema
            Tool(
                name = t.name,
                description = t.description,
                inputSchema = "type=${schema.type}; required=${schema.required ?: emptyList<String>()}",
            )
        }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String =
        client.callTool(name = name, arguments = arguments)
            .content
            .joinToString("\n") { if (it is TextContent) it.text else it.toString() }

    override suspend fun close() {
        runCatching { client.close() }
        serverProcess?.destroy()
    }
}
