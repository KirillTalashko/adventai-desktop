package com.example.adventdesktop.domain.rag

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TokenUsage

/** Использованный в ответе источник (фрагмент индекса): номер, файл, раздел, близость, полный текст. */
data class RagSource(
    val n: Int,
    val source: String,
    val section: String,
    val score: Float,
    val text: String,
)

/** Ответ агента в одном режиме (с RAG / без). [contextChars] — сколько символов контекста подмешано. */
data class RagAnswer(
    val mode: String,
    val text: String,
    val sources: List<RagSource>,
    val usage: TokenUsage?,
    val contextChars: Int,
)

private val REFUSAL_RE = Regex(
    "нет данных|не найдено|нет информации|не содержит|данных нет|в базе.{0,25}нет|нет.{0,25}базе|отсутству",
    RegexOption.IGNORE_CASE,
)

/**
 * Простое определение «честного отказа» в ответе С RAG (модель признала, что в базе нет данных). Используется
 * и в оценке набора ([GoldAnswer]), и в UI — чтобы честно подписать, почему для вопроса-ловушки всё равно
 * показаны найденные (но нерелевантные) фрагменты.
 */
fun ragLooksLikeRefusal(text: String): Boolean = REFUSAL_RE.containsMatchIn(text)

/**
 * Ответчик RAG (День 22): «вопрос → (найденные чанки) → объединение с вопросом → LLM». Домен знает только
 * порт [LlmGateway]; поиск чанков ([Scored]) делает вызывающий (через [RagSearch]) и передаёт сюда.
 *
 * Два режима для сравнения:
 * - [withContext] — **с RAG**: в промпт кладём пронумерованные фрагменты базы + инструкцию отвечать ТОЛЬКО
 *   по ним и честно признавать, если ответа в базе нет (анти-галлюцинация + ссылки на источники).
 * - [plain] — **без RAG**: тот же вопрос без контекста (модель отвечает из общих знаний).
 */
class RagAnswerer(private val gateway: LlmGateway) {

    suspend fun withContext(
        question: String,
        retrieved: List<Scored>,
        params: LlmParams = LlmParams(temperature = 0.2),
    ): RagAnswer {
        val sources = retrieved.mapIndexed { i, s ->
            RagSource(
                n = i + 1, source = s.chunk.meta.source,
                section = s.chunk.meta.section.ifBlank { "(без раздела)" },
                score = s.score, text = s.chunk.text.trim(),
            )
        }
        val context = retrieved.mapIndexed { i, s ->
            "[${i + 1}] (${s.chunk.meta.source}${sectionTail(s.chunk.meta.section)})\n${s.chunk.text.trim().take(CHUNK_CAP)}"
        }.joinToString("\n\n")
        val user = "[КОНТЕКСТ]\n$context\n\n[ВОПРОС]\n$question"
        val resp = gateway.complete(listOf(Message(Role.System, RAG_SYSTEM), Message(Role.User, user)), params = params)
        return RagAnswer("С RAG", resp.text.trim(), sources, resp.usage, context.length)
    }

    suspend fun plain(question: String, params: LlmParams = LlmParams(temperature = 0.2)): RagAnswer {
        val resp = gateway.complete(listOf(Message(Role.System, PLAIN_SYSTEM), Message(Role.User, question)), params = params)
        return RagAnswer("Без RAG", resp.text.trim(), emptyList(), resp.usage, 0)
    }

    private fun sectionTail(section: String): String = if (section.isBlank()) "" else " › $section"

    companion object {
        private const val CHUNK_CAP = 700

        /** Промпт «с RAG»: отвечать строго по контексту, честно признавать отсутствие данных, ссылаться на номера. */
        const val RAG_SYSTEM =
            "Ты — визовый консультант. Отвечай на вопрос ТОЛЬКО на основе фрагментов из блока [КОНТЕКСТ] ниже " +
                "(это выдержки из нашей базы знаний). Правила:\n" +
                "• Если ответа в контексте НЕТ — прямо напиши «В базе знаний нет данных по этому вопросу» и НЕ " +
                "придумывай факты.\n" +
                "• Опирайся на конкретные фрагменты и ссылайся на их номера в тексте: [1], [2].\n" +
                "• В конце добавь строку «Источники:» с номерами и именами файлов использованных фрагментов.\n" +
                "• Отвечай кратко и по делу, на русском."

        /** Промпт «без RAG»: обычный ответ из общих знаний модели (для контраста). */
        const val PLAIN_SYSTEM = "Ты — визовый консультант. Ответь на вопрос пользователя кратко и по делу, на русском."
    }
}
