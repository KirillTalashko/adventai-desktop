package com.example.adventdesktop.domain

/** Что уходит в модель + обновлённая производная память + сколько реплик свёрнуто. */
data class Assembled(
    val messages: List<Message>,
    val derived: Derived,
    val dropped: Int
)

/**
 * Единый под-капотом конвейер контекста (без пользовательских режимов):
 *
 *   system( базовый промпт + [ДОЛГОВРЕМЕННАЯ] + [РАБОЧАЯ] [+ [РЕЗЮМЕ]] ) + последние N реплик
 *
 * Пока окно свободно (заполнение < [SUMMARY_FILL]) — отправляется вся история. Когда окно заполнено
 * на [SUMMARY_FILL] и выше, старый хвост (всё, что старше последних N реплик) автоматически
 * сворачивается в rolling summary и подмешивается блоком — смысл не теряется, т.к. факты также
 * живут в долговременной/рабочей памяти. Зависит только от порта [LlmGateway] (Clean Architecture).
 */
class ContextAssembler(
    private val gateway: LlmGateway,
    private val systemPrompt: String,
    private val windowSize: Int = 12
) {
    suspend fun assemble(
        conversation: Conversation,
        working: WorkingMemory,
        longTerm: LongTermMemory,
        profile: UserProfile?,
        invariants: List<Invariant>,
        contextFill: Float
    ): Assembled {
        val history = conversation.messages
        var derived = conversation.derived

        // Окно ещё свободно — отправляем всю историю как есть (ничего не теряем).
        if (contextFill < SUMMARY_FILL || history.size <= windowSize) {
            return Assembled(listOf(system(systemBlock(working, longTerm, "", "", profile, invariants))) + history, derived, 0)
        }

        // Заполнение высокое: свернуть старый хвост (за пределами последних N) в УРОВНЕВУЮ память (P4).
        val oldCount = history.size - windowSize
        if (oldCount > derived.summarizedCount) {
            val lv = runCatching { summarizeLeveled(history.take(oldCount)) }.getOrNull()
            if (lv != null && (lv.strategic.isNotEmpty() || lv.tactical.isNotEmpty()))
                derived = derived.copy(summary = lv.tactical, summarizedCount = oldCount, facts = lv.strategic, factsCount = oldCount)
        }
        val sys = systemBlock(working, longTerm, derived.facts, derived.summary, profile, invariants)
        return Assembled(listOf(system(sys)) + history.takeLast(windowSize), derived, oldCount)
    }

    private fun systemBlock(working: WorkingMemory, longTerm: LongTermMemory, strategic: String, tactical: String, profile: UserProfile?, invariants: List<Invariant>): String = buildString {
        append(systemPrompt)
        val inv = renderInvariantsBlock(invariants)
        if (inv.isNotEmpty()) append("\n\n").append(inv)
        if (profile != null) {
            append("\n\n[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ — как отвечать]\n").append(profile.toPromptBlock())
        }
        if (!longTerm.isEmpty) {
            append("\n\n[ДОЛГОВРЕМЕННАЯ ПАМЯТЬ — что известно о пользователе из прошлых реплик; может устареть]\n")
            if (longTerm.profile.isNotBlank()) append(longTerm.profile.trim()).append('\n')
            longTerm.decisions.forEach { append("- решение: ").append(it).append('\n') }
        }
        if (!working.isEmpty) {
            append("\n\n[РАБОЧАЯ ПАМЯТЬ — предполагаемая цель/ограничения текущей задачи]\n")
            if (working.goal.isNotBlank()) append("Цель: ").append(working.goal).append('\n')
            working.constraints.forEach { append("Ограничение: ").append(it).append('\n') }
        }
        if (profile != null || !longTerm.isEmpty || !working.isEmpty) {
            append("\n\nПрофиль и память — вспомогательный фон, а НЕ текущий запрос и не ограничение. Отвечай на ")
            append("ТЕКУЩИЙ вопрос пользователя; если фон (например, упомянутая страна или планы) не совпадает с ")
            append("вопросом — это НЕ противоречие, просто ответь на заданный вопрос.")
        }
        // Уровневая память (P4): стратегический (устойчивое) и тактический (недавнее) уровни — отдельными
        // блоками; локальный уровень — это последние N реплик, которые идут как есть после system.
        if (strategic.isNotBlank()) {
            append("\n\n[СТРАТЕГИЧЕСКАЯ ПАМЯТЬ ДИАЛОГА — устойчивые факты, цели и договорённости за весь диалог]\n").append(strategic)
        }
        if (tactical.isNotBlank()) {
            append("\n\n[ТАКТИЧЕСКОЕ РЕЗЮМЕ — краткая сводка недавнего хода разговора]\n").append(tactical)
        }
    }.trim()

    /** Свёрнутый старый хвост, разбитый на уровни (P4). */
    private data class Leveled(val strategic: String, val tactical: String)

    private suspend fun summarizeLeveled(old: List<Message>): Leveled {
        val transcript = old.joinToString("\n") { "${label(it.role)}: ${it.text}" }
        val raw = gateway.complete(listOf(system(SUMMARY_PROMPT), Message(Role.User, transcript))).text
        val strategic = section(raw, "СТРАТЕГИЧЕСКОЕ")
        val tactical = section(raw, "ТАКТИЧЕСКОЕ")
        // Фолбэк: модель не разметила блоки — кладём весь ответ в тактический уровень (как было до P4).
        return if (strategic.isEmpty() && tactical.isEmpty()) Leveled("", raw.trim()) else Leveled(strategic, tactical)
    }

    /** Достаёт содержимое блока `[TAG] … ` до следующего `[` или конца текста. */
    private fun section(raw: String, tag: String): String {
        val open = "[$tag]"
        val start = raw.indexOf(open, ignoreCase = true)
        if (start < 0) return ""
        val from = start + open.length
        val end = raw.indexOf('[', from).let { if (it >= 0) it else raw.length }
        return raw.substring(from, end).trim()
    }

    private fun system(text: String) = Message(Role.System, text)

    private fun label(role: Role) = when (role) {
        Role.User -> "Пользователь"
        Role.Assistant -> "Агент"
        Role.System -> "Система"
    }

    private companion object {
        /** Порог заполнения окна, при котором включается авто-суммаризация. */
        const val SUMMARY_FILL = 0.30f
        const val SUMMARY_PROMPT =
            "Сожми приведённый диалог в ДВА уровня. Верни СТРОГО два блока в таком виде:\n" +
                "[СТРАТЕГИЧЕСКОЕ]\n- устойчивые факты, цели, договорённости и решения (верное для всего кейса; 3–6 пунктов)\n" +
                "[ТАКТИЧЕСКОЕ]\n- краткая сводка недавнего хода разговора (2–4 пункта)\n" +
                "Сохраняй факты, числа, даты, страны. Только эти два блока, без вступления."
    }
}
