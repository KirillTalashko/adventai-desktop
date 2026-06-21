package com.example.adventdesktop.domain

/**
 * Страж инвариантов (День 14) — второй рубеж двойной защиты: после ответа агента проверяет, не нарушает
 * ли он активные инварианты. Возвращает описание нарушения (какой инвариант и чем) или null, если чисто.
 * Отдельная служебная LLM-роль; при сбое/пустом ответе → null (страж не блокирует на ошибке).
 */
class InvariantGuard(private val gateway: LlmGateway) {

    suspend fun check(answer: String, invariants: List<Invariant>): String? {
        val active = invariants.filter { it.active }
        if (active.isEmpty() || answer.isBlank()) return null
        val raw = runCatching {
            gateway.complete(
                listOf(
                    Message(Role.System, SYSTEM_PROMPT),
                    Message(Role.User, buildInput(answer, active))
                )
            ).text
        }.getOrNull().orEmpty()
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.startsWith("VIOLATION", ignoreCase = true)) {
            line.substringAfter(':', "").trim().ifBlank { "ответ нарушает инвариант" }
        } else null
    }

    private fun buildInput(answer: String, invariants: List<Invariant>): String = buildString {
        append("ИНВАРИАНТЫ:\n")
        invariants.forEach { append("- ").append(it.text).append('\n') }
        append("\nОТВЕТ АССИСТЕНТА:\n").append(answer)
    }

    private companion object {
        val SYSTEM_PROMPT = """
            Ты — страж инвариантов визового ассистента. На вход дают список инвариантов и ОТВЕТ ассистента.
            Проверь, не нарушает ли ОТВЕТ хотя бы один инвариант (например: советует незаконное/обход закона,
            даёт юридическую гарантию, выходит за рамки роли, противоречит заданному правилу).
            Если нарушение есть — верни РОВНО одной строкой: VIOLATION: <какой инвариант и чем именно нарушен>.
            Если нарушений нет — верни ровно: OK. Без пояснений.
        """.trimIndent()
    }
}
