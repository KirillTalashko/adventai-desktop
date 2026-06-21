package com.example.adventdesktop.domain

/** Предложение доп-активности пользователю сверх плана (сейчас — пробное собеседование). */
data class Offer(val kind: String, val title: String)

/**
 * Агент-«разведчик возможностей»: смотрит только что выполненный шаг задачи и ищет, чем ещё можно помочь
 * сверх плана. Сейчас умеет предлагать ПРОБНОЕ собеседование, когда шаг про подготовку к собеседованию/
 * интервью в консульстве. Возвращает [Offer] или null. Служебная роль (дешёвая модель); ошибка/пусто → null.
 * Предлагает — не навязывает: пользователь вправе отказаться.
 */
class OfferAgent(private val gateway: LlmGateway) {

    suspend fun check(task: String, step: String): Offer? {
        if (step.isBlank()) return null
        val raw = runCatching {
            gateway.complete(
                listOf(
                    Message(Role.System, SYSTEM_PROMPT),
                    Message(Role.User, "Задача: $task\nТолько что выполненный шаг: $step")
                )
            ).text
        }.getOrNull().orEmpty()
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.startsWith("INTERVIEW", ignoreCase = true)) {
            Offer("interview", line.substringAfter(':', "").trim().ifBlank { "Хотите пройти пробное собеседование?" })
        } else null
    }

    private companion object {
        val SYSTEM_PROMPT = """
            Ты — агент-помощник, который ищет, чем ещё можно помочь пользователю на текущем шаге визовой задачи,
            сверх формального плана. Сейчас твоя единственная возможность — предложить ПРОБНОЕ собеседование.
            Если только что выполненный шаг связан с ПОДГОТОВКОЙ К СОБЕСЕДОВАНИЮ / ИНТЕРВЬЮ в консульстве —
            предложи пройти пробное собеседование. Верни РОВНО одной строкой:
            INTERVIEW: <короткое дружелюбное предложение пройти пробное собеседование>
            Если на этом шаге это неуместно — верни ровно: NONE. Без пояснений.
        """.trimIndent()
    }
}
