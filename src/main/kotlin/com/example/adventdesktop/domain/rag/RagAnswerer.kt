package com.example.adventdesktop.domain.rag

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TokenUsage

/** Использованный в ответе источник (фрагмент индекса): номер, файл, раздел, chunk_id, близость, полный текст. */
data class RagSource(
    val n: Int,
    val source: String,
    val section: String,
    val chunkId: String,
    val score: Float,
    val text: String,
)

/**
 * Цитата (День 24): короткий ДОСЛОВНЫЙ фрагмент из найденного чанка [n], на который опирается ответ. В
 * отличие от [RagSource.text] (весь чанк) — это одна максимально релевантная вопросу фраза. Извлекается
 * детерминированно из текста чанка, поэтому гарантированно настоящая (анти-галлюцинация: цитату нельзя выдумать).
 */
data class Citation(
    val n: Int,
    val source: String,
    val section: String,
    val chunkId: String,
    val quote: String,
)

/**
 * Ответ агента в одном режиме (с RAG / без). [contextChars] — сколько символов контекста подмешано;
 * [citations] — дословные цитаты из источников (День 24); [abstained] — сработал ли режим «не знаю»
 * (контекст слабее порога → ответ не генерируется, чтобы не выдумать).
 */
data class RagAnswer(
    val mode: String,
    val text: String,
    val sources: List<RagSource>,
    val usage: TokenUsage?,
    val contextChars: Int,
    val citations: List<Citation> = emptyList(),
    val abstained: Boolean = false,
)

// Отказ = мета-фраза «в базе/контексте НЕТ ДАННЫХ/информации/сведений» (именно про источник, а не любое
// «нет»/«отсутствует» в самом ответе). (?iu) — регистронезависимо + UNICODE_CASE (кириллица); \p{L}, а не
// \w, т.к. \w в Java-регэкспе матчит только ASCII и мимо кириллицы.
private val REFUSAL_RE = Regex(
    "(?iu)" +
        "нет\\s+(данных|информаци\\p{L}+|сведени\\p{L}+)" +          // «нет данных / информации / сведений»
        "|(данных|информаци\\p{L}+|сведени\\p{L}+)\\s+нет" +        // «данных нет»
        "|информаци\\p{L}+\\s+отсутству\\p{L}+" +                   // «информация отсутствует»
        "|не\\s+содерж\\p{L}+\\s+(данн\\p{L}+|информаци\\p{L}+|сведени\\p{L}+)" +  // «(контекст) не содержит данных»
        "|в\\s+баз\\p{L}+\\s+знаний\\s+нет" +                       // «в базе знаний нет …»
        "|по\\s+этому\\s+вопросу\\s+нет" +                          // «по этому вопросу нет …»
        "|не\\s+знаю",                                              // явный режим «не знаю» (День 24)
)

/**
 * Простое определение «честного отказа» в ответе С RAG (модель признала, что данных нет / «не знаю»).
 * Используется и в оценке набора ([GoldAnswer]), и в UI. Матчит только мета-фразы об отсутствии ДАННЫХ в
 * базе/контексте (как велит [RagAnswerer.RAG_SYSTEM]), а не любое «нет»/«отсутствует» внутри самого ответа.
 */
fun ragLooksLikeRefusal(text: String): Boolean = REFUSAL_RE.containsMatchIn(text)

