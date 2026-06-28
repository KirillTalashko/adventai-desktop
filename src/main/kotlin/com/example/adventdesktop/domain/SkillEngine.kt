package com.example.adventdesktop.domain

/**
 * Движок навыка (День 20, Skill + CLI) — текстовый аналог tool-loop для MCP. В отличие от MCP, где схемы
 * всех тулзов идут в КАЖДЫЙ запрос, здесь модели подаётся ОДИН `SKILL.md` (по требованию), а инструмент
 * вызывается строкой `[CLI] visa-cli …`. Цикл: ответ модели → выполнить найденные `[CLI]` через [SkillRunner]
 * → вернуть `[CLI_RESULT] …` → повтор, пока модель не даст финальный ответ без вызовов. Токены считаем
 * отдельно — для сравнения MCP vs Skill.
 *
 * Зависит только от портов [LlmGateway] и [SkillRunner] (Clean Architecture).
 */
class SkillEngine(
    private val gateway: LlmGateway,
    private val runner: SkillRunner,
    private val maxRounds: Int = 6,
) {
    /** Один исполненный вызов навыка: команда CLI и её вывод. */
    data class SkillCall(val command: String, val result: String)

    /** Итог прогона навыка: финальный ответ, след вызовов CLI и суммарные токены. */
    data class SkillRun(val reply: String, val calls: List<SkillCall>, val usage: TokenUsage)

    suspend fun run(skillDoc: String, history: List<Message>, goal: String): SkillRun {
        val messages = mutableListOf(Message(Role.System, "$SYSTEM_PREFIX\n\n$skillDoc"))
        messages.addAll(history.takeLast(8))
        messages.add(Message(Role.User, goal))

        val calls = mutableListOf<SkillCall>()
        var pTok = 0; var cTok = 0; var tTok = 0

        repeat(maxRounds) {
            val resp = gateway.complete(messages)
            resp.usage?.let { pTok += it.prompt; cTok += it.completion; tTok += it.total }

            val commands = parseCli(resp.text)
            if (commands.isEmpty()) {
                return SkillRun(clean(resp.text), calls, TokenUsage(pTok, cTok, tTok))
            }
            // Модель попросила CLI: фиксируем её ход и подкладываем результаты.
            messages.add(Message(Role.Assistant, resp.text))
            val feedback = buildString {
                for (cmd in commands) {
                    val out = runner.run(cmd)
                    calls.add(SkillCall(cmd, out))
                    append("[CLI_RESULT] ").append(cmd).append('\n').append(out).append("\n\n")
                }
                append("Теперь дай человеку финальный ответ по этим данным. Новые [CLI] вызывай только если данных не хватает.")
            }
            messages.add(Message(Role.User, feedback))
        }

        // Лимит раундов исчерпан — просим финальный ответ без новых вызовов.
        val resp = gateway.complete(messages + Message(Role.User, "Дай финальный ответ без вызовов [CLI]."))
        resp.usage?.let { pTok += it.prompt; cTok += it.completion; tTok += it.total }
        return SkillRun(clean(resp.text), calls, TokenUsage(pTok, cTok, tTok))
    }

    /** Команды из строк вида `[CLI] visa-cli …` (поддерживаем и обёртку в ``` ). */
    private fun parseCli(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().removePrefix("`").removeSuffix("`").trim() }
            .mapNotNull { Regex("^\\[CLI]\\s*(.+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** Убрать служебные строки [CLI]/[CLI_RESULT] из финального текста, показываемого пользователю. */
    private fun clean(text: String): String =
        text.lineSequence()
            .filterNot { val t = it.trim(); t.startsWith("[CLI]", true) || t.startsWith("[CLI_RESULT]", true) }
            .joinToString("\n").trim()

    private companion object {
        const val SYSTEM_PREFIX =
            "Ты — визовый специалист с доступом к ЛОКАЛЬНОМУ навыку (CLI). Если задача требует локальных данных " +
                "пользователя, ВЫЗОВИ инструмент: выведи ОТДЕЛЬНОЙ строкой `[CLI] visa-cli …` и дождись строки " +
                "`[CLI_RESULT] …`. Не выдумывай данные — опирайся только на результат. Если навык не нужен — просто ответь."
    }
}
