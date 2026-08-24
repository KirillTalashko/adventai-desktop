package com.example.adventdesktop.mcp

import com.example.adventdesktop.data.McpClient
import kotlinx.coroutines.runBlocking

/**
 * День 16 — приёмка: подключаемся к локальному MCP-серверу (поднимается как подпроцесс)
 * и печатаем список доступных инструментов.
 *
 * Запуск:  .\gradlew.bat runMcpDemo
 *
 * Ожидаемый результат: «Соединение установлено» + перечень тулзов (`ping`, `get_visa_news`).
 */
fun main() = runBlocking {
    val gateway = McpClient()
    println("→ Подключаюсь к MCP-серверу (stdio, подпроцесс)…")
    gateway.connect()
    println("✓ Соединение установлено.")

    val tools = gateway.listTools()
    println("✓ Доступно инструментов: ${tools.size}")
    tools.forEach { t ->
        println("  • ${t.name} — ${t.description ?: "(без описания)"}")
        t.inputSchema?.let { println("      input: $it") }
    }

    println("\n→ Вызов ping…")
    println("  ${gateway.callTool("ping")}")

    println("\n→ Вызов get_visa_requirements(destination=Испания, citizenship=Россия)…")
    println(gateway.callTool("get_visa_requirements", mapOf("destination" to "Испания", "citizenship" to "Россия", "purpose" to "туризм")))

    gateway.close()
    println("\n✓ Готово.")
}
