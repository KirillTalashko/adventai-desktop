package com.example.adventdesktop.domain

/** Вердикт досмотра tool-call: разрешить или заблокировать с причиной (для трейса/отказа). */
sealed interface ToolCallVerdict {
    data object Allow : ToolCallVerdict
    data class Block(val reason: String) : ToolCallVerdict
}

/**
 * Досмотр ИСХОДЯЩИХ вызовов инструментов ПЕРЕД исполнением — защита от «логических бомб» (лекция MCP:
 * имя встречи/заметки/документа с `rm -rf` → сервер это выполнил). В отличие от [InvariantGuard]
 * (семантическая проверка ТЕКСТА ответа через LLM) — здесь ДЕТЕРМИНИРОВАННЫЙ структурный фильтр
 * аргументов: без обращения к модели, idle-cost = 0, мгновенно. Это разные рубежи защиты.
 *
 * Принцип: блокируем только то, чего НЕ бывает в легитимных доменных аргументах (страна, гражданство,
 * цель поездки) — командные инъекции и деструктив. Обычная пунктуация/текст не трогаются (минимум
 * ложных срабатываний). По умолчанию набор инструментов не ограничивается; при желании можно передать
 * `allowedTools` (read-only allowlist) для defense-in-depth.
 */
class ToolCallGuard(
    private val allowedTools: Set<String>? = null,
    private val maxArgsLength: Int = 8_000,
) {
    fun inspect(name: String, argsJson: String): ToolCallVerdict {
        if (allowedTools != null && name !in allowedTools)
            return ToolCallVerdict.Block("инструмент «$name» не в списке разрешённых")
        if (argsJson.length > maxArgsLength)
            return ToolCallVerdict.Block("аргументы инструмента «$name» подозрительно длинные (${argsJson.length} симв.)")
        val hay = argsJson.lowercase()
        if (DANGEROUS.any { it.containsMatchIn(hay) })
            return ToolCallVerdict.Block("аргументы инструмента «$name» содержат потенциально опасную (исполняемую) конструкцию")
        return ToolCallVerdict.Allow
    }

    private companion object {
        /**
         * «Логические бомбы»: команд-инъекции/деструктив, просочившиеся в аргументы тула. Консервативно —
         * только то, чего не встретишь в названии страны/цели поездки (иначе ложные блокировки легитимных
         * визовых запросов). Пунктуацию саму по себе НЕ ловим — только осмысленные опасные конструкции.
         */
        val DANGEROUS: List<Regex> = listOf(
            Regex("""\brm\s+-[rf]{1,2}\b"""),                              // rm -rf / rm -fr
            Regex("""\brmdir\s+/s\b|\b(del|erase)\s+/[a-z]"""),          // windows-деструктив
            Regex("""\bformat\s+[a-z]:"""),                               // format c:
            Regex("""[`]"""),                                              // ` command substitution `
            Regex("""\$\("""),                                            // $(...)
            Regex("""\.\./\.\./"""),                                      // обход путей (≥2 уровней)
            Regex("""<\s*script\b"""),                                    // html/js-инъекция
            Regex("""\b(drop|truncate)\s+table\b|\bdelete\s+from\b"""),  // sql-деструктив
            Regex("""\|\s*(sh|bash|cmd|powershell)\b"""),                 // pipe в шелл
            Regex("""[;&]{1,2}\s*(rm|del|curl|wget|cat|nc|bash|sh|powershell|cmd)\b"""), // цепочка шелл-команд
        )
    }
}
