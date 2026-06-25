package com.example.adventdesktop.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Минимальный MCP-сервер «Визовый специалист» (День 16, Вариант 2).
 *
 * Общается по **stdio**: JSON-RPC идёт через **stdout**, поэтому печатать что-либо в stdout
 * НЕЛЬЗЯ — это сломает протокол. Логи SLF4J заглушены (`slf4j-nop`), диагностику пишем в stderr.
 *
 * Инструменты пока демонстрационные (`ping`, заглушка `get_visa_news`). Реальная логика
 * (парсинг официальных сайтов по визам) появится в Дне 17.
 */
fun main() = runBlocking {
    val server = Server(
        Implementation(name = "visa-mcp", version = "0.1.0"),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    // Тул 1 — проверка связи.
    server.addTool(
        name = "ping",
        description = "Проверка связи: возвращает «pong».",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        CallToolResult(content = listOf(TextContent("pong")))
    }

    // Тул 2 — доменная заглушка (реальный парсинг — День 17).
    server.addTool(
        name = "get_visa_news",
        description = "Свежие визовые новости/изменения по стране назначения (заглушка Дня 16).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("country") {
                    put("type", "string")
                    put("description", "Страна назначения, например «Испания»")
                }
            },
            required = listOf("country"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val country = request.arguments?.get("country")?.jsonPrimitive?.content ?: "—"
        CallToolResult(
            content = listOf(TextContent("[заглушка] Свежих изменений по визе для «$country» пока нет.")),
        )
    }

    // Транспорт stdio: читаем stdin, пишем stdout (API SDK 0.10.0 — вход через Ktor asInput()).
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
