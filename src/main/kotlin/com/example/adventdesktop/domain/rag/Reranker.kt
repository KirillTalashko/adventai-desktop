package com.example.adventdesktop.domain.rag

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.runCatchingCancellable

/** Способ второго этапа (День 23): без реранка, эвристика (cosine+лексика) или LLM-реранкер. */
enum class RerankMode { OFF, HEURISTIC, LLM }

/**
 * Настройки улучшенного RAG-пайплайна (День 23). [retrieveN] — широкий пул bi-encoder; [topK] — сколько
 * оставить после реранка/фильтра; [floor] — порог отсечения нерелевантных (по score реранка, [0..1]).
 */
data class RagOptions(
    val strategy: String = "contextual",
    val retrieveN: Int = 12,
    val topK: Int = 4,
    val floor: Float = 0.50f,
    val rerank: RerankMode = RerankMode.HEURISTIC,
    val rewrite: Boolean = false,
)

/**
 * Итог query rewrite для наглядного индикатора (День 23): выключен, переписал, вернул то же (переписывать
 * нечего) или вызов LLM упал (сеть/лимит) → искали по исходному вопросу.
 */
enum class RewriteOutcome { OFF, REWRITTEN, UNCHANGED, FAILED }

/** Трейс поиска для показа «до/после»: исходный/переписанный запрос, top-K до и после, размеры и отсев. */
data class RetrievalTrace(
    val originalQuery: String,
    val usedQuery: String,
    /** Что случилось с query rewrite на этом прогоне (для индикатора под тумблером). */
    val rewrite: RewriteOutcome,
    val before: List<Scored>,
    val after: List<Scored>,
    val poolSize: Int,
    val survived: Int,
    val droppedByFilter: Int,
)

/** Лёгкий стеммер (обрезка частых русских окончаний) — чтобы «отказа»/«отказе»/«отказ» считались одним словом. */
internal object Ru {
    private val stop = setOf(
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она", "так",
        "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "ее", "мне", "от", "о", "из", "для",
        "ли", "или", "это", "эта", "этот", "при", "нужна", "нужно", "нужен", "какая", "какой", "какие",
    )
    private val suffixes = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ах", "ях", "ов", "ев", "ий", "ый",
        "ой", "ая", "яя", "ое", "ее", "ые", "ие", "ам", "ям", "ах", "у", "ю", "е", "а", "я", "ы", "и", "о", "й", "ь",
    ).sortedByDescending { it.length }

    private val token = Regex("[\\p{L}\\p{Nd}]+")

    fun stem(w: String): String {
        for (suf in suffixes) if (w.length - suf.length >= 3 && w.endsWith(suf)) return w.dropLast(suf.length)
        return w
    }

    /** Значимые основы слов текста (без стоп-слов, длиной ≥3). */
    fun terms(text: String): Set<String> =
        token.findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 && it !in stop }
            .map { stem(it) }.toSet()
}

/**
 * **Эвристический реранкер** (День 23): пересортировывает кандидатов по комбинированному score
 * `wCosine·cosine + wLexical·lexical`, где lexical — доля основ слов запроса, встретившихся в чанке
 * (со стеммингом). Даёт буст документу, где реально есть слова запроса (вытаскивает нужный из хвоста), и
 * душит «похоже по вектору, но не по теме». Детерминирован, без сети.
 */
class HeuristicReranker(private val wCosine: Float = 0.5f, private val wLexical: Float = 0.5f) {
    fun rerank(query: String, candidates: List<Scored>): List<Scored> {
        val q = Ru.terms(query)
        return candidates.map { s ->
            val lexical = if (q.isEmpty()) 0f else {
                val c = Ru.terms(s.chunk.text)
                q.count { it in c }.toFloat() / q.size
            }
            Scored(s.chunk, wCosine * s.score + wLexical * lexical)
        }.sortedByDescending { it.score }
    }
}

/** Фильтр релевантности: отсечь всё ниже [floor] (по score реранка), затем оставить top-[topK]. */
object RelevanceFilter {
    fun apply(reranked: List<Scored>, floor: Float, topK: Int): List<Scored> =
        reranked.filter { it.score >= floor }.take(topK)
}

/**
 * **LLM-реранкер** (опционально): просит модель вернуть номера СТРОГО релевантных фрагментов по убыванию.
 * Невыбранные считаются нерелевантными (score 0 → отсекаются фильтром). Точнее эвристики, но нужен LLM.
 */
class LlmReranker(private val gateway: LlmGateway) {
    suspend fun rerank(query: String, candidates: List<Scored>): List<Scored> {
        if (candidates.isEmpty()) return candidates
        val listing = candidates.mapIndexed { i, s ->
            "${i + 1}. ${s.chunk.text.replace(Regex("\\s+"), " ").trim().take(220)}"
        }.joinToString("\n")
        val sys = "Ты — реранкер поиска. Верни номера ТОЛЬКО релевантных вопросу фрагментов через запятую, " +
            "от самого релевантного к менее. Нерелевантные не включай. Ответ — только номера, без слов."
        val user = "Вопрос: $query\n\nФрагменты:\n$listing"
        val resp = runCatchingCancellable {
            gateway.complete(listOf(Message(Role.System, sys), Message(Role.User, user)), params = LlmParams(temperature = 0.0))
        }.getOrNull() ?: return candidates
        val order = Regex("\\d+").findAll(resp.text).map { it.value.toInt() }.filter { it in 1..candidates.size }
            .distinct().toList()
        if (order.isEmpty()) return candidates
        // Оценка по рангу: 1.0, 0.9, …; невыбранные → 0 (уйдут под фильтр).
        val score = HashMap<Int, Float>()
        order.forEachIndexed { rank, num -> score[num - 1] = (1f - rank * 0.1f).coerceAtLeast(0.1f) }
        return candidates.mapIndexed { i, s -> Scored(s.chunk, score[i] ?: 0f) }.sortedByDescending { it.score }
    }
}

/** Результат переписывания: итоговый запрос + флаг «вызов LLM упал» (чтобы отличить сбой от no-op). */
data class RewriteResult(val query: String, val failed: Boolean)

/**
 * **Query rewrite** (опционально): переписывает разговорный вопрос в короткий поисковый запрос (ключевые
 * термины: страна, тип визы, суть) перед эмбеддингом — помогает bi-encoder на «живых» формулировках.
 * При сбое вызова возвращает исходный вопрос с [RewriteResult.failed]=true — чтобы UI различал «упал» и
 * «вернул то же».
 */
class QueryRewriter(private val gateway: LlmGateway) {
    suspend fun rewrite(question: String): RewriteResult {
        val sys = "Перепиши вопрос пользователя в КОРОТКИЙ поисковый запрос по визовой базе знаний: ключевые " +
            "термины (страна, тип визы, суть), без вводных слов и без пояснений. Верни только запрос одной строкой."
        val resp = runCatchingCancellable {
            gateway.complete(listOf(Message(Role.System, sys), Message(Role.User, question)), params = LlmParams(temperature = 0.0))
        }.getOrNull() ?: return RewriteResult(question, failed = true)
        val out = resp.text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200)?.ifBlank { question } ?: question
        return RewriteResult(out, failed = false)
    }
}
