package com.example.adventdesktop.domain

/**
 * Фоновый «агент памяти»: читает последние сообщения диалога и текущую память, извлекает ТОЛЬКО НОВЫЕ
 * факты и раскладывает их по слоям (рабочая: цель/ограничения; долговременная: профиль/решения).
 * Отдельная LLM-роль (служебный вызов, дешёвая модель). Не выдумывает — берёт строго из текста.
 * Любая ошибка/пустой ответ → пустой [MemoryUpdate] (память просто не растёт в этот ход).
 */
class MemoryExtractor(private val gateway: LlmGateway) {

    suspend fun extract(
        recent: List<Message>,
        working: WorkingMemory,
        longTerm: LongTermMemory
    ): MemoryUpdate {
        if (recent.none { it.role == Role.User }) return MemoryUpdate()
        val text = runCatching {
            gateway.complete(
                listOf(
                    Message(Role.System, SYSTEM_PROMPT),
                    Message(Role.User, buildInput(recent, working, longTerm))
                )
            ).text
        }.getOrNull().orEmpty()
        return parse(text)
    }

    private fun buildInput(recent: List<Message>, working: WorkingMemory, longTerm: LongTermMemory): String =
        buildString {
            append("ТЕКУЩАЯ ПАМЯТЬ:\n")
            append("[цель] ").append(working.goal.ifBlank { "—" }).append('\n')
            append("[ограничения] ").append(if (working.constraints.isEmpty()) "—" else working.constraints.joinToString("; ")).append('\n')
            append("[профиль] ").append(longTerm.profile.ifBlank { "—" }).append('\n')
            append("[решения] ").append(if (longTerm.decisions.isEmpty()) "—" else longTerm.decisions.joinToString("; ")).append("\n\n")
            append("ПОСЛЕДНИЕ СООБЩЕНИЯ:\n")
            recent.forEach { append(label(it.role)).append(": ").append(it.text.trim()).append('\n') }
        }

    private fun parse(raw: String): MemoryUpdate {
        var goal: String? = null
        val constraints = mutableListOf<String>()
        val profile = mutableListOf<String>()
        val decisions = mutableListOf<String>()
        var section = Section.NONE

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val header = trimmed.uppercase().removeSuffix(":").trim()
            when (header) {
                "ЦЕЛЬ" -> { section = Section.GOAL; return@forEach }
                "ОГРАНИЧЕНИЯ" -> { section = Section.CONSTRAINTS; return@forEach }
                "ПРОФИЛЬ" -> { section = Section.PROFILE; return@forEach }
                "РЕШЕНИЯ" -> { section = Section.DECISIONS; return@forEach }
            }
            // «ЦЕЛЬ: …» на одной строке
            if (trimmed.uppercase().startsWith("ЦЕЛЬ:")) {
                val v = trimmed.substringAfter(':').trim()
                if (!isNonFact(v)) goal = v
                section = Section.GOAL
                return@forEach
            }
            val item = trimmed.removePrefix("-").removePrefix("•").trim()
            if (isNonFact(item)) return@forEach
            when (section) {
                Section.GOAL -> if (goal == null) goal = item
                Section.CONSTRAINTS -> constraints += item
                Section.PROFILE -> profile += item
                Section.DECISIONS -> decisions += item
                Section.NONE -> {}
            }
        }
        return MemoryUpdate(goal = goal, constraints = constraints, profile = profile, decisions = decisions)
    }

    /** Отсев «не-фактов»: пустышки, плейсхолдеры `<…>` и маркеры догадок/инференса (баги памяти). */
    private fun isNonFact(s: String): Boolean {
        val t = s.trim().lowercase()
        if (t.isEmpty() || t == "—" || t == "-" || t == "нет") return true
        if (t.startsWith("<")) return true
        return NON_FACT_MARKERS.any { t.contains(it) }
    }

    private fun label(role: Role) = when (role) {
        Role.User -> "Пользователь"
        Role.Assistant -> "Агент"
        Role.System -> "Система"
    }

    private enum class Section { NONE, GOAL, CONSTRAINTS, PROFILE, DECISIONS }

    private companion object {
        /** Маркеры догадок/инференса/плейсхолдеров — такие «факты» в память не пишем. */
        val NON_FACT_MARKERS = listOf(
            "нет новой информации", "не опроверг", "уточнено в вопросе", "по умолчанию",
            "предположительно", "возможно,", "наверное", "если пользователь"
        )

        val SYSTEM_PROMPT = """
            Ты — модуль памяти ассистента «визовый специалист». На вход дают текущую память и последние
            сообщения диалога (роли «Пользователь» и «Агент»). Извлеки ТОЛЬКО НОВЫЕ факты, которых ещё НЕТ
            в текущей памяти.

            СТРОГИЕ ПРАВИЛА:
            - Бери факты ТОЛЬКО из реплик ПОЛЬЗОВАТЕЛЯ. Реплики «Агент» — лишь контекст; из них факты НЕ извлекай.
            - НЕ выдумывай и НЕ домысливай. Если пользователь прямо не сказал — не записывай. Никаких
              «по умолчанию», «предположительно», «не опроверг», догадок о цели или стране.
            - ЦЕЛЬ указывай ТОЛЬКО если пользователь явно назвал цель/задачу своими словами.

            Верни строго в таком формате (пустые секции пропускай, без пояснений):

            ЦЕЛЬ: <одна строка — цель, если пользователь её ЯВНО назвал>
            ОГРАНИЧЕНИЯ:
            - <сроки, бюджет, требования, числа и даты — дословно из слов пользователя>
            ПРОФИЛЬ:
            - <устойчивый факт о пользователе из его слов: гражданство, семья, история поездок, предпочтения>
            РЕШЕНИЯ:
            - <принятое решение или договорённость>

            Если новых фактов из слов пользователя нет — ответь одним словом: НЕТ.
        """.trimIndent()
    }
}
