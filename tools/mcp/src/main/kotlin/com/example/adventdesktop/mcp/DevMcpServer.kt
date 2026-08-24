package com.example.adventdesktop.mcp

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MCP-сервер «Ассистент разработчика» (День 31) — даёт агенту ЖИВОЙ контекст проекта, в котором он работает.
 *
 * Инструменты (все read-only, ничего не меняют в репозитории):
 *  - `git_current_branch` — текущая ветка (минимум по заданию дня);
 *  - `git_status` — короткий статус рабочего дерева;
 *  - `list_files` — файлы проекта под контролем git (с необязательным фильтром по подстроке).
 *
 * Корень проекта: env `PROJECT_ROOT`, иначе рабочий каталог процесса (под `gradlew run` — корень репозитория).
 * Транспорт — stdio: в stdout идёт ТОЛЬКО JSON-RPC, поэтому любая диагностика — в stderr.
 */

/** Корень проекта, в котором выполняем git-команды. */
private fun projectRoot(): File =
    System.getenv("PROJECT_ROOT")?.trim()?.ifBlank { null }?.let(::File)
        ?: File(System.getProperty("user.dir"))

/**
 * Результат внешней команды. [ok] отличает данные от диагностики: `git` пишет `fatal: …` и выходит с кодом
 * 128, а stderr слит в stdout — без явного флага такой текст уехал бы в ответ как «имя ветки».
 */
private data class CommandResult(val ok: Boolean, val output: String)

/**
 * Выполнить команду в [dir]. Ошибка/таймаут → `ok=false` и текст диагностики, а не исключение: ассистент
 * должен деградировать («git недоступен»), а не падать — но и не выдавать ошибку за данные.
 */
private fun runCommand(dir: File, vararg command: String, timeoutSec: Long = 10): CommandResult {
    val process = runCatching {
        ProcessBuilder(*command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
    }.getOrElse { return CommandResult(false, "не удалось запустить ${command.joinToString(" ")}: ${it.message}") }

    return try {
        // ВАЖНО: читаем вывод ДО waitFor. Иначе на большом выводе (напр. `git ls-files` — сотни путей)
        // дочерний процесс блокируется на переполненном pipe-буфере ОС, а waitFor уходит в таймаут.
        // Чтение до EOF само дожидается, пока процесс закончит писать.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(false, "таймаут ${timeoutSec}s: ${command.joinToString(" ")}")
        }
        // Код возврата — единственный надёжный признак: при ошибке git пишет диагностику в тот же поток.
        CommandResult(process.exitValue() == 0, output.trim())
    } catch (e: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        CommandResult(false, "прервано")
    } finally {
        process.destroy()
    }
}

/** Единый текст отказа инструмента — чтобы диагностика git никогда не выглядела как валидные данные. */
private fun failure(what: String, r: CommandResult): CallToolResult =
    CallToolResult(content = listOf(TextContent("⚠️ $what не удалось: ${r.output.lines().firstOrNull().orEmpty().take(200)}")))

private fun buildDevServer(root: File): Server {
    val server = Server(
        Implementation(name = "dev-mcp", version = "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
    )

    // --- Тул 1 (минимум по заданию): текущая git-ветка ---
    server.addTool(
        name = "git_current_branch",
        description = "Текущая git-ветка проекта, в котором работает ассистент. Без входных параметров.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) {
        val r = runCommand(root, "git", "rev-parse", "--abbrev-ref", "HEAD")
        if (!r.ok || r.output.isBlank()) failure("определить ветку", r)
        else CallToolResult(content = listOf(TextContent(r.output)))
    }

    // --- Тул 2: короткий статус рабочего дерева ---
    server.addTool(
        name = "git_status",
        description = "Короткий статус рабочего дерева (git status --short): что изменено, добавлено, не отслеживается.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) {
        val r = runCommand(root, "git", "status", "--short")
        if (!r.ok) failure("получить git-статус", r)
        else {
            val text = if (r.output.isBlank()) "Рабочее дерево чистое."
            else r.output.lineSequence().take(40).joinToString("\n")
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    // --- Тул 3: файлы проекта (под контролем git) ---
    server.addTool(
        name = "list_files",
        description = "Список файлов проекта под контролем git. " +
            "Необязательный параметр filter — подстрока пути (напр. «domain» или «.md»).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filter") { put("type", "string"); put("description", "Подстрока пути для фильтра, напр. «.kt»") }
            },
            required = emptyList(),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        val filter = request.arguments?.get("filter")?.jsonPrimitive?.content?.trim().orEmpty()
        val r = runCommand(root, "git", "ls-files")
        // Без проверки кода возврата «Файлов: 0» было бы неотличимо от честного пустого результата.
        if (!r.ok) failure("получить список файлов", r)
        else {
            val all = r.output.lineSequence().filter { it.isNotBlank() }
            val matched = (if (filter.isBlank()) all else all.filter { it.contains(filter, ignoreCase = true) }).toList()
            val shown = matched.take(60)
            val text = buildString {
                appendLine("Файлов: ${matched.size}${if (filter.isNotBlank()) " (фильтр «$filter»)" else ""}")
                shown.forEach { appendLine("  $it") }
                if (matched.size > shown.size) append("  … и ещё ${matched.size - shown.size}")
            }.trim()
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    return server
}

fun main(): Unit = runBlocking {
    val root = projectRoot()
    // Диагностика — только в stderr (stdout занят JSON-RPC).
    val probe = runCommand(root, "git", "rev-parse", "--abbrev-ref", "HEAD")
    System.err.println(
        "dev-mcp ready: root=${root.absolutePath}, git=" +
            if (probe.ok) probe.output else "НЕДОСТУПЕН (${probe.output.lines().firstOrNull().orEmpty().take(120)})",
    )

    val server = buildDevServer(root)
    val transport = StdioServerTransport(System.`in`.asInput(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
