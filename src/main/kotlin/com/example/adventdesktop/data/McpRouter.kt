package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolGateway

/**
 * Мультисервер-маршрутизатор MCP (День 20, оркестрация: «несколько MCP-серверов»). Реализует тот же порт
 * [ToolGateway], но внутри — НЕСКОЛЬКО серверов. Агент видит объединённый список тулзов; вызов
 * [callToolJson]/[callTool] маршрутизируется в сервер-владелец по имени инструмента. При коллизии имён —
 * неймспейсим (`<label>__<tool>`). К описанию тула добавляем метку сервера — видно, откуда он.
 *
 * Подключение best-effort: если один сервер не поднялся, остальные продолжают работать.
 */
class McpRouter(private val servers: List<Pair<String, ToolGateway>>) : ToolGateway {

    /** имя тула (как видит агент) → сервер-владелец. Заполняется в [listTools]. */
    private val owner = mutableMapOf<String, ToolGateway>()

    override suspend fun connect() {
        servers.forEach { (_, gw) -> runCatching { gw.connect() } }
    }

    override suspend fun listTools(): List<Tool> {
        owner.clear()
        val all = mutableListOf<Tool>()
        for ((label, gw) in servers) {
            val tools = runCatching { gw.listTools() }.getOrDefault(emptyList())
            for (t in tools) {
                val name = if (owner.containsKey(t.name)) "${label}__${t.name}" else t.name
                owner[name] = gw
                all += t.copy(name = name, description = "[$label] ${t.description.orEmpty()}".trim())
            }
        }
        return all
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String =
        route(name).callTool(realName(name), arguments)

    override suspend fun callToolJson(name: String, argumentsJson: String): String =
        route(name).callToolJson(realName(name), argumentsJson)

    override suspend fun close() {
        servers.forEach { (_, gw) -> runCatching { gw.close() } }
    }

    private fun route(name: String): ToolGateway =
        owner[name] ?: error("Нет сервера для инструмента «$name» (сначала вызови listTools)")

    /** Снять префикс сервера, добавленный при коллизии имён. */
    private fun realName(name: String): String = name.substringAfter("__", name)
}
