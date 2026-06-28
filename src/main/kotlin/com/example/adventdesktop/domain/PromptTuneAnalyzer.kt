package com.example.adventdesktop.domain

/** Предложение персонализации промта роли (День 20): что добавить и на каком основании. */
data class PromptProposal(val role: String, val add: String, val why: String)

/**
 * Аналитик навыка prompt-tune (День 20): по СИГНАЛАМ из диалогов (вывод `visa-cli prompt-tune collect`)
 * предлагает 0–3 маленькие АДДИТИВНЫЕ улучшения промтов тюнящихся ролей под конкретного пользователя.
 * Перила в промте: только добавки, только «мягкое» поведение, никогда — безопасность/отказы/пропуск стадий.
 * Применение — ТОЛЬКО с подтверждения пользователя (за это отвечает UI), здесь лишь генерация предложений.
 */
class PromptTuneAnalyzer(private val gateway: LlmGateway) {

    suspend fun analyze(collectData: String): List<PromptProposal> {
        val raw = runCatching {
            gateway.complete(listOf(Message(Role.System, PROMPT), Message(Role.User, collectData))).text
        }.getOrNull().orEmpty()
        return parse(raw)
    }

    private fun parse(raw: String): List<PromptProposal> {
        if (raw.trim().equals("НЕТ", ignoreCase = true)) return emptyList()
        val proposals = mutableListOf<PromptProposal>()
        var role = ""; var add = ""; var why = ""
        fun flush() {
            if (TunableRole.byId(role) != null && add.isNotBlank()) proposals += PromptProposal(role, add.trim(), why.trim())
            role = ""; add = ""; why = ""
        }
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            when {
                l == "---" -> flush()
                l.startsWith("ROLE:", true) -> role = l.substringAfter(':').trim().lowercase()
                l.startsWith("ADD:", true) -> add = l.substringAfter(':').trim()
                l.startsWith("WHY:", true) -> why = l.substringAfter(':').trim()
            }
        }
        flush()
        return proposals.take(3)
    }

    private companion object {
        const val PROMPT =
            "Ты — аналитик персонализации промтов визового агента. На входе — СИГНАЛЫ из диалогов пользователя и " +
                "список тюнящихся ролей. Предложи 0–3 МАЛЕНЬКИХ улучшения промтов ролей под ЭТОГО пользователя.\n" +
                "ЖЁСТКИЕ ПРАВИЛА: только ДОБАВКИ (ничего не удалять и не переписывать); только «мягкое» поведение " +
                "(стиль, что спрашивать раньше, уровень детализации, ссылки, тон). НИКОГДА не предлагай ослабить " +
                "безопасность, правила отказа или пропуск стадий. Опирайся на реальные сигналы; не выдумывай. " +
                "Если явных оснований нет — ответь одним словом: НЕТ.\n" +
                "Формат КАЖДОГО предложения (блоки разделяй строкой ---):\n" +
                "ROLE: <id роли из списка>\n" +
                "ADD: <одна короткая строка-инструкция в повелительном наклонении>\n" +
                "WHY: <кратко, со ссылкой на сигнал/пример>"
    }
}
