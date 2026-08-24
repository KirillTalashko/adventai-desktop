package com.example.adventdesktop.mcp

import com.example.adventdesktop.data.McpClient
import kotlinx.coroutines.runBlocking

/**
 * День 31 — приёмка dev-MCP: поднимаем [DevMcpServer] подпроцессом и дёргаем все его git-инструменты.
 *
 * Запуск:  .\gradlew.bat runDevMcp
 *
 * Ожидаемый результат: список из трёх инструментов, текущая ветка репозитория, короткий git-статус
 * и выборка файлов проекта. Это доказывает, что ассистент получает контекст проекта ЖИВЬЁМ, а не из констант.
 */
fun main() = runBlocking {
    val gateway = McpClient(serverMainClass = "com.example.adventdesktop.mcp.DevMcpServerKt")
    println("→ Подключаюсь к dev-MCP (stdio, подпроцесс)…")
    gateway.connect()
    println("✓ Соединение установлено.")

    val tools = gateway.listTools()
    println("✓ Инструментов: ${tools.size}")
    tools.forEach { println("  • ${it.name} — ${it.description ?: "(без описания)"}") }

    println("\n→ git_current_branch")
    println("  ветка: ${gateway.callTool("git_current_branch")}")

    println("\n→ git_status")
    println(gateway.callTool("git_status").prependIndent("  "))

    println("\n→ list_files(filter=\".claude\")")
    println(gateway.callTool("list_files", mapOf("filter" to ".claude")).prependIndent("  "))

    gateway.close()
    println("\n✓ Готово.")
}
