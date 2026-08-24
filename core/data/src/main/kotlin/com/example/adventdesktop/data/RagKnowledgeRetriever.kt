package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.CountryScope
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.rag.KnowledgeRetriever
import com.example.adventdesktop.domain.rag.KnowledgeScope
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.Scored
import kotlinx.coroutines.CancellationException

/**
 * Реализация порта [KnowledgeRetriever] (День 25): поиск во внутренней базе знаний через существующий
 * двухэтапный пайплайн [KnowledgeIndex.retrieve] (Дни 23–24) — contextual chunking + эвристический реранк +
 * фильтр по порогу. Детерминированно, БЕЗ LLM (gateway = null, rewrite/LLM-реранк выключены): нужен только
 * эмбеддинг (локальная Ollama работает и headless) — поэтому пригодно для каждого хода агента и для тестов.
 *
 * Эмбеддер создаётся и закрывается НА ВЫЗОВ (как в dev-панели), чтобы не держать HTTP-клиент открытым между
 * ходами. Слабый/пустой контекст → пустой список: RAG «молчит», агент опирается на MCP/[СПРАВКА] (аддитивность).
 */
class RagKnowledgeRetriever(
    private val index: KnowledgeIndex,
    private val embedderFactory: () -> Embedder,
    private val options: RagOptions = RagOptions(),
) : KnowledgeRetriever {

    override suspend fun retrieve(query: String, scope: KnowledgeScope?): List<KnowledgeHit> {
        if (query.isBlank()) return emptyList()
        val embedder = embedderFactory()
        return try {
            // Страновой фильтр применяется ВНУТРИ retrieve — до косинуса, на множестве кандидатов.
            val hits = index.retrieve(
                gateway = null, embedder = embedder, options = options, question = query,
                allow = { src -> scope?.allows(src) ?: true },
            ).after.map(::toHit)
            if (scope == null || !scope.hasCountryDoc) hits else anchored(embedder, query, scope, hits)
        } catch (e: CancellationException) {
            throw e       // отмену хода агента НЕ глотаем — пробрасываем (kotlin-coroutines-flows)
        } catch (_: Exception) {
            emptyList()   // при сбое (нет Ollama/индекса) RAG молчит — агент работает без базы, не падает
        } finally {
            (embedder as? OllamaEmbedder)?.close()
        }
    }

    /**
     * **Якорь страны.** Одного фильтра мало: корпус асимметричен — страновой документ бывает тонким и
     * делегирует общие правила рамочному (`spain.md` → `schengen.md`), поэтому по «типовым» словам шага
     * («базовый пакет документов») его обходят общие материалы и он не попадает даже в пул кандидатов.
     * Если страна известна и её документ есть, но в выдаче его нет — добавляем ОДИН лучший фрагмент именно
     * из него: это первичный источник по кейсу, а не «ещё один похожий текст».
     *
     * Прогоняется тем же пайплайном (рерank), но без отсечки по порогу: порог защищает от офтопа, а здесь
     * множество кандидатов — ровно один документ нужной страны, и он по определению по теме.
     */
    private suspend fun anchored(
        embedder: Embedder,
        query: String,
        scope: KnowledgeScope,
        hits: List<KnowledgeHit>,
    ): List<KnowledgeHit> {
        if (hits.any { CountryScope.countryOfSource(it.source) == scope.country }) return hits
        val anchor = index.retrieve(
            gateway = null, embedder = embedder, options = options.copy(floor = 0f, topK = 1),
            question = query, allow = { src -> CountryScope.countryOfSource(src) == scope.country },
        ).after.map(::toHit)
        return (anchor + hits).take(options.topK)
    }

    private fun toHit(s: Scored) = KnowledgeHit(
        source = s.chunk.meta.source,
        section = s.chunk.meta.section,
        chunkId = s.chunk.meta.chunkId,
        score = s.score,
        text = s.chunk.text.trim(),
    )
}
