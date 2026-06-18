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
        contextFill: Float
    ): Assembled {
        val history = conversation.messages
        var derived = conversation.derived

        // Окно ещё свободно — отправляем всю историю как есть (ничего не теряем).
        if (contextFill < SUMMARY_FILL || history.size <= windowSize) {
            return Assembled(listOf(system(systemBlock(working, longTerm, ""))) + history, derived, 0)
        }

        // Заполнение высокое: свернуть старый хвост (за пределами последних N) в резюме.
        val oldCount = history.size - windowSize
        if (oldCount > derived.summarizedCount) {
            val text = runCatching { summarize(history.take(oldCount)) }.getOrNull()?.trim()
            if (!text.isNullOrEmpty()) derived = derived.copy(summary = text, summarizedCount = oldCount)
        }
        val sys = systemBlock(working, longTerm, derived.summary)
        return Assembled(listOf(system(sys)) + history.takeLast(windowSize), derived, oldCount)
    }

    private fun systemBlock(working: WorkingMemory, longTerm: LongTermMemory, summary: String): String = buildString {
        append(systemPrompt)
        if (!longTerm.isEmpty) {
            append("\n\n[ДОЛГОВРЕМЕННАЯ ПАМЯТЬ — профиль и решения; считай это фактами о пользователе]\n")
            if (longTerm.profile.isNotBlank()) append(longTerm.profile.trim()).append('\n')
            longTerm.decisions.forEach { append("- решение: ").append(it).append('\n') }
        }
        if (!working.isEmpty) {
            append("\n\n[РАБОЧАЯ ПАМЯТЬ — текущая задача]\n")
            if (working.goal.isNotBlank()) append("Цель: ").append(working.goal).append('\n')
            working.constraints.forEach { append("Ограничение: ").append(it).append('\n') }
        }
        if (summary.isNotBlank()) {
            append("\n\n[РЕЗЮМЕ РАННЕЙ ЧАСТИ ДИАЛОГА]\n").append(summary)
        }
    }.trim()

    private suspend fun summarize(old: List<Message>): String {
        val transcript = old.joinToString("\n") { "${label(it.role)}: ${it.text}" }
        return gateway.complete(listOf(system(SUMMARY_PROMPT), Message(Role.User, transcript))).text
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
            "Сожми приведённый диалог в краткое резюме (5–8 пунктов). Сохрани факты, числа, даты, страны, " +
                "решения и договорённости. Только резюме, без вступления."
    }
}
