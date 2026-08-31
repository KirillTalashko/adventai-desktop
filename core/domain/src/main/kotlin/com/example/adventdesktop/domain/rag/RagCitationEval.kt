package com.example.adventdesktop.domain.rag

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.runCatchingCancellable

/**
 * Проверка одного контрольного вопроса по критериям Дня 24: есть ли ИСТОЧНИКИ, есть ли ЦИТАТЫ и совпадает ли
 * смысл ответа с цитатами ([faithful], метрика Faithfulness). Для негативного вопроса правильное поведение —
 * режим «не знаю» ([abstained]); тогда источники/цитаты не нужны, а [faithful] не считается (null).
 */
data class CitationCheck(
    val q: GoldQuestion,
    val answer: RagAnswer,
    val faithful: Boolean?,
) {
    val abstained: Boolean get() = answer.abstained
    val sourcesPresent: Boolean get() = answer.sources.isNotEmpty()
    val quotesPresent: Boolean get() = answer.citations.isNotEmpty()
    val expectedAbstain: Boolean get() = q.isNegative

    /** Итоговый вердикт: негатив — правильно ли воздержался; позитив — есть источники+цитаты и смысл совпал. */
    val pass: Boolean get() =
        if (expectedAbstain) abstained
        else !abstained && sourcesPresent && quotesPresent && faithful == true
}

/**
 * Судья Faithfulness (День 24): опирается ли смысл ОТВЕТА на ФРАГМЕНТЫ базы, на которых он построен (не
 * противоречит и не добавляет важных фактов сверх них). Сверяем именно против источников ответа (полные
 * найденные чанки), а не одной вырезанной цитаты, — иначе оценка искусственно занижается. Отдельная
 * модель-судья (как в метриках недели 5). При сбое сети считаем «не подтверждено» (консервативно).
 */
class RagFaithfulnessJudge(private val gateway: LlmGateway) {
    suspend fun faithful(answer: String, evidence: List<String>): Boolean {
        if (evidence.isEmpty()) return false
        val listing = evidence.mapIndexed { i, e ->
            "[${i + 1}] ${e.replace(Regex("\\s+"), " ").trim().take(500)}"
        }.joinToString("\n")
        val sys = "Ты — проверяющий фактов (метрика Faithfulness). Тебе дают ОТВЕТ и ФРАГМЕНТЫ базы знаний, на " +
            "которых он должен основываться. Ответь ОДНИМ словом: ДА — если смысл ответа согласуется с " +
            "фрагментами, не противоречит им и не добавляет важных фактов, которых в них нет; иначе НЕТ."
        val user = "ОТВЕТ:\n$answer\n\nФРАГМЕНТЫ:\n$listing"
        val resp = runCatchingCancellable {
            gateway.complete(listOf(Message(Role.System, sys), Message(Role.User, user)), params = LlmParams(temperature = 0.0))
        }.getOrNull() ?: return false
        return verdictIsYes(resp.text)
    }
}

/**
 * Разбор вердикта судьи: «да/yes» → true, «нет/no» → false (нет — приоритетнее). `(?U)` обязателен —
 * без UNICODE_CHARACTER_CLASS граница слова `\b` в Java опирается на ASCII-`\w` и НЕ срабатывает на
 * кириллице, из-за чего `\bда\b` не матчит «да» и судья всегда возвращает false.
 */
internal fun verdictIsYes(text: String): Boolean {
    val t = text.trim().lowercase()
    if (Regex("(?U)\\b(нет|no)\\b").containsMatchIn(t)) return false
    return Regex("(?U)\\b(да|yes)\\b").containsMatchIn(t)
}
