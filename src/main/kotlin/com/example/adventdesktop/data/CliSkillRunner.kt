package com.example.adventdesktop.data

import com.example.adventdesktop.domain.SkillRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Реализация [SkillRunner] (День 20): выполняет наш локальный `visa-cli` отдельным JVM-процессом на текущем
 * classpath (как [McpClient] поднимает локальный сервер). **Безопасность:** исполняем ИСКЛЮЧИТЕЛЬНО `visa-cli`
 * (whitelist), запускаем напрямую через [ProcessBuilder] БЕЗ shell — никакой интерполяции и произвольных
 * команд, поэтому «логическую бомбу» в аргументах исполнить нельзя. Активный аккаунт прокидываем `--account`.
 *
 * @param accountId аккаунт, чьи данные читает CLI; подставляется, если модель не указала `--account`.
 */
class CliSkillRunner(private val accountId: String?) : SkillRunner {

    override suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        val trimmed = command.trim().removePrefix("`").removeSuffix("`").trim()
        // Только наш CLI — ничего больше выполнить нельзя.
        val rest = when {
            trimmed == "visa-cli" -> ""
            trimmed.startsWith("visa-cli ") -> trimmed.removePrefix("visa-cli ").trim()
            else -> return@withContext "⛔ Отказано: разрешён только visa-cli (получено: «${trimmed.take(60)}»)."
        }
        val args = splitArgs(rest).toMutableList()
        if (accountId != null && "--account" !in args) {
            args += listOf("--account", accountId)
        }
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        // UTF-8 на дочернем процессе (вывод кириллицы) и при чтении на нашей стороне — иначе кракозябры в результате.
        val cmd = listOf(
            javaBin, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            // Глушим логи PDFBox (commons-logging) — иначе font-cache WARNING попадут в вывод и в LLM.
            "-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog",
            "-cp", classpath, MAIN_CLASS,
        ) + args
        runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            proc.waitFor()
            out.trim().ifEmpty { "(пустой вывод visa-cli)" }
        }.getOrElse { "Ошибка запуска visa-cli: ${it.message}" }
    }

    /** Разбивка строки на аргументы с учётом двойных кавычек (без shell-семантики). */
    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in s) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> if (sb.isNotEmpty()) { result += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result += sb.toString()
        return result
    }

    private companion object {
        const val MAIN_CLASS = "com.example.adventdesktop.cli.VisaCliMainKt"
    }
}