/**
 * Ответчик RAG (Дни 22–24): «вопрос → (найденные чанки) → объединение с вопросом → LLM».
 * День 24 усиливает результат: обязательные ИСТОЧНИКИ (source + section + chunk_id) и дословные ЦИТАТЫ из
 * чанков + режим [abstain] («не знаю» при слабом контексте — включается вызывающим, когда фильтр по порогу
 * оставил пусто). Домен знает только порт [LlmGateway]; поиск чанков ([Scored]) делает вызывающий.
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
                chunkId = s.chunk.meta.chunkId, score = s.score, text = s.chunk.text.trim(),
            )
        }
        // Цитаты — детерминированно: самая релевантная вопросу фраза каждого чанка (гарантированно настоящая).
        val citations = sources.mapIndexed { i, src ->
            Citation(src.n, src.source, src.section, src.chunkId, bestQuote(retrieved[i].chunk.text, question))
        }
        val context = retrieved.mapIndexed { i, s ->
            "[${i + 1}] (${s.chunk.meta.source}${sectionTail(s.chunk.meta.section)} · ${s.chunk.meta.chunkId})\n" +
                s.chunk.text.trim().take(CHUNK_CAP)
        }.joinToString("\n\n")
        val user = "[КОНТЕКСТ]\n$context\n\n[ВОПРОС]\n$question"
        val resp = gateway.complete(listOf(Message(Role.System, RAG_SYSTEM), Message(Role.User, user)), params = params)
        return RagAnswer("С RAG", resp.text.trim(), sources, resp.usage, context.length, citations)
    }

    /**
     * Режим «не знаю» (День 24, «усиление»): контекст слабее порога релевантности → НЕ зовём LLM (чтобы
     * нечего было выдумать) и детерминированно просим уточнение. Ни источников, ни цитат — честное воздержание.
     */
    fun abstain(question: String): RagAnswer = RagAnswer(
        mode = "С RAG",
        text = "Не знаю: в базе знаний нет достаточно релевантной информации по этому вопросу. " +
            "Уточните, пожалуйста — укажите страну, тип визы или конкретный шаг, и я поищу точнее.",
        sources = emptyList(),
        usage = null,
        contextChars = 0,
        citations = emptyList(),
        abstained = true,
    )

    suspend fun plain(question: String, params: LlmParams = LlmParams(temperature = 0.2)): RagAnswer {
        val resp = gateway.complete(listOf(Message(Role.System, PLAIN_SYSTEM), Message(Role.User, question)), params = params)
        return RagAnswer("Без RAG", resp.text.trim(), emptyList(), resp.usage, 0)
    }

    private fun sectionTail(section: String): String = if (section.isBlank()) "" else " › $section"

    /**
     * Дословная цитата: наиболее релевантное вопросу предложение чанка + смежный сосед (окно из соседних
     * фраз, чтобы цитата оставалась НЕПРЕРЫВНЫМ фрагментом текста, а не склейкой разных мест).
     */
    private fun bestQuote(chunkText: String, question: String): String {
        val q = Ru.terms(question)
        val sentences = chunkText.split(SENTENCE_SPLIT).map { it.trim().trim('•', '-', '—', ' ') }
            .filter { it.length in 20..400 }
        if (sentences.isEmpty()) return chunkText.oneLine().take(QUOTE_CAP)
        if (q.isEmpty()) return sentences.first().oneLine().take(QUOTE_CAP)
        val scores = sentences.map { s -> Ru.terms(s).count { it in q } }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        val prev = best - 1
        val next = best + 1
        // берём соседа с бОльшим совпадением, сохраняя смежность (непрерывность фрагмента)
        val neighbor = when {
            prev >= 0 && scores[prev] >= scores.getOrElse(next) { -1 } -> prev
            next < sentences.size -> next
            else -> best
        }
        val lo = minOf(best, neighbor)
        val hi = maxOf(best, neighbor)
        return (lo..hi).joinToString(" ") { sentences[it] }.oneLine().take(QUOTE_CAP)
    }

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CHUNK_CAP = 700
        private const val QUOTE_CAP = 320
        private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n+")

        /**
         * Промпт «с RAG» (День 24): отвечать строго по контексту, ссылаться на номера [n], честно признавать
         * отсутствие данных. Цитаты и список источников формируются кодом детерминированно (не парсим LLM).
         */
        const val RAG_SYSTEM =
            "Ты — визовый консультант. Отвечай на вопрос ТОЛЬКО на основе фрагментов из блока [КОНТЕКСТ] ниже " +
                "(это выдержки из нашей базы знаний). Правила:\n" +
                "• Если ответа в контексте НЕТ — прямо напиши «Не знаю: в базе знаний нет данных по этому " +
                "вопросу» и НЕ придумывай факты.\n" +
                "• Опирайся на конкретные фрагменты и ссылайся на их номера в тексте: [1], [2].\n" +
                "• Не добавляй фактов, которых нет в контексте.\n" +
                "• Отвечай кратко и по делу, на русском."

        /** Промпт «без RAG»: обычный ответ из общих знаний модели (для контраста). */
        const val PLAIN_SYSTEM = "Ты — визовый консультант. Ответь на вопрос пользователя кратко и по делу, на русском."
    }
}
